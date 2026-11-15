package vdisplay

// 测试用途：验证幂等停止时各类清理独立执行且分类失败可见，保护普通用户不受伴生服务故障阻断。

import (
	"context"
	"errors"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

type stopCleanupHost struct {
	*cameraHostFake
	associationErr, imageErr, filesErr error
	filesRemoved, imagesCleaned        int
}

func (h *stopCleanupHost) CleanupCameraAssociation(context.Context, string, int) error {
	h.cleaned++
	return h.associationErr
}
func (h *stopCleanupHost) CleanupCameraImages(context.Context, string) error {
	h.imagesCleaned++
	return h.imageErr
}
func (h *stopCleanupHost) RemoveDisplaydFiles(context.Context, string) error {
	h.filesRemoved++
	return h.filesErr
}

func TestStopWithoutHelperAttemptsFilesWhenCameraAssociationFails(t *testing.T) {
	for _, reason := range []string{"Cannot list companion associations: service unavailable", "Unrecognized companiondevice list format"} {
		t.Run(reason, func(t *testing.T) {
			c, base := cameraWire(t, true, func(map[string]any) string { t.Error("stopped helper must not receive wire calls"); return "" })
			base.state.PID = 0
			base.state.Bound = false
			h := &stopCleanupHost{cameraHostFake: base, associationErr: apperr.New(apperr.CodeVirtualCameraUnavailable, reason, false, nil)}
			c.host = h
			r, err := c.Stop(context.Background(), "SERIAL")
			requireCode(t, err, apperr.CodeVirtualCameraUnavailable)
			if !r.Stopped || h.filesRemoved != 1 || h.imagesCleaned != 1 || r.Cleanup == nil || !r.Cleanup.HelperFiles.OK || r.Cleanup.CameraAssociation.OK || !r.Cleanup.CameraImages.OK {
				t.Fatal(r, h)
			}
			var app *apperr.Error
			if !errors.As(err, &app) || app.Details["cleanup"] == nil || app.Details["stopped"] != true {
				t.Fatal("CLI failure envelope cannot expose cleanup categories", err)
			}
			if base.forwarded != 0 {
				t.Fatal("absent helper opened forward")
			}
		})
	}
}
func TestStopWithoutHelperAndNoAssociationKeepsSuccessShape(t *testing.T) {
	c, base := cameraWire(t, true, func(map[string]any) string { return "" })
	base.state.PID = 0
	base.state.Bound = false
	h := &stopCleanupHost{cameraHostFake: base}
	c.host = h
	r, err := c.Stop(context.Background(), "SERIAL")
	if err != nil || !r.Stopped || r.Cleanup != nil || h.filesRemoved != 1 || h.cleaned != 1 || h.imagesCleaned != 1 {
		t.Fatal(r, err, h)
	}
}
func TestStopRunningOrdinaryHelperSkipsCompanionCleanup(t *testing.T) {
	var h *stopCleanupHost
	c, base := cameraWire(t, false, func(r map[string]any) string {
		if r["method"] != "shutdown" {
			t.Error(r)
		}
		h.state.PID = 0
		h.state.Bound = false
		return cameraOK(`{"stopped":true}`)
	})
	h = &stopCleanupHost{cameraHostFake: base}
	c.host = h
	r, err := c.Stop(context.Background(), "SERIAL")
	if err != nil || !r.Stopped || h.cleaned != 0 || h.imagesCleaned != 0 || h.filesRemoved != 1 || h.forwarded != h.removed {
		t.Fatal(r, err, h)
	}
}
func TestStopMergesAllCleanupFailures(t *testing.T) {
	c, base := cameraWire(t, true, func(map[string]any) string { return "" })
	base.state.PID = 0
	base.state.Bound = false
	filesErr := errors.New("helper files failed")
	imagesErr := errors.New("camera images failed")
	associationErr := errors.New("association failed")
	h := &stopCleanupHost{cameraHostFake: base, filesErr: filesErr, imageErr: imagesErr, associationErr: associationErr}
	c.host = h
	r, err := c.Stop(context.Background(), "SERIAL")
	if !errors.Is(err, filesErr) || !errors.Is(err, imagesErr) || !errors.Is(err, associationErr) || r.Cleanup == nil || r.Cleanup.HelperFiles.OK || r.Cleanup.CameraImages.OK || r.Cleanup.CameraAssociation.OK {
		t.Fatal(r, err)
	}
}
