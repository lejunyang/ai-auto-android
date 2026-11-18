package cli

// 测试用途：确保close/stop忙碌信封保留下一步提示、可重试语义和稳定退出码，不自动重试。

import (
	"context"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
)

type cameraBusyAutomation struct{ displayCapturingAutomation }

func (f *cameraBusyAutomation) ManageDisplay(_ context.Context, request service.DisplayRequest) (any, error) {
	f.managed = append(f.managed, request)
	return nil, apperr.New(apperr.CodeVirtualCameraBusy, "Application is still using the camera; release it then retry close/stop. Do not kill the helper.", true, nil)
}
func TestCLICameraBusyCloseAndStopAreRetryable(t *testing.T) {
	for _, operation := range []string{"close", "stop", "keep-stop"} {
		fake := &cameraBusyAutomation{}
		app, out := testApp(&commandExecutor{}, "")
		app.Automation = fake
		args := []string{"display", "stop", "--device", "SERIAL", "--json"}
		if operation == "close" {
			args[1] = "close"
			args = append(args, "--display", "7")
		}
		if operation == "keep-stop" {
			args = append(args, "--keep-camera-association")
		}
		if app.Run(context.Background(), args) != apperr.ExitDevice {
			t.Fatal(out.String())
		}
		envelope := decodeEnvelope(t, out.Bytes())
		if envelope.Error == nil || envelope.Error.Code != apperr.CodeVirtualCameraBusy || !envelope.Error.Retryable || !strings.Contains(envelope.Error.Message, "Do not kill") || len(fake.managed) != 1 {
			t.Fatal(out.String(), fake.managed)
		}
	}
}
