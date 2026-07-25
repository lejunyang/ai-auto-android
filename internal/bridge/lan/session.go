package lan

// 安全约束：本文件管理确认后 framed session 的有界 I/O、到期和幂等秘密清理。

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"sync"
	"time"
)

// Session 是完成双方确认后的唯一 LAN transport，不包含 loopback Bridge token。
type Session struct {
	sendMu       sync.Mutex
	recvMu       sync.Mutex
	stateMu      sync.Mutex
	connection   net.Conn
	reader       *bufio.Reader
	framer       *Framer
	token        []byte
	transcript   [32]byte
	capabilities []string
	endpoint     Endpoint
	expiresAt    time.Time
	closed       bool
}

func newSession(
	connection net.Conn,
	framer *Framer,
	token []byte,
	transcript [32]byte,
	capabilities []string,
	endpoint Endpoint,
	expiresAt time.Time,
) *Session {
	return &Session{
		connection:   connection,
		reader:       bufio.NewReaderSize(connection, MaxFramePlaintextBytes+64*1024),
		framer:       framer,
		token:        token,
		transcript:   transcript,
		capabilities: append([]string(nil), capabilities...),
		endpoint:     endpoint,
		expiresAt:    expiresAt,
	}
}

// Capabilities 返回双方明确协商能力的副本。
func (session *Session) Capabilities() []string {
	session.stateMu.Lock()
	defer session.stateMu.Unlock()
	return append([]string(nil), session.capabilities...)
}

// Endpoint 返回 transcript 绑定的客户端选定 endpoint。
func (session *Session) Endpoint() Endpoint {
	session.stateMu.Lock()
	defer session.stateMu.Unlock()
	return session.endpoint
}

// ExpiresAt 返回该一次性 LAN session 的硬过期时间。
func (session *Session) ExpiresAt() time.Time {
	session.stateMu.Lock()
	defer session.stateMu.Unlock()
	return session.expiresAt
}

// ValidateToken 以常量时间校验 LAN token；该 token 不可传给 loopback Bridge。
func (session *Session) ValidateToken(encoded string) bool {
	session.stateMu.Lock()
	defer session.stateMu.Unlock()
	if session.closed {
		return false
	}
	received, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return false
	}
	defer clear(received)
	return subtle.ConstantTimeCompare(received, session.token) == 1
}

// TokenForPeer 返回只应置于加密 frame 内的 LAN token 编码。
func (session *Session) TokenForPeer() (string, error) {
	session.stateMu.Lock()
	defer session.stateMu.Unlock()
	if session.closed {
		return "", fail(CodeSessionClosed, "LAN session is closed")
	}
	return base64.RawURLEncoding.EncodeToString(session.token), nil
}

// WriteFrame 在 context deadline 内加密并发送一个严格 NDJSON frame。
func (session *Session) WriteFrame(
	ctx context.Context,
	frameType string,
	plaintext []byte,
) error {
	session.sendMu.Lock()
	defer session.sendMu.Unlock()
	connection, framer, expiresAt, err := session.openState()
	if err != nil {
		return err
	}
	if err := setConnectionDeadline(connection, ctx, expiresAt); err != nil {
		_ = session.Close()
		return err
	}
	frame, err := framer.Seal(frameType, plaintext)
	if err != nil {
		_ = session.Close()
		return err
	}
	payload, err := json.Marshal(frame)
	if err != nil {
		_ = session.Close()
		return wrap(CodeFrameInvalid, "encrypted frame could not be encoded", err)
	}
	defer clear(payload)
	if len(payload)+1 > MaxFramePlaintextBytes+64*1024 {
		_ = session.Close()
		return fail(CodeFrameTooLarge, "encoded frame exceeds the transport limit")
	}
	payload = append(payload, '\n')
	if _, err := connection.Write(payload); err != nil {
		_ = session.Close()
		return wrap(CodeConnectionClosed, "encrypted frame could not be sent", err)
	}
	return nil
}

