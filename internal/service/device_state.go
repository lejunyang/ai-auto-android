package service

// 功能用途：本文件实现动作面扩展的设备状态观察、输入法枚举与文件传输。
//
// 这三类不走 ExecuteAction：它们的返回值不是 adb.ActionResult，
// 硬塞进那个签名会让调用方拿不到实际结果——亮屏状态、输入法列表、
// 传输的本地与远端路径都是调用方要用的信息，丢掉它们等于这些能力白做。

import (
	"context"

	"github.com/lejunyang/ai-auto-android/internal/adb"
)

// ScreenState 读取屏幕点亮状态。
func (s *Service) ScreenState(
	ctx context.Context,
	device string,
) (adb.ScreenState, error) {
	if err := adb.ValidateSerial(device); err != nil {
		return adb.ScreenState{}, err
	}
	if s.direct == nil {
		return adb.ScreenState{}, unavailable("ADB screen state observation")
	}
	return s.direct.ScreenState(ctx, device)
}

// InputMethods 列出设备上已启用的输入法。
//
// 返回列表而非只返回当前项：切换输入法前必须知道有哪些可选，
// 而 `ime set` 对未启用的输入法会静默失败，让调用方先看到候选集
// 才能给出有效的选择。
func (s *Service) InputMethods(
	ctx context.Context,
	device string,
) (InputMethodListResult, error) {
	if err := adb.ValidateSerial(device); err != nil {
		return InputMethodListResult{}, err
	}
	if s.direct == nil {
		return InputMethodListResult{}, unavailable("ADB input method enumeration")
	}
	identifiers, err := s.direct.ListInputMethods(ctx, device)
	if err != nil {
		return InputMethodListResult{}, err
	}
	return InputMethodListResult{
		Device: device,
		IDs:    identifiers,
		Count:  len(identifiers),
	}, nil
}

// PushFile 把本地文件推送到设备。
func (s *Service) PushFile(
	ctx context.Context,
	request FileTransferRequest,
) (adb.FileTransfer, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return adb.FileTransfer{}, err
	}
	if s.direct == nil {
		return adb.FileTransfer{}, unavailable("ADB file push")
	}
	return s.direct.PushFile(ctx, request.Device, request.LocalPath, request.RemotePath)
}

// PullFile 把设备文件拉取到本地。
func (s *Service) PullFile(
	ctx context.Context,
	request FileTransferRequest,
) (adb.FileTransfer, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return adb.FileTransfer{}, err
	}
	if s.direct == nil {
		return adb.FileTransfer{}, unavailable("ADB file pull")
	}
	return s.direct.PullFile(ctx, request.Device, request.RemotePath, request.LocalPath)
}
