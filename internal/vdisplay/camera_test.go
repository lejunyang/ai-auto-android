package vdisplay

// 测试用途：验证旧 helper 兼容、三种摄像头错误、图片解码预算、快照传输与失败清理，不接触真实设备。

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

type cameraHostFake struct {
	*fakeHost
	ensured, cleaned, pushed, removedImages int
	startupErr, pushErr, removeErr          error
	local, remote                           string
	bytes                                   []byte
	cleanupUsers                            []int
}

func (h *cameraHostFake) CleanupCameraImages(context.Context, string) error { return nil }

func (h *cameraHostFake) EnsureCameraAssociation(context.Context, string) (adb.CameraAssociation, error) {
	h.ensured++
	return adb.CameraAssociation{UserID: 10, ID: 71}, nil
}
func (h *cameraHostFake) CleanupCameraAssociation(_ context.Context, _ string, user int) error {
	h.cleaned++
	h.cleanupUsers = append(h.cleanupUsers, user)
	return nil
}
func (h *cameraHostFake) StartDisplaydCamera(context.Context, string, adb.CameraAssociation) error {
	if h.startupErr != nil {
		return h.startupErr
	}
	h.state.PID = 42
	h.state.Bound = true
	return nil
}
func (h *cameraHostFake) PushCameraImage(_ context.Context, _ string, local, remote string) error {
	h.pushed++
	h.local, h.remote = local, remote
	h.bytes, _ = os.ReadFile(local)
	return h.pushErr
}
func (h *cameraHostFake) RemoveCameraImage(ctx context.Context, _, remote string) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	h.removedImages++
	if remote != h.remote {
		return errors.New("cleanup changed path")
	}
	return h.removeErr
}
func cameraWire(t *testing.T, enabled bool, response func(map[string]any) string) (*Client, *cameraHostFake) {
	t.Helper()
	h := &cameraHostFake{fakeHost: &fakeHost{state: adb.DisplaydState{APILevel: 35, PID: 42, Bound: true}}}
	c := New(h)
	c.dial = func(context.Context, string) (net.Conn, error) {
		a, b := net.Pipe()
		go func() {
			defer b.Close()
			line, _ := bufio.NewReader(b).ReadBytes('\n')
			var r map[string]any
			_ = json.Unmarshal(line, &r)
			data := helloJSON
			if r["method"] == "hello" {
				if enabled {
					data = strings.Replace(data, `"binaryPng":true`, `"virtualCamera":true,"binaryPng":true`, 1)
					data = strings.Replace(data, `"apiLevel":35`, `"virtualCameraUser":10,"apiLevel":35`, 1)
				}
			} else {
				data = response(r) + "\n"
			}
			_, _ = b.Write([]byte(data))
		}()
		return a, nil
	}
	return c, h
}

const cameraScreenJSON = `{"displayId":7,"width":640,"height":480,"dpi":240,"focus":"isolated","focusIsolated":true,"camera":"back","displayFlags":6144,"cameraInfo":{"cameraId":"0","virtualDeviceId":8,"streams":[]}}`

func cameraOK(result string) string { return `{"id":1,"ok":true,"result":` + result + `}` }
func testImage(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "source.png")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	m := image.NewRGBA(image.Rect(0, 0, 16, 12))
	m.Set(0, 0, color.White)
	if err = png.Encode(file, m); err != nil {
		t.Fatal(err)
	}
	if err = file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}
