// Package visual 测试原子桌面视觉动作端口的证据绑定、失败关闭和图片清零。
// 测试用途：验证 fresh attestation、漂移零提交、unknown commit 和敏感图片清理。
package visual

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

func TestDesktopAttestedActionCapturesProposesExecutesAndVerifiesAtomically(t *testing.T) {
	backend, sourceImages := successfulAttestedBackend(t)
	port := &attestedActionPort{
		result: AttestedActionPortResult{
			CommitStatus:  ActionCommitCommitted,
			ActionCommits: intPointer(1),
			Verified:      true,
			Route:         "gesture",
			Screen: AttestedScreenEvidence{
				NaturalWidth:  100,
				NaturalHeight: 200,
				DensityDPI:    420,
				Rotation:      0,
				Window:        PixelBounds{Left: 0, Top: 0, Right: 100, Bottom: 200},
				Insets:        AttestedInsets{},
			},
		},
	}
	service := NewDesktopAttestedActionService(backend, port, DesktopAttestedActionOptions{
		Now: func() time.Time { return desktopTime("2026-08-01T09:00:00Z") },
		ObservationID: func() string {
			return "019fbcaa-0000-7000-8000-000000000047"
		},
	})

	result := service.ProposeAndExecute(context.Background(), DesktopAttestedActionRequest{
		Device:          "SERIAL",
		ExpectedPackage: "com.example.notes",
		Target:          DesktopTarget{Role: "button", Label: "Save"},
		Action:          DesktopVisualAction{Type: VisualActionTap},
	})

	if !result.Succeeded || !result.Verified ||
		result.CommitStatus != ActionCommitCommitted ||
		result.ActionCommits == nil || *result.ActionCommits != 1 ||
		result.ObservationID != "019fbcaa-0000-7000-8000-000000000047" ||
		result.DeviceFingerprint == "" || result.ScreenFingerprint == "" {
		t.Fatalf("result = %#v", result)
	}
	if port.calls != 1 {
		t.Fatalf("port calls = %d", port.calls)
	}
	request := port.requests[0]
	if request.DeviceSerial != "SERIAL" ||
		request.DeviceFingerprint != result.DeviceFingerprint ||
		request.TargetPackage != "com.example.notes" ||
		request.Observation.ID != result.ObservationID ||
		request.Observation.PNG.SHA256 == "" ||
		request.Candidate.ObservationID != result.ObservationID ||
		request.Candidate.Confidence < 0.70 ||
		!request.ExpiresAt.After(request.CapturedAt) {
		t.Fatalf("port request = %#v", request)
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}
	for _, forbidden := range []string{
		`"candidate"`, `"point"`, `"bounds"`, `"x"`, `"y"`, `"pngBytes"`, `"handle"`,
	} {
		if bytes.Contains(encoded, []byte(forbidden)) {
			t.Fatalf("result exposes %q: %s", forbidden, encoded)
		}
	}
	for index, image := range sourceImages {
		if !allZero(image) {
			t.Fatalf("image %d was not cleared", index)
		}
	}
	if strings.Join(backend.calls, ",") != strings.Join([]string{
		"device", "hierarchy", "screenshot", "hierarchy", "device",
		"device", "hierarchy", "screenshot", "hierarchy", "device",
		"device", "hierarchy", "screenshot", "hierarchy", "device",
	}, ",") {
		t.Fatalf("backend calls = %#v", backend.calls)
	}
}

func TestDesktopAttestedActionRejectsPreCommitEvidenceDriftWithoutCallingPort(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*attestedBackend)
		code   Code
	}{
		{
			name: "device fingerprint",
			mutate: func(backend *attestedBackend) {
				backend.devices[2].Model = "Changed"
				backend.devices[3].Model = "Changed"
			},
			code: CodeDeviceFingerprintChanged,
		},
		{
			name: "package",
			mutate: func(backend *attestedBackend) {
				backend.hierarchies[2] = desktopHierarchyForPackage(
					"0",
					"com.example.changed",
					"Save",
					"secret",
				)
				backend.hierarchies[3] = backend.hierarchies[2]
			},
			code: CodeForegroundPackageChanged,
		},
		{
			name: "png hash",
			mutate: func(backend *attestedBackend) {
				changed := append(
					desktopPNG(t, 100, 200, false),
					[]byte("attested-hash-drift")...,
				)
				backend.screenshots[1] = adb.Screenshot{
					Bytes:  changed,
					SHA256: hashDesktopBytes(changed),
				}
			},
			code: CodeImageUnverified,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			backend, _ := successfulAttestedBackend(t)
			test.mutate(backend)
			port := &attestedActionPort{}
			service := NewDesktopAttestedActionService(
				backend,
				port,
				DesktopAttestedActionOptions{
					Now: func() time.Time {
						return desktopTime("2026-08-01T09:00:00Z")
					},
					ObservationID: func() string {
						return "019fbcaa-0000-7000-8000-000000000047"
					},
				},
			)

			result := service.ProposeAndExecute(
				context.Background(),
				DesktopAttestedActionRequest{
					Device:          "SERIAL",
					ExpectedPackage: "com.example.notes",
					Target:          DesktopTarget{Label: "Save"},
					Action:          DesktopVisualAction{Type: VisualActionTap},
				},
			)

			if result.ErrorCode != test.code ||
				result.CommitStatus != ActionCommitNotCommitted ||
				result.ActionCommits == nil || *result.ActionCommits != 0 ||
				port.calls != 0 {
				t.Fatalf("result=%#v port calls=%d", result, port.calls)
			}
		})
	}
}

