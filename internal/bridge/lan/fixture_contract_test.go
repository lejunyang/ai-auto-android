// Package lan 实现桌面 LAN invitation、加密握手和短期 RPC 会话的安全边界。
package lan

// 测试用途：验证 Go 消费全部 N36 fixture，并固定严格预检、密码向量和低阶点拒绝契约。

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"
)

type threatSuite struct {
	Cases []threatCase `json:"cases"`
}

type threatCase struct {
	Name         string            `json:"name"`
	Stage        string            `json:"stage"`
	Context      fixtureContext    `json:"context"`
	ExpectedCode Code              `json:"expectedCode"`
	Mutations    []fixtureMutation `json:"mutations"`
}

type fixtureContext struct {
	Now                   string             `json:"now"`
	ConsumedInvitationIDs []string           `json:"consumedInvitationIds"`
	ConsumedNonces        []string           `json:"consumedNonces"`
	ExpectedInterface     *SelectedInterface `json:"expectedInterface"`
}

type fixtureMutation struct {
	Op    string `json:"op"`
	Path  string `json:"path"`
	Value any    `json:"value"`
}

type cryptoVectorFixture struct {
	ClientHello    ClientHello `json:"clientHello"`
	Selection      Selection   `json:"selection"`
	SharedSecret   string      `json:"sharedSecretHex"`
	Expected       cryptoExpected
	LowOrderPoints []lowOrderPoint `json:"lowOrderPublicKeys"`
}

type cryptoExpected struct {
	Fingerprint         string `json:"fingerprint"`
	TranscriptHash      string `json:"transcriptHashHex"`
	ClientToDesktopKey  string `json:"clientToDesktopKeyHex"`
	DesktopToClientKey  string `json:"desktopToClientKeyHex"`
	ConfirmationKey     string `json:"confirmationKeyHex"`
	TokenBindingKey     string `json:"tokenBindingKeyHex"`
	DesktopConfirmation string `json:"desktopConfirmationHex"`
	ClientConfirmation  string `json:"clientConfirmationHex"`
}

type lowOrderPoint struct {
	Name      string `json:"name"`
	PublicKey string `json:"publicKey"`
}

func TestN36ValidInvitationAndAllThreatFixtures(t *testing.T) {
	basePayload := readProtocolFixture(t, "lan-invitation-v1-valid.json")
	suitePayload := readProtocolFixture(t, "lan-invitation-v1-threats.json")

	invitation, err := ParseInvitationJSON(basePayload)
	if err != nil {
		t.Fatalf("ParseInvitationJSON(valid) error = %v", err)
	}
	var suite threatSuite
	if err := json.Unmarshal(suitePayload, &suite); err != nil {
		t.Fatalf("decode threat suite: %v", err)
	}
	if got, want := len(suite.Cases), 18; got != want {
		t.Fatalf("threat fixture count = %d, want %d", got, want)
	}

	now := mustParseTime(t, "2026-07-25T10:00:30Z")
	if err := ValidateInvitation(invitation, ValidationContext{
		Now:               now,
		ExpectedInterface: &invitation.Listener.SelectedInterface,
	}); err != nil {
		t.Fatalf("ValidateInvitation(valid) error = %v", err)
	}

	for _, threat := range suite.Cases {
		threat := threat
		t.Run(threat.Name, func(t *testing.T) {
			mutated := applyFixtureMutations(t, basePayload, threat.Mutations)
			before := append([]byte(nil), mutated...)
			candidate, parseErr := ParseInvitationJSON(mutated)

			var gotErr error
			if threat.Stage == "schema" {
				gotErr = parseErr
			} else {
				if parseErr != nil {
					t.Fatalf("ParseInvitationJSON(preflight threat) error = %v", parseErr)
				}
				context := ValidationContext{
					Now:                   now,
					ConsumedInvitationIDs: stringSet(threat.Context.ConsumedInvitationIDs),
					ConsumedNonces:        stringSet(threat.Context.ConsumedNonces),
					ExpectedInterface:     threat.Context.ExpectedInterface,
				}
				if threat.Context.Now != "" {
					context.Now = mustParseTime(t, threat.Context.Now)
				}
				gotErr = ValidateInvitation(candidate, context)
			}

			if code := ErrorCode(gotErr); code != threat.ExpectedCode {
				t.Fatalf("error code = %q, want %q (error=%v)", code, threat.ExpectedCode, gotErr)
			}
			if !reflect.DeepEqual(mutated, before) {
				t.Fatal("fixture validation mutated its input payload")
			}
		})
	}
}

