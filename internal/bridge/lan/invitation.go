package lan

// 安全约束：本文件生成一次性 N36 payload、手工码及可销毁的 X25519 私钥容器。

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base32"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"slices"
	"strings"
	"time"
)

const (
	// QRFormatPayloadOnly 明确表示未生成二维码图像，只提供 provider 集成 payload。
	QRFormatPayloadOnly = "payload-only"
	manualCodePrefix    = "AIAUTO1-"
)

// QRRepresentation 是外部二维码 provider 的结果；核心包不伪造图像编码能力。
type QRRepresentation struct {
	Format    string `json:"format"`
	Data      []byte `json:"-"`
	Generated bool   `json:"generated"`
}

// QRProvider 接收完整 invitation JSON，并由集成方选择具体二维码实现。
type QRProvider interface {
	Encode(ctx context.Context, payload []byte) (QRRepresentation, error)
}

// InvitationOptions 固定 invitation 的接口、端口、TTL、能力和可注入测试源。
type InvitationOptions struct {
	Context      context.Context
	Interface    NetworkInterface
	Candidate    AddressCandidate
	Port         int
	TTL          time.Duration
	Capabilities []string
	Now          func() time.Time
	Random       io.Reader
	QRProvider   QRProvider
}

// InvitationBundle 返回同一 invitation 的 JSON、手工码和可选二维码 provider 结果。
type InvitationBundle struct {
	Invitation Invitation       `json:"invitation"`
	Payload    []byte           `json:"payload"`
	ManualCode string           `json:"manualCode"`
	QR         QRRepresentation `json:"qr"`
}

// EphemeralPrivateKey 将可控私钥 bytes 与标准库对象的短作用域分开管理。
type EphemeralPrivateKey struct {
	privateBytes [32]byte
	destroyed    bool
}

// NewEphemeralPrivateKey 从明确随机源读取 32 bytes，便于在销毁时主动清零。
func NewEphemeralPrivateKey(random io.Reader) (*EphemeralPrivateKey, error) {
	if random == nil {
		random = rand.Reader
	}
	key := &EphemeralPrivateKey{}
	if _, err := io.ReadFull(random, key.privateBytes[:]); err != nil {
		key.Destroy()
		return nil, wrap(CodeEphemeralKeyInvalid, "X25519 private key could not be generated", err)
	}
	if allBytesZero(key.privateBytes[:]) {
		key.Destroy()
		return nil, fail(CodeEphemeralKeyWeak, "X25519 private key cannot be all zero")
	}
	if _, err := ecdh.X25519().NewPrivateKey(key.privateBytes[:]); err != nil {
		key.Destroy()
		return nil, wrap(CodeEphemeralKeyInvalid, "X25519 private key is invalid", err)
	}
	return key, nil
}

// PublicKey 返回公开 X25519 bytes；已销毁容器拒绝继续使用。
func (key *EphemeralPrivateKey) PublicKey() ([]byte, error) {
	privateKey, err := key.standardKey()
	if err != nil {
		return nil, err
	}
	return privateKey.PublicKey().Bytes(), nil
}

// ECDH 只在调用期间重建标准库私钥，并由派生函数清零返回的 shared secret。
func (key *EphemeralPrivateKey) ECDH(peerPublicKey []byte) ([]byte, error) {
	privateKey, err := key.standardKey()
	if err != nil {
		return nil, err
	}
	publicKey, err := ecdh.X25519().NewPublicKey(peerPublicKey)
	if err != nil {
		return nil, wrap(CodeEphemeralKeyInvalid, "peer X25519 public key is invalid", err)
	}
	sharedSecret, err := privateKey.ECDH(publicKey)
	if err != nil || len(sharedSecret) != 32 || allBytesZero(sharedSecret) {
		clear(sharedSecret)
		return nil, wrap(CodeEphemeralKeyWeak, "peer X25519 public key produced a weak shared secret", err)
	}
	return sharedSecret, nil
}