func TestCameraOldHelperDoesNotReceiveUnknownFields(t *testing.T) {
	calls := 0
	c, _ := cameraWire(t, false, func(map[string]any) string { calls++; return "" })
	_, err := c.CreateWithCamera(context.Background(), "SERIAL", 640, 480, 240, "isolated", "back")
	requireCode(t, err, apperr.CodeVirtualCameraNotEnabled)
	_, err = c.SetCameraImage(context.Background(), "SERIAL", 7, testImage(t))
	requireCode(t, err, apperr.CodeVirtualCameraNotEnabled)
	if calls != 0 {
		t.Fatal("old helper received camera method")
	}
}
func TestCameraWireAndTypedErrors(t *testing.T) {
	c, _ := cameraWire(t, true, func(r map[string]any) string {
		if r["camera"] != "back" || r["focus"] != "isolated" || len(r) != 7 {
			t.Error(r)
		}
		return cameraOK(cameraScreenJSON)
	})
	screen, err := c.CreateWithCamera(context.Background(), "SERIAL", 640, 480, 240, "", "back")
	if err != nil || screen.CameraInfo.VirtualDeviceID != 8 {
		t.Fatal(screen, err)
	}
	for _, code := range []string{apperr.CodeVirtualCameraNotEnabled, apperr.CodeVirtualCameraUnavailable, apperr.CodeCameraImageInvalid} {
		c, _ = cameraWire(t, true, func(map[string]any) string {
			return `{"id":1,"ok":false,"error":{"code":"` + code + `","message":"reason"}}`
		})
		_, err = c.CreateWithCamera(context.Background(), "SERIAL", 640, 480, 240, "isolated", "back")
		requireCode(t, err, code)
	}
}

func TestCameraStatusRemoveModePreservesUnknown(t *testing.T) {
	for _, mode := range []string{"1", "null"} {
		c, _ := cameraWire(t, true, func(r map[string]any) string {
			if r["method"] != "status" {
				t.Error(r)
			}
			screen := strings.TrimSuffix(cameraScreenJSON, "}") + `,"state":2,"displayGroupId":1,"groupAwake":true,"removeMode":` + mode + `}`
			return cameraOK(`{"displays":[` + screen + `],"keepAwakeIntervalMs":2000}`)
		})
		status, err := c.Status(context.Background(), "SERIAL")
		if err != nil || len(status.Displays) != 1 {
			t.Fatal(status, err)
		}
		got := status.Displays[0].RemoveMode
		if mode == "null" {
			if got != nil {
				t.Fatal("unknown removeMode reported as verified", got)
			}
		} else if got == nil || *got != 1 {
			t.Fatal(got)
		}
	}
}
func TestCameraImageSnapshotAndCleanup(t *testing.T) {
	for _, failure := range []string{"", "push", "helper", "cleanup"} {
		t.Run(failure, func(t *testing.T) {
			requestedPath := ""
			c, h := cameraWire(t, true, func(r map[string]any) string {
				if r["method"] == "display.list" {
					return cameraOK(`{"displays":[` + cameraScreenJSON + `]}`)
				}
				if r["method"] != "camera.setImage" || r["displayId"] != float64(7) || len(r) != 4 {
					t.Error("unexpected image request", r)
				}
				requestedPath, _ = r["path"].(string)
				if failure == "helper" {
					return `{"id":1,"ok":false,"error":{"code":"CAMERA_IMAGE_INVALID","message":"decode"}}`
				}
				return cameraOK(`{"displayId":7,"imageSet":true}`)
			})
			if failure == "push" {
				h.pushErr = errors.New("push failed")
			}
			if failure == "cleanup" {
				h.removeErr = errors.New("cleanup failed")
			}
			original := testImage(t)
			result, err := c.SetCameraImage(context.Background(), "SERIAL", 7, original)
			if (err != nil) != (failure != "") || (err == nil && !result.ImageSet) {
				t.Fatal(result, err)
			}
			if h.pushed != 1 || h.removedImages != 1 || h.local == original || len(h.bytes) == 0 || !regexp.MustCompile(`^/data/local/tmp/aactl-camera-[0-9a-f-]{36}\.img$`).MatchString(h.remote) {
				t.Fatal(h)
			}
			if failure != "push" && requestedPath != h.remote {
				t.Fatal("helper received a different path")
			}
			if _, e := os.Stat(h.local); !os.IsNotExist(e) {
				t.Fatal("local snapshot leaked")
			}
		})
	}
}
func TestCameraImageInvalidBeforeDevice(t *testing.T) {
	for _, kind := range []string{"missing", "directory", "bad", "oversize", "dimension"} {
		t.Run(kind, func(t *testing.T) {
			directory := t.TempDir()
			path := filepath.Join(directory, "image")
			switch kind {
			case "directory":
				path = directory
			case "bad":
				_ = os.WriteFile(path, []byte("not an image"), 0600)
			case "oversize":
				f, _ := os.Create(path)
				_ = f.Truncate(maxCameraImage + 1)
				_ = f.Close()
			case "dimension":
				f, _ := os.Create(path)
				_ = png.Encode(f, image.NewRGBA(image.Rect(0, 0, 4097, 1)))
				_ = f.Close()
			}
			c, h := fakeClient(t, nil)
			_, err := c.SetCameraImage(context.Background(), "SERIAL", 7, path)
			requireCode(t, err, apperr.CodeCameraImageInvalid)
			if h.forwarded != 0 {
				t.Fatal("invalid image reached device")
			}
		})
	}
}

