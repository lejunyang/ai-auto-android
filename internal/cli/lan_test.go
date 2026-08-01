package cli

// 测试用途：验证 LAN CLI 的显式网卡选择、流式邀请、失败关闭和秘密脱敏边界。

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge/lan"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

func TestLANInterfacesDoesNotResolveADB(t *testing.T) {
	var stdout bytes.Buffer
	app := &App{
		Locator:  fixedLocator{err: context.Canceled},
		Executor: &commandExecutor{},
		Stdin:    strings.NewReader(""),
		Stdout:   &stdout,
		Stderr:   &bytes.Buffer{},
		LANInterfaces: fixedLANInterfaces{snapshots: []lan.InterfaceSnapshot{
			{
				Index:     7,
				Name:      "en0",
				Up:        true,
				Addresses: []string{"192.168.50.12/24", "203.0.113.7/24"},
			},
			{
				Index:     8,
				Name:      "utun4",
				Up:        true,
				Addresses: []string{"10.8.0.2/24"},
			},
		}},
	}

	if exitCode := app.Run(
		context.Background(),
		[]string{"bridge", "lan", "interfaces", "--json"},
	); exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	payload := remarshalLANData[lanInterfacesResult](t, envelope.Data)
	if payload.Count != 2 {
		t.Fatalf("interface count = %d, want 2", payload.Count)
	}
	if payload.Interfaces[0].ID != "if-7-en0" ||
		payload.Interfaces[0].Candidates[0].Host != "192.168.50.12" {
		t.Fatalf("first interface = %#v", payload.Interfaces[0])
	}
	if !payload.Interfaces[1].Tunnel {
		t.Fatalf("VPN interface was not identified: %#v", payload.Interfaces[1])
	}
}

func TestLANListenStreamsFixedInvitationThenTimeoutWithoutSecrets(t *testing.T) {
	now := time.Date(2026, 7, 26, 2, 3, 4, 0, time.UTC)
	pending := &fakeLANPending{
		bundle: fixedLANBundle(now),
		acceptErr: &lan.Error{
			Code:    lan.CodeAcceptTimeout,
			Message: "listener accept timed out",
		},
	}
	starter := &fakeLANStarter{pending: pending}
	privateMarker := "PRIVATE-KEY-MUST-NOT-LEAK"
	random := strings.NewReader(privateMarker)
	qr := &fixedQRProvider{}
	var stdout bytes.Buffer
	app := lanTestApp(&stdout, starter, random, qr, now)

	exitCode := app.Run(context.Background(), []string{
		"bridge", "lan", "listen",
		"--interface", "if-7-en0",
		"--address", "192.168.50.12",
		"--ttl", "90s",
		"--accept-timeout", "20ms",
		"--json",
	})
	if exitCode == 0 {
		t.Fatalf("listen unexpectedly succeeded: %s", stdout.String())
	}

	events := decodeLANEnvelopes(t, stdout.Bytes())
	if len(events) != 2 || !events[0].OK || events[1].OK {
		t.Fatalf("events = %#v", events)
	}
	invitation := remarshalLANData[lanInvitationResult](t, events[0].Data)
	if invitation.Phase != "invitation" ||
		invitation.ManualCode != pending.bundle.ManualCode ||
		invitation.Fingerprint != pending.bundle.Invitation.Fingerprint ||
		invitation.Endpoint.Host != "192.168.50.12" ||
		invitation.ExpiresAt != now.Add(90*time.Second).Format(time.RFC3339) {
		t.Fatalf("invitation output = %#v", invitation)
	}
	if !json.Valid(invitation.InvitationJSON) {
		t.Fatalf("invitation JSON is invalid: %q", invitation.InvitationJSON)
	}
	if invitation.QRFormat != lan.QRFormatTerminalUTF8 ||
		invitation.QRText != "fixed-terminal-qr" ||
		!invitation.QRGenerated {
		t.Fatalf("terminal QR output = %#v", invitation)
	}
	if events[1].Error == nil || events[1].Error.Code != string(lan.CodeAcceptTimeout) {
		t.Fatalf("timeout error = %#v", events[1].Error)
	}
	for _, secret := range []string{
		privateMarker,
		"derived-key",
		"confirmation-tag",
		"lan-session-token",
	} {
		if strings.Contains(stdout.String(), secret) {
			t.Fatalf("secret %q leaked into stdout: %s", secret, stdout.String())
		}
	}
	if !pending.closed {
		t.Fatal("timeout did not close pending listener")
	}
	if starter.options.Selected.ID != "if-7-en0" ||
		starter.options.Candidate.Host != "192.168.50.12" ||
		starter.options.Random != random ||
		starter.options.QRProvider != qr {
		t.Fatalf("listener options = %#v", starter.options)
	}
}

