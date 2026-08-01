// Package visual 测试桌面端同轮截图、层级、设备 attestation 与只读模板候选。
// 测试用途：验证稳定采集、敏感节点脱敏、漂移拒绝、图片所有权和候选零执行边界。
package visual

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/png"
	"reflect"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

func TestDesktopProposalServiceBindsStableCaptureAndTransfersImageOwnership(t *testing.T) {
	pngBytes := desktopPNG(t, 100, 200, false)
	backend := &desktopBackend{
		device: desktopDevice(),
		hierarchies: []adb.Hierarchy{
			desktopHierarchy("0", "Save", "secret-123"),
			desktopHierarchy("0", "Save", "secret-123"),
		},
		screenshot: adb.Screenshot{Bytes: pngBytes, SHA256: hashDesktopBytes(pngBytes)},
	}
	service := NewDesktopProposalService(backend, DesktopOptions{
		Now: func() time.Time { return desktopTime("2026-08-01T09:00:00Z") },
		ObservationID: func() string {
			return "019fbcaa-0000-7000-8000-000000000044"
		},
	})

	lease, err := service.Propose(context.Background(), DesktopProposalRequest{
		Device:          "SERIAL",
		ExpectedPackage: "com.example.notes",
		Target:          DesktopTarget{Role: "button", Label: "Save"},
	})
	if err != nil {
		t.Fatalf("Propose() error = %v", err)
	}
	t.Cleanup(lease.Close)
	if !reflect.DeepEqual(
		backend.calls,
		[]string{"device", "hierarchy", "screenshot", "hierarchy", "device"},
	) {
		t.Fatalf("backend calls = %#v", backend.calls)
	}
	bundle := lease.Bundle()
	if bundle.Observation.ID != "019fbcaa-0000-7000-8000-000000000044" ||
		bundle.Observation.ForegroundPackage != "com.example.notes" ||
		bundle.Observation.Screen != (Screen{Width: 100, Height: 200, Rotation: 0}) ||
		bundle.Observation.Crop != (PixelBounds{Left: 0, Top: 0, Right: 100, Bottom: 200}) ||
		len(bundle.Candidates) != 1 ||
		bundle.Candidates[0].Source != SourceTemplate ||
		bundle.ActionCommitCount != 0 {
		t.Fatalf("bundle = %#v", bundle)
	}
	if len(bundle.Observation.Hierarchy) != 1 ||
		len(bundle.Observation.Hierarchy[0].Children) != 2 ||
		bundle.Observation.Hierarchy[0].Children[1].Label != "" ||
		!bundle.Observation.Hierarchy[0].Children[1].Sensitive {
		t.Fatalf("hierarchy = %#v", bundle.Observation.Hierarchy)
	}
	if !allZero(pngBytes) {
		t.Fatal("backend screenshot bytes were not cleared")
	}

	transferred, err := lease.TakeImage()
	if err != nil || len(transferred) == 0 {
		t.Fatalf("TakeImage() bytes=%d error=%v", len(transferred), err)
	}
	lease.Close()
	if allZero(transferred) {
		t.Fatal("transferred image was cleared before its owner released it")
	}
	clearBytes(transferred)
	if !allZero(transferred) {
		t.Fatal("transferred image could not be cleared")
	}
	if _, err := lease.TakeImage(); ErrorCode(err) != CodeObservationNotFound {
		t.Fatalf("second TakeImage() error code = %q", ErrorCode(err))
	}
}

func TestDesktopProposalServiceMapsRotatedHierarchyIntoNaturalScreen(t *testing.T) {
	pngBytes := desktopPNG(t, 200, 100, false)
	hierarchy := desktopHierarchyWithButtons(
		"1",
		[]desktopButton{{label: "Save", bounds: "[20,10][60,50]"}},
	)
	backend := &desktopBackend{
		device:      desktopDevice(),
		hierarchies: []adb.Hierarchy{hierarchy, hierarchy},
		screenshot:  adb.Screenshot{Bytes: pngBytes, SHA256: hashDesktopBytes(pngBytes)},
	}
	service := NewDesktopProposalService(backend, DesktopOptions{
		Now:           func() time.Time { return desktopTime("2026-08-01T09:00:00Z") },
		ObservationID: func() string { return "019fbcaa-0000-7000-8000-000000000044" },
	})

	lease, err := service.Propose(context.Background(), DesktopProposalRequest{
		Device:          "SERIAL",
		ExpectedPackage: "com.example.notes",
		Target:          DesktopTarget{Role: "button", Label: "Save"},
	})

	if err != nil {
		t.Fatalf("Propose() error = %v", err)
	}
	defer lease.Close()
	bundle := lease.Bundle()
	if bundle.Observation.Screen != (Screen{Width: 100, Height: 200, Rotation: 90}) {
		t.Fatalf("screen = %#v", bundle.Observation.Screen)
	}
	candidate := bundle.Candidates[0]
	assertPointClose(t, candidate.Point, NormalizedPoint{X: 0.3, Y: 0.8})
	assertBoundsClose(
		t,
		candidate.Bounds,
		NormalizedBounds{Left: 0.1, Top: 0.7, Right: 0.5, Bottom: 0.9},
	)
}