func (key *EphemeralPrivateKey) standardKey() (*ecdh.PrivateKey, error) {
	if key == nil || key.destroyed {
		return nil, fail(CodeSessionClosed, "ephemeral private key was destroyed")
	}
	privateKey, err := ecdh.X25519().NewPrivateKey(key.privateBytes[:])
	if err != nil {
		return nil, wrap(CodeEphemeralKeyInvalid, "ephemeral private key could not be reconstructed", err)
	}
	return privateKey, nil
}

// Destroy 清零可控 private bytes，重复调用安全。
func (key *EphemeralPrivateKey) Destroy() {
	if key == nil || key.destroyed {
		return
	}
	clear(key.privateBytes[:])
	key.destroyed = true
}

// Destroyed 报告私钥容器是否已经失效并清零。
func (key *EphemeralPrivateKey) Destroyed() bool {
	return key == nil || key.destroyed
}

// CreateInvitation 生成可由 N36 严格解析器复验的唯一公开 payload。
func CreateInvitation(
	options InvitationOptions,
) (InvitationBundle, *EphemeralPrivateKey, error) {
	if err := validateBindCandidate(options.Interface, options.Candidate, options.Port); err != nil {
		return InvitationBundle{}, nil, err
	}
	if options.Port < 1024 {
		return InvitationBundle{}, nil, fail(CodeListenerBindFailed, "invitation port must be non-privileged")
	}
	ttlSeconds := int(options.TTL / time.Second)
	if options.TTL != time.Duration(ttlSeconds)*time.Second ||
		ttlSeconds < minInvitationTTLSeconds ||
		ttlSeconds > maxInvitationTTLSeconds {
		return InvitationBundle{}, nil, fail(CodeInvitationTTLInvalid, "invitation TTL must be a whole number of seconds between 15 and 120")
	}
	if !validStringList(options.Capabilities, 2, 64, capabilityNamePattern) ||
		!containsCapability(options.Capabilities, requiredRPC) ||
		!containsCapability(options.Capabilities, requiredConfirmation) {
		return InvitationBundle{}, nil, fail(CodeCapabilityMismatch, "invitation capabilities are invalid")
	}
	random := options.Random
	if random == nil {
		random = rand.Reader
	}
	privateKey, err := NewEphemeralPrivateKey(random)
	if err != nil {
		return InvitationBundle{}, nil, err
	}
	cleanup := true
	defer func() {
		if cleanup {
			privateKey.Destroy()
		}
	}()

	publicKey, err := privateKey.PublicKey()
	if err != nil {
		return InvitationBundle{}, nil, err
	}
	defer clear(publicKey)
	nonce := make([]byte, 32)
	defer clear(nonce)
	if _, err := io.ReadFull(random, nonce); err != nil {
		return InvitationBundle{}, nil, wrap(CodeInvitationSchemaInvalid, "invitation nonce could not be generated", err)
	}
	uuidBytes := make([]byte, 16)
	defer clear(uuidBytes)
	if _, err := io.ReadFull(random, uuidBytes); err != nil {
		return InvitationBundle{}, nil, wrap(CodeInvitationSchemaInvalid, "invitation ID could not be generated", err)
	}
	uuidBytes[6] = (uuidBytes[6] & 0x0f) | 0x40
	uuidBytes[8] = (uuidBytes[8] & 0x3f) | 0x80

	now := time.Now().UTC()
	if options.Now != nil {
		now = options.Now().UTC()
	}
	capabilities := append([]string(nil), options.Capabilities...)
	slices.Sort(capabilities)
	invitation := Invitation{
		Kind:         invitationKind,
		Version:      protocolVersion,
		InvitationID: formatUUID(uuidBytes),
		IssuedAt:     now.Format(time.RFC3339),
		ExpiresAt:    now.Add(options.TTL).Format(time.RFC3339),
		TTLSeconds:   ttlSeconds,
		Nonce:        base64.RawURLEncoding.EncodeToString(nonce),
		Listener: ListenerDescriptor{
			Port: options.Port,
			SelectedInterface: SelectedInterface{
				ID:   options.Interface.ID,
				Name: options.Interface.Name,
				Kind: options.Interface.Kind,
			},
			AddressCandidates: []AddressCandidate{options.Candidate},
		},
		EphemeralKey: EphemeralKey{
			Algorithm: "X25519",
			Encoding:  "base64url",
			PublicKey: base64.RawURLEncoding.EncodeToString(publicKey),
		},
		Capabilities: capabilities,
		Security: SecurityDescriptor{
			Suite:        securitySuite,
			Transcript:   transcriptDomain,
			Confirmation: confirmationAlgorithm,
		},
	}
	invitation.Fingerprint, err = ComputeInvitationFingerprint(invitation)
	if err != nil {
		return InvitationBundle{}, nil, wrap(CodeInvitationSchemaInvalid, "invitation fingerprint could not be computed", err)
	}
	payload, err := json.Marshal(invitation)
	if err != nil {
		return InvitationBundle{}, nil, wrap(CodeInvitationSchemaInvalid, "invitation payload could not be encoded", err)
	}
	manualCode := manualCodePrefix + base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(payload)
	qr := QRRepresentation{Format: QRFormatPayloadOnly, Generated: false}
	if options.QRProvider != nil {
		qrContext := options.Context
		if qrContext == nil {
			qrContext = context.Background()
		}
		providerPayload := append([]byte(nil), payload...)
		qr, err = options.QRProvider.Encode(qrContext, providerPayload)
		clear(providerPayload)
		if err != nil {
			clear(qr.Data)
			if ErrorCode(err) == CodeQRCapacityExceeded {
				qr = QRRepresentation{
					Format:    QRFormatPayloadOnly,
					Generated: false,
				}
				err = nil
			} else {
				clear(payload)
			}
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return InvitationBundle{}, nil, fail(
					CodeCancelled,
					"QR generation was cancelled",
				)
			}
			if err != nil {
				return InvitationBundle{}, nil, wrap(
					CodeInvitationSchemaInvalid,
					"QR provider rejected the invitation payload",
					err,
				)
			}
		}
	}
	cleanup = false
	return InvitationBundle{
		Invitation: invitation,
		Payload:    payload,
		ManualCode: manualCode,
		QR:         qr,
	}, privateKey, nil
}

