package adb

// 测试用途：用真实双显示 XML 验证显示归属筛选、格式错误与预算拒绝，防止其它显示内容泄露。

import (
	"context"
	"errors"
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
	assertAppErrorCode(t, err, apperr.CodeDisplayEmpty)
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

// 有效XML中的空屏与损坏输出不同；即使目标组缺失也必须校验完整尾部并清理远端文件。
func TestDisplayHierarchyReportsEmptyAfterFullValidation(t *testing.T) {
	for _, id := range []int{0, 7} {
		f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("35")}, {}, {Stdout: []byte("<displays/>")}, {}}, errors: make([]error, 5)}
		_, err := NewClient("adb", f).WithDisplay(id).Hierarchy(context.Background(), "FIRST")
		assertAppErrorCode(t, err, "DISPLAY_EMPTY")
		if len(f.calls) != 5 || f.calls[4][3] != "rm" {
			t.Fatal("cleanup missing or fallback attempted")
		}
	}
	_, err := selectDisplayWindows([]byte(`<displays><display id="0"/></displays>`), 0)
	assertAppErrorCode(t, err, "DISPLAY_EMPTY")
	_, err = selectDisplayWindows([]byte(`<displays></displays><broken`), 7)
	assertAppErrorCode(t, err, apperr.CodeActionFailed)
}

// 无文件/空文件是系统没有提供窗口，不是畸形XML；截断、权限失败与设备失败不能混为此错误。
type missingWindowExecutor struct {
	fakeADB
	missing bool
}

func (e *missingWindowExecutor) Run(ctx context.Context, program string, args []string, options process.Options) (process.Result, error) {
	if len(args) > 4 && args[2] == "exec-out" && e.missing {
		e.calls = append(e.calls, append([]string(nil), args...))
		return process.Result{ExitCode: 1, Stderr: []byte("cat: " + args[4] + ": No such file or directory\n")}, errors.New("exit 1")
	}
	return e.fakeADB.Run(ctx, program, args, options)
}
func TestMissingWindowDumpsHaveActionableError(t *testing.T) {
	for _, data := range [][]byte{nil, []byte(" \n\t")} {
		_, err := selectDisplayWindows(data, 7)
		assertAppErrorCode(t, err, "WINDOWS_UNAVAILABLE")
	}
	for _, missing := range []bool{false, true} {
		e := &missingWindowExecutor{missing: missing, fakeADB: fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("35")}, {}, {}, {}}, errors: make([]error, 5)}}
		_, err := NewClient("adb", e).WithDisplay(7).Hierarchy(context.Background(), "FIRST")
		assertAppErrorCode(t, err, "WINDOWS_UNAVAILABLE")
		if err == nil || !strings.Contains(err.Error(), "isolated") || !strings.Contains(err.Error(), "steal") || len(e.calls) != 5 || e.calls[4][3] != "rm" {
			t.Fatal("recovery/cleanup", err, e.calls)
		}
	}
}

func TestWindowDumpFailuresDoNotMasqueradeAsNoWindows(t *testing.T) {
	for _, tc := range []struct {
		result process.Result
		err    error
		code   string
	}{
		{process.Result{}, process.ErrTimeout, apperr.CodeDeadlineExceeded},
		{process.Result{ExitCode: 1, Stderr: []byte("Permission denied")}, errors.New("exit 1"), apperr.CodeDeviceUnreachable},
		{process.Result{OutputTruncated: true}, nil, apperr.CodeActionFailed},
		{process.Result{Stdout: []byte("<displays><display")}, nil, apperr.CodeActionFailed},
	} {
		f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("35")}, {}, tc.result, {}}, errors: []error{nil, nil, nil, tc.err, nil}}
		_, err := NewClient("adb", f).WithDisplay(7).Hierarchy(context.Background(), "FIRST")
		assertAppErrorCode(t, err, tc.code)
		if len(f.calls) != 5 {
			t.Fatal("fallback or missing cleanup", f.calls)
		}
	}
}
