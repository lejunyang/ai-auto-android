package lan

// 测试用途：本文件用真实 localhost socket 验证 accept、取消、超时、切网和端口清理生命周期。

import (
	"bytes"
	"context"
	"errors"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"
)

func TestPendingListenerUsesRealSocketAndClosesPortOnCancellation(t *testing.T) {
	source, selected := stableFakeNetwork()
	pending, err := StartListener(context.Background(), ListenerOptions{
		InterfaceSource: source,
		Binder:          localhostTestBinder{},
		Selected:        selected,
		Candidate:       selected.Candidates[0],
		Port:            0,
		TTL:             30 * time.Second,
		Capabilities:    []string{requiredConfirmation, requiredRPC},
		Random:          bytes.NewReader(bytes.Repeat([]byte{0x45}, 256)),
	})
	if err != nil {
		t.Fatalf("StartListener() error = %v", err)
	}
	address := pending.listener.Addr().String()
	if pending.Invitation().Listener.Port < 1024 {
		t.Fatalf("invitation port = %d, want allocated non-privileged port", pending.Invitation().Listener.Port)
	}

	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, acceptErr := pending.Accept(ctx, HandshakePolicy{
			AllowedCapabilities: []string{requiredConfirmation, requiredRPC},
		})
		result <- acceptErr
	}()
	cancel()
	if code := ErrorCode(<-result); code != CodeCancelled {
		t.Fatalf("Accept(cancel) error code = %q, want %q", code, CodeCancelled)
	}
	if !pending.Closed() || !pending.privateKey.Destroyed() {
		t.Fatal("cancel did not close listener and destroy ephemeral private key")
	}
	connection, dialErr := net.DialTimeout("tcp", address, 100*time.Millisecond)
	if dialErr == nil {
		connection.Close()
		t.Fatal("listener port remained reachable after cancellation")
	}
}

func TestPendingListenerAcceptTimeoutClosesPortAndDestroysKey(t *testing.T) {
	source, selected := stableFakeNetwork()
	pending, err := StartListener(context.Background(), ListenerOptions{
		InterfaceSource: source,
		Binder:          localhostTestBinder{},
		Selected:        selected,
		Candidate:       selected.Candidates[0],
		TTL:             30 * time.Second,
		AcceptTimeout:   20 * time.Millisecond,
		Capabilities:    []string{requiredConfirmation, requiredRPC},
		Random:          bytes.NewReader(bytes.Repeat([]byte{0x46}, 256)),
	})
	if err != nil {
		t.Fatalf("StartListener() error = %v", err)
	}
	_, err = pending.Accept(context.Background(), HandshakePolicy{
		AllowedCapabilities: []string{requiredConfirmation, requiredRPC},
	})
	if code := ErrorCode(err); code != CodeAcceptTimeout {
		t.Fatalf("Accept(timeout) error code = %q, want %q (error=%v)", code, CodeAcceptTimeout, err)
	}
	if !pending.Closed() || !pending.privateKey.Destroyed() {
		t.Fatal("timeout did not close listener and destroy private key")
	}
}

func TestPendingListenerDetectsInterfaceChangeBeforeHandshake(t *testing.T) {
	source, selected := stableFakeNetwork()
	pending, err := StartListener(context.Background(), ListenerOptions{
		InterfaceSource: source,
		Binder:          localhostTestBinder{},
		Selected:        selected,
		Candidate:       selected.Candidates[0],
		TTL:             30 * time.Second,
		Capabilities:    []string{requiredConfirmation, requiredRPC},
		Random:          bytes.NewReader(bytes.Repeat([]byte{0x47}, 256)),
	})
	if err != nil {
		t.Fatalf("StartListener() error = %v", err)
	}
	source.set([]InterfaceSnapshot{{
		Index:     7,
		Name:      "en0",
		Up:        true,
		Addresses: []string{"192.168.51.9/24"},
	}})

	result := make(chan error, 1)
	go func() {
		_, acceptErr := pending.Accept(context.Background(), HandshakePolicy{
			AllowedCapabilities: []string{requiredConfirmation, requiredRPC},
		})
		result <- acceptErr
	}()
	connection, err := net.DialTimeout("tcp", pending.listenerAddress(), time.Second)
	if err != nil {
		t.Fatalf("dial localhost listener: %v", err)
	}
	defer connection.Close()
	if code := ErrorCode(<-result); code != CodeInterfaceMismatch {
		t.Fatalf("network switch error code = %q, want %q", code, CodeInterfaceMismatch)
	}
	if !pending.privateKey.Destroyed() {
		t.Fatal("network switch did not destroy ephemeral private key")
	}
}