func TestDesktopAttestedActionReturnsUnknownOnceForTransportFailure(t *testing.T) {
	backend, sourceImages := successfulAttestedBackend(t)
	port := &attestedActionPort{err: errors.New("connection reset after write")}
	service := NewDesktopAttestedActionService(backend, port, DesktopAttestedActionOptions{
		Now:           func() time.Time { return desktopTime("2026-08-01T09:00:00Z") },
		ObservationID: func() string { return "019fbcaa-0000-7000-8000-000000000047" },
	})

	result := service.ProposeAndExecute(context.Background(), DesktopAttestedActionRequest{
		Device:          "SERIAL",
		ExpectedPackage: "com.example.notes",
		Target:          DesktopTarget{Label: "Save"},
		Action:          DesktopVisualAction{Type: VisualActionTap},
	})

	if result.CommitStatus != ActionCommitUnknown ||
		result.ActionCommits != nil ||
		result.ErrorCode != CodeActionCommitUnknown ||
		port.calls != 1 {
		t.Fatalf("result=%#v port calls=%d", result, port.calls)
	}
	for index, image := range sourceImages {
		if !allZero(image) {
			t.Fatalf("image %d was not cleared", index)
		}
	}
}

func TestDesktopAttestedActionRejectsUnsafeCandidateCaptureAndExpiryWithoutPortCall(t *testing.T) {
	tests := []struct {
		name    string
		backend func(*testing.T) *attestedBackend
		options DesktopAttestedActionOptions
		want    Code
	}{
		{
			name: "low confidence",
			backend: func(t *testing.T) *attestedBackend {
				backend, _ := successfulAttestedBackend(t)
				return backend
			},
			options: DesktopAttestedActionOptions{
				CandidateConfidence: 0.69,
			},
			want: CodeCandidateLowConfidence,
		},
		{
			name: "multiple candidates",
			backend: func(t *testing.T) *attestedBackend {
				backend, _ := successfulAttestedBackend(t)
				ambiguous := desktopHierarchyWithButtons(
					"0",
					[]desktopButton{
						{label: "Save", bounds: "[10,20][50,60]"},
						{label: "Save", bounds: "[52,20][92,60]"},
					},
				)
				backend.hierarchies[0] = ambiguous
				backend.hierarchies[1] = ambiguous
				return backend
			},
			want: CodeCandidateAmbiguous,
		},
		{
			name: "secure capture",
			backend: func(t *testing.T) *attestedBackend {
				backend, _ := successfulAttestedBackend(t)
				secure := desktopPNG(t, 100, 200, true)
				backend.screenshots[0] = adb.Screenshot{
					Bytes:  secure,
					SHA256: hashDesktopBytes(secure),
				}
				return backend
			},
			want: CodeSecureWindow,
		},
		{
			name: "expired before commit",
			backend: func(t *testing.T) *attestedBackend {
				backend, _ := successfulAttestedBackend(t)
				return backend
			},
			options: DesktopAttestedActionOptions{},
			want:    CodeObservationExpired,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			port := &attestedActionPort{}
			nowCalls := 0
			options := test.options
			options.ObservationID = func() string {
				return "019fbcaa-0000-7000-8000-000000000047"
			}
			options.Now = func() time.Time {
				nowCalls++
				if test.name == "expired before commit" && nowCalls > 1 {
					return desktopTime("2026-08-01T09:00:11Z")
				}
				return desktopTime("2026-08-01T09:00:00Z")
			}
			service := NewDesktopAttestedActionService(
				test.backend(t),
				port,
				options,
			)

			result := service.ProposeAndExecute(
				context.Background(),
				DesktopAttestedActionRequest{
					Device:          "SERIAL",
					ExpectedPackage: "com.example.notes",
					Target:          DesktopTarget{Label: "Save"},
					Action:          DesktopVisualAction{Type: VisualActionTap},
				},
			)

			if result.ErrorCode != test.want ||
				result.CommitStatus != ActionCommitNotCommitted ||
				result.ActionCommits == nil || *result.ActionCommits != 0 ||
				port.calls != 0 {
				t.Fatalf("result=%#v port calls=%d", result, port.calls)
			}
		})
	}
}

