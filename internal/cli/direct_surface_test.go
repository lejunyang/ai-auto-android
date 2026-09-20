package cli

// 测试用途：验证 Wave B 新增的动作与观察子命令确实映射到共享服务层的同一套
// ActionRequest 语义，而不是各自另起一套参数含义。
//
// 为什么逐条断言映射而不是只看退出码：这些动作在 internal/service 早已实现并分派，
// 缺的只是 CLI 出口。出口接错（比如把 rotate 的 orientation 填进 Text 字段）
// 依然能让命令成功返回，只有比对落到服务层的请求才能发现。

import (
	"context"
	"reflect"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/service"
)

// runActionCommand 执行一条 CLI 命令并返回它落到服务层的动作请求。
func runActionCommand(t *testing.T, args ...string) service.ActionRequest {
	t.Helper()
	automation := &actionCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = automation

	exitCode := app.Run(context.Background(), append(args, "--json"))
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	if envelope := decodeEnvelope(t, stdout.Bytes()); !envelope.OK {
		t.Fatalf("envelope = %#v", envelope)
	}
	if len(automation.actions) != 1 {
		t.Fatalf("service action calls = %#v", automation.actions)
	}
	return automation.actions[0]
}

func TestActionSubcommandsMapToSharedServiceSemantics(t *testing.T) {
	tests := []struct {
		name string
		args []string
		want service.ActionRequest
	}{
		{
			name: "drag",
			args: []string{
				"action", "drag", "--device", "SERIAL",
				"--x1", "10", "--y1", "20", "--x2", "30", "--y2", "40",
				"--duration", "700",
			},
			want: service.ActionRequest{
				Device: "SERIAL", Action: service.ActionDrag,
				StartX: 10, StartY: 20, EndX: 30, EndY: 40, DurationMS: 700,
			},
		},
		{
			name: "multi-tap",
			args: []string{
				"action", "multi-tap", "--device", "SERIAL",
				"--x", "15", "--y", "25", "--taps", "3",
			},
			want: service.ActionRequest{
				Device: "SERIAL", Action: service.ActionMultiTap,
				X: 15, Y: 25, Taps: 3,
			},
		},
		{
			name: "path-gesture",
			args: []string{
				"action", "path-gesture", "--device", "SERIAL",
				"--points", "10,20 30,40 50,60", "--duration", "900",
			},
			want: service.ActionRequest{
				Device: "SERIAL", Action: service.ActionPathGesture,
				Points:     []adb.Point{{X: 10, Y: 20}, {X: 30, Y: 40}, {X: 50, Y: 60}},
				DurationMS: 900,
			},
		},
		{
			name: "rotate",
			args: []string{
				"action", "rotate", "--device", "SERIAL", "--orientation", "landscape",
			},
			want: service.ActionRequest{
				Device: "SERIAL", Action: service.ActionDeviceRotate,
				Orientation: "landscape",
			},
		},
		{
			name: "wake",
			args: []string{"action", "wake", "--device", "SERIAL"},
			want: service.ActionRequest{Device: "SERIAL", Action: service.ActionDeviceWake},
		},
		{
			name: "unlock",
			args: []string{"action", "unlock", "--device", "SERIAL"},
			want: service.ActionRequest{Device: "SERIAL", Action: service.ActionDeviceUnlock},
		},
		{
			name: "ime-hide",
			args: []string{"action", "ime-hide", "--device", "SERIAL"},
			want: service.ActionRequest{Device: "SERIAL", Action: service.ActionImeHide},
		},
		{
			name: "ime-select",
			args: []string{
				"action", "ime-select", "--device", "SERIAL",
				"--ime", "com.example.ime/.Service",
			},
			want: service.ActionRequest{
				Device: "SERIAL", Action: service.ActionImeSelect,
				InputMethod: "com.example.ime/.Service",
			},
		},
		{
			name: "deeplink",
			args: []string{
				"action", "deeplink", "--device", "SERIAL",
				"--uri", "https://example.com/item/1", "--package", "com.example.app",
			},
			want: service.ActionRequest{
				Device: "SERIAL", Action: service.ActionDeeplink,
				URI: "https://example.com/item/1", Package: "com.example.app",
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := runActionCommand(t, test.args...)
			// 用 reflect.DeepEqual：ActionRequest 含 []adb.Point 切片字段，不可用 == 比较。
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("service request = %#v, want %#v", got, test.want)
			}
		})
	}
}

