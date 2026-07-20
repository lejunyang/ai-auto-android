// Package apperr 统一应用错误、协议错误与进程退出码之间的稳定映射。
package apperr

import (
	"errors"
	"fmt"

	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

// CodeInvalidArgument 等常量是 CLI、Bridge 与 MCP 共享的稳定机器错误码。
const (
	CodeInvalidArgument   = "INVALID_ARGUMENT"
	CodeADBNotFound       = "ADB_NOT_FOUND"
	CodeADBUnauthorized   = "ADB_UNAUTHORIZED"
	CodeDeviceOffline     = "DEVICE_OFFLINE"
	CodeDeviceNotFound    = "DEVICE_NOT_FOUND"
	CodeMultipleDevices   = "MULTIPLE_DEVICES"
	CodeDeviceUnreachable = "DEVICE_UNREACHABLE"
	CodeCapabilityMissing = "CAPABILITY_UNAVAILABLE"
	CodeVersionMismatch   = "VERSION_INCOMPATIBLE"
	CodeAuthRequired      = "AUTH_REQUIRED"
	CodeAuthInvalid       = "AUTH_INVALID"
	CodeAuthExpired       = "AUTH_EXPIRED"
	CodeRequestReplayed   = "REQUEST_REPLAYED"
	CodeDeadlineExceeded  = "DEADLINE_EXCEEDED"
	CodeMessageTooLarge   = "MESSAGE_TOO_LARGE"
	CodeRateLimited       = "RATE_LIMITED"
	CodePermissionDenied  = "PERMISSION_DENIED"
	CodeActionNotAllowed  = "ACTION_NOT_ALLOWED"
	CodeActionFailed      = "ACTION_FAILED"
	CodeSelectorNotFound  = "SELECTOR_NOT_FOUND"
	CodeSelectorAmbiguous = "SELECTOR_AMBIGUOUS"
	CodeScriptNotFound    = "SCRIPT_NOT_FOUND"
	CodeProtocol          = "PROTOCOL_ERROR"
	CodeInternal          = "INTERNAL_ERROR"
)

// ExitSuccess 等常量定义 aactl 对脚本和 Agent 稳定公开的进程退出码。
const (
	ExitSuccess     = 0
	ExitArgument    = 2
	ExitAuth        = 3
	ExitDevice      = 4
	ExitTimeout     = 5
	ExitUnsupported = 6
	ExitInternal    = 10
)

// Error 携带稳定错误码、重试语义和可安全公开的结构化详情。
type Error struct {
	Code      string
	Message   string
	Retryable bool
	Details   map[string]any
	Cause     error
}

// Error 返回适合日志定位的消息，并在本地保留底层原因链。
func (e *Error) Error() string {
	if e.Cause == nil {
		return e.Message
	}
	return fmt.Sprintf("%s: %v", e.Message, e.Cause)
}

// Unwrap 允许 errors.Is 和 errors.As 检查底层原因。
func (e *Error) Unwrap() error {
	return e.Cause
}

// New 创建不包装底层原因的应用错误。
func New(code, message string, retryable bool, details map[string]any) *Error {
	return &Error{Code: code, Message: message, Retryable: retryable, Details: details}
}

// Wrap 创建保留底层原因、但对外仍使用稳定错误语义的应用错误。
func Wrap(code, message string, retryable bool, cause error) *Error {
	return &Error{Code: code, Message: message, Retryable: retryable, Cause: cause}
}

// Protocol 将任意错误转换为协议可序列化错误，并隐藏未知内部细节。
func Protocol(err error) *protocol.Error {
	var appError *Error
	if errors.As(err, &appError) {
		return &protocol.Error{
			Code:      appError.Code,
			Message:   appError.Message,
			Retryable: appError.Retryable,
			Details:   appError.Details,
		}
	}
	return &protocol.Error{
		Code:      CodeInternal,
		Message:   "An internal error occurred.",
		Retryable: false,
	}
}

// ExitCode 将应用错误分类为稳定的命令行退出码。
func ExitCode(err error) int {
	var appError *Error
	if !errors.As(err, &appError) {
		return ExitInternal
	}
	switch appError.Code {
	case CodeInvalidArgument, CodeMessageTooLarge, CodeRequestReplayed:
		return ExitArgument
	case CodeADBUnauthorized, CodeAuthRequired, CodeAuthInvalid, CodeAuthExpired, CodePermissionDenied:
		return ExitAuth
	case CodeADBNotFound, CodeDeviceOffline, CodeDeviceNotFound, CodeMultipleDevices, CodeDeviceUnreachable:
		return ExitDevice
	case CodeDeadlineExceeded:
		return ExitTimeout
	case CodeCapabilityMissing, CodeVersionMismatch, CodeActionNotAllowed:
		return ExitUnsupported
	case CodeActionFailed, CodeSelectorNotFound, CodeSelectorAmbiguous, CodeScriptNotFound:
		return ExitDevice
	default:
		return ExitInternal
	}
}
