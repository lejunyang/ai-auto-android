package cli

// 测试用途：验证stop专用保留参数、真实适配链路的精确只读查询及默认删除argv不变。

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

type keptAssociationExecutor struct {
	stopCleanupExecutor
	companion  []string
	associated bool
}

func (f *keptAssociationExecutor) Run(ctx context.Context, path string, args []string, opts process.Options) (process.Result, error) {
	if len(args) > 5 && args[4] == "companiondevice" {
		f.companion = append(f.companion, strings.Join(args[5:], " "))
		if args[5] == "disassociate" {
			f.associated = false
			return process.Result{}, nil
		}
		if f.unavailable {
			return process.Result{ExitCode: 1}, errors.New("list unavailable")
		}
		text := "Association ID | Package Name | Mac Address\n"
		if f.associated {
			text += "9 | com.android.shell | 00:00:00:00:00:10\n"
		}
		return process.Result{Stdout: []byte(text)}, nil
	}
	return f.stopCleanupExecutor.Run(ctx, path, args, opts)
}
func TestCLIStopKeepAssociationRealAdapterContract(t *testing.T) {
	for _, kind := range []string{"keep", "default", "none", "unavailable"} {
		t.Run(kind, func(t *testing.T) {
			f := &keptAssociationExecutor{stopCleanupExecutor: stopCleanupExecutor{api: "35", unavailable: kind == "unavailable"}, associated: kind != "none"}
			app, out := testApp(f, "")
			args := []string{"display", "stop", "--device", "SERIAL", "--json"}
			if kind != "default" {
				args = append(args, "--keep-camera-association")
			}
			exit := app.Run(context.Background(), args)
			if !f.filesCleaned || !f.imagesChecked {
				t.Fatal(out.String())
			}
			if kind == "unavailable" {
				e := decodeEnvelope(t, out.Bytes())
				if exit == 0 || e.Error == nil || e.Error.Code != apperr.CodeVirtualCameraUnavailable || !strings.Contains(out.String(), `"cameraImages":{"ok":true}`) {
					t.Fatal(out.String())
				}
			} else if exit != 0 {
				t.Fatal(out.String())
			}
			want := []string{"list 0"}
			if kind == "default" {
				want = append(want, "disassociate 0 com.android.shell 00:00:00:00:00:10", "list 0")
			}
			if !reflect.DeepEqual(f.companion, want) {
				t.Fatal(f.companion, want)
			}
			if (kind == "keep") != strings.Contains(out.String(), `"keptCameraAssociation":{"userId":0,"id":9}`) {
				t.Fatal(out.String())
			}
			if kind == "default" && !strings.Contains(out.String(), `"data":{"stopped":true}`) {
				t.Fatal(out.String())
			}
		})
	}
}
func TestCLIKeepAssociationFlagOnlyAcceptedByStop(t *testing.T) {
	for _, value := range []string{"--keep-camera-association", "--keep-camera-association=true"} {
		fake := &displayCapturingAutomation{}
		app, out := testApp(&commandExecutor{}, "")
		app.Automation = fake
		if app.Run(context.Background(), []string{"display", "stop", "--device", "SERIAL", value}) != 0 || len(fake.managed) != 1 || !fake.managed[0].KeepCameraAssociation {
			t.Fatal(out.String(), fake.managed)
		}
	}
	for _, args := range [][]string{
		{"stop", "--keep-camera-association=false"}, {"stop", "--keep-camera-association", "false"}, {"stop", "--keep-camera-association=1"}, {"stop", "--keep-camera-association", "--keep-camera-association"},
		{"start", "--keep-camera-association"}, {"status", "--keep-camera-association"}, {"list", "--keep-camera-association"}, {"create", "--keep-camera-association"}, {"close", "--keep-camera-association"},
	} {
		fake := &displayCapturingAutomation{}
		app, out := testApp(&commandExecutor{}, "")
		app.Automation = fake
		if app.Run(context.Background(), append([]string{"display"}, append(args, "--device", "SERIAL")...)) != apperr.ExitArgument || len(fake.managed) != 0 {
			t.Fatal(args, out.String())
		}
	}
}