func TestDefaultAppInjectsBoundedTerminalQRProvider(t *testing.T) {
	app := DefaultApp(strings.NewReader(""), &bytes.Buffer{}, &bytes.Buffer{})
	if app.LANQR == nil {
		t.Fatal("DefaultApp did not inject the production QR provider")
	}
	representation, err := app.LANQR.Encode(
		context.Background(),
		[]byte(`{"kind":"ai-auto-lan-invitation"}`),
	)
	if err != nil {
		t.Fatalf("production QR provider error = %v", err)
	}
	if representation.Format != lan.QRFormatTerminalUTF8 ||
		!representation.Generated ||
		len(representation.Data) == 0 ||
		len(representation.Data) > lan.MaxTerminalQRBytes {
		t.Fatalf("production QR representation = %#v", representation)
	}
	clear(representation.Data)
}

func TestLANListenClosesOnCancellationAndNetworkSwitch(t *testing.T) {
	tests := []struct {
		name string
		code lan.Code
	}{
		{name: "cancelled", code: lan.CodeCancelled},
		{name: "network switched", code: lan.CodeInterfaceMismatch},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			now := time.Date(2026, 7, 26, 2, 3, 4, 0, time.UTC)
			pending := &fakeLANPending{
				bundle: fixedLANBundle(now),
				acceptErr: &lan.Error{
					Code:    test.code,
					Message: "fixed lifecycle failure",
				},
			}
			var stdout bytes.Buffer
			app := lanTestApp(
				&stdout,
				&fakeLANStarter{pending: pending},
				strings.NewReader(strings.Repeat("r", 128)),
				nil,
				now,
			)

			exitCode := app.Run(context.Background(), []string{
				"bridge", "lan", "listen",
				"--interface", "if-7-en0",
				"--address", "192.168.50.12",
				"--json",
			})
			if exitCode == 0 {
				t.Fatalf("listen unexpectedly succeeded: %s", stdout.String())
			}
			events := decodeLANEnvelopes(t, stdout.Bytes())
			if len(events) != 2 ||
				events[1].Error == nil ||
				events[1].Error.Code != string(test.code) {
				t.Fatalf("events = %#v", events)
			}
			if !pending.closed {
				t.Fatal("lifecycle failure did not close pending listener")
			}
		})
	}
}

func TestLANListenClosesAuthenticatedSessionWithoutExposingToken(t *testing.T) {
	now := time.Date(2026, 7, 26, 2, 3, 4, 0, time.UTC)
	session := &fakeLANSession{
		capabilities: []string{
			lan.CapabilityMutualConfirmation,
			lan.CapabilityRPC,
		},
		endpoint: lan.Endpoint{
			Host:        "192.168.50.12",
			Family:      "ipv4",
			Port:        47831,
			InterfaceID: "if-7-en0",
		},
		expiresAt: now.Add(90 * time.Second),
	}
	pending := &fakeLANPending{
		bundle:  fixedLANBundle(now),
		session: session,
	}
	var stdout bytes.Buffer
	app := lanTestApp(
		&stdout,
		&fakeLANStarter{pending: pending},
		strings.NewReader(strings.Repeat("s", 128)),
		nil,
		now,
	)

	if exitCode := app.Run(context.Background(), []string{
		"bridge", "lan", "listen",
		"--interface", "if-7-en0",
		"--address", "192.168.50.12",
		"--json",
	}); exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	events := decodeLANEnvelopes(t, stdout.Bytes())
	if len(events) != 2 || !events[1].OK {
		t.Fatalf("events = %#v", events)
	}
	closed := remarshalLANData[lanClosedResult](t, events[1].Data)
	if closed.Phase != "closed" ||
		!closed.Authenticated ||
		closed.Endpoint != session.endpoint ||
		len(closed.Capabilities) != 2 {
		t.Fatalf("closed output = %#v", closed)
	}
	if !session.closed || !pending.closed {
		t.Fatalf("session closed=%t pending closed=%t", session.closed, pending.closed)
	}
	if strings.Contains(stdout.String(), "lan-session-token") {
		t.Fatalf("LAN token leaked into stdout: %s", stdout.String())
	}
}

