package adb

// 测试用途：用真实双显示 XML 验证显示归属筛选、格式错误与预算拒绝，防止其它显示内容泄露。

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

func windowsFixture(t *testing.T) []byte {
	t.Helper()
	data, err := os.ReadFile("testdata/windows-api35.xml")
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestSelectDisplayWindowsUsesRealDisplayGroups(t *testing.T) {
	data := windowsFixture(t)
	for _, id := range []int{0, 2} {
		selected, err := selectDisplayWindows(data, id)
		if err != nil {
			t.Fatal(err)
		}
		text := string(selected)
		if id == 2 {
			if !strings.Contains(text, `package="dev.aiauto.fixture"`) || strings.Contains(text, "nexuslauncher") || strings.Contains(text, `<display id="0"`) {
				t.Fatal("other display leaked")
			}
		} else if !strings.Contains(text, "nexuslauncher") || strings.Contains(text, "dev.aiauto.fixture") {
			t.Fatal("wrong primary selection")
		}
	}
	// 调换真实 display 块的顺序，验证筛选依据是编号而不是位置。
	start := strings.Index(string(data), `<display id="2">`)
	end := strings.LastIndex(string(data), "</displays>")
	first := strings.Index(string(data), "<display ")
	reordered := string(data[:first]) + string(data[start:end]) + string(data[first:start]) + "</displays>"
	selected, err := selectDisplayWindows([]byte(reordered), 2)
	if err != nil || !strings.Contains(string(selected), "dev.aiauto.fixture") || strings.Contains(string(selected), "nexuslauncher") {
		t.Fatalf("reordered groups: %v", err)
	}
}

func TestSelectDisplayWindowsRejectsMissingMalformedAndOversize(t *testing.T) {
	data := windowsFixture(t)
	_, err := selectDisplayWindows(data, 999)
	assertAppErrorCode(t, err, apperr.CodeDisplayNotFound)
	for name, invalid := range map[string][]byte{
		"truncated":    data[:len(data)-20],
		"invalid id":   []byte(strings.Replace(string(data), `<display id="2">`, `<display id="bogus">`, 1)),
		"duplicate id": []byte(strings.Replace(string(data), `<display id="0">`, `<display id="2">`, 1)),
		"second root":  append(append([]byte(nil), data...), data...),
		"oversize":     append(append([]byte(nil), data...), make([]byte, maxHierarchyOutput)...),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := selectDisplayWindows(invalid, 2); err == nil {
				t.Fatal("invalid XML accepted")
			}
		})
	}
}

func TestDisplayHierarchyRealHelpIsIrrelevantAndWindowsArgIsExact(t *testing.T) {
	help, err := os.ReadFile("testdata/uiautomator-help-api35.txt")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(help), "--windows") || strings.Contains(string(help), "--display-id") {
		t.Fatal("real help fixture unexpectedly changed")
	}
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("35")}, {}, {Stdout: windowsFixture(t)}, {}}, errors: make([]error, 5)}
	got, err := NewClient("adb", f).WithDisplay(2).Hierarchy(context.Background(), "FIRST")
	if err != nil {
		t.Fatal(err)
	}
	if got.Format != "uiautomator-windows-xml" || strings.Contains(got.XML, "nexuslauncher") || !strings.Contains(got.XML, "dev.aiauto.fixture") {
		t.Fatalf("result: %+v", got)
	}
	path := f.calls[2][len(f.calls[2])-1]
	assertArgs(t, f.calls[1], "-s", "FIRST", "shell", "getprop", "ro.build.version.sdk")
	assertArgs(t, f.calls[2], "-s", "FIRST", "shell", "uiautomator", "dump", "--windows", path)
	for _, args := range f.calls {
		if strings.Contains(strings.Join(args, " "), "uiautomator help") {
			t.Fatal("help consulted")
		}
	}
}

func TestDisplayHierarchyRejectsUnknownOrOldAPIBeforeDump(t *testing.T) {
	for _, sdk := range []string{"29", "", "unknown", "35garbage"} {
		t.Run(sdk, func(t *testing.T) {
			f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte(sdk)}}, errors: make([]error, 2)}
			_, err := NewClient("adb", f).WithDisplay(2).Hierarchy(context.Background(), "FIRST")
			assertAppErrorCode(t, err, apperr.CodeCapabilityMissing)
			if len(f.calls) != 2 {
				t.Fatal("command sent despite unsupported API")
			}
		})
	}
}

func TestDisplayHierarchyAcceptsAPI30WindowsBoundary(t *testing.T) {
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("30")}, {}, {Stdout: windowsFixture(t)}, {}}, errors: make([]error, 5)}
	if _, err := NewClient("adb", f).WithDisplay(2).Hierarchy(context.Background(), "FIRST"); err != nil {
		t.Fatal(err)
	}
}

func TestDisplayHierarchyRejectsTruncatedADBOutputAndCleans(t *testing.T) {
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("35")}, {}, {Stdout: windowsFixture(t), OutputTruncated: true}, {}}, errors: make([]error, 5)}
	_, err := NewClient("adb", f).WithDisplay(2).Hierarchy(context.Background(), "FIRST")
	if err == nil {
		t.Fatal("truncated output accepted")
	}
	if len(f.calls) != 5 || f.calls[4][3] != "rm" {
		t.Fatal("remote file not cleaned")
	}
}
