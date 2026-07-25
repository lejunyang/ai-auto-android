package lan

// 安全约束：本文件实现 N36 X25519、HKDF、transcript 和双方确认的密钥分域与清零。

import (
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"sync"
)

// SessionKeys 保存四个独立派生 key；会话所有者必须调用 Destroy。
type SessionKeys struct {
	mu              sync.Mutex
	clientToDesktop [32]byte
	desktopToClient [32]byte
	confirmation    [32]byte
	tokenBinding    [32]byte
	destroyed       bool
}

// ClientToDesktop 返回客户端到桌面方向 key 的副本，调用方负责清零副本。
func (keys *SessionKeys) ClientToDesktop() []byte {
	keys.mu.Lock()
	defer keys.mu.Unlock()
	return append([]byte(nil), keys.clientToDesktop[:]...)
}

// DesktopToClient 返回桌面到客户端方向 key 的副本，调用方负责清零副本。
func (keys *SessionKeys) DesktopToClient() []byte {
	keys.mu.Lock()
	defer keys.mu.Unlock()
	return append([]byte(nil), keys.desktopToClient[:]...)
}

// Confirmation 返回 confirmation key 的副本，调用方负责清零副本。
func (keys *SessionKeys) Confirmation() []byte {
	keys.mu.Lock()
	defer keys.mu.Unlock()
	return append([]byte(nil), keys.confirmation[:]...)
}

// TokenBinding 返回 LAN token-binding key 的副本，不能用于 loopback Bridge。
func (keys *SessionKeys) TokenBinding() []byte {
	keys.mu.Lock()
	defer keys.mu.Unlock()
	return append([]byte(nil), keys.tokenBinding[:]...)
}

// Destroy 清零 SessionKeys 中所有可控 key material，重复调用安全。
func (keys *SessionKeys) Destroy() {
	if keys == nil {
		return
	}
	keys.mu.Lock()
	defer keys.mu.Unlock()
	if keys.destroyed {
		return
	}
	clear(keys.clientToDesktop[:])
	clear(keys.desktopToClient[:])
	clear(keys.confirmation[:])
	clear(keys.tokenBinding[:])
	keys.destroyed = true
}

// ComputeInvitationFingerprint 计算 N36 全公开字段绑定的 64-bit 展示指纹。
func ComputeInvitationFingerprint(invitation Invitation) (string, error) {
	copy := invitation
	copy.Fingerprint = ""
	encoded, err := jsonObjectWithoutFingerprint(copy)
	if err != nil {
		return "", err
	}
	digest := sha256.New()
	digest.Write([]byte(fingerprintDomain))
	digest.Write([]byte{0})
	digest.Write(encoded)
	sum := digest.Sum(nil)
	defer clear(sum)
	hexValue := make([]byte, hex.EncodedLen(8))
	hex.Encode(hexValue, sum[:8])
	for index := range hexValue {
		if hexValue[index] >= 'a' && hexValue[index] <= 'f' {
			hexValue[index] -= 'a' - 'A'
		}
	}
	result := string(hexValue[0:4]) + "-" +
		string(hexValue[4:8]) + "-" +
		string(hexValue[8:12]) + "-" +
		string(hexValue[12:16])
	clear(hexValue)
	return result, nil
}

func jsonObjectWithoutFingerprint(invitation Invitation) ([]byte, error) {
	encoded, err := canonicalJSON(invitation)
	if err != nil {
		return nil, err
	}
	// 空字符串字段必须从 fingerprint 对象删除，而不是编码为空值。
	var object map[string]any
	if err := decodeCanonicalObject(encoded, &object); err != nil {
		return nil, err
	}
	delete(object, "fingerprint")
	return canonicalJSON(object)
}

// BuildTranscriptHash 固定 invitation、client hello 和有序 selection 的 transcript。
func BuildTranscriptHash(
	invitation Invitation,
	hello ClientHello,
	selection Selection,
) ([32]byte, error) {
	var result [32]byte
	encoded, err := canonicalJSON(struct {
		Invitation  Invitation  `json:"invitation"`
		ClientHello ClientHello `json:"clientHello"`
		Selection   Selection   `json:"selection"`
	}{
		Invitation:  invitation,
		ClientHello: hello,
		Selection:   selection,
	})
	if err != nil {
		return result, err
	}
	defer clear(encoded)
	digest := sha256.New()
	digest.Write([]byte(transcriptDomain))
	digest.Write([]byte{0})
	digest.Write(encoded)
	copy(result[:], digest.Sum(nil))
	return result, nil
}