func TestN36FingerprintTranscriptHKDFAndConfirmationVector(t *testing.T) {
	invitation, err := ParseInvitationJSON(readProtocolFixture(t, "lan-invitation-v1-valid.json"))
	if err != nil {
		t.Fatalf("ParseInvitationJSON(valid) error = %v", err)
	}
	var vector cryptoVectorFixture
	if err := json.Unmarshal(
		readProtocolFixture(t, "lan-handshake-v1-vector.json"),
		&vector,
	); err != nil {
		t.Fatalf("decode crypto vector: %v", err)
	}

	fingerprint, err := ComputeInvitationFingerprint(invitation)
	if err != nil {
		t.Fatalf("ComputeInvitationFingerprint() error = %v", err)
	}
	if fingerprint != vector.Expected.Fingerprint {
		t.Fatalf("fingerprint = %q, want %q", fingerprint, vector.Expected.Fingerprint)
	}
	if err := ValidateClientHello(invitation, vector.ClientHello); err != nil {
		t.Fatalf("ValidateClientHello(vector) error = %v", err)
	}

	transcriptHash, err := BuildTranscriptHash(invitation, vector.ClientHello, vector.Selection)
	if err != nil {
		t.Fatalf("BuildTranscriptHash() error = %v", err)
	}
	assertHex(t, "transcript hash", transcriptHash[:], vector.Expected.TranscriptHash)

	sharedSecret := mustDecodeHex(t, vector.SharedSecret)
	keys, err := DeriveSessionKeys(sharedSecret, transcriptHash)
	clear(sharedSecret)
	if err != nil {
		t.Fatalf("DeriveSessionKeys() error = %v", err)
	}
	defer keys.Destroy()

	assertHex(t, "client-to-desktop key", keys.ClientToDesktop(), vector.Expected.ClientToDesktopKey)
	assertHex(t, "desktop-to-client key", keys.DesktopToClient(), vector.Expected.DesktopToClientKey)
	assertHex(t, "confirmation key", keys.Confirmation(), vector.Expected.ConfirmationKey)
	assertHex(t, "token-binding key", keys.TokenBinding(), vector.Expected.TokenBindingKey)

	desktopTag, err := SignConfirmation(ConfirmationDesktop, keys.Confirmation(), transcriptHash)
	if err != nil {
		t.Fatalf("SignConfirmation(desktop) error = %v", err)
	}
	defer clear(desktopTag)
	clientTag, err := SignConfirmation(ConfirmationClient, keys.Confirmation(), transcriptHash)
	if err != nil {
		t.Fatalf("SignConfirmation(client) error = %v", err)
	}
	defer clear(clientTag)
	assertHex(t, "desktop confirmation", desktopTag, vector.Expected.DesktopConfirmation)
	assertHex(t, "client confirmation", clientTag, vector.Expected.ClientConfirmation)

	if !VerifyConfirmation(ConfirmationDesktop, keys.Confirmation(), transcriptHash, desktopTag) {
		t.Fatal("desktop confirmation did not verify")
	}
	if !VerifyConfirmation(ConfirmationClient, keys.Confirmation(), transcriptHash, clientTag) {
		t.Fatal("client confirmation did not verify")
	}
	if VerifyConfirmation(ConfirmationClient, keys.Confirmation(), transcriptHash, desktopTag) {
		t.Fatal("desktop confirmation was accepted for client role")
	}
}

func TestN36X25519LowOrderPointsFailClosed(t *testing.T) {
	var vector cryptoVectorFixture
	if err := json.Unmarshal(
		readProtocolFixture(t, "lan-handshake-v1-vector.json"),
		&vector,
	); err != nil {
		t.Fatalf("decode crypto vector: %v", err)
	}
	privateKey, err := ecdh.X25519().GenerateKey(newDeterministicReader(0x5a))
	if err != nil {
		t.Fatalf("GenerateKey() error = %v", err)
	}
	transcriptHash := [32]byte{0x5a}

	for _, point := range vector.LowOrderPoints {
		point := point
		t.Run(point.Name, func(t *testing.T) {
			publicKey := mustDecodeBase64URL(t, point.PublicKey)
			if allZero(publicKey) {
				t.Fatal("low-order vector unexpectedly contains an all-zero public key")
			}
			_, deriveErr := DeriveSessionKeysX25519(privateKey, publicKey, transcriptHash)
			if code := ErrorCode(deriveErr); code != CodeEphemeralKeyWeak {
				t.Fatalf("error code = %q, want %q (error=%v)", code, CodeEphemeralKeyWeak, deriveErr)
			}
		})
	}
}

