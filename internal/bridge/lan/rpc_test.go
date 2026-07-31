package lan

// 测试用途：本文件用真实双向加密 Session 验证持续 Bridge RPC、响应绑定和关闭边界。

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

func TestSessionCallKeepsOneEncryptedConnectionForMultipleRPCs(t *testing.T) {
	desktop, client := pairedRPCSessions(t)
	defer desktop.Close()
	defer client.Close()

	peerDone := make(chan error, 1)
	go func() {
		for index, method := range []string{"device.info", "recording.list"} {
			frameType, payload, err := client.ReadFrame(context.Background())
			if err != nil {
				peerDone <- err
				return
			}
			request := decodeRPCRequest(t, frameType, payload)
			clear(payload)
			if request.Method != method || !client.ValidateToken(request.Token) {
				peerDone <- fail(CodeFrameInvalid, "RPC request metadata is invalid")
				return
			}
			result, err := json.Marshal(map[string]any{"index": index, "method": method})
			if err != nil {
				peerDone <- err
				return
			}
			response, err := json.Marshal(bridge.Response{
				JSONRPC:         "2.0",
				ID:              request.ID,
				RequestID:       request.RequestID,
				ProtocolVersion: bridge.ProtocolVersion,
				Result:          result,
			})
			clear(result)
			if err != nil {
				peerDone <- err
				return
			}
			err = client.WriteFrame(context.Background(), "bridge.response", response)
			clear(response)
			if err != nil {
				peerDone <- err
				return
			}
		}
		peerDone <- nil
	}()

	for index, method := range []string{"device.info", "recording.list"} {
		var result struct {
			Index  int    `json:"index"`
			Method string `json:"method"`
		}
		if err := desktop.Call(context.Background(), method, map[string]any{}, &result); err != nil {
			t.Fatalf("Call(%s) error = %v", method, err)
		}
		if result.Index != index || result.Method != method {
			t.Fatalf("Call(%s) result = %#v", method, result)
		}
	}
	if err := <-peerDone; err != nil {
		t.Fatalf("peer RPC loop error = %v", err)
	}
	if desktop.Closed() {
		t.Fatal("successful multi-call session closed early")
	}
}

func TestSessionCallClosesOnResponseTypeOrIdentifierMismatch(t *testing.T) {
	for _, test := range []struct {
		name      string
		frameType string
		mutate    func(*bridge.Response)
	}{
		{
			name:      "wrong frame type",
			frameType: "bridge.request",
			mutate:    func(*bridge.Response) {},
		},
		{
			name:      "wrong response identifier",
			frameType: "bridge.response",
			mutate: func(response *bridge.Response) {
				response.RequestID = "00000000-0000-4000-8000-000000000000"
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			desktop, client := pairedRPCSessions(t)
			defer client.Close()
			go func() {
				frameType, payload, err := client.ReadFrame(context.Background())
				if err != nil {
					return
				}
				request := decodeRPCRequest(t, frameType, payload)
				clear(payload)
				response := bridge.Response{
					JSONRPC:         "2.0",
					ID:              request.ID,
					RequestID:       request.RequestID,
					ProtocolVersion: bridge.ProtocolVersion,
					Result:          json.RawMessage(`{"ok":true}`),
				}
				test.mutate(&response)
				encoded, _ := json.Marshal(response)
				_ = client.WriteFrame(context.Background(), test.frameType, encoded)
				clear(encoded)
			}()

			var result map[string]any
			if err := desktop.Call(
				context.Background(),
				"device.info",
				map[string]any{},
				&result,
			); err == nil {
				t.Fatal("mismatched response unexpectedly succeeded")
			}
			if !desktop.Closed() {
				t.Fatal("protocol mismatch did not close the session")
			}
		})
	}
}

func TestSessionCloseRPCClearsLocalSessionAfterResponse(t *testing.T) {
	desktop, client := pairedRPCSessions(t)
	defer client.Close()
	go func() {
		frameType, payload, err := client.ReadFrame(context.Background())
		if err != nil {
			return
		}
		request := decodeRPCRequest(t, frameType, payload)
		clear(payload)
		response, _ := json.Marshal(bridge.Response{
			JSONRPC:         "2.0",
			ID:              request.ID,
			RequestID:       request.RequestID,
			ProtocolVersion: bridge.ProtocolVersion,
			Result:          json.RawMessage(`{"closed":true}`),
		})
		_ = client.WriteFrame(context.Background(), "bridge.response", response)
		clear(response)
	}()

	var result struct {
		Closed bool `json:"closed"`
	}
	if err := desktop.Call(
		context.Background(),
		"session.close",
		map[string]any{},
		&result,
	); err != nil {
		t.Fatalf("session.close Call() error = %v", err)
	}
	if !result.Closed || !desktop.Closed() {
		t.Fatalf("session close result=%#v closed=%v", result, desktop.Closed())
	}
}

