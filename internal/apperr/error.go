package apperr

import (
	"errors"
	"fmt"

	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

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
	CodeProtocol          = "PROTOCOL_ERROR"
	CodeInternal          = "INTERNAL_ERROR"
)

const (
	ExitSuccess     = 0
	ExitArgument    = 2
	ExitAuth        = 3
	ExitDevice      = 4
	ExitTimeout     = 5
	ExitUnsupported = 6
	ExitInternal    = 10
)

type Error struct {
	Code      string
	Message   string
	Retryable bool
	Details   map[string]any
	Cause     error
}

func (e *Error) Error() string {
	if e.Cause == nil {
		return e.Message
	}
	return fmt.Sprintf("%s: %v", e.Message, e.Cause)
}

func (e *Error) Unwrap() error {
	return e.Cause
}

func New(code, message string, retryable bool, details map[string]any) *Error {
	return &Error{Code: code, Message: message, Retryable: retryable, Details: details}
}

func Wrap(code, message string, retryable bool, cause error) *Error {
	return &Error{Code: code, Message: message, Retryable: retryable, Cause: cause}
}

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
	case CodeActionFailed, CodeSelectorNotFound, CodeSelectorAmbiguous:
		return ExitDevice
	default:
		return ExitInternal
	}
}
