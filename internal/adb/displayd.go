package adb

// 功能用途：封装 displayd 固定部署路径、进程与套接字归属检查，不开放任意 shell 执行。

import (
	"context"
	"fmt"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"regexp"
	"strconv"
	"strings"
)

// DisplaydSocket 与部署路径是协议固定值，不能由远端响应或调用方改变。
const DisplaydSocket = "aactl-displayd-v1"
const displaydAPK = "/data/local/tmp/aactl-displayd.apk"
const displaydLog = "/data/local/tmp/aactl-displayd.log"

// DisplaydState 是经设备侧 /proc 核对的 shell 进程及监听套接字归属。
type DisplaydState struct {
	APILevel int
	PID      int
	Bound    bool
}

// InspectDisplayd 在建立 forward 前检查身份，拒绝被其它进程抢占的抽象套接字。
func (c *Client) InspectDisplayd(ctx context.Context, serial string) (DisplaydState, error) {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return DisplaydState{}, err
	}
	read := func(args ...string) (string, error) {
		r, e := c.run(ctx, append([]string{"-s", serial, "shell"}, args...), process.Options{})
		if e != nil {
			return "", e
		}
		if r.OutputTruncated {
			return "", apperr.New(apperr.CodeProtocol, "Truncated displayd identity output.", false, nil)
		}
		return string(r.Stdout), nil
	}
	sdk, err := read("getprop", "ro.build.version.sdk")
	if err != nil {
		return DisplaydState{}, err
	}
	api, err := strconv.Atoi(strings.TrimSpace(sdk))
	if err != nil {
		return DisplaydState{}, apperr.New(apperr.CodeProtocol, "Invalid device API level.", false, nil)
	}
	state := DisplaydState{APILevel: api}
	if api < 33 {
		return state, apperr.New(apperr.CodeUnsupportedAPI, "Virtual displays require Android API 33+.", false, nil)
	}
	ps, err := read("ps", "-A", "-o", "USER,PID,NAME")
	if err != nil {
		return state, err
	}
	identityError := func() error {
		return apperr.New(apperr.CodeDisplaydIdentity, "Cannot verify shell-owned aactl-displayd and its listening socket; refusing connection.", false, nil)
	}
	for _, line := range strings.Split(ps, "\n") {
		f := strings.Fields(line)
		if len(f) != 3 || f[2] != "aactl-displayd" {
			continue
		}
		if f[0] != "shell" || state.PID != 0 {
			return state, identityError()
		}
		state.PID, err = strconv.Atoi(f[1])
		if err != nil || state.PID <= 0 {
			return state, identityError()
		}
	}
	sockets, err := read("cat", "/proc/net/unix")
	if err != nil {
		return state, err
	}
	inode := ""
	for _, line := range strings.Split(sockets, "\n") {
		f := strings.Fields(line)
		if len(f) == 8 && f[7] == "@"+DisplaydSocket {
			if inode != "" || f[3] != "00010000" || f[4] != "0001" {
				return state, identityError()
			}
			inode = f[6]
		}
	}
	if inode == "" {
		return state, nil
	}
	if state.PID == 0 {
		return state, identityError()
	}
	if _, err := strconv.ParseUint(inode, 10, 64); err != nil {
		return state, identityError()
	}
	fds, err := read("ls", "-l", fmt.Sprintf("/proc/%d/fd", state.PID))
	if err != nil {
		return state, identityError()
	}
	if !strings.Contains(fds, " -> socket:["+inode+"]") {
		return state, identityError()
	}
	state.Bound = true
	return state, nil
}

// DeployDisplayd 只在本地与远端 SHA-256 不一致时推送，推送后再验证。
func (c *Client) DeployDisplayd(ctx context.Context, serial, local, digest string, replace bool) (bool, error) {
	if !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(digest) {
		return false, invalidField("digest", "must be SHA-256")
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return false, err
	}
	remote := func() (string, error) {
		r, e := c.run(ctx, []string{"-s", serial, "shell", "sha256sum", displaydAPK}, process.Options{})
		if e != nil {
			if strings.Contains(string(r.Stderr)+string(r.Stdout), "No such file") {
				return "", nil
			}
			return "", e
		}
		fields := strings.Fields(string(r.Stdout))
		if r.OutputTruncated || len(fields) != 2 || !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(fields[0]) {
			return "", apperr.New(apperr.CodeProtocol, "Invalid remote helper SHA-256.", false, nil)
		}
		return fields[0], nil
	}
	got, err := remote()
	if err != nil {
		return false, err
	}
	if got == digest {
		return false, nil
	}
	if !replace {
		return false, apperr.New(apperr.CodeDisplaydIdentity, "Running displayd APK differs from local helper; stop it before deploying a new build.", false, nil)
	}
	if _, err = c.run(ctx, []string{"-s", serial, "push", local, displaydAPK}, process.Options{}); err != nil {
		return false, err
	}
	got, err = remote()
	if err != nil {
		return false, err
	}
	if got != digest {
		return false, apperr.New(apperr.CodeProtocol, "Deployed helper SHA-256 does not match local APK.", false, nil)
	}
	return true, nil
}

// StartDisplayd 只发出固定框架入口命令，启动后的绑定身份由客户端再次核对。
func (c *Client) StartDisplayd(ctx context.Context, serial string) error {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return err
	}
	_, err := c.run(ctx, []string{"-s", serial, "shell", "CLASSPATH=" + displaydAPK + " nohup app_process / --nice-name=aactl-displayd dev.aiauto.displayd.Main </dev/null >" + displaydLog + " 2>&1 &"}, process.Options{})
	return err
}

// RemoveDisplaydFiles 仅删除本功能固定的 APK 与日志，不接受外部路径。
func (c *Client) RemoveDisplaydFiles(ctx context.Context, serial string) error {
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return err
	}
	_, err := c.run(ctx, []string{"-s", serial, "shell", "rm", "-f", displaydAPK, displaydLog}, process.Options{})
	return err
}
