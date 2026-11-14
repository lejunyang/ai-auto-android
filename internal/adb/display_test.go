package adb

// 测试用途：验证显示参数的 argv，并确保省略参数时所有既有命令逐字不变。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"strings"
	"testing"
)

func TestDisplayInputArgumentsAndLegacyCompatibility(t *testing.T) {
	tests := []struct {
		name string
		call func(*Client) error
		args []string
	}{
		{"tap", func(c *Client) error { _, e := c.Tap(context.Background(), "FIRST", 1, 2); return e }, []string{"tap", "1", "2"}},
		{"swipe", func(c *Client) error { _, e := c.Swipe(context.Background(), "FIRST", 1, 2, 3, 4, 500); return e }, []string{"swipe", "1", "2", "3", "4", "500"}},
		{"key", func(c *Client) error { _, e := c.Key(context.Background(), "FIRST", "BACK"); return e }, []string{"keyevent", "KEYCODE_BACK"}},
		{"text", func(c *Client) error { _, e := c.Text(context.Background(), "FIRST", "hello world"); return e }, []string{"text", "hello%sworld"}},
	}
	for _, tt := range tests {
		for _, target := range []bool{false, true} {
			t.Run(tt.name+map[bool]string{true: "/display", false: "/legacy"}[target], func(t *testing.T) {
				f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {}}, errors: []error{nil, nil}}
				c := NewClient("adb", f)
				if target {
					c = c.WithDisplay(7)
				}
				if err := tt.call(c); err != nil {
					t.Fatal(err)
				}
				args := []string{"-s", "FIRST", "shell", "input"}
				if target {
					args = append(args, "-d", "7")
				}
				args = append(args, tt.args...)
				assertArgs(t, f.calls[1], args...)
			})
		}
	}
}

func TestDisplayLaunchAndHierarchyNeverUsePrimaryFallback(t *testing.T) {
	for _, target := range []bool{false, true} {
		t.Run(map[bool]string{false: "legacy", true: "display"}[target], func(t *testing.T) {
			f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {}}, errors: []error{nil, nil}}
			c := NewClient("adb", f)
			if target {
				c = c.WithDisplay(7)
			}
			if _, err := c.Launch(context.Background(), "FIRST", "com.example.app", ""); err != nil {
				t.Fatal(err)
			}
			if target {
				assertArgs(t, f.calls[1], "-s", "FIRST", "shell", "am", "start", "-W", "--display", "7", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", "com.example.app")
			} else {
				assertArgs(t, f.calls[1], "-s", "FIRST", "shell", "monkey", "-p", "com.example.app", "-c", "android.intent.category.LAUNCHER", "1")
			}
			f = &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {}}, errors: []error{nil, nil}}
			c = NewClient("adb", f)
			if target {
				c = c.WithDisplay(7)
			}
			if _, err := c.Launch(context.Background(), "FIRST", "com.example.app", ".MainActivity"); err != nil {
				t.Fatal(err)
			}
			args := []string{"-s", "FIRST", "shell", "am", "start", "-W"}
			if target {
				args = append(args, "--display", "7")
			}
			args = append(args, "-n", "com.example.app/.MainActivity")
			assertArgs(t, f.calls[1], args...)
			results := []process.Result{{Stdout: []byte(twoOnlineDevices)}}
			xmlData := []byte("<hierarchy/>")
			if target {
				results = append(results, process.Result{Stdout: []byte("35")})
				xmlData = []byte(strings.Replace(string(windowsFixture(t)), `<display id="2">`, `<display id="7">`, 1))
			}
			results = append(results, process.Result{}, process.Result{Stdout: xmlData}, process.Result{})
			f = &fakeADB{results: results, errors: make([]error, len(results))}
			c = NewClient("adb", f)
			if target {
				c = c.WithDisplay(7)
			}
			if _, err := c.Hierarchy(context.Background(), "FIRST"); err != nil {
				t.Fatal(err)
			}
			index := 1
			if target {
				index = 2
			}
			path := f.calls[index][len(f.calls[index])-1]
			args = []string{"-s", "FIRST", "shell", "uiautomator", "dump"}
			if target {
				args = append(args, "--windows")
			}
			args = append(args, path)
			assertArgs(t, f.calls[index], args...)
		})
	}
}

func TestDisplayHierarchyRejectsUnsupportedAPIBeforeDump(t *testing.T) {
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("29")}}, errors: []error{nil, nil}}
	_, err := NewClient("adb", f).WithDisplay(7).Hierarchy(context.Background(), "FIRST")
	if err == nil {
		t.Fatal("missing display-id support accepted")
	}
	for _, args := range f.calls {
		if strings.Contains(strings.Join(args, " "), "uiautomator dump") {
			t.Fatal("dump attempted without support")
		}
	}
}

func TestForwardAbstractUsesExactSocket(t *testing.T) {
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("41237")}}, errors: []error{nil, nil}}
	got, err := NewClient("adb", f).ForwardAbstract(context.Background(), "FIRST", "aactl-displayd-v1")
	if err != nil {
		t.Fatal(err)
	}
	if got.LocalPort != 41237 {
		t.Fatal(got)
	}
	assertArgs(t, f.calls[1], "-s", "FIRST", "forward", "tcp:0", "localabstract:aactl-displayd-v1")
}
