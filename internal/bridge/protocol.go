package bridge

// 功能用途：本文件定义桌面 Bridge 的版本、消息边界和 JSON-RPC 数据模型。

import (
	"bytes"
	"encoding/json"
	"fmt"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

// ProtocolVersion 等常量固定双方协商版本、设备端口和资源上限。
const (
	ProtocolVersion   = "1.0"
	ClientVersion     = "0.1.0"
	AndroidBridgePort = 38383
	MaxMessageBytes   = 1024 * 1024
	DefaultDeadlineMS = 30_000
	MaxDeadlineMS     = 300_000
	SessionTokenTTL   = 900
)

// Request 是附带稳定请求标识、截止时间和可选会话 token 的 RPC 请求。
type Request struct {
	JSONRPC         string `json:"jsonrpc"`
	ID              string `json:"id"`
	RequestID       string `json:"requestId"`
	ProtocolVersion string `json:"protocolVersion"`
	Method          string `json:"method"`
	Params          any    `json:"params"`
	DeadlineMS      int    `json:"deadlineMs"`
	Token           string `json:"token,omitempty"`
}

// Response 是必须与原请求标识和协议版本一致的 RPC 响应。
type Response struct {
	JSONRPC         string          `json:"jsonrpc"`
	ID              string          `json:"id"`
	RequestID       string          `json:"requestId"`
	ProtocolVersion string          `json:"protocolVersion"`
	Result          json.RawMessage `json:"result,omitempty"`
	Error           *RPCError       `json:"error,omitempty"`
}

// RPCError 同时携带 JSON-RPC 保留错误码和稳定协议错误数据。
type RPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    *protocol.Error `json:"data"`
}

// HelloParams 声明桌面客户端版本及可接受协议版本和能力。
type HelloParams struct {
	ClientVersion             string                `json:"clientVersion"`
	SupportedProtocolVersions []string              `json:"supportedProtocolVersions"`
	Capabilities              []protocol.Capability `json:"capabilities"`
}

// HelloResult 返回 App 端版本、选定协议版本和能力集合。
type HelloResult struct {
	ServerVersion           string                `json:"serverVersion"`
	SelectedProtocolVersion string                `json:"selectedProtocolVersion"`
	Capabilities            []protocol.Capability `json:"capabilities"`
}

// SessionOpenParams 携带仅用于本次建立会话的一次性配对码。
type SessionOpenParams struct {
	PairingCode string `json:"pairingCode"`
	HostName    string `json:"hostName"`
}

// SessionOpenResult 返回短期 token 及其过期时间和协议版本。
type SessionOpenResult struct {
	Token           string `json:"token"`
	ExpiresAt       string `json:"expiresAt"`
	ProtocolVersion string `json:"protocolVersion"`
}

// SessionCloseResult 表示 App 是否确认关闭远端会话。
type SessionCloseResult struct {
	Closed bool `json:"closed"`
}

// DecodeResult 将成功结果解码到目标对象，或恢复稳定的 App 错误。
func (r Response) DecodeResult(destination any) error {
	if r.Error != nil {
		if r.Error.Data != nil {
			return apperr.New(
				r.Error.Data.Code,
				r.Error.Data.Message,
				r.Error.Data.Retryable,
				r.Error.Data.Details,
			)
		}
		return apperr.New(
			apperr.CodeProtocol,
			"The Android bridge returned an error without stable data.",
			false,
			map[string]any{"rpcCode": r.Error.Code},
		)
	}
	if len(r.Result) == 0 {
		return apperr.New(
			apperr.CodeProtocol,
			"The Android bridge returned no result.",
			false,
			nil,
		)
	}
	if destination == nil {
		return nil
	}
	if err := json.Unmarshal(r.Result, destination); err != nil {
		return apperr.Wrap(
			apperr.CodeProtocol,
			"The Android bridge returned an invalid result.",
			false,
			err,
		)
	}
	return nil
}

// ValidateResponse 拒绝错配标识、未知错误码及结果/错误结构冲突。
func ValidateResponse(response Response, request Request) error {
	if response.JSONRPC != "2.0" {
		return protocolError("response jsonrpc is not 2.0")
	}
	if response.ID != request.ID || response.RequestID != request.RequestID {
		return protocolError("response identifiers do not match the request")
	}
	if response.ProtocolVersion != ProtocolVersion {
		return apperr.New(
			apperr.CodeVersionMismatch,
			"The Android bridge response protocol version is incompatible.",
			false,
			nil,
		)
	}
	if response.Error != nil && len(response.Result) != 0 {
		return protocolError("response contains both result and error")
	}
	if response.Error == nil && len(response.Result) == 0 {
		return protocolError("response contains neither result nor error")
	}
	if response.Error != nil {
		if response.Error.Code < -32099 || response.Error.Code > -32000 {
			return protocolError("response error code is outside the reserved range")
		}
		if response.Error.Message == "" || response.Error.Data == nil {
			return protocolError("response error is missing stable data")
		}
		if !knownProtocolErrorCode(response.Error.Data.Code) {
			return protocolError("response uses an unknown stable error code")
		}
		return nil
	}
	result := bytes.TrimSpace(response.Result)
	if len(result) == 0 || result[0] != '{' {
		return protocolError("response result is not an object")
	}
	return nil
}

func knownProtocolErrorCode(code string) bool {
	switch code {
	case "INVALID_ARGUMENT",
		"ADB_NOT_FOUND",
		"ADB_UNAUTHORIZED",
		"DEVICE_OFFLINE",
		"DEVICE_NOT_FOUND",
		"MULTIPLE_DEVICES",
		"DEVICE_UNREACHABLE",
		"PERMISSION_DENIED",
		"CAPABILITY_UNAVAILABLE",
		"VERSION_INCOMPATIBLE",
		"AUTH_REQUIRED",
		"AUTH_INVALID",
		"AUTH_EXPIRED",
		"REQUEST_REPLAYED",
		"DEADLINE_EXCEEDED",
		"MESSAGE_TOO_LARGE",
		"RATE_LIMITED",
		"ACTION_NOT_ALLOWED",
		"ACTION_FAILED",
		"SELECTOR_NOT_FOUND",
		"SELECTOR_AMBIGUOUS",
		"SCRIPT_NOT_FOUND",
		"PROTOCOL_ERROR",
		"INTERNAL_ERROR":
		return true
	default:
		return false
	}
}

func validPairingCode(code string) bool {
	if len(code) != 6 {
		return false
	}
	for _, character := range code {
		if character < '0' || character > '9' {
			return false
		}
	}
	return true
}

func validToken(token string) bool {
	if len(token) < 32 || len(token) > 512 {
		return false
	}
	for _, character := range token {
		if character >= 'A' && character <= 'Z' ||
			character >= 'a' && character <= 'z' ||
			character >= '0' && character <= '9' {
			continue
		}
		switch character {
		case '.', '_', '~', '-':
			continue
		default:
			return false
		}
	}
	return true
}

func protocolError(reason string) error {
	return apperr.New(
		apperr.CodeProtocol,
		fmt.Sprintf("The Android bridge protocol is invalid: %s.", reason),
		false,
		nil,
	)
}
