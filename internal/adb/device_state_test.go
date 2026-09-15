package adb

// 测试用途：本文件用真实设备验证设备状态控制、IME 枚举与文件传输，
// 并用参数校验断言覆盖不需要设备的分支。
//
// 设备测试通过 AACTL_DEVICE_SERIAL 与 AACTL_ADB_PATH 显式门控而非自动发现：
// 这些操作会真实改变设备状态（旋转屏幕、写文件），自动发现会让一次无意的
// `go test ./...` 动到操作员正在使用的设备。

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/process"
)

func TestValidateRemotePathRejectsInjectionAndTraversal(t *testing.T) {
	// 远端路径会作为 shell 参数传给设备，因此注入字符必须在到达 adb 之前被拒。
	cases := map[string]string{
		"relative":        "data/local/tmp/a.txt",
		"parent":          "/data/local/tmp/../../system/x",
		"semicolon":       "/data/local/tmp/a;rm -rf /",
		"backtick":        "/data/local/tmp/`id`",
		"dollar":          "/data/local/tmp/$HOME",
		"doubleQuote":     "/data/local/tmp/\"a\"",
		"singleQuote":     "/data/local/tmp/'a'",
		"pipe":            "/data/local/tmp/a|b",
		"ampersand":       "/data/local/tmp/a&b",
		"empty":           "",
		"newlineInjected": "/data/local/tmp/a\nrm",
	}
	for name, remotePath := range cases {
		t.Run(name, func(t *testing.T) {
			if err := validateRemotePath(remotePath); err == nil {
				t.Fatalf("validateRemotePath(%q) = nil, want error", remotePath)
			}
		})
	}
}

func TestValidateRemotePathAcceptsOrdinaryDevicePaths(t *testing.T) {
	for _, remotePath := range []string{
		"/data/local/tmp/a.txt",
		"/sdcard/Download/report 1.json",
		"/data/local/tmp/nested/dir-name_v2.log",
	} {
		if err := validateRemotePath(remotePath); err != nil {
			t.Fatalf("validateRemotePath(%q) = %v, want nil", remotePath, err)
		}
	}
}

func TestValidateLocalPathAllowsNonASCIIButRejectsNullByte(t *testing.T) {
	// 本地路径不经 shell 解释，因此不限制字符集——限制会拒绝合法的中文路径。
	if err := validateLocalPath("E:\\项目\\报告 1.json"); err != nil {
		t.Fatalf("validateLocalPath(non-ASCII) = %v, want nil", err)
	}
	if err := validateLocalPath("a\x00b"); err == nil {
		t.Fatal("validateLocalPath(null byte) = nil, want error")
	}
	if err := validateLocalPath(""); err == nil {
		t.Fatal("validateLocalPath(empty) = nil, want error")
	}
}

func TestRotateRejectsUnknownOrientation(t *testing.T) {
	client := NewClient("adb", process.Runner{})
	// 未知方向必须在接触设备之前被拒，因此这条断言不需要真实设备。
	if _, err := client.Rotate(
		context.Background(), "SERIAL", Orientation("upside"),
	); err == nil {
		t.Fatal("Rotate(unknown orientation) = nil, want error")
	}
}

func TestOpenDeeplinkRejectsMalformedAndUnsafeURIs(t *testing.T) {
	client := NewClient("adb", process.Runner{})
	for name, uri := range map[string]string{
		"noScheme":   "example.com/path",
		"empty":      "",
		"withSpace":  "https://example.com/a b",
		"semicolon":  "https://example.com/a;id",
		"backtick":   "https://example.com/`id`",
		"substitute": "https://example.com/$(id)",
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := client.OpenDeeplink(
				context.Background(), "SERIAL", uri, "",
			); err == nil {
				t.Fatalf("OpenDeeplink(%q) = nil, want error", uri)
			}
		})
	}
}

func TestSelectInputMethodRejectsMalformedIdentifier(t *testing.T) {
	client := NewClient("adb", process.Runner{})
	for _, identifier := range []string{
		"notacomponent",
		"com.example",
		"com.example/",
		"/com.example.Ime",
		"com.example/com.example.Ime;id",
	} {
		if _, err := client.SelectInputMethod(
			context.Background(), "SERIAL", identifier,
		); err == nil {
			t.Fatalf("SelectInputMethod(%q) = nil, want error", identifier)
		}
	}
}