func TestCameraJPEGAndExactFileBudget(t *testing.T) {
	path := filepath.Join(t.TempDir(), "image.jpeg")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = jpeg.Encode(file, image.NewRGBA(image.Rect(0, 0, 32, 24)), nil); err != nil {
		t.Fatal(err)
	}
	if err = file.Close(); err != nil {
		t.Fatal(err)
	}
	data, err := cameraImage(path)
	if err != nil || len(data) == 0 {
		t.Fatal(err)
	}
	file, err = os.OpenFile(path, os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	if err = file.Truncate(maxCameraImage); err != nil {
		t.Fatal(err)
	}
	if err = file.Close(); err != nil {
		t.Fatal(err)
	}
	data, err = cameraImage(path)
	if err != nil || len(data) != maxCameraImage {
		t.Fatal("exact 8MiB rejected", len(data), err)
	}
}
func TestCameraStartFailureCleanupAndRunningHelperProtection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "helper.apk")
	_ = os.WriteFile(path, []byte("test helper"), 0600)
	c, h := cameraWire(t, true, func(r map[string]any) string { return cameraOK(`{"stopped":true}`) })
	h.state.PID = 0
	h.state.Bound = false
	h.startupErr = errors.New("start failed")
	_, err := c.StartWithCamera(context.Background(), "SERIAL", path)
	if err == nil || h.ensured != 1 || h.cleaned < 1 {
		t.Fatal(err, h)
	}
	c, h = cameraWire(t, false, func(map[string]any) string { return "" })
	_, err = c.StartWithCamera(context.Background(), "SERIAL", path)
	requireCode(t, err, apperr.CodeVirtualCameraNotEnabled)
	if h.ensured != 0 || h.cleaned != 0 {
		t.Fatal("existing helper association was mutated")
	}
}

func TestCameraLifecycleCleansStartupUserAfterShutdown(t *testing.T) {
	path := filepath.Join(t.TempDir(), "helper.apk")
	if err := os.WriteFile(path, []byte("test helper"), 0600); err != nil {
		t.Fatal(err)
	}
	var h *cameraHostFake
	var c *Client
	c, h = cameraWire(t, true, func(r map[string]any) string {
		if r["method"] != "shutdown" {
			t.Error("unexpected method", r)
		}
		h.state.PID = 0
		h.state.Bound = false
		return cameraOK(`{"stopped":true}`)
	})
	h.state.PID = 0
	h.state.Bound = false
	hello, err := c.StartWithCamera(context.Background(), "SERIAL", path)
	if err != nil || !hello.Capabilities["virtualCamera"] || h.ensured != 1 {
		t.Fatal(hello, err, h)
	}
	result, err := c.Stop(context.Background(), "SERIAL")
	if err != nil || !result.Stopped || h.cleaned != 1 || len(h.cleanupUsers) != 1 || h.cleanupUsers[0] != 10 {
		t.Fatal(result, err, h)
	}
}
