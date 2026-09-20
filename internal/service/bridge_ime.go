package service

// 功能用途：本文件在共享服务层实现桥侧 ime.show 的路由，
// 使 CLI 与 MCP 通过同一套语义聚焦输入控件。
//
// 为何不放进 ExecuteAction：那条路是直连通道，switch 分支全部落到 s.direct.*。
// ime.hide 能在那里是因为它有直连实现（KEYCODE_BACK），而 Android 没有对称的
// 「打开键盘」keycode，ime.show 只能走无障碍。挂到直连侧会永远拿到
// unavailable，因此归属桥通道，与 BridgeSetText 同列。

import (
	"context"
	"encoding/json"

	"github.com/lejunyang/ai-auto-android/internal/adb"
)

// imeShowAction 是发给桥的协议动作对象。
//
// ime.show 不接受任何参数：聚焦的是系统当前输入焦点，不接受选择器。
// params 必须是空对象，与 action.schema.json 的 emptyParams 一致。
const imeShowAction = `{"type":"ime.show","params":{}}`

// BridgeImeShow 通过 App Bridge 聚焦当前输入控件。
//
// 能力上限：这不是「显示键盘」的确定开关。无障碍无法查询键盘可见性，
// 键盘能否弹出取决于输入法实现与当前焦点状态。返回的成功语义是「已聚焦」，
// 调用方不应据此断定键盘已经可见。没有可聚焦的可编辑控件时，
// App 侧返回 ACTION_NOT_SUPPORTED，而不是报告假成功。
func (s *Service) BridgeImeShow(
	ctx context.Context,
	device string,
) (json.RawMessage, error) {
	if err := adb.ValidateSerial(device); err != nil {
		return nil, err
	}
	if s.bridge == nil {
		return nil, unavailable("bridge ime show")
	}
	return s.bridge.Action(ctx, device, json.RawMessage(imeShowAction))
}
