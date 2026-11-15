package vdisplay

// 测试用途：验证保留关联只读报告且不阻断文件/图片清理，普通模式与默认停止契约保持。

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

type keptStopHost struct {
	*stopCleanupHost
	found   adb.CameraAssociation
	findErr error
	reads   int
	user    int
}

func (h *keptStopHost) FindCameraAssociation(_ context.Context, _ string, user int) (adb.CameraAssociation, error) {
	h.reads++
	h.user = user
	return h.found, h.findErr
}
func TestStopKeepAssociationReportsActualIDAndCleansFiles(t *testing.T) {
	for _, running := range []bool{false, true} {
		t.Run(map[bool]string{false: "absent", true: "camera-helper"}[running], func(t *testing.T) {
			var h *keptStopHost
			c, base := cameraWire(t, true, func(r map[string]any) string {
				if r["method"] != "shutdown" {
					t.Error(r)
				}
				h.state.PID = 0
				h.state.Bound = false
				return cameraOK(`{"stopped":true}`)
			})
			if !running {
				base.state.PID = 0
				base.state.Bound = false
			}
			h = &keptStopHost{stopCleanupHost: &stopCleanupHost{cameraHostFake: base}, found: adb.CameraAssociation{UserID: 10, ID: 71}}
			c.host = h
			r, err := c.StopKeepingCameraAssociation(context.Background(), "SERIAL")
			if err != nil || !r.Stopped || h.cleaned != 0 || h.reads != 1 || h.filesRemoved != 1 || h.imagesCleaned != 1 || h.forwarded != h.removed {
				t.Fatal(r, err, h)
			}
			wantUser := -1
			if running {
				wantUser = 10
			}
			if h.user != wantUser {
				t.Fatal(h.user)
			}
			wire, _ := json.Marshal(r)
			if string(wire) != `{"stopped":true,"keptCameraAssociation":{"userId":10,"id":71}}` {
				t.Fatal(string(wire))
			}
		})
	}
}
func TestStopKeepAssociationAbsentOrQueryFailureStillCleans(t *testing.T) {
	for _, reason := range []string{"", "Cannot list companion associations", "Unrecognized companiondevice list format"} {
		t.Run(reason, func(t *testing.T) {
			c, base := cameraWire(t, true, func(map[string]any) string { return "" })
			base.state.PID = 0
			base.state.Bound = false
			h := &keptStopHost{stopCleanupHost: &stopCleanupHost{cameraHostFake: base}}
			if reason != "" {
				h.findErr = apperr.New(apperr.CodeVirtualCameraUnavailable, reason, false, nil)
			}
			c.host = h
			r, err := c.StopKeepingCameraAssociation(context.Background(), "SERIAL")
			if (err != nil) != (reason != "") || r.KeptCameraAssociation != nil || h.filesRemoved != 1 || h.imagesCleaned != 1 || h.cleaned != 0 || h.reads != 1 {
				t.Fatal(r, err, h)
			}
			if reason != "" {
				requireCode(t, err, apperr.CodeVirtualCameraUnavailable)
				if r.Cleanup == nil || r.Cleanup.CameraAssociation.OK {
					t.Fatal(r)
				}
			} else {
				wire, _ := json.Marshal(r)
				if string(wire) != `{"stopped":true}` {
					t.Fatal(string(wire))
				}
			}
		})
	}
}
func TestStopKeepAssociationMergesIndependentCleanupErrors(t *testing.T) {
	c, base := cameraWire(t, true, func(map[string]any) string { return "" })
	base.state.PID = 0
	base.state.Bound = false
	fileErr, imageErr := errors.New("files failed"), errors.New("images failed")
	h := &keptStopHost{stopCleanupHost: &stopCleanupHost{cameraHostFake: base, filesErr: fileErr, imageErr: imageErr}, found: adb.CameraAssociation{UserID: 10, ID: 71}}
	c.host = h
	r, err := c.StopKeepingCameraAssociation(context.Background(), "SERIAL")
	var app *apperr.Error
	if !errors.Is(err, fileErr) || !errors.Is(err, imageErr) || !errors.As(err, &app) || r.KeptCameraAssociation == nil || app.Details["keptCameraAssociation"] == nil || h.imagesCleaned != 1 || h.cleaned != 0 || !r.Cleanup.CameraAssociation.OK {
		t.Fatal(r, err, h)
	}
}
func TestStopOrdinaryHelperIgnoresKeepAssociationFlag(t *testing.T) {
	for _, keep := range []bool{false, true} {
		var h *keptStopHost
		c, base := cameraWire(t, false, func(map[string]any) string {
			h.state.PID = 0
			h.state.Bound = false
			return cameraOK(`{"stopped":true}`)
		})
		h = &keptStopHost{stopCleanupHost: &stopCleanupHost{cameraHostFake: base}, found: adb.CameraAssociation{UserID: 10, ID: 71}}
		c.host = h
		var r StopResult
		var err error
		if keep {
			r, err = c.StopKeepingCameraAssociation(context.Background(), "SERIAL")
		} else {
			r, err = c.Stop(context.Background(), "SERIAL")
		}
		wire, _ := json.Marshal(r)
		if err != nil || string(wire) != `{"stopped":true}` || h.cleaned != 0 || h.reads != 0 || h.imagesCleaned != 0 || h.filesRemoved != 1 {
			t.Fatal(r, err, h)
		}
	}
}
