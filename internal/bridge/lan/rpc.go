package lan

// 功能用途：实现已确认 LAN Session 的 Bridge JSON-RPC 复用，并确保一次请求只绑定一个响应。

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/output"
)

const (
	rpcRequestFrameType  = "bridge.request"
	rpcResponseFrameType = "bridge.response"
)

// Call 在同一加密连接上完成一次严格 Bridge RPC；协议违约会关闭整个 LAN Session。
func (session *Session) Call(
	ctx context.Context,
	method string,
	params any,
	result any,
) error {
	if session == nil {
		return fail(CodeSessionClosed, "LAN session is unavailable")
	}
	if method == "" || method == "rpc.hello" || method == "session.open" {
		return fail(CodeFrameInvalid, "LAN RPC method cannot renegotiate session authentication")
	}
	session.rpcMu.Lock()
	defer session.rpcMu.Unlock()

	token, err := session.TokenForPeer()
	if err != nil {
		return err
	}
	requestID, err := output.RequestID()
	if err != nil {
		return wrap(CodeFrameInvalid, "LAN RPC request ID could not be generated", err)
	}
	rpcID, err := output.RequestID()
	if err != nil {
		return wrap(CodeFrameInvalid, "LAN RPC ID could not be generated", err)
	}
	deadline := rpcDeadline(ctx)
	request := bridge.Request{
		JSONRPC:         "2.0",
		ID:              rpcID,
		RequestID:       requestID,
		ProtocolVersion: bridge.ProtocolVersion,
		Method:          method,
		Params:          params,
		DeadlineMS:      int(deadline.Milliseconds()),
		Token:           token,
	}
	payload, err := json.Marshal(request)
	if err != nil {
		return wrap(CodeFrameInvalid, "LAN RPC request could not be encoded", err)
	}
	defer clear(payload)

	callContext, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	if err := session.WriteFrame(callContext, rpcRequestFrameType, payload); err != nil {
		return err
	}
	frameType, responsePayload, err := session.ReadFrame(callContext)
	if err != nil {
		return err
	}
	defer clear(responsePayload)
	if frameType != rpcResponseFrameType {
		_ = session.Close()
		return fail(CodeFrameInvalid, "LAN RPC response frame type is invalid")
	}
	if err := rejectDuplicateJSONKeys(responsePayload); err != nil {
		_ = session.Close()
		return wrap(CodeFrameInvalid, "LAN RPC response JSON is not strict", err)
	}
	var response bridge.Response
	if err := decodeStrictJSON(responsePayload, &response); err != nil {
		_ = session.Close()
		return wrap(CodeFrameInvalid, "LAN RPC response JSON is invalid", err)
	}
	if err := bridge.ValidateResponse(response, request); err != nil {
		_ = session.Close()
		return wrap(CodeFrameInvalid, "LAN RPC response does not match its request", err)
	}
	if err := response.DecodeResult(result); err != nil {
		if isBridgeBusinessError(err) {
			if isBridgeAuthenticationError(err) {
				_ = session.Close()
			}
			return err
		}
		_ = session.Close()
		return wrap(CodeFrameInvalid, "LAN RPC response result is invalid", err)
	}
	if method == "session.close" {
		return session.Close()
	}
	return nil
}

func rpcDeadline(ctx context.Context) time.Duration {
	deadline := bridge.DefaultDeadlineMS * time.Millisecond
	if contextDeadline, ok := ctx.Deadline(); ok {
		remaining := time.Until(contextDeadline)
		if remaining < deadline {
			deadline = remaining
		}
	}
	if deadline < time.Millisecond {
		return time.Millisecond
	}
	if deadline > bridge.MaxDeadlineMS*time.Millisecond {
		return bridge.MaxDeadlineMS * time.Millisecond
	}
	return deadline
}

func isBridgeBusinessError(err error) bool {
	var appError *apperr.Error
	return errors.As(err, &appError) && appError.Code != apperr.CodeProtocol
}

func isBridgeAuthenticationError(err error) bool {
	var appError *apperr.Error
	return errors.As(err, &appError) &&
		(appError.Code == apperr.CodeAuthRequired ||
			appError.Code == apperr.CodeAuthInvalid ||
			appError.Code == apperr.CodeAuthExpired)
}
