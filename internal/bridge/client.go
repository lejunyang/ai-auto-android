package bridge

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/output"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

type Dialer interface {
	DialContext(ctx context.Context, network, address string) (net.Conn, error)
}

type Client struct {
	dialer  Dialer
	timeout time.Duration
}

func NewClient() *Client {
	return &Client{
		dialer:  &net.Dialer{Timeout: 10 * time.Second},
		timeout: 30 * time.Second,
	}
}

func NewClientWithDialer(dialer Dialer, timeout time.Duration) *Client {
	return &Client{dialer: dialer, timeout: timeout}
}

func (c *Client) Open(
	ctx context.Context,
	localPort int,
	pairingCode string,
	hostName string,
) (HelloResult, SessionOpenResult, error) {
	if err := validatePort(localPort); err != nil {
		return HelloResult{}, SessionOpenResult{}, err
	}
	if !validPairingCode(pairingCode) {
		return HelloResult{}, SessionOpenResult{}, invalidArgument(
			"pairingCode",
			"must contain exactly six digits",
		)
	}
	if len(hostName) < 1 || len(hostName) > 128 {
		return HelloResult{}, SessionOpenResult{}, invalidArgument("hostName", "must contain between 1 and 128 characters")
	}
	connection, err := c.dial(ctx, localPort)
	if err != nil {
		return HelloResult{}, SessionOpenResult{}, err
	}
	defer connection.Close()

	hello, err := c.hello(ctx, connection)
	if err != nil {
		return HelloResult{}, SessionOpenResult{}, err
	}
	var opened SessionOpenResult
	if err := c.call(
		ctx,
		connection,
		"session.open",
		SessionOpenParams{PairingCode: pairingCode, HostName: hostName},
		"",
		&opened,
	); err != nil {
		return HelloResult{}, SessionOpenResult{}, err
	}
	if !validToken(opened.Token) || opened.ProtocolVersion != ProtocolVersion {
		return HelloResult{}, SessionOpenResult{}, protocolError(
			"session.open returned invalid session metadata",
		)
	}
	return hello, opened, nil
}

func (c *Client) Call(
	ctx context.Context,
	localPort int,
	token string,
	method string,
	params any,
	result any,
) error {
	if err := validatePort(localPort); err != nil {
		return err
	}
	if !validToken(token) {
		return apperr.New(
			apperr.CodeAuthRequired,
			"A current bridge session token is required.",
			false,
			nil,
		)
	}
	connection, err := c.dial(ctx, localPort)
	if err != nil {
		return err
	}
	defer connection.Close()
	if _, err := c.hello(ctx, connection); err != nil {
		return err
	}
	return c.call(ctx, connection, method, params, token, result)
}

func (c *Client) hello(ctx context.Context, connection net.Conn) (HelloResult, error) {
	var result HelloResult
	err := c.call(
		ctx,
		connection,
		"rpc.hello",
		HelloParams{
			ClientVersion:             ClientVersion,
			SupportedProtocolVersions: []string{ProtocolVersion},
			Capabilities:              []protocol.Capability{},
		},
		"",
		&result,
	)
	if err != nil {
		return HelloResult{}, err
	}
	if result.SelectedProtocolVersion != ProtocolVersion {
		return HelloResult{}, apperr.New(
			apperr.CodeVersionMismatch,
			"The Android bridge selected an incompatible protocol version.",
			false,
			nil,
		)
	}
	return result, nil
}