func TestReplayGuardAtomicallyConsumesInvitationAndNonce(t *testing.T) {
	guard := NewReplayGuard()
	expiry := time.Now().Add(time.Minute)
	if err := guard.Consume("invitation-a", "nonce-a", expiry, time.Now()); err != nil {
		t.Fatalf("first Consume() error = %v", err)
	}
	if code := ErrorCode(guard.Consume("invitation-a", "nonce-b", expiry, time.Now())); code != CodeInvitationReplayed {
		t.Fatalf("invitation replay code = %q", code)
	}
	if code := ErrorCode(guard.Consume("invitation-b", "nonce-a", expiry, time.Now())); code != CodeNonceReplayed {
		t.Fatalf("nonce replay code = %q", code)
	}
}

func TestSessionConcurrentCloseInterruptsBlockedRead(t *testing.T) {
	keys := deterministicSessionKeys()
	defer keys.Destroy()
	framer, err := NewFramer(FramerDesktop, keys, [32]byte{0x63})
	if err != nil {
		t.Fatalf("NewFramer() error = %v", err)
	}
	desktopConnection, peerConnection := net.Pipe()
	defer peerConnection.Close()
	session := newSession(
		desktopConnection,
		framer,
		bytes.Repeat([]byte{0x73}, 32),
		[32]byte{0x63},
		[]string{requiredConfirmation, requiredRPC},
		Endpoint{
			Host:        "192.168.50.12",
			Family:      "ipv4",
			Port:        47831,
			InterfaceID: "if-7-en0",
		},
		time.Now().Add(time.Minute),
	)

	readResult := make(chan error, 1)
	go func() {
		_, plaintext, readErr := session.ReadFrame(context.Background())
		clear(plaintext)
		readResult <- readErr
	}()
	time.Sleep(10 * time.Millisecond)

	const closers = 32
	closeErrors := make(chan error, closers)
	var group sync.WaitGroup
	for index := 0; index < closers; index++ {
		group.Add(1)
		go func() {
			defer group.Done()
			closeErrors <- session.Close()
		}()
	}
	group.Wait()
	close(closeErrors)
	for closeErr := range closeErrors {
		if closeErr != nil {
			t.Fatalf("concurrent Close() error = %v", closeErr)
		}
	}
	select {
	case readErr := <-readResult:
		if code := ErrorCode(readErr); code != CodeConnectionClosed &&
			code != CodeSessionClosed {
			t.Fatalf("blocked ReadFrame() error code = %q, error=%v", code, readErr)
		}
	case <-time.After(time.Second):
		t.Fatal("concurrent Close() did not interrupt blocked ReadFrame")
	}
	if !session.Closed() || !framer.Destroyed() {
		t.Fatal("concurrent close did not destroy session/framer state")
	}
}

func stableFakeNetwork() (*fakeInterfaceSource, NetworkInterface) {
	source := &fakeInterfaceSource{interfaces: []InterfaceSnapshot{{
		Index:     7,
		Name:      "en0",
		Up:        true,
		Addresses: []string{"192.168.50.12/24"},
	}}}
	selected := NetworkInterface{
		ID:         "if-7-en0",
		Name:       "en0",
		Kind:       "wifi",
		Candidates: privateCandidates("if-7-en0", "en0", "192.168.50.12"),
	}
	return source, selected
}

type localhostTestBinder struct{}

func (localhostTestBinder) Listen(
	ctx context.Context,
	_ NetworkInterface,
	candidate AddressCandidate,
	port int,
) (net.Listener, error) {
	network := "tcp4"
	host := "127.0.0.1"
	if candidate.Family == "ipv6" {
		network = "tcp6"
		host = "::1"
	}
	return (&net.ListenConfig{}).Listen(ctx, network, net.JoinHostPort(host, strconv.Itoa(port)))
}

type blockingListener struct {
	mu     sync.Mutex
	closed bool
}

func (listener *blockingListener) Accept() (net.Conn, error) {
	for {
		listener.mu.Lock()
		closed := listener.closed
		listener.mu.Unlock()
		if closed {
			return nil, net.ErrClosed
		}
		time.Sleep(time.Millisecond)
	}
}

func (listener *blockingListener) Close() error {
	listener.mu.Lock()
	defer listener.mu.Unlock()
	if listener.closed {
		return net.ErrClosed
	}
	listener.closed = true
	return nil
}

func (listener *blockingListener) Addr() net.Addr {
	return fakeAddr("127.0.0.1:47831")
}

type fakeAddr string

func (address fakeAddr) Network() string { return "tcp" }
func (address fakeAddr) String() string  { return string(address) }

var _ = errors.Is
