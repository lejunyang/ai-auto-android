package mcpserver

// 测试用途：验证关闭错误透传BUSY，以及已移除屏的待释放状态可见，不新增恢复或杀进程工具。

import (
	"context"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/lejunyang/ai-auto-android/internal/vdisplay"
)

type cameraBusyDisplayAutomation struct{ displayAutomationFake }

func (f *cameraBusyDisplayAutomation) ManageDisplay(_ context.Context, request service.DisplayRequest) (any, error) {
	f.managed = append(f.managed, request)
	if request.Command == "close" {
		return nil, apperr.New(apperr.CodeVirtualCameraBusy, "Release the application camera and retry close. Do not kill the helper.", true, nil)
	}
	flags := 6144
	return vdisplay.Status{Displays: []vdisplay.ScreenStatus{{Screen: vdisplay.Screen{DisplayID: 7, Width: 640, Height: 480, DPI: 240, Focus: "isolated", FocusIsolated: true, Camera: "back", DisplayFlags: &flags,
		CameraInfo: &vdisplay.CameraInfo{CameraID: "0", VirtualDeviceID: 8, Streams: []vdisplay.CameraStream{}, ReleasePending: true}}, DisplayGroupID: -1}}, PendingCameraReleases: 1, KeepAwakeIntervalMS: 2000}, nil
}
func TestMCPCameraBusyCloseAndPendingStatus(t *testing.T) {
	fake := &cameraBusyDisplayAutomation{}
	session := connectClient(t, fake)
	result := callDisplayTool(t, session, ToolDisplayClose, map[string]any{"device": "SERIAL", "displayId": 7}, false)
	text := toolErrorText(t, result)
	if !strings.Contains(text, `"code":"VIRTUAL_CAMERA_BUSY"`) || !strings.Contains(text, `"retryable":true`) || !strings.Contains(text, "Do not kill") {
		t.Fatal(text)
	}
	callDisplayTool(t, session, ToolDisplayStatus, map[string]any{"device": "SERIAL"}, true)
	if len(fake.managed) != 2 {
		t.Fatal("unexpected retry/recovery", fake.managed)
	}
}