func TestDesktopAttestedActionRejectsScreenRotationDriftBeforePortCall(t *testing.T) {
	backend, _ := successfulAttestedBackend(t)
	rotated := desktopHierarchy("1", "Save", "secret")
	backend.hierarchies[2] = rotated
	backend.hierarchies[3] = rotated
	rotatedPNG := desktopPNG(t, 200, 100, false)
	backend.screenshots[1] = adb.Screenshot{
		Bytes:  rotatedPNG,
		SHA256: hashDesktopBytes(rotatedPNG),
	}
	port := &attestedActionPort{}
	service := NewDesktopAttestedActionService(
		backend,
		port,
		DesktopAttestedActionOptions{
			Now: func() time.Time {
				return desktopTime("2026-08-01T09:00:00Z")
			},
			ObservationID: func() string {
				return "019fbcaa-0000-7000-8000-000000000047"
			},
		},
	)

	result := service.ProposeAndExecute(
		context.Background(),
		DesktopAttestedActionRequest{
			Device:          "SERIAL",
			ExpectedPackage: "com.example.notes",
			Target:          DesktopTarget{Label: "Save"},
			Action:          DesktopVisualAction{Type: VisualActionTap},
		},
	)

	if result.ErrorCode != CodeScreenMetadataChanged ||
		result.CommitStatus != ActionCommitNotCommitted ||
		port.calls != 0 {
		t.Fatalf("result=%#v port calls=%d", result, port.calls)
	}
}

type attestedActionPort struct {
	requests []AttestedActionPortRequest
	result   AttestedActionPortResult
	err      error
	calls    int
}

func (port *attestedActionPort) ExecuteAttestedVisualAction(
	_ context.Context,
	request AttestedActionPortRequest,
) (AttestedActionPortResult, error) {
	port.calls++
	port.requests = append(port.requests, request)
	return port.result, port.err
}

type attestedBackend struct {
	devices     []protocol.Device
	hierarchies []adb.Hierarchy
	screenshots []adb.Screenshot
	calls       []string
}

func (backend *attestedBackend) DeviceInfo(
	context.Context,
	string,
) (protocol.Device, error) {
	backend.calls = append(backend.calls, "device")
	value := backend.devices[0]
	backend.devices = backend.devices[1:]
	return value, nil
}

func (backend *attestedBackend) Hierarchy(
	context.Context,
	string,
) (adb.Hierarchy, error) {
	backend.calls = append(backend.calls, "hierarchy")
	value := backend.hierarchies[0]
	backend.hierarchies = backend.hierarchies[1:]
	return value, nil
}

func (backend *attestedBackend) Screenshot(
	context.Context,
	string,
) (adb.Screenshot, error) {
	backend.calls = append(backend.calls, "screenshot")
	value := backend.screenshots[0]
	backend.screenshots = backend.screenshots[1:]
	return value, nil
}

func successfulAttestedBackend(
	t *testing.T,
) (*attestedBackend, [][]byte) {
	t.Helper()
	device := desktopDevice()
	sourceHierarchy := desktopHierarchy("0", "Save", "secret")
	postHierarchy := desktopHierarchy("0", "Saved", "secret")
	sourcePNG := desktopPNG(t, 100, 200, false)
	preCommitPNG := append([]byte(nil), sourcePNG...)
	postPNG := append([]byte(nil), sourcePNG...)
	return &attestedBackend{
			devices: []protocol.Device{
				device, device,
				device, device,
				device, device,
			},
			hierarchies: []adb.Hierarchy{
				sourceHierarchy, sourceHierarchy,
				sourceHierarchy, sourceHierarchy,
				postHierarchy, postHierarchy,
			},
			screenshots: []adb.Screenshot{
				{Bytes: sourcePNG, SHA256: hashDesktopBytes(sourcePNG)},
				{Bytes: preCommitPNG, SHA256: hashDesktopBytes(preCommitPNG)},
				{Bytes: postPNG, SHA256: hashDesktopBytes(postPNG)},
			},
		},
		[][]byte{sourcePNG, preCommitPNG, postPNG}
}

func intPointer(value int) *int {
	return &value
}
