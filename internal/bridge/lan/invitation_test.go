package lan

// 测试用途：本文件验证 invitation、手工邀请码和二维码 provider 使用完全相同的公开 payload。

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestCreateInvitationProducesN36PayloadManualCodeAndProviderInput(t *testing.T) {
	selected := NetworkInterface{
		ID:         "if-7-en0",
		Name:       "en0",
		Kind:       "wifi",
		Candidates: privateCandidates("if-7-en0", "en0", "192.168.50.12"),
	}
	random := bytes.NewReader(bytes.Repeat([]byte{0x42}, 128))
	provider := &capturingQRProvider{representation: QRRepresentation{
		Format: "test-provider",
		Data:   []byte("provider-result"),
	}}

	bundle, privateKey, err := CreateInvitation(InvitationOptions{
		Interface: selected,
		Candidate: selected.Candidates[0],
		Port:      47831,
		TTL:       90 * time.Second,
		Capabilities: []string{
			requiredConfirmation,
			requiredRPC,
			"ui.snapshot",
		},
		Now:        func() time.Time { return time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC) },
		Random:     random,
		QRProvider: provider,
	})
	if err != nil {
		t.Fatalf("CreateInvitation() error = %v", err)
	}
	defer privateKey.Destroy()

	parsed, err := ParseInvitationJSON(bundle.Payload)
	if err != nil {
		t.Fatalf("generated payload is not a valid N36 invitation: %v", err)
	}
	if parsed.Listener.Port != 47831 || parsed.Listener.SelectedInterface.ID != selected.ID {
		t.Fatalf("generated listener = %#v", parsed.Listener)
	}
	if err := ValidateInvitation(parsed, ValidationContext{
		Now:               time.Date(2026, 7, 25, 10, 0, 30, 0, time.UTC),
		ExpectedInterface: &parsed.Listener.SelectedInterface,
	}); err != nil {
		t.Fatalf("ValidateInvitation(generated) error = %v", err)
	}

	decodedManual, err := DecodeManualInvitation(bundle.ManualCode)
	if err != nil {
		t.Fatalf("DecodeManualInvitation() error = %v", err)
	}
	if !bytes.Equal(decodedManual, bundle.Payload) {
		t.Fatal("manual invitation did not decode to the exact QR payload")
	}
	if !bytes.Equal(provider.payload, bundle.Payload) {
		t.Fatal("QR provider did not receive the exact invitation payload")
	}
	if bundle.QR.Format != "test-provider" || string(bundle.QR.Data) != "provider-result" {
		t.Fatalf("QR representation = %#v", bundle.QR)
	}
	assertInvitationContainsNoSecretFields(t, bundle.Payload)
}

func TestCreateInvitationWithoutProviderReportsPayloadOnly(t *testing.T) {
	selected := NetworkInterface{
		ID:         "if-7-en0",
		Name:       "en0",
		Kind:       "wifi",
		Candidates: privateCandidates("if-7-en0", "en0", "192.168.50.12"),
	}
	bundle, privateKey, err := CreateInvitation(InvitationOptions{
		Interface: selected,
		Candidate: selected.Candidates[0],
		Port:      47831,
		TTL:       30 * time.Second,
		Capabilities: []string{
			requiredConfirmation,
			requiredRPC,
		},
		Now:    func() time.Time { return time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC) },
		Random: bytes.NewReader(bytes.Repeat([]byte{0x24}, 128)),
	})
	if err != nil {
		t.Fatalf("CreateInvitation() error = %v", err)
	}
	defer privateKey.Destroy()
	if bundle.QR.Format != QRFormatPayloadOnly || len(bundle.QR.Data) != 0 {
		t.Fatalf("payload-only QR representation = %#v", bundle.QR)
	}
	if bundle.QR.Generated {
		t.Fatal("payload-only result falsely claimed that an image was generated")
	}
}

func TestEphemeralPrivateKeyDestroyClearsControllableBytes(t *testing.T) {
	key, err := NewEphemeralPrivateKey(bytes.NewReader(bytes.Repeat([]byte{0x35}, 64)))
	if err != nil {
		t.Fatalf("NewEphemeralPrivateKey() error = %v", err)
	}
	if key.Destroyed() || allBytesZero(key.privateBytes[:]) {
		t.Fatal("new private key unexpectedly started destroyed")
	}
	key.Destroy()
	if !key.Destroyed() || !allBytesZero(key.privateBytes[:]) {
		t.Fatal("Destroy() did not clear controllable private key bytes")
	}
	if _, err := key.PublicKey(); ErrorCode(err) != CodeSessionClosed {
		t.Fatalf("PublicKey() after destroy error = %v", err)
	}
}

type capturingQRProvider struct {
	payload        []byte
	representation QRRepresentation
}

func (provider *capturingQRProvider) Encode(
	_ context.Context,
	payload []byte,
) (QRRepresentation, error) {
	provider.payload = append([]byte(nil), payload...)
	return provider.representation, nil
}

func assertInvitationContainsNoSecretFields(t *testing.T, payload []byte) {
	t.Helper()
	var generic map[string]any
	if err := json.Unmarshal(payload, &generic); err != nil {
		t.Fatalf("decode invitation for secret audit: %v", err)
	}
	for _, forbidden := range []string{
		"token",
		"sessionToken",
		"pairingCode",
		"privateKey",
		"apiKey",
		"secret",
		"tag",
	} {
		if _, exists := generic[forbidden]; exists {
			t.Fatalf("invitation contains forbidden field %q", forbidden)
		}
		if strings.Contains(strings.ToLower(string(payload)), strings.ToLower(`"`+forbidden+`"`)) {
			t.Fatalf("invitation payload contains forbidden key %q", forbidden)
		}
	}
}