func TestDesktopProposalServiceRejectsEveryCaptureDriftBeforeCandidates(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*desktopBackend)
	}{
		{
			name: "device identity",
			mutate: func(backend *desktopBackend) {
				backend.finalDevice = desktopDevice()
				backend.finalDevice.Connection.TransportID = 8
			},
		},
		{
			name: "hierarchy bytes",
			mutate: func(backend *desktopBackend) {
				backend.hierarchies[1] = desktopHierarchy("0", "Changed", "secret-123")
			},
		},
		{
			name: "rotation",
			mutate: func(backend *desktopBackend) {
				backend.hierarchies[1] = desktopHierarchy("1", "Save", "secret-123")
			},
		},
		{
			name: "foreground package",
			mutate: func(backend *desktopBackend) {
				backend.hierarchies[1] = desktopHierarchyForPackage(
					"0",
					"com.example.changed",
					"Save",
					"secret-123",
				)
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			pngBytes := desktopPNG(t, 100, 200, false)
			backend := &desktopBackend{
				device: desktopDevice(),
				hierarchies: []adb.Hierarchy{
					desktopHierarchy("0", "Save", "secret-123"),
					desktopHierarchy("0", "Save", "secret-123"),
				},
				screenshot: adb.Screenshot{Bytes: pngBytes, SHA256: hashDesktopBytes(pngBytes)},
			}
			test.mutate(backend)
			service := NewDesktopProposalService(backend, DesktopOptions{
				Now:           func() time.Time { return desktopTime("2026-08-01T09:00:00Z") },
				ObservationID: func() string { return "019fbcaa-0000-7000-8000-000000000044" },
			})

			lease, err := service.Propose(context.Background(), DesktopProposalRequest{
				Device:          "SERIAL",
				ExpectedPackage: "com.example.notes",
				Target:          DesktopTarget{Label: "Save"},
			})

			if lease != nil || ErrorCode(err) != CodeObservationIDDrift {
				t.Fatalf("lease=%#v error code=%q error=%v", lease, ErrorCode(err), err)
			}
			if !allZero(pngBytes) {
				t.Fatal("drifted capture image was not cleared")
			}
		})
	}
}

func TestDesktopProposalServiceFailsClosedForBlankSecureAndAmbiguousTargets(t *testing.T) {
	tests := []struct {
		name      string
		image     func(*testing.T) []byte
		hierarchy adb.Hierarchy
		target    DesktopTarget
		want      Code
	}{
		{
			name:      "uniform black secure capture",
			image:     func(t *testing.T) []byte { return desktopPNG(t, 100, 200, true) },
			hierarchy: desktopHierarchy("0", "Save", "secret-123"),
			target:    DesktopTarget{Label: "Save"},
			want:      CodeSecureWindow,
		},
		{
			name:      "no target",
			image:     func(t *testing.T) []byte { return desktopPNG(t, 100, 200, false) },
			hierarchy: desktopHierarchy("0", "Save", "secret-123"),
			target:    DesktopTarget{Label: "Missing"},
			want:      CodeCandidateNotFound,
		},
		{
			name:  "nearby ambiguous target",
			image: func(t *testing.T) []byte { return desktopPNG(t, 100, 200, false) },
			hierarchy: desktopHierarchyWithButtons(
				"0",
				[]desktopButton{
					{label: "Save", bounds: "[10,20][50,60]"},
					{label: "Save", bounds: "[12,22][52,62]"},
				},
			),
			target: DesktopTarget{Label: "Save"},
			want:   CodeCandidateAmbiguous,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			pngBytes := test.image(t)
			backend := &desktopBackend{
				device:      desktopDevice(),
				hierarchies: []adb.Hierarchy{test.hierarchy, test.hierarchy},
				screenshot:  adb.Screenshot{Bytes: pngBytes, SHA256: hashDesktopBytes(pngBytes)},
			}
			service := NewDesktopProposalService(backend, DesktopOptions{
				Now:           func() time.Time { return desktopTime("2026-08-01T09:00:00Z") },
				ObservationID: func() string { return "019fbcaa-0000-7000-8000-000000000044" },
			})

			lease, err := service.Propose(context.Background(), DesktopProposalRequest{
				Device:          "SERIAL",
				ExpectedPackage: "com.example.notes",
				Target:          test.target,
			})

			if lease != nil || ErrorCode(err) != test.want {
				t.Fatalf("lease=%#v error code=%q want=%q", lease, ErrorCode(err), test.want)
			}
			if !allZero(pngBytes) {
				t.Fatal("rejected image was not cleared")
			}
		})
	}
}

