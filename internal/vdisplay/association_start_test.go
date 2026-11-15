package vdisplay

// 测试用途：验证启动失败只回收本次新建关联，保留既有授权且仍关闭helper、清文件与图片。

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

type failedAssociationStartHost struct {
	*stopCleanupHost
	created, bound bool
	startErr       error
	reads          int
	readUser       int
}

func (h *failedAssociationStartHost) EnsureCameraAssociation(context.Context, string) (adb.CameraAssociation, error) {
	h.ensured++
	return adb.CameraAssociation{UserID: 10, ID: 71, Created: h.created}, nil
}
func (h *failedAssociationStartHost) FindCameraAssociation(_ context.Context, _ string, user int) (adb.CameraAssociation, error) {
	h.reads++
	h.readUser = user
	return adb.CameraAssociation{UserID: 10, ID: 71}, nil
}
func (h *failedAssociationStartHost) StartDisplaydCamera(context.Context, string, adb.CameraAssociation) error {
	if h.bound {
		h.state.PID = 42
		h.state.Bound = true
	}
	return h.startErr
}
func TestCameraFailedStartCleansOnlyNewAssociation(t *testing.T) {
	for _, created := range []bool{false, true} {
		for _, bound := range []bool{false, true} {
			name := "existing"
			if created {
				name = "new"
			}
			if bound {
				name += "/bound"
			}
			t.Run(name, func(t *testing.T) {
				var h *failedAssociationStartHost
				c, base := cameraWire(t, true, func(r map[string]any) string {
					if r["method"] != "shutdown" {
						t.Error(r)
					}
					h.state.PID = 0
					h.state.Bound = false
					return cameraOK(`{"stopped":true}`)
				})
				base.state.PID = 0
				base.state.Bound = false
				cause := apperr.New(apperr.CodeDeadlineExceeded, "transient start timeout", true, nil)
				h = &failedAssociationStartHost{stopCleanupHost: &stopCleanupHost{cameraHostFake: base}, created: created, bound: bound, startErr: cause}
				c.host = h
				path := filepath.Join(t.TempDir(), "helper.apk")
				if err := os.WriteFile(path, []byte("test helper"), 0600); err != nil {
					t.Fatal(err)
				}
				_, err := c.StartWithCamera(context.Background(), "SERIAL", path)
				requireCode(t, err, apperr.CodeDeadlineExceeded)
				wantCleanup := 0
				action := "kept"
				if created {
					wantCleanup = 1
					action = "removed"
				}
				var app *apperr.Error
				if !errors.Is(err, cause) || !errors.As(err, &app) || h.cleaned != wantCleanup || h.filesRemoved != 1 || h.imagesCleaned != 1 || h.readUser != 10 || h.forwarded != h.removed || h.state.PID != 0 {
					t.Fatal(err, h)
				}
				detail, ok := app.Details["cameraAssociation"].(map[string]any)
				if !ok || detail["action"] != action || detail["id"] != 71 || detail["userId"] != 10 || detail["created"] != created || !strings.Contains(app.Details["startError"].(string), "transient start timeout") {
					t.Fatal(app)
				}
			})
		}
	}
}
func TestCameraFailedStartReportsRemovalAndIndependentCleanupFailures(t *testing.T) {
	c, base := cameraWire(t, true, func(map[string]any) string { return "" })
	base.state.PID = 0
	base.state.Bound = false
	fileErr, imageErr, associationErr := errors.New("files failed"), errors.New("images failed"), errors.New("association failed")
	h := &failedAssociationStartHost{stopCleanupHost: &stopCleanupHost{cameraHostFake: base, filesErr: fileErr, imageErr: imageErr, associationErr: associationErr}, created: true, startErr: errors.New("startup failed")}
	c.host = h
	path := filepath.Join(t.TempDir(), "helper.apk")
	if err := os.WriteFile(path, []byte("test helper"), 0600); err != nil {
		t.Fatal(err)
	}
	_, err := c.StartWithCamera(context.Background(), "SERIAL", path)
	var app *apperr.Error
	if !errors.Is(err, fileErr) || !errors.Is(err, imageErr) || !errors.Is(err, associationErr) || !errors.As(err, &app) || app.Details["cameraAssociation"].(map[string]any)["action"] != "removeFailed" || h.cleaned != 1 || h.imagesCleaned != 1 {
		t.Fatal(err, h)
	}
}
