package lan

// 测试用途：验证真实握手读取器预读的首个加密 frame 在会话交接后仍能被认证读取。

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net"
	"testing"
	"time"
)

func TestHandshakeRetainsBufferedEncryptedFrame(t *testing.T) {
	_, selected := stableFakeNetwork()
	bundle, desktopKey, err := CreateInvitation(InvitationOptions{
		Interface: selected, Candidate: selected.Candidates[0], Port: 47831,
		TTL: time.Minute, Capabilities: []string{requiredConfirmation, requiredRPC},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer desktopKey.Destroy()
	clientKey, err := NewEphemeralPrivateKey(bytes.NewReader(bytes.Repeat([]byte{0x23}, 32)))
	if err != nil {
		t.Fatal(err)
	}
	defer clientKey.Destroy()
	public, err := clientKey.PublicKey()
	if err != nil {
		t.Fatal(err)
	}
	invitation := bundle.Invitation
	hello := ClientHello{
		Type: "lan.clientHello", Version: protocolVersion, InvitationID: invitation.InvitationID,
		Nonce: invitation.Nonce, ClientEphemeralPublicKey: base64.RawURLEncoding.EncodeToString(public),
		SelectedEndpoint:        Endpoint{Host: selected.Candidates[0].Host, Family: "ipv4", Port: 47831, InterfaceID: selected.ID},
		SupportedBridgeVersions: []string{"1.0"}, RequestedCapabilities: []string{requiredConfirmation, requiredRPC},
	}
	// 双端均由真实协议函数派生密钥，不绕过 transcript 或双方确认。
	selection, err := NegotiateSelection(invitation, hello, hello.RequestedCapabilities)
	if err != nil {
		t.Fatal(err)
	}
	transcript, err := BuildTranscriptHash(invitation, hello, selection)
	if err != nil {
		t.Fatal(err)
	}
	secret, err := clientKey.ECDH(decodeBase64URL32(invitation.EphemeralKey.PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	keys, err := DeriveSessionKeys(secret, transcript)
	clear(secret)
	if err != nil {
		t.Fatal(err)
	}
	defer keys.Destroy()
	confirmationKey := keys.Confirmation()
	defer clear(confirmationKey)
	tag, err := SignConfirmation(ConfirmationClient, confirmationKey, transcript)
	if err != nil {
		t.Fatal(err)
	}
	defer clear(tag)
	framer, err := NewFramer(FramerClient, keys, transcript)
	if err != nil {
		t.Fatal(err)
	}
	defer framer.Destroy()
	frame, err := framer.Seal("bridge.response", []byte(`{"ok":true}`))
	if err != nil {
		t.Fatal(err)
	}
	frameBytes, err := json.Marshal(frame)
	if err != nil {
		t.Fatal(err)
	}
	confirmationBytes, err := json.Marshal(confirmationMessage{
		Type: "lan.confirmation", Version: protocolVersion, InvitationID: invitation.InvitationID,
		Role: ConfirmationClient, TranscriptHash: base64.RawURLEncoding.EncodeToString(transcript[:]),
		Tag: base64.RawURLEncoding.EncodeToString(tag),
	})
	if err != nil {
		t.Fatal(err)
	}
	desktopConn, peerConn := net.Pipe()
	defer desktopConn.Close()
	defer peerConn.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = peerConn.SetDeadline(time.Now().Add(2 * time.Second))
	pending := &PendingListener{selected: selected, bundle: bundle, privateKey: desktopKey, replayGuard: NewReplayGuard(), now: time.Now}
	type outcome struct {
		session *Session
		err     error
	}
	done := make(chan outcome, 1)
	go func() {
		session, err := pending.performHandshake(ctx, desktopConn, HandshakePolicy{AllowedCapabilities: hello.RequestedCapabilities})
		done <- outcome{session, err}
	}()
	if err := writeHandshakeMessage(peerConn, hello); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(peerConn)
	var selectedMessage desktopSelectionMessage
	if err := readHandshakeMessage(reader, &selectedMessage); err != nil {
		t.Fatal(err)
	}
	var desktopConfirmation confirmationMessage
	if err := readHandshakeMessage(reader, &desktopConfirmation); err != nil {
		t.Fatal(err)
	}
	if err := validateConfirmationMessage(desktopConfirmation, invitation.InvitationID, ConfirmationDesktop, transcript, confirmationKey); err != nil {
		t.Fatal(err)
	}
	// 单次 Write 合并确认和首帧，强制服务端 bufio.Reader 发生合法预读。
	payload := append(confirmationBytes, '\n')
	payload = append(payload, frameBytes...)
	payload = append(payload, '\n')
	if _, err := peerConn.Write(payload); err != nil {
		t.Fatal(err)
	}
	result := <-done
	if result.err != nil {
		t.Fatal(result.err)
	}
	defer result.session.Close()
	kind, plaintext, err := result.session.ReadFrame(ctx)
	if err != nil || kind != "bridge.response" || string(plaintext) != `{"ok":true}` {
		t.Fatalf("first frame = %q %q %v", kind, plaintext, err)
	}
	clear(plaintext)
}

func TestBoundedLinePreservesFollowingLineAndExactLimit(t *testing.T) {
	for _, bufferSize := range []int{16, 4096} {
		reader := bufio.NewReaderSize(bytes.NewBufferString("12345\n{}\r\n"), bufferSize)
		line, err := readBoundedLANLine(reader, 6)
		if err != nil || string(line) != "12345\n" {
			t.Fatalf("first line = %q %v", line, err)
		}
		var second map[string]any
		if err := readHandshakeMessage(reader, &second); err != nil {
			t.Fatal(err)
		}
	}
}
