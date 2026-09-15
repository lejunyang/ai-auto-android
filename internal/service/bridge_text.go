package service

// 功能用途：本文件在共享服务层实现桥侧文本输入的校验与路由，
// 使 CLI 与 MCP 通过同一套语义写入任意 Unicode 文本。
//
// 为何不复用 ExecuteAction：那条路走 adb shell input text，
// 其空格转义只对 ASCII 可靠，非 ASCII 会被静默写错（见 internal/adb/direct.go）。
// 桥侧走无障碍 ACTION_SET_TEXT，参数为 CharSequence，不经任何 Shell 转义。

import (
	"context"
	"encoding/json"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
)

// BridgeTextRequest 描述一次桥侧文本输入。
//
// 三个定位字段互斥且至少填一个，由 bridge.TextTarget 校验；
// 这里不重复该判据，避免两处规则不一致。
type BridgeTextRequest struct {
	Device string `json:"device"`
	Text   string `json:"text"`
	// Package 限定目标应用，为空时由 App 侧按当前活动窗口判定。
	Package string `json:"package,omitempty"`
	// ResourceID、Description、MatchText 三选一，按可靠性排列。
	ResourceID  string `json:"resourceId,omitempty"`
	Description string `json:"description,omitempty"`
	MatchText   string `json:"matchText,omitempty"`
}

// BridgeSetText 通过 App Bridge 把文本写入指定元素。
//
// 返回 App 侧的原始应答（含 route、matchedPath、matchScore），
// 不做二次封装：这些字段说明"命中了哪个节点、匹配有多确定"，
// 是调用方判断写入是否落到预期元素的唯一依据。
func (s *Service) BridgeSetText(
	ctx context.Context,
	request BridgeTextRequest,
) (json.RawMessage, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return nil, err
	}
	if request.Package != "" {
		if err := adb.ValidatePackageName(request.Package); err != nil {
			return nil, err
		}
	}
	if s.bridge == nil {
		return nil, unavailable("bridge text input")
	}
	return s.bridge.SetText(
		ctx,
		request.Device,
		bridge.TextTarget{
			Package:            request.Package,
			ResourceID:         request.ResourceID,
			ContentDescription: request.Description,
			Text:               request.MatchText,
		},
		request.Text,
	)
}
