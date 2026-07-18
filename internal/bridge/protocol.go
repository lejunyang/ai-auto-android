package bridge

import (
	"bytes"
	"encoding/json"
	"fmt"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

const (
	ProtocolVersion   = "1.0"
	ClientVersion     = "0.1.0"
	AndroidBridgePort = 38383
	MaxMessageBytes   = 1024 * 1024
	DefaultDeadlineMS = 30_000
	MaxDeadlineMS     = 300_000
	SessionTokenTTL   = 900
)

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

type Response struct {
	JSONRPC         string          `json:"jsonrpc"`
	ID              string          `json:"id"`
	RequestID       string          `json:"requestId"`
	ProtocolVersion string          `json:"protocolVersion"`
	Result          json.RawMessage `json:"result,omitempty"`
	Error           *RPCError       `json:"error,omitempty"`
}

type RPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    *protocol.Error `json:"data"`
}

type HelloParams struct {
	ClientVersion             string                `json:"clientVersion"`
	SupportedProtocolVersions []string              `json:"supportedProtocolVersions"`
	Capabilities              []protocol.Capability `json:"capabilities"`
}

type HelloResult struct {
	ServerVersion           string                `json:"serverVersion"`
	SelectedProtocolVersion string                `json:"selectedProtocolVersion"`
	Capabilities            []protocol.Capability `json:"capabilities"`
}

type SessionOpenParams struct {
	PairingCode string `json:"pairingCode"`
	HostName    string `json:"hostName"`
}

type SessionOpenResult struct {
	Token           string `json:"token"`
	ExpiresAt       string `json:"expiresAt"`
	ProtocolVersion string `json:"protocolVersion"`
}

type SessionCloseResult struct {
	Closed bool `json:"closed"`
}

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
