package adb

// 测试用途：验证文件传输的「只新增、不覆盖」保护。
//
// 覆盖保护放在 adb 层而不是 CLI 参数层：CLI 只是出口之一，若保护写在出口侧，
// 将来其它调用方接出口时会绕过它。这些用例直接打 Client.PushFile / PullFile，
// 正是为了证明保护在这一层生效。

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

// onlineDeviceResult 是 requireOnlineDevice 需要的设备列表输出。
func onlineDeviceResult() process.Result {
	return process.Result{
		Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1 transport_id:1\n"),
	}
}

// existsProbeResult 构造远端存在性探测的返回。
//
// 约定：命中（文件已存在）时探测命令以退出码 0 输出存在标记，
// 未命中时以非零退出码返回。
func existsProbeResult(exists bool) (process.Result, error) {
	if exists {
		return process.Result{Stdout: []byte(remoteExistsMarker + "\n")}, nil
	}
	return process.Result{ExitCode: 1}, nil
}

func TestPushFileRefusesToOverwriteAnExistingRemotePath(t *testing.T) {
	probe, probeErr := existsProbeResult(true)
	fake := &fakeADB{
		results: []process.Result{onlineDeviceResult(), probe},
		errors:  []error{nil, probeErr},
	}
	client := NewClient("adb", fake)
	local := filepath.Join(t.TempDir(), "src.txt")
	if err := os.WriteFile(local, []byte("x"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	_, err := client.PushFile(
		context.Background(), "SERIAL", local, "/data/local/tmp/taken.txt",
	)

	if err == nil {
		t.Fatal("PushFile(existing remote) = nil, want error")
	}
	assertAppErrorCode(t, err, apperr.CodeActionNotAllowed)

	// 关键断言：绝不能发生 push。adb push 会直接覆盖，一旦发出就无法挽回。
	for _, call := range fake.calls {
		for _, arg := range call {
			if arg == "push" {
				t.Fatalf("push was executed despite an existing remote file: %#v", fake.calls)
			}
		}
	}
}

func TestPushFileWritesWhenTheRemotePathIsFree(t *testing.T) {
	probe, probeErr := existsProbeResult(false)
	fake := &fakeADB{
		results: []process.Result{onlineDeviceResult(), probe, {}},
		errors:  []error{nil, probeErr, nil},
	}
	client := NewClient("adb", fake)
	local := filepath.Join(t.TempDir(), "src.txt")
	if err := os.WriteFile(local, []byte("x"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	transfer, err := client.PushFile(
		context.Background(), "SERIAL", local, "/data/local/tmp/free.txt",
	)

	if err != nil {
		t.Fatalf("PushFile() error = %v", err)
	}
	if transfer.Action != "file.push" || transfer.RemotePath != "/data/local/tmp/free.txt" {
		t.Fatalf("transfer = %#v", transfer)
	}
	last := fake.calls[len(fake.calls)-1]
	if last[len(last)-3] != "push" {
		t.Fatalf("last call was not a push: %#v", last)
	}
}

// 含空格的远端路径是本项最容易踩空的地方。
//
// validateRemotePath 的字符集允许空格，若探测命令把路径裸拼进 shell，
// `test -e /sdcard/a b.txt` 会被拆成两个参数，对已存在的文件误判为不存在，
// 于是静默放行覆盖——这不是注入，是保护失效。
func TestPushFileDetectsExistingRemotePathContainingSpaces(t *testing.T) {
	probe, probeErr := existsProbeResult(true)
	fake := &fakeADB{
		results: []process.Result{onlineDeviceResult(), probe},
		errors:  []error{nil, probeErr},
	}
	client := NewClient("adb", fake)
	local := filepath.Join(t.TempDir(), "src.txt")
	if err := os.WriteFile(local, []byte("x"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	remote := "/sdcard/Download/report 1.json"

	_, err := client.PushFile(context.Background(), "SERIAL", local, remote)

	if err == nil {
		t.Fatal("PushFile(existing remote with spaces) = nil, want error")
	}
	// 路径必须作为单个 shell 词传递，否则探测命令看到的不是完整路径。
	probeCall := strings.Join(fake.calls[len(fake.calls)-1], " ")
	if !strings.Contains(probeCall, "'"+remote+"'") {
		t.Fatalf("remote path was not quoted as one shell word: %s", probeCall)
	}
}

func TestPullFileRefusesToOverwriteAnExistingLocalPath(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{onlineDeviceResult()},
		errors:  []error{nil},
	}
	client := NewClient("adb", fake)
	local := filepath.Join(t.TempDir(), "already-here.txt")
	if err := os.WriteFile(local, []byte("precious"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	_, err := client.PullFile(
		context.Background(), "SERIAL", "/data/local/tmp/a.txt", local,
	)

	if err == nil {
		t.Fatal("PullFile(existing local) = nil, want error")
	}
	assertAppErrorCode(t, err, apperr.CodeActionNotAllowed)

	// 本地文件内容必须原样保留。
	content, readErr := os.ReadFile(local)
	if readErr != nil || string(content) != "precious" {
		t.Fatalf("local file was disturbed: %q, err=%v", string(content), readErr)
	}
	for _, call := range fake.calls {
		for _, arg := range call {
			if arg == "pull" {
				t.Fatalf("pull was executed despite an existing local file: %#v", fake.calls)
			}
		}
	}
}

func TestPullFileWritesWhenTheLocalPathIsFree(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{onlineDeviceResult(), {}},
		errors:  []error{nil, nil},
	}
	client := NewClient("adb", fake)
	local := filepath.Join(t.TempDir(), "fresh.txt")

	transfer, err := client.PullFile(
		context.Background(), "SERIAL", "/data/local/tmp/a.txt", local,
	)

	if err != nil {
		t.Fatalf("PullFile() error = %v", err)
	}
	if transfer.Action != "file.pull" {
		t.Fatalf("transfer = %#v", transfer)
	}
}

// 存在性探测本身失败时必须拒绝传输，不能「探测不出来就当作不存在」。
//
// 否则设备临时不可达或命令被拒时，保护会静默退化成允许覆盖。
func TestPushFileRefusesWhenExistenceCannotBeDetermined(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{onlineDeviceResult(), {ExitCode: 2, Stderr: []byte("boom")}},
		errors:  []error{nil, context.DeadlineExceeded},
	}
	client := NewClient("adb", fake)
	local := filepath.Join(t.TempDir(), "src.txt")
	if err := os.WriteFile(local, []byte("x"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	_, err := client.PushFile(
		context.Background(), "SERIAL", local, "/data/local/tmp/unknown.txt",
	)

	if err == nil {
		t.Fatal("PushFile(probe failed) = nil, want error")
	}
	for _, call := range fake.calls {
		for _, arg := range call {
			if arg == "push" {
				t.Fatalf("push ran after an inconclusive probe: %#v", fake.calls)
			}
		}
	}
}
