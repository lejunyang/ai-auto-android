package lan

// 安全约束：本文件管理一次性 listener 的端口、accept、重放、切网、取消和秘密清理。

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	defaultAcceptTimeout = 30 * time.Second
	maxHandshakeBytes    = 64 * 1024
)

// ListenerOptions 声明明确接口、候选、TTL、能力和可注入网络边界。
type ListenerOptions struct {
	InterfaceSource InterfaceSource
	Binder          ListenerBinder
	Selected        NetworkInterface
	Candidate       AddressCandidate
	Port            int
	TTL             time.Duration
	AcceptTimeout   time.Duration
	Capabilities    []string
	Random          io.Reader
	QRProvider      QRProvider
	ReplayGuard     *ReplayGuard
	Now             func() time.Time
}

// HandshakePolicy 限定桌面允许协商的 capability 和单步握手截止时间。
type HandshakePolicy struct {
	AllowedCapabilities []string
	HandshakeTimeout    time.Duration
}

// PendingListener 持有一个未消费 invitation 及其唯一临时私钥和端口。
type PendingListener struct {
	mu              sync.Mutex
	listener        net.Listener
	interfaceSource InterfaceSource
	selected        NetworkInterface
	candidate       AddressCandidate
	bundle          InvitationBundle
	privateKey      *EphemeralPrivateKey
	replayGuard     *ReplayGuard
	acceptTimeout   time.Duration
	now             func() time.Time
	closed          bool
	accepted        bool
}

// StartListener 先绑定明确候选，再用实际端口生成 invitation；任一步失败都关闭端口。
func StartListener(ctx context.Context, options ListenerOptions) (*PendingListener, error) {
	if options.InterfaceSource == nil || options.Binder == nil {
		return nil, fail(CodeInterfaceUnavailable, "interface source and listener binder are required")
	}
	if err := VerifySelectedInterface(options.InterfaceSource, options.Selected); err != nil {
		return nil, err
	}
	if err := validateBindCandidate(options.Selected, options.Candidate, options.Port); err != nil {
		return nil, err
	}
	listener, err := options.Binder.Listen(
		ctx,
		options.Selected,
		options.Candidate,
		options.Port,
	)
	if err != nil {
		return nil, err
	}
	cleanup := true
	defer func() {
		if cleanup {
			_ = listener.Close()
		}
	}()
	port, err := listenerPort(listener.Addr())
	if err != nil {
		return nil, err
	}
	bundle, privateKey, err := CreateInvitation(InvitationOptions{
		Context:      ctx,
		Interface:    options.Selected,
		Candidate:    options.Candidate,
		Port:         port,
		TTL:          options.TTL,
		Capabilities: options.Capabilities,
		Now:          options.Now,
		Random:       options.Random,
		QRProvider:   options.QRProvider,
	})
	if err != nil {
		return nil, err
	}
	timeout := options.AcceptTimeout
	if timeout <= 0 {
		timeout = defaultAcceptTimeout
	}
	if timeout > options.TTL {
		timeout = options.TTL
	}
	guard := options.ReplayGuard
	if guard == nil {
		guard = NewReplayGuard()
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}
	pending := &PendingListener{
		listener:        listener,
		interfaceSource: options.InterfaceSource,
		selected:        cloneNetworkInterface(options.Selected),
		candidate:       options.Candidate,
		bundle:          bundle,
		privateKey:      privateKey,
		replayGuard:     guard,
		acceptTimeout:   timeout,
		now:             now,
	}
	cleanup = false
	return pending, nil
}

// Invitation 返回不含私钥的 invitation 副本。
func (pending *PendingListener) Invitation() Invitation {
	pending.mu.Lock()
	defer pending.mu.Unlock()
	return cloneInvitation(pending.bundle.Invitation)
}

// Bundle 返回可展示 payload 的副本，关闭后返回已清理的空内容。
func (pending *PendingListener) Bundle() InvitationBundle {
	pending.mu.Lock()
	defer pending.mu.Unlock()
	result := pending.bundle
	result.Invitation = cloneInvitation(pending.bundle.Invitation)
	result.Payload = append([]byte(nil), pending.bundle.Payload...)
	result.QR.Data = append([]byte(nil), pending.bundle.QR.Data...)
	return result
}