func (c *Client) call(
	ctx context.Context,
	connection net.Conn,
	method string,
	params any,
	token string,
	result any,
) error {
	requestID, err := output.RequestID()
	if err != nil {
		return apperr.Wrap(apperr.CodeInternal, "Could not generate a bridge request ID.", false, err)
	}
	rpcID, err := output.RequestID()
	if err != nil {
		return apperr.Wrap(apperr.CodeInternal, "Could not generate a bridge RPC ID.", false, err)
	}
	deadline := deadlineFromContext(ctx, c.timeout)
	request := Request{
		JSONRPC:         "2.0",
		ID:              rpcID,
		RequestID:       requestID,
		ProtocolVersion: ProtocolVersion,
		Method:          method,
		Params:          params,
		DeadlineMS:      int(deadline.Milliseconds()),
		Token:           token,
	}
	payload, err := json.Marshal(request)
	if err != nil {
		return apperr.Wrap(apperr.CodeInvalidArgument, "Bridge request could not be encoded.", false, err)
	}
	if len(payload)+1 > MaxMessageBytes {
		return apperr.New(
			apperr.CodeMessageTooLarge,
			"Bridge request exceeds the 1048576 byte limit.",
			false,
			nil,
		)
	}

	callContext, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	if deadlineAt, ok := callContext.Deadline(); ok {
		if err := connection.SetDeadline(deadlineAt); err != nil {
			return connectionError(err)
		}
	}
	if _, err := connection.Write(append(payload, '\n')); err != nil {
		return mapNetworkError(callContext, err)
	}
	responsePayload, err := readBoundedLine(connection)
	if err != nil {
		return mapNetworkError(callContext, err)
	}
	var response Response
	if err := json.Unmarshal(responsePayload, &response); err != nil {
		return apperr.Wrap(
			apperr.CodeProtocol,
			"The Android bridge returned malformed JSON.",
			false,
			err,
		)
	}
	if err := ValidateResponse(response, request); err != nil {
		return err
	}
	return response.DecodeResult(result)
}

func (c *Client) dial(ctx context.Context, localPort int) (net.Conn, error) {
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(localPort))
	connection, err := c.dialer.DialContext(ctx, "tcp4", address)
	if err != nil {
		return nil, mapNetworkError(ctx, err)
	}
	return connection, nil
}

func readBoundedLine(reader io.Reader) ([]byte, error) {
	buffered := bufio.NewReaderSize(reader, 64*1024)
	payload := make([]byte, 0, 4096)
	for {
		fragment, err := buffered.ReadSlice('\n')
		if len(payload)+len(fragment) > MaxMessageBytes {
			return nil, apperr.New(
				apperr.CodeMessageTooLarge,
				"Bridge response exceeds the 1048576 byte limit.",
				false,
				nil,
			)
		}
		payload = append(payload, fragment...)
		switch {
		case err == nil:
			return bytesTrimLineEnding(payload), nil
		case errors.Is(err, bufio.ErrBufferFull):
			continue
		case errors.Is(err, io.EOF):
			if len(payload) == 0 {
				return nil, apperr.New(
					apperr.CodeDeviceUnreachable,
					"The Android bridge disconnected before responding.",
					true,
					nil,
				)
			}
			return nil, protocolError("response is not newline terminated")
		default:
			return nil, err
		}
	}
}

func bytesTrimLineEnding(value []byte) []byte {
	value = value[:len(value)-1]
	if len(value) > 0 && value[len(value)-1] == '\r' {
		value = value[:len(value)-1]
	}
	return value
}

func deadlineFromContext(ctx context.Context, fallback time.Duration) time.Duration {
	if fallback <= 0 || fallback > MaxDeadlineMS*time.Millisecond {
		fallback = DefaultDeadlineMS * time.Millisecond
	}
	if deadline, ok := ctx.Deadline(); ok {
		remaining := time.Until(deadline)
		if remaining < fallback {
			fallback = remaining
		}
	}
	if fallback < time.Millisecond {
		return time.Millisecond
	}
	return fallback
}

func validatePort(port int) error {
	if port < 1 || port > 65535 {
		return invalidArgument("localPort", "must be between 1 and 65535")
	}
	return nil
}

func invalidArgument(field, reason string) error {
	return apperr.New(
		apperr.CodeInvalidArgument,
		fmt.Sprintf("%s %s.", field, reason),
		false,
		map[string]any{"field": field},
	)
}

func mapNetworkError(ctx context.Context, err error) error {
	var appError *apperr.Error
	if errors.As(err, &appError) {
		return err
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return apperr.New(
			apperr.CodeDeadlineExceeded,
			"The Android bridge request exceeded its deadline.",
			true,
			nil,
		)
	}
	var netError net.Error
	if errors.As(err, &netError) && netError.Timeout() {
		return apperr.New(
			apperr.CodeDeadlineExceeded,
			"The Android bridge request exceeded its deadline.",
			true,
			nil,
		)
	}
	if strings.Contains(strings.ToLower(err.Error()), "message_too_large") {
		return apperr.New(apperr.CodeMessageTooLarge, "Bridge response is too large.", false, nil)
	}
	return connectionError(err)
}

func connectionError(err error) error {
	return apperr.Wrap(
		apperr.CodeDeviceUnreachable,
		"The Android bridge is unreachable. Confirm the App bridge is enabled.",
		true,
		err,
	)
}