// TestDeviceStateAgainstRealDevice 在显式指定设备时验证真实行为。
//
// 一次跑完亮屏检测、IME 枚举、旋转与文件往返，而不是拆成多个用例：
// 这些操作共享同一台设备，拆开跑会让清理顺序难以保证——旋转没恢复就进入
// 下一个用例，后续断言会在意外的屏幕方向上执行。
func TestDeviceStateAgainstRealDevice(t *testing.T) {
	serial := os.Getenv("AACTL_DEVICE_SERIAL")
	adbPath := os.Getenv("AACTL_ADB_PATH")
	if serial == "" || adbPath == "" {
		t.Skip(
			"set AACTL_DEVICE_SERIAL and AACTL_ADB_PATH to run device state checks",
		)
	}
	client := NewClient(adbPath, process.Runner{})
	ctx := context.Background()

	// 亮屏检测：先确认设备处于可交互状态，后续动作才有意义。
	state, err := client.ScreenState(ctx, serial)
	if err != nil {
		t.Fatalf("ScreenState() error = %v", err)
	}
	if state.State == "" {
		t.Fatal("ScreenState() returned an empty state name")
	}
	if !state.On {
		if _, err := client.Wake(ctx, serial); err != nil {
			t.Fatalf("Wake() error = %v", err)
		}
	}

	// IME 枚举：至少要有一个输入法，否则文本输入类能力在此设备上无从验证。
	methods, err := client.ListInputMethods(ctx, serial)
	if err != nil {
		t.Fatalf("ListInputMethods() error = %v", err)
	}
	if len(methods) == 0 {
		t.Fatal("ListInputMethods() returned no enabled input methods")
	}
	for _, identifier := range methods {
		if !imeIDPattern.MatchString(identifier) {
			t.Fatalf("ListInputMethods() returned malformed identifier %q", identifier)
		}
	}
	// 未启用的输入法必须被拒：这条断言证明校验发生在 `ime set` 之前，
	// 否则会得到一次静默失败而不是明确错误。
	if _, err := client.SelectInputMethod(
		ctx, serial, "com.example.absent/com.example.absent.Ime",
	); err == nil {
		t.Fatal("SelectInputMethod(not enabled) = nil, want error")
	}

	// 旋转：横屏后必须能恢复，否则会把设备留在意外方向上影响后续测试。
	for _, orientation := range []Orientation{
		OrientationLandscape,
		OrientationPortrait,
		// 最后交还传感器，把设备恢复到测试前的常规状态。
		OrientationAuto,
	} {
		if _, err := client.Rotate(ctx, serial, orientation); err != nil {
			t.Fatalf("Rotate(%s) error = %v", orientation, err)
		}
	}

	// 文件往返：push 后 pull 回来比对内容。只验证 push 成功不足以证明文件
	// 真的完整落在设备上。
	localDir := t.TempDir()
	sourcePath := filepath.Join(localDir, "aactl-transfer-probe.txt")
	payload := "aactl action surface transfer probe\n"
	if err := os.WriteFile(sourcePath, []byte(payload), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	remotePath := "/data/local/tmp/aactl-transfer-probe.txt"
	if _, err := client.PushFile(ctx, serial, sourcePath, remotePath); err != nil {
		t.Fatalf("PushFile() error = %v", err)
	}
	// 无论后续断言成败都清理设备端文件：残留会让下一轮的比对结果失去意义。
	defer func() {
		_, _ = client.run(context.WithoutCancel(ctx), []string{
			"-s", serial, "shell", "rm", "-f", remotePath,
		}, process.Options{Timeout: 10 * time.Second})
	}()

	destinationPath := filepath.Join(localDir, "aactl-transfer-probe-back.txt")
	if _, err := client.PullFile(ctx, serial, remotePath, destinationPath); err != nil {
		t.Fatalf("PullFile() error = %v", err)
	}
	pulled, err := os.ReadFile(destinationPath)
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
	if strings.TrimRight(string(pulled), "\r\n") != strings.TrimRight(payload, "\r\n") {
		t.Fatalf("pulled content = %q, want %q", string(pulled), payload)
	}
}