func TestLANListenRunsBoundedReadOnlyRPCProbeBeforeClosing(t *testing.T) {
	now := time.Date(2026, 7, 26, 2, 3, 4, 0, time.UTC)
	session := &fakeLANSession{
		capabilities: []string{
			lan.CapabilityMutualConfirmation,
			lan.CapabilityRPC,
		},
		endpoint: lan.Endpoint{
			Host:        "192.168.50.12",
			Family:      "ipv4",
			Port:        47831,
			InterfaceID: "if-7-en0",
		},
		expiresAt: now.Add(90 * time.Second),
	}
	pending := &fakeLANPending{
		bundle:  fixedLANBundle(now),
		session: session,
	}
	var stdout bytes.Buffer
	app := lanTestApp(
		&stdout,
		&fakeLANStarter{pending: pending},
		strings.NewReader(strings.Repeat("s", 128)),
		nil,
		now,
	)

	if exitCode := app.Run(context.Background(), []string{
		"bridge", "lan", "listen",
		"--interface", "if-7-en0",
		"--address", "192.168.50.12",
		"--rpc-method", "device.info",
		"--rpc-count", "2",
		"--json",
	}); exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	events := decodeLANEnvelopes(t, stdout.Bytes())
	if len(events) != 4 {
		t.Fatalf("event count = %d, want invitation + 2 RPC + closed", len(events))
	}
	for index := 1; index <= 2; index++ {
		rpc := remarshalLANData[lanRPCResult](t, events[index].Data)
		if rpc.Phase != "rpc" ||
			rpc.Method != "device.info" ||
			rpc.Iteration != index ||
			!json.Valid(rpc.Result) {
			t.Fatalf("RPC event[%d] = %#v", index, rpc)
		}
	}
	if got := strings.Join(session.calls, ","); got != "device.info,device.info,session.close" {
		t.Fatalf("RPC calls = %q", got)
	}
	if !session.closed || strings.Contains(stdout.String(), "lan-session-token") {
		t.Fatalf("closed=%t output=%s", session.closed, stdout.String())
	}
}

func TestLANListenRejectsArbitraryRPCMethodBeforeStartingListener(t *testing.T) {
	starter := &fakeLANStarter{}
	var stdout bytes.Buffer
	app := lanTestApp(
		&stdout,
		starter,
		strings.NewReader(strings.Repeat("s", 128)),
		nil,
		time.Date(2026, 7, 26, 2, 3, 4, 0, time.UTC),
	)

	if exitCode := app.Run(context.Background(), []string{
		"bridge", "lan", "listen",
		"--interface", "if-7-en0",
		"--address", "192.168.50.12",
		"--rpc-method", "action.execute",
		"--json",
	}); exitCode == 0 {
		t.Fatalf("arbitrary RPC method unexpectedly succeeded: %s", stdout.String())
	}
	if starter.calls != 0 {
		t.Fatalf("invalid RPC method started listener %d times", starter.calls)
	}
}

func TestLANListenRejectsImplicitOrVPNSelectionBeforeStart(t *testing.T) {
	tests := [][]string{
		{
			"bridge", "lan", "listen",
			"--interface", "if-7-en0",
		},
		{
			"bridge", "lan", "listen",
			"--interface", "if-8-utun4",
			"--address", "10.8.0.2",
		},
		{
			"bridge", "lan", "listen",
			"--interface", "if-7-en0",
			"--address", "0.0.0.0",
		},
	}
	for _, args := range tests {
		var stdout bytes.Buffer
		starter := &fakeLANStarter{}
		app := lanTestApp(
			&stdout,
			starter,
			strings.NewReader(strings.Repeat("t", 128)),
			nil,
			time.Now().UTC(),
		)
		exitCode := app.Run(context.Background(), append(args, "--json"))
		if exitCode == 0 {
			t.Fatalf("args=%#v unexpectedly succeeded: %s", args, stdout.String())
		}
		if starter.calls != 0 {
			t.Fatalf("args=%#v started listener %d times", args, starter.calls)
		}
	}
}

