// Package mcpserver 将共享自动化服务暴露为受严格模式和风险门约束的 MCP 工具。
package mcpserver

import (
	"encoding/json"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
)

const codeConfirmationRequired = "CONFIRMATION_REQUIRED"

type modelToolError struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
}

// Error 将模型工具错误编码为 MCP 可传递的结构化 JSON。
func (e modelToolError) Error() string {
	payload, err := json.Marshal(e)
	if err != nil {
		return e.Code + ": " + e.Message
	}
	return string(payload)
}

func requireModelActionAllowed(action string) error {
	// 模型只能触发显式低风险白名单；停止 App 等动作即使在 CLI 可用也不会透传。
	switch action {
	case service.ActionTap,
		service.ActionSwipe,
		service.ActionSetText,
		service.ActionPressKey,
		service.ActionLaunch:
		return nil
	default:
		return modelToolError{
			Code:      apperr.CodeActionNotAllowed,
			Message:   "The requested action is not allowed for model-initiated tools.",
			Retryable: false,
		}
	}
}

func requireReplayConfirmation() error {
	return modelToolError{
		Code: codeConfirmationRequired,
		Message: "Recording replay can execute destructive or high-risk steps and requires " +
			"a non-model human confirmation credential, which is unavailable in this MVP.",
		Retryable: false,
	}
}