// ReadFrame 在 context deadline 内读取、严格解析并认证下一个单调 frame。
func (session *Session) ReadFrame(ctx context.Context) (string, []byte, error) {
	session.recvMu.Lock()
	defer session.recvMu.Unlock()
	connection, framer, expiresAt, err := session.openState()
	if err != nil {
		return "", nil, err
	}
	if err := setConnectionDeadline(connection, ctx, expiresAt); err != nil {
		_ = session.Close()
		return "", nil, err
	}
	payload, err := session.reader.ReadBytes('\n')
	if err != nil {
		_ = session.Close()
		if errors.Is(ctx.Err(), context.Canceled) {
			return "", nil, fail(CodeCancelled, "frame read was cancelled")
		}
		return "", nil, wrap(CodeConnectionClosed, "encrypted peer disconnected", err)
	}
	if len(payload) > MaxFramePlaintextBytes+64*1024 {
		clear(payload)
		_ = session.Close()
		return "", nil, fail(CodeFrameTooLarge, "encoded frame exceeds the transport limit")
	}
	payload = bytesTrimLine(payload)
	defer clear(payload)
	if err := rejectDuplicateJSONKeys(payload); err != nil {
		_ = session.Close()
		return "", nil, wrap(CodeFrameInvalid, "encrypted frame JSON is not strict", err)
	}
	var frame EncryptedFrame
	if err := decodeStrictJSON(payload, &frame); err != nil {
		_ = session.Close()
		return "", nil, wrap(CodeFrameInvalid, "encrypted frame JSON is invalid", err)
	}
	plaintext, err := framer.Open(frame)
	if err != nil {
		_ = session.Close()
		return "", nil, err
	}
	if session.Closed() {
		clear(plaintext)
		return "", nil, fail(CodeSessionClosed, "LAN session closed while reading a frame")
	}
	return frame.Type, plaintext, nil
}

// Close 关闭 socket 并清零 framer key、token、transcript 和协商元数据。
func (session *Session) Close() error {
	if session == nil {
		return nil
	}
	session.stateMu.Lock()
	if session.closed {
		session.stateMu.Unlock()
		return nil
	}
	session.closed = true
	connection := session.connection
	framer := session.framer
	clear(session.token)
	clear(session.transcript[:])
	clear(session.capabilities)
	session.token = nil
	session.capabilities = nil
	session.endpoint = Endpoint{}
	session.stateMu.Unlock()

	var closeErr error
	if connection != nil {
		closeErr = connection.Close()
	}
	framer.Destroy()
	if closeErr != nil && !errors.Is(closeErr, net.ErrClosed) {
		return wrap(CodeConnectionClosed, "LAN session connection could not be closed", closeErr)
	}
	return nil
}

// Closed 报告 session 是否已关闭并完成可控秘密清理。
func (session *Session) Closed() bool {
	if session == nil {
		return true
	}
	session.stateMu.Lock()
	defer session.stateMu.Unlock()
	return session.closed
}

func (session *Session) openState() (net.Conn, *Framer, time.Time, error) {
	session.stateMu.Lock()
	if session.closed {
		session.stateMu.Unlock()
		return nil, nil, time.Time{}, fail(CodeSessionClosed, "LAN session is closed")
	}
	connection := session.connection
	framer := session.framer
	expiresAt := session.expiresAt
	session.stateMu.Unlock()
	if !time.Now().Before(expiresAt) {
		_ = session.Close()
		return nil, nil, time.Time{}, fail(CodeInvitationExpired, "LAN session has expired")
	}
	return connection, framer, expiresAt, nil
}

func setConnectionDeadline(connection net.Conn, ctx context.Context, expiresAt time.Time) error {
	deadline := expiresAt
	if contextDeadline, ok := ctx.Deadline(); ok && contextDeadline.Before(deadline) {
		deadline = contextDeadline
	}
	if !deadline.After(time.Now()) {
		if errors.Is(ctx.Err(), context.Canceled) {
			return fail(CodeCancelled, "LAN operation was cancelled")
		}
		return fail(CodeInvitationExpired, "LAN session deadline has expired")
	}
	if err := connection.SetDeadline(deadline); err != nil {
		return wrap(CodeConnectionClosed, "LAN session deadline could not be set", err)
	}
	return nil
}
