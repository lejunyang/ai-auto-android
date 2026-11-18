package adb

// 功能用途：为直接后端提供可选显示目标，保持未指定目标时的既有命令兼容。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"strconv"
	"strings"
)

// WithDisplay 返回针对一个显示目标的独立客户端视图。
func (c *Client) WithDisplay(id int) *Client { clone := *c; clone.displayID = &id; return &clone }

// ForwardAbstract 为本地抽象套接字建立临时转发。
func (c *Client) ForwardAbstract(ctx context.Context, serial, socket string) (Forward, error) {
	if socket == "" || len(socket) > 100 || strings.IndexFunc(socket, func(r rune) bool {
		return !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.')
	}) >= 0 {
		return Forward{}, invalidField("socket", "has an invalid localabstract name")
	}
	if err := c.requireOnlineDevice(ctx, serial); err != nil {
		return Forward{}, err
	}
	result, err := c.run(ctx, []string{"-s", serial, "forward", "tcp:0", "localabstract:" + socket}, process.Options{})
	if err != nil {
		return Forward{}, err
	}
	port, err := strconv.Atoi(strings.TrimSpace(string(result.Stdout)))
	if result.OutputTruncated || err != nil || port < 1 || port > MaxTCPPort {
		return Forward{}, apperr.New(apperr.CodeProtocol, "ADB returned an invalid localabstract port allocation.", false, nil)
	}
	return Forward{Device: serial, LocalPort: port}, nil
}

// Sleep 使用专用幂等按键使主屏息屏，不扩大普通按键白名单。
func (c *Client) Sleep(ctx context.Context, serial string) (ActionResult, error) {
	if c.displayID != nil {
		return ActionResult{}, invalidField("display", "cannot be used with device.sleep")
	}
	return c.inputAction(ctx, serial, "device.sleep", "keyevent", "KEYCODE_SLEEP")
}