// DecodeManualInvitation 恢复同一 JSON payload，不引入第二套认证材料。
func DecodeManualInvitation(code string) ([]byte, error) {
	if !strings.HasPrefix(code, manualCodePrefix) {
		return nil, fail(CodeInvitationSchemaInvalid, "manual invitation prefix is invalid")
	}
	payload, err := base32.StdEncoding.WithPadding(base32.NoPadding).
		DecodeString(strings.TrimPrefix(code, manualCodePrefix))
	if err != nil {
		return nil, wrap(CodeInvitationSchemaInvalid, "manual invitation encoding is invalid", err)
	}
	if _, err := ParseInvitationJSON(payload); err != nil {
		clear(payload)
		return nil, err
	}
	return payload, nil
}

func formatUUID(value []byte) string {
	const hexCharacters = "0123456789abcdef"
	encoded := make([]byte, 36)
	inputIndex := 0
	for outputIndex := range encoded {
		switch outputIndex {
		case 8, 13, 18, 23:
			encoded[outputIndex] = '-'
		default:
			byteValue := value[inputIndex/2]
			if inputIndex%2 == 0 {
				encoded[outputIndex] = hexCharacters[byteValue>>4]
			} else {
				encoded[outputIndex] = hexCharacters[byteValue&0x0f]
			}
			inputIndex++
		}
	}
	result := string(encoded)
	clear(encoded)
	return result
}
