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
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/mcpserver"
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

// TestFileTransferRefusesToOverwriteOrDelete 接替原先的
// TestFileTransferHasNoCLIEntryPoint。
//
// 原测试锁定「file.push / file.pull 没有 CLI 出口」。出口现已开放，但锁定测试
// 不取消、只换目标：现在锁定的是出口开放后必须成立的新不变量——只新增、
// 不覆盖、不删除。任何日后把覆盖或删除顺手放开的改动都会在这里失败。
func TestFileTransferRefusesToOverwriteOrDelete(t *testing.T) {
	// 不存在任何删除类子命令，也不存在覆盖开关。
	t.Run("no delete or force subcommands", func(t *testing.T) {
		for _, args := range [][]string{
			{"file", "delete", "--device", "SERIAL", "--remote", "/sdcard/a"},
			{"file", "rm", "--device", "SERIAL", "--remote", "/sdcard/a"},
			{"file", "remove", "--device", "SERIAL", "--remote", "/sdcard/a"},
			// --force / --overwrite 必须是「未知选项」。这里带上值：
			// 不带值时 parseNamedOptions 会先以「缺少值」失败，那样即使选项被
			// 加进白名单测试也照样通过——断言就成了空的。带值才真正测到白名单。
			{
				"file", "push", "--device", "SERIAL", "--local", "a",
				"--remote", "/sdcard/b", "--force", "true",
			},
			{
				"file", "push", "--device", "SERIAL", "--local", "a",
				"--remote", "/sdcard/b", "--overwrite", "true",
			},
		} {
			automation := &actionCapturingAutomation{}
			app, _ := testApp(&commandExecutor{}, "")
			app.Automation = automation

			exitCode := app.Run(context.Background(), append(args, "--json"))

			if exitCode == 0 {
				t.Fatalf("%v was accepted; delete and overwrite must not exist", args)
			}
			if len(automation.fileTransfers) != 0 {
				t.Fatalf("%v reached the service: %#v", args, automation.fileTransfers)
			}
		}
	})

	// MCP 出口已开放（见 internal/mcpserver/file_transfer.go 的独立风险门）。
	// 原先这里锁的是「MCP 无出口」，该条已不成立；换锁新不变量：出口存在，
	// 但仍不得出现任何删除类工具。覆盖与不覆盖的实证在 mcpserver 侧用真实服务层验证。
	t.Run("mcp exposes transfer but never deletion", func(t *testing.T) {
		var hasPush, hasPull bool
		for _, name := range mcpserver.ToolNames {
			lowered := strings.ToLower(name)
			for _, banned := range []string{"delete", "remove", "unlink"} {
				if strings.Contains(lowered, banned) {
					t.Fatalf("MCP exposes a deletion tool %q", name)
				}
			}
			switch name {
			case mcpserver.ToolFilePush:
				hasPush = true
			case mcpserver.ToolFilePull:
				hasPull = true
			}
		}
		if !hasPush || !hasPull {
			t.Fatalf("file transfer tools missing: push=%v pull=%v", hasPush, hasPull)
		}
	})

	// push / pull 的正常出口确实存在且能把请求送到服务层——否则上面的否定断言
	// 会因为「命令根本不存在」而空洞通过。
	t.Run("push and pull entry points exist", func(t *testing.T) {
		for _, subcommand := range []string{"push", "pull"} {
			automation := &actionCapturingAutomation{}
			app, _ := testApp(&commandExecutor{}, "")
			app.Automation = automation

			exitCode := app.Run(context.Background(), []string{
				"file", subcommand, "--device", "SERIAL",
				"--local", "a", "--remote", "/sdcard/b", "--json",
			})

			if exitCode != 0 {
				t.Fatalf("file %s exited %d", subcommand, exitCode)
			}
			if len(automation.fileTransfers) != 1 {
				t.Fatalf("file %s did not reach the service: %#v",
					subcommand, automation.fileTransfers)
			}
		}
	})
}

// TestBridgeImeShowRoutesThroughTheBridgeChannel 锁定 ime.show 的通道归属。
//
// 它必须挂在 bridge 子命令下而不是 action：action 走直连，而 Android 没有
// 「打开键盘」的 keycode，ime.show 只能走无障碍。若有人把它挪到 action 下，
// 命令会因 bridge 为 nil 永远返回 unavailable——那种失败在编译期看不出来。
func TestBridgeImeShowRoutesThroughTheBridgeChannel(t *testing.T) {
	automation := &actionCapturingAutomation{}
	app, _ := testApp(&commandExecutor{}, "")
	app.Automation = automation

	exitCode := app.Run(context.Background(), []string{
		"bridge", "ime-show", "--device", "SERIAL", "--json",
	})

	if exitCode != 0 {
		t.Fatalf("exit code = %d", exitCode)
	}
	if len(automation.imeShowDevices) != 1 || automation.imeShowDevices[0] != "SERIAL" {
		t.Fatalf("ime show calls = %#v", automation.imeShowDevices)
	}
	// 不接受选择器类参数：聚焦的是系统当前输入焦点。
	rejected := app.Run(context.Background(), []string{
		"bridge", "ime-show", "--device", "SERIAL", "--resource-id", "x", "--json",
	})
	if rejected == 0 {
		t.Fatal("ime-show accepted a selector option")
	}
}