func TestLANListenOutputFailureClosesBeforeAccept(t *testing.T) {
	pending := &fakeLANPending{
		bundle: fixedLANBundle(time.Date(2026, 7, 26, 2, 3, 4, 0, time.UTC)),
	}
	app := lanTestApp(
		failingWriter{},
		&fakeLANStarter{pending: pending},
		strings.NewReader(strings.Repeat("u", 128)),
		nil,
		time.Date(2026, 7, 26, 2, 3, 4, 0, time.UTC),
	)

	if exitCode := app.Run(context.Background(), []string{
		"bridge", "lan", "listen",
		"--interface", "if-7-en0",
		"--address", "192.168.50.12",
		"--json",
	}); exitCode != apperr.ExitInternal {
		t.Fatalf("exit code = %d, want %d", exitCode, apperr.ExitInternal)
	}
	if !pending.closed || pending.acceptCalls != 0 {
		t.Fatalf("pending closed=%t accept calls=%d", pending.closed, pending.acceptCalls)
	}
}

type fixedLANInterfaces struct {
	snapshots []lan.InterfaceSnapshot
}

func (source fixedLANInterfaces) Interfaces() ([]lan.InterfaceSnapshot, error) {
	result := make([]lan.InterfaceSnapshot, len(source.snapshots))
	copy(result, source.snapshots)
	for index := range result {
		result[index].Addresses = append([]string(nil), result[index].Addresses...)
	}
	return result, nil
}

type fixedQRProvider struct{}

func (*fixedQRProvider) Encode(
	_ context.Context,
	payload []byte,
) (lan.QRRepresentation, error) {
	return lan.QRRepresentation{
		Format:    "test-provider",
		Data:      append([]byte(nil), payload...),
		Generated: true,
	}, nil
}

type fakeLANStarter struct {
	mu      sync.Mutex
	pending lanPending
	err     error
	options lan.ListenerOptions
	calls   int
}

func (starter *fakeLANStarter) Start(
	_ context.Context,
	options lan.ListenerOptions,
) (lanPending, error) {
	starter.mu.Lock()
	defer starter.mu.Unlock()
	starter.calls++
	starter.options = options
	return starter.pending, starter.err
}

type fakeLANPending struct {
	bundle      lan.InvitationBundle
	session     lanSession
	acceptErr   error
	closed      bool
	acceptCalls int
}

func (pending *fakeLANPending) Bundle() lan.InvitationBundle {
	return pending.bundle
}

func (pending *fakeLANPending) Accept(
	_ context.Context,
	_ lan.HandshakePolicy,
) (lanSession, error) {
	pending.acceptCalls++
	return pending.session, pending.acceptErr
}

func (pending *fakeLANPending) Close() error {
	pending.closed = true
	return nil
}

type fakeLANSession struct {
	capabilities []string
	endpoint     lan.Endpoint
	expiresAt    time.Time
	closed       bool
	calls        []string
	callErr      error
}

func (session *fakeLANSession) Capabilities() []string {
	return append([]string(nil), session.capabilities...)
}

func (session *fakeLANSession) Endpoint() lan.Endpoint {
	return session.endpoint
}

func (session *fakeLANSession) ExpiresAt() time.Time {
	return session.expiresAt
}

func (session *fakeLANSession) Call(
	_ context.Context,
	method string,
	_ any,
	result any,
) error {
	session.calls = append(session.calls, method)
	if session.callErr != nil {
		return session.callErr
	}
	payload := []byte(`{"ok":true}`)
	if method == "session.close" {
		payload = []byte(`{"closed":true}`)
		session.closed = true
	}
	return json.Unmarshal(payload, result)
}