// Accept 最多接收一个连接，并在任何失败后撤销 invitation 和清理 listener。
func (pending *PendingListener) Accept(
	ctx context.Context,
	policy HandshakePolicy,
) (*Session, error) {
	if pending == nil {
		return nil, fail(CodeSessionClosed, "listener is unavailable")
	}
	pending.mu.Lock()
	if pending.closed || pending.accepted {
		pending.mu.Unlock()
		return nil, fail(CodeSessionClosed, "listener is already closed or consumed")
	}
	pending.accepted = true
	listener := pending.listener
	timeout := pending.acceptTimeout
	pending.mu.Unlock()

	acceptContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	stopCloser := make(chan struct{})
	go func() {
		select {
		case <-acceptContext.Done():
			_ = listener.Close()
		case <-stopCloser:
		}
	}()
	connection, err := listener.Accept()
	close(stopCloser)
	if err != nil {
		pending.Close()
		if errors.Is(ctx.Err(), context.Canceled) {
			return nil, fail(CodeCancelled, "listener accept was cancelled")
		}
		if errors.Is(acceptContext.Err(), context.DeadlineExceeded) {
			return nil, fail(CodeAcceptTimeout, "listener accept timed out")
		}
		return nil, wrap(CodeConnectionClosed, "listener accept failed", err)
	}
	_ = listener.Close()
	pending.mu.Lock()
	pending.listener = nil
	pending.mu.Unlock()

	if err := VerifySelectedInterface(pending.interfaceSource, pending.selected); err != nil {
		_ = connection.Close()
		pending.Close()
		return nil, err
	}
	session, err := pending.performHandshake(ctx, connection, policy)
	if err != nil {
		_ = connection.Close()
		pending.Close()
		return nil, err
	}
	pending.finishAfterHandshake()
	return session, nil
}

// Close 幂等关闭端口、撤销 invitation 并清零临时公开缓冲和私钥。
func (pending *PendingListener) Close() error {
	if pending == nil {
		return nil
	}
	pending.mu.Lock()
	if pending.closed {
		pending.mu.Unlock()
		return nil
	}
	pending.closed = true
	listener := pending.listener
	pending.listener = nil
	privateKey := pending.privateKey
	pending.clearBundleLocked()
	pending.mu.Unlock()
	if listener != nil {
		_ = listener.Close()
	}
	privateKey.Destroy()
	return nil
}

// Closed 报告 listener 是否已撤销并关闭。
func (pending *PendingListener) Closed() bool {
	if pending == nil {
		return true
	}
	pending.mu.Lock()
	defer pending.mu.Unlock()
	return pending.closed
}

func (pending *PendingListener) finishAfterHandshake() {
	pending.mu.Lock()
	pending.closed = true
	pending.listener = nil
	privateKey := pending.privateKey
	pending.clearBundleLocked()
	pending.mu.Unlock()
	privateKey.Destroy()
}

func (pending *PendingListener) clearBundleLocked() {
	clear(pending.bundle.Payload)
	clear(pending.bundle.QR.Data)
	pending.bundle.Payload = nil
	pending.bundle.QR.Data = nil
	pending.bundle.ManualCode = ""
	pending.bundle.Invitation.Nonce = ""
	pending.bundle.Invitation.EphemeralKey.PublicKey = ""
}

func (pending *PendingListener) listenerAddress() string {
	pending.mu.Lock()
	defer pending.mu.Unlock()
	if pending.listener == nil {
		return ""
	}
	return pending.listener.Addr().String()
}

func listenerPort(address net.Addr) (int, error) {
	if address == nil {
		return 0, fail(CodeListenerBindFailed, "listener address is unavailable")
	}
	_, portText, err := net.SplitHostPort(address.String())
	if err != nil {
		return 0, wrap(CodeListenerBindFailed, "listener address has no explicit port", err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1024 || port > 65535 {
		return 0, fail(CodeListenerBindFailed, "listener allocated an invalid port")
	}
	return port, nil
}

func readHandshakeMessage(reader *bufio.Reader, destination any) error {
	payload, err := reader.ReadBytes('\n')
	if err != nil {
		return wrap(CodeConnectionClosed, "handshake peer disconnected", err)
	}
	if len(payload) > maxHandshakeBytes {
		clear(payload)
		return fail(CodeFrameTooLarge, "handshake message exceeds the limit")
	}
	payload = bytesTrimLine(payload)
	defer clear(payload)
	if err := rejectDuplicateJSONKeys(payload); err != nil {
		return wrap(CodeInvitationSchemaInvalid, "handshake JSON is not strict", err)
	}
	if err := decodeStrictJSON(payload, destination); err != nil {
		return wrap(CodeInvitationSchemaInvalid, "handshake JSON does not match its schema", err)
	}
	return nil
}

func writeHandshakeMessage(connection net.Conn, value any) error {
	payload, err := json.Marshal(value)
	if err != nil {
		return wrap(CodeInvitationSchemaInvalid, "handshake message could not be encoded", err)
	}
	defer clear(payload)
	if len(payload)+1 > maxHandshakeBytes {
		return fail(CodeFrameTooLarge, "handshake message exceeds the limit")
	}
	payload = append(payload, '\n')
	_, err = connection.Write(payload)
	if err != nil {
		return wrap(CodeConnectionClosed, "handshake message could not be sent", err)
	}
	return nil
}

func bytesTrimLine(payload []byte) []byte {
	return []byte(strings.TrimSuffix(strings.TrimSuffix(string(payload), "\n"), "\r"))
}

func cloneInvitation(source Invitation) Invitation {
	source.Capabilities = append([]string(nil), source.Capabilities...)
	source.Listener.AddressCandidates = append(
		[]AddressCandidate(nil),
		source.Listener.AddressCandidates...,
	)
	return source
}