func readProtocolFixture(t *testing.T, name string) []byte {
	t.Helper()
	path := filepath.Join("..", "..", "..", "protocol", "fixtures", name)
	payload, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return payload
}

func applyFixtureMutations(t *testing.T, base []byte, mutations []fixtureMutation) []byte {
	t.Helper()
	var root any
	if err := json.Unmarshal(base, &root); err != nil {
		t.Fatalf("decode base fixture: %v", err)
	}
	for _, mutation := range mutations {
		parts := strings.Split(strings.TrimPrefix(mutation.Path, "/"), "/")
		for index := range parts {
			parts[index] = strings.ReplaceAll(strings.ReplaceAll(parts[index], "~1", "/"), "~0", "~")
		}
		current := root
		for _, part := range parts[:len(parts)-1] {
			switch container := current.(type) {
			case map[string]any:
				current = container[part]
			case []any:
				index, err := strconv.Atoi(part)
				if err != nil || index < 0 || index >= len(container) {
					t.Fatalf("mutation %s has invalid array index %q", mutation.Path, part)
				}
				current = container[index]
			default:
				t.Fatalf("mutation %s traverses a scalar", mutation.Path)
			}
		}
		property := parts[len(parts)-1]
		switch container := current.(type) {
		case map[string]any:
			switch mutation.Op {
			case "add", "replace":
				container[property] = mutation.Value
			case "remove":
				delete(container, property)
			default:
				t.Fatalf("unsupported fixture mutation %q", mutation.Op)
			}
		case []any:
			index, err := strconv.Atoi(property)
			if err != nil || index < 0 || index >= len(container) {
				t.Fatalf("mutation %s has invalid final array index %q", mutation.Path, property)
			}
			switch mutation.Op {
			case "add", "replace":
				container[index] = mutation.Value
			case "remove":
				container = append(container[:index], container[index+1:]...)
			default:
				t.Fatalf("unsupported fixture mutation %q", mutation.Op)
			}
		default:
			t.Fatalf("mutation %s has a scalar parent", mutation.Path)
		}
	}
	payload, err := json.Marshal(root)
	if err != nil {
		t.Fatalf("encode mutated fixture: %v", err)
	}
	return payload
}

func mustParseTime(t *testing.T, value string) time.Time {
	t.Helper()
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		t.Fatalf("parse fixture time %q: %v", value, err)
	}
	return parsed
}

func stringSet(values []string) map[string]struct{} {
	result := make(map[string]struct{}, len(values))
	for _, value := range values {
		result[value] = struct{}{}
	}
	return result
}

func assertHex(t *testing.T, name string, actual []byte, expected string) {
	t.Helper()
	if got := hex.EncodeToString(actual); got != expected {
		t.Fatalf("%s = %s, want %s", name, got, expected)
	}
}

func mustDecodeHex(t *testing.T, value string) []byte {
	t.Helper()
	decoded, err := hex.DecodeString(value)
	if err != nil {
		t.Fatalf("decode hex fixture: %v", err)
	}
	return decoded
}

func mustDecodeBase64URL(t *testing.T, value string) []byte {
	t.Helper()
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		t.Fatalf("decode base64url fixture: %v", err)
	}
	return decoded
}

func allZero(value []byte) bool {
	for _, item := range value {
		if item != 0 {
			return false
		}
	}
	return true
}

type deterministicReader struct {
	value byte
}

func newDeterministicReader(value byte) *deterministicReader {
	return &deterministicReader{value: value}
}

func (r *deterministicReader) Read(destination []byte) (int, error) {
	for index := range destination {
		destination[index] = r.value
	}
	return len(destination), nil
}

func requireLanError(t *testing.T, err error) {
	t.Helper()
	if err == nil {
		t.Fatal("expected LAN error")
	}
	var lanError *Error
	if !errors.As(err, &lanError) {
		t.Fatalf("error type = %T, want *lan.Error", err)
	}
}