// DeriveSessionKeys 按 N36 顺序派生双向、确认和 token-binding 四个 key。
func DeriveSessionKeys(sharedSecret []byte, transcriptHash [32]byte) (*SessionKeys, error) {
	if len(sharedSecret) != 32 || allBytesZero(sharedSecret) {
		return nil, fail(CodeEphemeralKeyWeak, "X25519 shared secret must be non-zero and 32 bytes")
	}
	saltInput := make([]byte, 0, len(hkdfSaltDomain)+1+len(transcriptHash))
	saltInput = append(saltInput, hkdfSaltDomain...)
	saltInput = append(saltInput, 0)
	saltInput = append(saltInput, transcriptHash[:]...)
	salt := sha256.Sum256(saltInput)
	clear(saltInput)

	material, err := hkdf.Key(sha256.New, sharedSecret, salt[:], hkdfInfoDomain+"\x00", 128)
	clear(salt[:])
	if err != nil {
		return nil, wrap(CodeEphemeralKeyInvalid, "HKDF derivation failed", err)
	}
	defer clear(material)
	keys := &SessionKeys{}
	copy(keys.clientToDesktop[:], material[0:32])
	copy(keys.desktopToClient[:], material[32:64])
	copy(keys.confirmation[:], material[64:96])
	copy(keys.tokenBinding[:], material[96:128])
	return keys, nil
}

// DeriveSessionKeysX25519 拒绝非法或低阶 peer point，并立即清零 shared secret。
func DeriveSessionKeysX25519(
	privateKey *ecdh.PrivateKey,
	peerPublicKey []byte,
	transcriptHash [32]byte,
) (*SessionKeys, error) {
	if privateKey == nil || len(peerPublicKey) != 32 {
		return nil, fail(CodeEphemeralKeyInvalid, "X25519 key input is invalid")
	}
	publicKey, err := ecdh.X25519().NewPublicKey(peerPublicKey)
	if err != nil {
		return nil, wrap(CodeEphemeralKeyInvalid, "X25519 public key is invalid", err)
	}
	sharedSecret, err := privateKey.ECDH(publicKey)
	if err != nil || len(sharedSecret) != 32 || allBytesZero(sharedSecret) {
		clear(sharedSecret)
		return nil, wrap(CodeEphemeralKeyWeak, "X25519 public key produced a weak shared secret", err)
	}
	defer clear(sharedSecret)
	return DeriveSessionKeys(sharedSecret, transcriptHash)
}

// SignConfirmation 生成角色分域的 N36 HMAC confirmation tag。
func SignConfirmation(
	role ConfirmationRole,
	confirmationKey []byte,
	transcriptHash [32]byte,
) ([]byte, error) {
	if role != ConfirmationDesktop && role != ConfirmationClient {
		return nil, fail(CodeConfirmationInvalid, "confirmation role is invalid")
	}
	if len(confirmationKey) != 32 {
		return nil, fail(CodeConfirmationInvalid, "confirmation key is invalid")
	}
	mac := hmac.New(sha256.New, confirmationKey)
	mac.Write([]byte(confirmationDomain))
	mac.Write([]byte{0})
	mac.Write([]byte(role))
	mac.Write([]byte{0})
	mac.Write(transcriptHash[:])
	return mac.Sum(nil), nil
}

// VerifyConfirmation 以常量时间验证角色标签，并清零内部 expected tag。
func VerifyConfirmation(
	role ConfirmationRole,
	confirmationKey []byte,
	transcriptHash [32]byte,
	receivedTag []byte,
) bool {
	if len(receivedTag) != sha256.Size {
		return false
	}
	expected, err := SignConfirmation(role, confirmationKey, transcriptHash)
	if err != nil {
		return false
	}
	defer clear(expected)
	return subtle.ConstantTimeCompare(expected, receivedTag) == 1
}

func decodeCanonicalObject(encoded []byte, destination any) error {
	if err := decodeStrictJSON(encoded, destination); err != nil {
		return err
	}
	return nil
}

func verifyTranscriptHash(expected, received [32]byte) error {
	if subtle.ConstantTimeCompare(expected[:], received[:]) != 1 {
		return fail(CodeTranscriptMismatch, "transcript hash does not match")
	}
	return nil
}

func ensureKeysAlive(keys *SessionKeys) error {
	if keys == nil {
		return errors.New("LAN session keys are unavailable")
	}
	keys.mu.Lock()
	defer keys.mu.Unlock()
	if keys.destroyed {
		return errors.New("LAN session keys are unavailable")
	}
	return nil
}