// TestActionSubcommandsRejectInvalidArguments 覆盖新子命令的参数校验。
// 只验证命令能跑通不够：参数写错时必须明确失败，而不是带着默认值执行动作。
func TestActionSubcommandsRejectInvalidArguments(t *testing.T) {
	tests := []struct {
		name string
		args []string
	}{
		{"drag missing endpoint", []string{"action", "drag", "--device", "SERIAL", "--x1", "1", "--y1", "2"}},
		{"multi-tap non integer taps", []string{
			"action", "multi-tap", "--device", "SERIAL", "--x", "1", "--y", "2", "--taps", "many",
		}},
		{"path-gesture single point", []string{
			"action", "path-gesture", "--device", "SERIAL", "--points", "10,20",
		}},
		{"path-gesture malformed point", []string{
			"action", "path-gesture", "--device", "SERIAL", "--points", "10,20 30",
		}},
		{"rotate unknown orientation", []string{
			"action", "rotate", "--device", "SERIAL", "--orientation", "sideways",
		}},
		{"ime-select missing ime", []string{"action", "ime-select", "--device", "SERIAL"}},
		{"deeplink missing uri", []string{"action", "deeplink", "--device", "SERIAL"}},
		{"deeplink invalid package", []string{
			"action", "deeplink", "--device", "SERIAL",
			"--uri", "https://example.com", "--package", "not a package",
		}},
		{"wake rejects extra option", []string{
			"action", "wake", "--device", "SERIAL", "--orientation", "portrait",
		}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			automation := &actionCapturingAutomation{}
			app, stdout := testApp(&commandExecutor{}, "")
			app.Automation = automation

			exitCode := app.Run(context.Background(), append(test.args, "--json"))
			if exitCode == 0 {
				t.Fatalf("invalid arguments were accepted, output = %s", stdout.String())
			}
			if len(automation.actions) != 0 {
				t.Fatalf("invalid arguments reached the service: %#v", automation.actions)
			}
		})
	}
}

func TestObserveScreenStateUsesSharedService(t *testing.T) {
	automation := &actionCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = automation

	exitCode := app.Run(context.Background(), []string{
		"observe", "screen-state", "--device", "SERIAL", "--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	if envelope := decodeEnvelope(t, stdout.Bytes()); !envelope.OK {
		t.Fatalf("envelope = %#v", envelope)
	}
	if len(automation.screenStates) != 1 || automation.screenStates[0] != "SERIAL" {
		t.Fatalf("screen state calls = %#v", automation.screenStates)
	}
}

func TestObserveInputMethodsUsesSharedService(t *testing.T) {
	automation := &actionCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = automation

	exitCode := app.Run(context.Background(), []string{
		"observe", "input-methods", "--device", "SERIAL", "--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	if envelope := decodeEnvelope(t, stdout.Bytes()); !envelope.OK {
		t.Fatalf("envelope = %#v", envelope)
	}
	if len(automation.inputMethodQueries) != 1 ||
		automation.inputMethodQueries[0] != "SERIAL" {
		t.Fatalf("input method calls = %#v", automation.inputMethodQueries)
	}
}

// TestFileTransferHasNoCLIEntryPoint 锁定一个有意的缺口：Service.PushFile /
// PullFile 已实现，但本轮不给 CLI 出口。暴露远端路径读写前必须先定义路径白名单
// 与确认策略（change spec 的待决项 T4）。这条测试防止有人顺手接线。
func TestFileTransferHasNoCLIEntryPoint(t *testing.T) {
	for _, subcommand := range []string{"push", "pull", "file-push", "file-pull"} {
		t.Run(subcommand, func(t *testing.T) {
			automation := &actionCapturingAutomation{}
			app, _ := testApp(&commandExecutor{}, "")
			app.Automation = automation

			exitCode := app.Run(context.Background(), []string{
				"action", subcommand, "--device", "SERIAL",
				"--local", "a", "--remote", "/sdcard/b", "--json",
			})
			if exitCode == 0 {
				t.Fatalf("%q is wired without the path allowlist decision", subcommand)
			}
			if len(automation.fileTransfers) != 0 {
				t.Fatalf("file transfer reached the service: %#v", automation.fileTransfers)
			}
		})
	}
}
