package adb

// 测试用途：验证显示定向启动严格解析同包单行Launcher，异常输出或解析失败绝不执行am或回退主屏。

import (
	"context"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"testing"
)

func TestDisplayLaunchResolvesSingleSamePackageComponent(t *testing.T) {
	for _, component := range []string{"com.example.app/.MainActivity", "com.example.app/com.example.app.MainActivity"} {
		f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte(component + "\r\n")}, {}}, errors: make([]error, 3)}
		_, err := NewClient("adb", f).WithDisplay(7).Launch(context.Background(), "FIRST", "com.example.app", "")
		if err != nil || len(f.calls) != 3 {
			t.Fatal(err, f.calls)
		}
		assertArgs(t, f.calls[1], "-s", "FIRST", "shell", "cmd", "package", "resolve-activity", "--brief", "--components", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "com.example.app")
		assertArgs(t, f.calls[2], "-s", "FIRST", "shell", "am", "start", "-W", "--display", "7", "-n", component)
	}
}
func TestDisplayLaunchRejectsUntrustedResolverOutput(t *testing.T) {
	for _, output := range []string{"", "No activity found\n", "other.pkg/.MainActivity\n", "com.example.app/.MainActivity\ncom.example.app/.Other\n", "priority=0\ncom.example.app/.MainActivity\n", "\ncom.example.app/.MainActivity\n", "com.example.app/.MainActivity\n\n", "com.example.app/.MainActivity --display 0", "com.example.app/.Main;id", "com.example.app/MainActivity", "com.example.app/.MainActivity/other"} {
		t.Run(output, func(t *testing.T) {
			f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte(output)}}, errors: make([]error, 2)}
			_, err := NewClient("adb", f).WithDisplay(7).Launch(context.Background(), "FIRST", "com.example.app", "")
			assertAppErrorCode(t, err, apperr.CodeActionFailed)
			if len(f.calls) != 2 {
				t.Fatal("resolver failure started activity", f.calls)
			}
		})
	}
	for _, result := range []process.Result{{Stdout: []byte("com.example.app/.MainActivity"), OutputTruncated: true}, {Stdout: []byte("com.example.app/.MainActivity"), Stderr: []byte("warning")}, {ExitCode: 1}} {
		failErr := error(nil)
		if result.ExitCode != 0 {
			failErr = errors.New("resolver failed")
		}
		f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, result}, errors: []error{nil, failErr}}
		_, err := NewClient("adb", f).WithDisplay(7).Launch(context.Background(), "FIRST", "com.example.app", "")
		assertAppErrorCode(t, err, apperr.CodeActionFailed)
		if len(f.calls) != 2 {
			t.Fatal(f.calls)
		}
	}
}
