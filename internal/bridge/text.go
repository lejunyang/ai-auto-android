package bridge

// 功能用途：本文件为 App Bridge 提供文本输入通道，绕开 ADB input text 的 ASCII 限制。
//
// 为何需要单独一条通道：直连侧的 `adb shell input text` 把空格编码成 %s，
// 那套转义只对 ASCII 可靠，因此 internal/adb/direct.go 用正则把非 ASCII 拒在门外。
// 桥侧走无障碍 ACTION_SET_TEXT，参数是 CharSequence，天然支持任意 Unicode，
// 不经过任何 Shell 转义。中文、emoji 与其它非 ASCII 文本只能走这里。
//
// 与 Action() 的关系：Action() 能传任意 action 对象，理论上调用方自己拼
// ui.setText 也可行。但那要求调用方懂 target 选择器的 JSON 结构与权重字段，
// 而这些细节属于协议而非调用意图。这里把"往哪个元素写什么"收成两个参数。

import (
	"context"
	"encoding/json"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

const (
	// maxBridgeTextLength 与协议层 uiSetText 的 maxLength 保持一致。
	maxBridgeTextLength = 10000

	// selectorWeight 是选择器候选的权重。
	// 取 1.0：这里只产生单个候选，不存在需要排序的多候选场景；
	// 权重机制是给录制回放用的，那里同一元素会有多个不同强度的候选。
	selectorWeight = 1.0
)

// TextTarget 描述要写入文本的元素。
//
// 三种定位方式互斥，按可靠性排列：resourceId 最稳（结构性标识），
// contentDescription 次之，text 最弱（写入后自身就会变，不能用于重复定位）。
type TextTarget struct {
	// Package 限定目标应用，为空时由 App 侧按当前活动窗口判定。
	Package string
	// ResourceID、ContentDescription、Text 三选一。
	ResourceID         string
	ContentDescription string
	Text               string
}

// selector 把定位条件转成协议要求的候选列表。
func (target TextTarget) selector() (map[string]any, error) {
	var strategy, value string
	switch {
	case target.ResourceID != "":
		strategy, value = "resourceId", target.ResourceID
	case target.ContentDescription != "":
		strategy, value = "contentDescription", target.ContentDescription
	case target.Text != "":
		strategy, value = "text", target.Text
	default:
		return nil, apperr.New(
			apperr.CodeInvalidArgument,
			"A text target requires resourceId, contentDescription, or text.",
			false,
			map[string]any{"field": "target"},
		)
	}

	candidate := map[string]any{
		"strategy": strategy,
		"value":    value,
		"weight":   selectorWeight,
		"required": true,
	}
	selector := map[string]any{
		"selectorCandidates": []any{candidate},
	}
	if target.Package != "" {
		selector["packageName"] = target.Package
	}
	return selector, nil
}

// SetText 通过无障碍把文本写入指定元素。
//
// 与 ADB input text 的关键差别：文本原样走 JSON 到 App 侧，
// 由 ACTION_SET_TEXT 的 CharSequence 参数写入，全程不经 Shell 转义，
// 因此任意 Unicode 都安全。
func (s *Service) SetText(
	ctx context.Context,
	device string,
	target TextTarget,
	text string,
) (json.RawMessage, error) {
	if text == "" {
		return nil, apperr.New(
			apperr.CodeInvalidArgument,
			"Text must not be empty.",
			false,
			map[string]any{"field": "text"},
		)
	}
	// 按字节而非字符计数：与协议层 maxLength 的口径一致，
	// 且下游的消息大小限制也是按字节算的。
	if len(text) > maxBridgeTextLength {
		return nil, apperr.New(
			apperr.CodeInvalidArgument,
			"Text exceeds the protocol length limit.",
			false,
			map[string]any{"field": "text", "maxBytes": maxBridgeTextLength},
		)
	}

	selector, err := target.selector()
	if err != nil {
		return nil, err
	}

	action, err := json.Marshal(map[string]any{
		"type": "ui.setText",
		"params": map[string]any{
			"target": selector,
			"text":   text,
		},
	})
	if err != nil {
		return nil, apperr.Wrap(
			apperr.CodeInvalidArgument,
			"The text action could not be encoded.",
			false,
			err,
		)
	}
	return s.Action(ctx, device, action)
}
