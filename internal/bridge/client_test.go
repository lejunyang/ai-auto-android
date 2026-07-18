package bridge

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

func TestClientOpenSendsHelloBeforeSessionOpen(t *testing.T) {
	dialer := pipeDialer(func(connection net.Conn) {
		defer connection.Close()
		requests := readRequests(t, connection, 2)
		if requests[0].Method != "rpc.hello" || requests[1].Method != "session.open" {
			t.Errorf("methods = %q, %q", requests[0].Method, requests[1].Method)
		}
	})
	client := NewClientWithDialer(dialer, time.Second)

	hello, opened, err := client.Open(
		context.Background(),
		41237,
		"123456",
		"test-host",
	)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if hello.SelectedProtocolVersion != "1.0" || len(opened.Token) < 32 {
		t.Fatalf("hello = %#v, opened = %#v", hello, opened)
	}
}

func TestClientRejectsMismatchedResponseIdentifiers(t *testing.T) {
	dialer := pipeDialer(func(connection net.Conn) {
		defer connection.Close()
		request := readRequest(t, connection)
		response := successfulResponse(request, `{
			"serverVersion":"0.1.0",
			"selectedProtocolVersion":"1.0",
			"capabilities":[]
		}`)
		response.RequestID = "99999999-9999-4999-8999-999999999999"
		writeResponse(t, connection, response)
	})
	client := NewClientWithDialer(dialer, time.Second)

	err := client.Call(
		context.Background(),
		41237,
		"random-token-value-that-is-longer-than-32-bytes",
		"device.info",
		map[string]any{},
		&map[string]any{},
	)
	assertBridgeErrorCode(t, err, apperr.CodeProtocol)
}

func TestClientReturnsStableRemoteError(t *testing.T) {
	dialer := pipeDialer(func(connection net.Conn) {
		defer connection.Close()
		hello := readRequest(t, connection)
		writeResponse(t, connection, successfulResponse(hello, `{
			"serverVersion":"0.1.0",
			"selectedProtocolVersion":"1.0",
			"capabilities":[]
		}`))
		request := readRequest(t, connection)
		writeResponse(t, connection, Response{
			JSONRPC:         "2.0",
			ID:              request.ID,
			RequestID:       request.RequestID,
			ProtocolVersion: "1.0",
			Error: &RPCError{
				Code:    -32005,
				Message: "The token has expired.",
				Data: &protocol.Error{
					Code:      apperr.CodeAuthExpired,
					Message:   "The token has expired.",
					Retryable: false,
				},
			},
		})
	})
	client := NewClientWithDialer(dialer, time.Second)

	err := client.Call(
		context.Background(),
		41237,
		"random-token-value-that-is-longer-than-32-bytes",
		"device.info",
		map[string]any{},
		&map[string]any{},
	)
	assertBridgeErrorCode(t, err, apperr.CodeAuthExpired)
}

func TestClientTimesOutWhenPeerDoesNotRespond(t *testing.T) {
	dialer := pipeDialer(func(connection net.Conn) {
		defer connection.Close()
		_ = readRequest(t, connection)
		time.Sleep(200 * time.Millisecond)
	})
	client := NewClientWithDialer(dialer, 20*time.Millisecond)

	err := client.Call(
		context.Background(),
		41237,
		"random-token-value-that-is-longer-than-32-bytes",
		"device.info",
		map[string]any{},
		&map[string]any{},
	)
	assertBridgeErrorCode(t, err, apperr.CodeDeadlineExceeded)
}

func TestClientRejectsOversizedResponse(t *testing.T) {
	dialer := pipeDialer(func(connection net.Conn) {
		defer connection.Close()
		_ = readRequest(t, connection)
		_, _ = connection.Write([]byte(strings.Repeat("x", MaxMessageBytes) + "\n"))
	})
	client := NewClientWithDialer(dialer, time.Second)

	err := client.Call(
		context.Background(),
		41237,
		"random-token-value-that-is-longer-than-32-bytes",
		"device.info",
		map[string]any{},
		&map[string]any{},
	)
	assertBridgeErrorCode(t, err, apperr.CodeMessageTooLarge)
}

