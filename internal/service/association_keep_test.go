package service

// 测试用途：验证保留关联仅沿操作员stop窄接口传递，默认stop和其它命令不扩张能力。

import (
	"context"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/vdisplay"
)

type associationStopDisplays struct {
	DisplayBackend
	kept, stopped int
}

func (f *associationStopDisplays) Stop(context.Context, string) (vdisplay.StopResult, error) {
	f.stopped++
	return vdisplay.StopResult{Stopped: true}, nil
}
func (f *associationStopDisplays) StopKeepingCameraAssociation(context.Context, string) (vdisplay.StopResult, error) {
	f.kept++
	return vdisplay.StopResult{Stopped: true, KeptCameraAssociation: &vdisplay.KeptCameraAssociation{UserID: 0, ID: 9}}, nil
}
func TestServiceKeepAssociationUsesStopOnlyBackend(t *testing.T) {
	f := &associationStopDisplays{}
	s := New(&fakeDirect{}, nil)
	s.displays = f
	for _, keep := range []bool{false, true} {
		if _, err := s.ManageDisplay(context.Background(), DisplayRequest{Command: "stop", Device: "SERIAL", KeepCameraAssociation: keep}); err != nil {
			t.Fatal(err)
		}
	}
	if f.kept != 1 || f.stopped != 1 {
		t.Fatal(f)
	}
	for _, command := range []string{"start", "status", "list", "create", "close", "camera.set-image"} {
		_, err := s.ManageDisplay(context.Background(), DisplayRequest{Command: command, Device: "SERIAL", KeepCameraAssociation: true})
		assertServiceCode(t, err, apperr.CodeInvalidArgument)
	}
	if f.kept != 1 || f.stopped != 1 {
		t.Fatal("invalid command reached backend", f)
	}
}
