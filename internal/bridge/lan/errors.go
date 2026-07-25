package lan

// 功能用途：本文件定义 LAN invitation、握手、传输和生命周期的稳定失败代码。

import (
	"errors"
	"fmt"
)

// Code 是可安全记录且不携带秘密的稳定 LAN 错误码。
type Code string

const (
	CodeInvitationSchemaInvalid Code = "LAN_INVITATION_SCHEMA_INVALID"
	CodeVersionIncompatible     Code = "LAN_VERSION_INCOMPATIBLE"
	CodeDowngradeRejected       Code = "LAN_DOWNGRADE_REJECTED"
	CodeInvitationTimeInvalid   Code = "LAN_INVITATION_TIME_INVALID"
	CodeInvitationTTLInvalid    Code = "LAN_INVITATION_TTL_INVALID"
	CodeInvitationTTLMismatch   Code = "LAN_INVITATION_TTL_MISMATCH"
	CodeInvitationNotYetValid   Code = "LAN_INVITATION_NOT_YET_VALID"
	CodeInvitationExpired       Code = "LAN_INVITATION_EXPIRED"
	CodeInvitationReplayed      Code = "LAN_INVITATION_REPLAYED"
	CodeNonceReplayed           Code = "LAN_NONCE_REPLAYED"
	CodeAddressInvalid          Code = "LAN_ADDRESS_INVALID"
	CodeAddressUnspecified      Code = "LAN_ADDRESS_UNSPECIFIED"
	CodeAddressMulticast        Code = "LAN_ADDRESS_MULTICAST"
	CodeAddressNotPrivate       Code = "LAN_ADDRESS_NOT_PRIVATE"
	CodeAddressScopeMismatch    Code = "LAN_ADDRESS_SCOPE_MISMATCH"
	CodeInterfaceMismatch       Code = "LAN_INTERFACE_MISMATCH"
	CodeEphemeralKeyInvalid     Code = "LAN_EPHEMERAL_KEY_INVALID"
	CodeEphemeralKeyWeak        Code = "LAN_EPHEMERAL_KEY_WEAK"
	CodeFingerprintMismatch     Code = "LAN_FINGERPRINT_MISMATCH"
	CodeCapabilityMismatch      Code = "LAN_CAPABILITY_MISMATCH"
	CodeTranscriptMismatch      Code = "LAN_TRANSCRIPT_MISMATCH"
	CodeConfirmationInvalid     Code = "LAN_CONFIRMATION_INVALID"

	CodeInterfaceAmbiguous   Code = "LAN_INTERFACE_AMBIGUOUS"
	CodeInterfaceUnavailable Code = "LAN_INTERFACE_UNAVAILABLE"
	CodeListenerBindFailed   Code = "LAN_LISTENER_BIND_FAILED"
	CodeAcceptTimeout        Code = "LAN_ACCEPT_TIMEOUT"
	CodeConnectionClosed     Code = "LAN_CONNECTION_CLOSED"
	CodeFrameInvalid         Code = "LAN_FRAME_INVALID"
	CodeFrameOutOfOrder      Code = "LAN_FRAME_OUT_OF_ORDER"
	CodeFrameReplay          Code = "LAN_FRAME_REPLAYED"
	CodeFrameTooLarge        Code = "LAN_FRAME_TOO_LARGE"
	CodeSessionClosed        Code = "LAN_SESSION_CLOSED"
	CodeCancelled            Code = "LAN_CANCELLED"
)

// Error 只保留稳定代码和不含协议秘密的静态原因。
type Error struct {
	Code    Code
	Message string
	Cause   error
}

func (e *Error) Error() string {
	if e.Message == "" {
		return string(e.Code)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

func (e *Error) Unwrap() error {
	return e.Cause
}

// ErrorCode 从错误链提取稳定 LAN 错误码，非 LAN 错误返回空值。
func ErrorCode(err error) Code {
	var lanError *Error
	if errors.As(err, &lanError) {
		return lanError.Code
	}
	return ""
}

func fail(code Code, message string) error {
	return &Error{Code: code, Message: message}
}

func wrap(code Code, message string, cause error) error {
	return &Error{Code: code, Message: message, Cause: cause}
}