func TestClientMapsDisconnectBeforeResponse(t *testing.T) {
	dialer := pipeDialer(func(connection net.Conn) {
		_ = readRequest(t, connection)
		_ = connection.Close()
	})
	client := NewClientWithDialer(dialer, time.Second)

	err := client.Call(
		context.Background(),
		41237,
		"random-token-value-that-is-longer-than-32-bytes",
		"device.info",
		map[string]any{},
		&map[string]any{},
	)
	assertBridgeErrorCode(t, err, apperr.CodeDeviceUnreachable)
}

func TestValidateResponseRejectsUnknownErrorsAndNonObjectResults(t *testing.T) {
	request := Request{
		ID:              "11111111-1111-4111-8111-111111111111",
		RequestID:       "22222222-2222-4222-8222-222222222222",
		ProtocolVersion: ProtocolVersion,
	}
	unknownError := Response{
		JSONRPC:         "2.0",
		ID:              request.ID,
		RequestID:       request.RequestID,
		ProtocolVersion: ProtocolVersion,
		Error: &RPCError{
			Code:    -32099,
			Message: "unknown",
			Data: &protocol.Error{
				Code:    "NOT_IN_PROTOCOL",
				Message: "unknown",
			},
		},
	}
	assertBridgeErrorCode(
		t,
		ValidateResponse(unknownError, request),
		apperr.CodeProtocol,
	)

	nonObject := Response{
		JSONRPC:         "2.0",
		ID:              request.ID,
		RequestID:       request.RequestID,
		ProtocolVersion: ProtocolVersion,
		Result:          json.RawMessage(`[]`),
	}
	assertBridgeErrorCode(
		t,
		ValidateResponse(nonObject, request),
		apperr.CodeProtocol,
	)
}

type dialerFunc func(ctx context.Context, network, address string) (net.Conn, error)

func (function dialerFunc) DialContext(
	ctx context.Context,
	network string,
	address string,
) (net.Conn, error) {
	return function(ctx, network, address)
}

func pipeDialer(server func(net.Conn)) Dialer {
	return dialerFunc(func(_ context.Context, network, address string) (net.Conn, error) {
		if network != "tcp4" || !strings.HasPrefix(address, "127.0.0.1:") {
			return nil, net.ErrClosed
		}
		client, peer := net.Pipe()
		go server(peer)
		return client, nil
	})
}

func readRequests(t *testing.T, connection net.Conn, count int) []Request {
	t.Helper()
	requests := make([]Request, 0, count)
	reader := bufio.NewReader(connection)
	for range count {
		request := decodeRequest(t, reader)
		requests = append(requests, request)
		var result string
		switch request.Method {
		case "rpc.hello":
			result = `{
				"serverVersion":"0.1.0",
				"selectedProtocolVersion":"1.0",
				"capabilities":[]
			}`
		case "session.open":
			result = `{
				"token":"random-token-value-that-is-longer-than-32-bytes",
				"expiresAt":"2026-07-18T00:15:00Z",
				"protocolVersion":"1.0"
			}`
		default:
			result = `{}`
		}
		writeResponse(t, connection, successfulResponse(request, result))
	}
	return requests
}

func readRequest(t *testing.T, connection net.Conn) Request {
	t.Helper()
	return decodeRequest(t, bufio.NewReader(connection))
}

func decodeRequest(t *testing.T, reader *bufio.Reader) Request {
	t.Helper()
	line, err := reader.ReadBytes('\n')
	if err != nil {
		t.Errorf("read request: %v", err)
		return Request{}
	}
	var request Request
	if err := json.Unmarshal(line, &request); err != nil {
		t.Errorf("decode request: %v", err)
	}
	return request
}

func successfulResponse(request Request, result string) Response {
	return Response{
		JSONRPC:         "2.0",
		ID:              request.ID,
		RequestID:       request.RequestID,
		ProtocolVersion: "1.0",
		Result:          json.RawMessage(result),
	}
}

func writeResponse(t *testing.T, connection net.Conn, response Response) {
	t.Helper()
	if err := json.NewEncoder(connection).Encode(response); err != nil {
		t.Errorf("write response: %v", err)
	}
}