func TestSessionCallRejectsRenegotiationBeforeWritingFrame(t *testing.T) {
	desktop, client := pairedRPCSessions(t)
	defer desktop.Close()
	defer client.Close()

	for _, method := range []string{"", "rpc.hello", "session.open"} {
		if err := desktop.Call(
			context.Background(),
			method,
			map[string]any{},
			&map[string]any{},
		); ErrorCode(err) != CodeFrameInvalid {
			t.Fatalf("Call(%q) code = %q, want %q", method, ErrorCode(err), CodeFrameInvalid)
		}
	}
	if desktop.Closed() {
		t.Fatal("local method validation unexpectedly closed an unused session")
	}
}

func TestSessionCallClosesOnlyForAuthenticationBusinessErrors(t *testing.T) {
	tests := []struct {
		name       string
		code       string
		rpcCode    int
		wantClosed bool
	}{
		{
			name:       "authentication invalid",
			code:       apperr.CodeAuthInvalid,
			rpcCode:    -32004,
			wantClosed: true,
		},
		{
			name:       "capability unavailable",
			code:       apperr.CodeCapabilityMissing,
			rpcCode:    -32001,
			wantClosed: false,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			desktop, client := pairedRPCSessions(t)
			defer desktop.Close()
			defer client.Close()
			go func() {
				frameType, payload, err := client.ReadFrame(context.Background())
				if err != nil {
					return
				}
				request := decodeRPCRequest(t, frameType, payload)
				clear(payload)
				response, _ := json.Marshal(bridge.Response{
					JSONRPC:         "2.0",
					ID:              request.ID,
					RequestID:       request.RequestID,
					ProtocolVersion: bridge.ProtocolVersion,
					Error: &bridge.RPCError{
						Code:    test.rpcCode,
						Message: "stable failure",
						Data: &protocol.Error{
							Code:      test.code,
							Message:   "stable failure",
							Retryable: false,
						},
					},
				})
				_ = client.WriteFrame(context.Background(), "bridge.response", response)
				clear(response)
			}()

			err := desktop.Call(
				context.Background(),
				"device.info",
				map[string]any{},
				&map[string]any{},
			)
			var appError *apperr.Error
			if !errors.As(err, &appError) || appError.Code != test.code {
				t.Fatalf("Call() error = %#v, want %s", err, test.code)
			}
			if desktop.Closed() != test.wantClosed {
				t.Fatalf("closed = %t, want %t", desktop.Closed(), test.wantClosed)
			}
		})
	}
}

func pairedRPCSessions(t *testing.T) (*Session, *Session) {
	t.Helper()
	keys := deterministicSessionKeys()
	desktopFramer, err := NewFramer(FramerDesktop, keys, [32]byte{0x71})
	if err != nil {
		t.Fatalf("desktop NewFramer() error = %v", err)
	}
	clientFramer, err := NewFramer(FramerClient, keys, [32]byte{0x71})
	keys.Destroy()
	if err != nil {
		desktopFramer.Destroy()
		t.Fatalf("client NewFramer() error = %v", err)
	}
	desktopConnection, clientConnection := net.Pipe()
	token := []byte("0123456789abcdef0123456789abcdef")
	expiresAt := time.Now().Add(time.Minute)
	endpoint := Endpoint{
		Host:        "192.168.50.12",
		Family:      "ipv4",
		Port:        47831,
		InterfaceID: "if-7-en0",
	}
	return newSession(
			desktopConnection,
			desktopFramer,
			append([]byte(nil), token...),
			[32]byte{0x71},
			[]string{requiredConfirmation, requiredRPC},
			endpoint,
			expiresAt,
		), newSession(
			clientConnection,
			clientFramer,
			append([]byte(nil), token...),
			[32]byte{0x71},
			[]string{requiredConfirmation, requiredRPC},
			endpoint,
			expiresAt,
		)
}

func decodeRPCRequest(t *testing.T, frameType string, payload []byte) bridge.Request {
	t.Helper()
	if frameType != "bridge.request" {
		t.Fatalf("request frame type = %q", frameType)
	}
	if err := rejectDuplicateJSONKeys(payload); err != nil {
		t.Fatalf("request JSON is not strict: %v", err)
	}
	var request bridge.Request
	if err := decodeStrictJSON(payload, &request); err != nil {
		t.Fatalf("decode request: %v", err)
	}
	return request
}
