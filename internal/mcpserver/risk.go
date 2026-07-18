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

func (e modelToolError) Error() string {
	payload, err := json.Marshal(e)
	if err != nil {
		return e.Code + ": " + e.Message
	}
	return string(payload)
}

func requireModelActionAllowed(action string) error {
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