func (session *fakeLANSession) Close() error {
	session.closed = true
	return nil
}

func fixedLANBundle(now time.Time) lan.InvitationBundle {
	invitation := lan.Invitation{
		Kind:         "ai-auto-lan-invitation",
		Version:      "1.0",
		InvitationID: "647d42e0-47c3-4ac8-9e7f-8f8bcf716c41",
		IssuedAt:     now.Format(time.RFC3339),
		ExpiresAt:    now.Add(90 * time.Second).Format(time.RFC3339),
		TTLSeconds:   90,
		Nonce:        "public-invitation-nonce",
		Listener: lan.ListenerDescriptor{
			Port: 47831,
			SelectedInterface: lan.SelectedInterface{
				ID:   "if-7-en0",
				Name: "en0",
				Kind: "wifi",
			},
			AddressCandidates: []lan.AddressCandidate{{
				Host:        "192.168.50.12",
				Family:      "ipv4",
				Scope:       "private",
				InterfaceID: "if-7-en0",
			}},
		},
		EphemeralKey: lan.EphemeralKey{
			Algorithm: "X25519",
			Encoding:  "base64url",
			PublicKey: "public-ephemeral-key",
		},
		Fingerprint: "8975-0256-F8CF-C56A",
		Capabilities: []string{
			lan.CapabilityMutualConfirmation,
			lan.CapabilityRPC,
		},
		Security: lan.SecurityDescriptor{
			Suite:        "X25519-HKDF-SHA256-AES-256-GCM",
			Transcript:   "AIAUTO-LAN-BRIDGE-TRANSCRIPT-V1",
			Confirmation: "HMAC-SHA256",
		},
	}
	payload, err := json.Marshal(invitation)
	if err != nil {
		panic(err)
	}
	return lan.InvitationBundle{
		Invitation: invitation,
		Payload:    payload,
		ManualCode: "AIAUTO1-FIXED-MANUAL-CODE",
		QR: lan.QRRepresentation{
			Format:    lan.QRFormatTerminalUTF8,
			Data:      []byte("fixed-terminal-qr"),
			Generated: true,
		},
	}
}

func lanTestApp(
	stdout io.Writer,
	starter lanListenerStarter,
	random io.Reader,
	qr lan.QRProvider,
	now time.Time,
) *App {
	return &App{
		Locator:  fixedLocator{err: context.Canceled},
		Executor: process.Executor(&commandExecutor{}),
		Stdin:    strings.NewReader(""),
		Stdout:   stdout,
		Stderr:   &bytes.Buffer{},
		LANInterfaces: fixedLANInterfaces{snapshots: []lan.InterfaceSnapshot{
			{
				Index:     7,
				Name:      "en0",
				Up:        true,
				Addresses: []string{"192.168.50.12/24"},
			},
			{
				Index:     8,
				Name:      "utun4",
				Up:        true,
				Addresses: []string{"10.8.0.2/24"},
			},
		}},
		LANBinder:    rejectingLANBinder{},
		LANRandom:    random,
		LANQR:        qr,
		LANNow:       func() time.Time { return now },
		LANListeners: starter,
	}
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) {
	return 0, io.ErrClosedPipe
}

type rejectingLANBinder struct{}

func (rejectingLANBinder) Listen(
	context.Context,
	lan.NetworkInterface,
	lan.AddressCandidate,
	int,
) (net.Listener, error) {
	return nil, context.Canceled
}

func decodeLANEnvelopes(t *testing.T, payload []byte) []protocol.Envelope {
	t.Helper()
	decoder := json.NewDecoder(bytes.NewReader(payload))
	result := make([]protocol.Envelope, 0, 2)
	for {
		var envelope protocol.Envelope
		if err := decoder.Decode(&envelope); err != nil {
			if err == io.EOF {
				return result
			}
			t.Fatalf("decode LAN output %q: %v", payload, err)
		}
		result = append(result, envelope)
	}
}

func remarshalLANData[T any](t *testing.T, value any) T {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal data: %v", err)
	}
	var result T
	if err := json.Unmarshal(payload, &result); err != nil {
		t.Fatalf("unmarshal data %q: %v", payload, err)
	}
	return result
}