type desktopBackend struct {
	device      protocol.Device
	finalDevice protocol.Device
	hierarchies []adb.Hierarchy
	screenshot  adb.Screenshot
	calls       []string
}

func (backend *desktopBackend) DeviceInfo(context.Context, string) (protocol.Device, error) {
	backend.calls = append(backend.calls, "device")
	if len(backend.calls) > 1 && backend.finalDevice.Serial != "" {
		return backend.finalDevice, nil
	}
	return backend.device, nil
}

func (backend *desktopBackend) Hierarchy(context.Context, string) (adb.Hierarchy, error) {
	backend.calls = append(backend.calls, "hierarchy")
	value := backend.hierarchies[0]
	backend.hierarchies = backend.hierarchies[1:]
	return value, nil
}

func (backend *desktopBackend) Screenshot(context.Context, string) (adb.Screenshot, error) {
	backend.calls = append(backend.calls, "screenshot")
	return backend.screenshot, nil
}

func desktopDevice() protocol.Device {
	return protocol.Device{
		Serial:    "SERIAL",
		State:     "device",
		Transport: "usb",
		Model:     "Pixel",
		Product:   "pixel",
		Connection: protocol.DeviceConnection{
			TransportID: 7,
		},
		Capabilities: []protocol.Capability{},
	}
}

func desktopHierarchy(rotation, label, secret string) adb.Hierarchy {
	return desktopHierarchyWithButtons(rotation, []desktopButton{{label: label, bounds: "[10,20][50,60]"}},
		secret)
}

func desktopHierarchyForPackage(rotation, packageName, label, secret string) adb.Hierarchy {
	xml := `<?xml version="1.0"?><hierarchy rotation="` + rotation + `">` +
		`<node class="android.widget.FrameLayout" package="` + packageName + `" ` +
		`bounds="[0,0][100,200]" text="" content-desc="" password="false">` +
		`<node class="android.widget.Button" package="` + packageName + `" bounds="[10,20][50,60]" ` +
		`text="` + label + `" content-desc="" password="false"/>` +
		`<node class="android.widget.EditText" package="` + packageName + `" bounds="[10,70][90,100]" ` +
		`text="` + secret + `" content-desc="" password="true"/>` +
		`</node></hierarchy>`
	return adb.Hierarchy{XML: xml, Bytes: len(xml), SHA256: hashDesktopBytes([]byte(xml))}
}

type desktopButton struct {
	label  string
	bounds string
}

func desktopHierarchyWithButtons(
	rotation string,
	buttons []desktopButton,
	secret ...string,
) adb.Hierarchy {
	rootBounds := "[0,0][100,200]"
	if rotation == "1" || rotation == "3" {
		rootBounds = "[0,0][200,100]"
	}
	xml := `<?xml version="1.0"?><hierarchy rotation="` + rotation + `">` +
		`<node class="android.widget.FrameLayout" package="com.example.notes" ` +
		`bounds="` + rootBounds + `" text="" content-desc="" password="false">`
	for _, button := range buttons {
		xml += `<node class="android.widget.Button" package="com.example.notes" bounds="` +
			button.bounds + `" text="` + button.label + `" content-desc="" password="false"/>`
	}
	if len(secret) == 1 {
		xml += `<node class="android.widget.EditText" package="com.example.notes" ` +
			`bounds="[10,70][90,100]" text="` + secret[0] +
			`" content-desc="" password="true"/>`
	}
	xml += `</node></hierarchy>`
	return adb.Hierarchy{XML: xml, Bytes: len(xml), SHA256: hashDesktopBytes([]byte(xml))}
}

func desktopPNG(t *testing.T, width, height int, black bool) []byte {
	t.Helper()
	canvas := image.NewRGBA(image.Rect(0, 0, width, height))
	fill := color.RGBA{R: 0x44, G: 0x66, B: 0x88, A: 0xff}
	if black {
		fill = color.RGBA{A: 0xff}
	}
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			canvas.SetRGBA(x, y, fill)
		}
	}
	if !black {
		canvas.SetRGBA(0, 0, color.RGBA{R: 0xff, A: 0xff})
	}
	var output bytes.Buffer
	if err := png.Encode(&output, canvas); err != nil {
		t.Fatalf("png.Encode() error = %v", err)
	}
	return output.Bytes()
}

func desktopTime(value string) time.Time {
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		panic(err)
	}
	return parsed
}
