package adb

// 测试用途：验证套接字抢占、伪造进程名、fd 归属与 SHA-256 部署边界。

import (
	"context"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"strings"
	"testing"
)

func TestDisplaydIdentityRequiresShellAndSocketInode(t *testing.T) {
	socket := "000: 00000002 00000000 00010000 0001 01 666 @aactl-displayd-v1\n"
	for _, tt := range []struct{ name, ps, sockets, fds, code string }{
		{"owned", "shell 42 aactl-displayd", socket, "lrwx------ 1 shell shell 64 7 -> socket:[666]", ""},
		{"absent", "USER PID NAME", "", "", ""},
		{"occupied without helper", "u0_a12 51 other", socket, "", apperr.CodeDisplaydIdentity},
		{"spoofed name", "u0_a12 42 aactl-displayd", socket, "", apperr.CodeDisplaydIdentity},
		{"root is not shell", "root 42 aactl-displayd", socket, "", apperr.CodeDisplaydIdentity},
		{"wrong inode", "shell 42 aactl-displayd", socket, "7 -> socket:[6660]", apperr.CodeDisplaydIdentity},
		{"duplicate helper", "shell 42 aactl-displayd\nshell 43 aactl-displayd", socket, "", apperr.CodeDisplaydIdentity},
	} {
		t.Run(tt.name, func(t *testing.T) {
			f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("35")}, {Stdout: []byte(tt.ps)}, {Stdout: []byte(tt.sockets)}, {Stdout: []byte(tt.fds)}}, errors: make([]error, 5)}
			state, err := NewClient("adb", f).InspectDisplayd(context.Background(), "FIRST")
			if tt.code != "" {
				assertAppErrorCode(t, err, tt.code)
			} else if err != nil {
				t.Fatal(err)
			} else if tt.name == "owned" && (!state.Bound || state.PID != 42) {
				t.Fatal(state)
			}
			for _, args := range f.calls {
				if strings.Contains(strings.Join(args, " "), "forward") {
					t.Fatal("identity inspection opened socket")
				}
			}
		})
	}
}

func TestDisplaydAPIFloorAndInvalidTarget(t *testing.T) {
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte("32")}}, errors: []error{nil, nil}}
	_, err := NewClient("adb", f).InspectDisplayd(context.Background(), "FIRST")
	assertAppErrorCode(t, err, apperr.CodeUnsupportedAPI)
	f = &fakeADB{}
	_, err = NewClient("adb", f).WithDisplay(-1).Tap(context.Background(), "FIRST", 1, 2)
	assertAppErrorCode(t, err, apperr.CodeDisplayNotFound)
	if len(f.calls) != 0 {
		t.Fatal("invalid target sent to adb")
	}
}

func TestDeployDisplaydHashesBeforeAndAfterPush(t *testing.T) {
	digest := strings.Repeat("a", 64)
	for _, tt := range []struct {
		name, remote, after string
		replace, push       bool
		code                string
	}{
		{"same", digest, digest, true, false, ""},
		{"changed", strings.Repeat("b", 64), digest, true, true, ""},
		{"bad transfer", strings.Repeat("b", 64), strings.Repeat("c", 64), true, true, apperr.CodeProtocol},
		{"running mismatch", strings.Repeat("b", 64), digest, false, false, apperr.CodeDisplaydIdentity},
	} {
		t.Run(tt.name, func(t *testing.T) {
			f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stdout: []byte(tt.remote + "  " + displaydAPK)}, {}, {Stdout: []byte(tt.after + "  " + displaydAPK)}}, errors: make([]error, 4)}
			_, err := NewClient("adb", f).DeployDisplayd(context.Background(), "FIRST", "C:/with space/helper.apk", digest, tt.replace)
			if tt.code != "" {
				assertAppErrorCode(t, err, tt.code)
			} else if err != nil {
				t.Fatal(err)
			}
			if tt.push {
				assertArgs(t, f.calls[2], "-s", "FIRST", "push", "C:/with space/helper.apk", displaydAPK)
			} else if len(f.calls) != 2 {
				t.Fatal("unexpected push")
			}
		})
	}
	// 文件不存在才允许首次部署，权限或传输错误不能被当作缺失。
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {Stderr: []byte("No such file"), ExitCode: 1}, {}, {Stdout: []byte(digest + "  " + displaydAPK)}}, errors: []error{nil, errors.New("missing"), nil, nil}}
	if _, err := NewClient("adb", f).DeployDisplayd(context.Background(), "FIRST", "helper.apk", digest, true); err != nil {
		t.Fatal(err)
	}
}

func TestSleepUsesMainDisplayDedicatedKey(t *testing.T) {
	f := &fakeADB{results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {}}, errors: []error{nil, nil}}
	_, err := NewClient("adb", f).Sleep(context.Background(), "FIRST")
	if err != nil {
		t.Fatal(err)
	}
	assertArgs(t, f.calls[1], "-s", "FIRST", "shell", "input", "keyevent", "KEYCODE_SLEEP")
}
