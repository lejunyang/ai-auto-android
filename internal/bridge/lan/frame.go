package lan

// 安全约束：本文件用独立方向 key、确定 nonce 和完整 AAD 保护有界 LAN RPC frame。

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/binary"
	"math"
	"regexp"
	"sync"
)

const (
	// MaxFramePlaintextBytes 限制解密前后的单个 RPC frame，避免内存放大。
	MaxFramePlaintextBytes = 1024 * 1024
	frameAADDomain         = "AIAUTO-LAN-BRIDGE-FRAME-AAD-V1"
)

// FrameDirection 是 N36 双向 key 和 nonce 空间的线协议标识。
type FrameDirection string

const (
	DirectionClientToDesktop FrameDirection = "client-to-desktop"
	DirectionDesktopToClient FrameDirection = "desktop-to-client"
)

// FramerRole 决定本地发送和接收分别使用哪一个方向 key。
type FramerRole string

const (
	FramerDesktop FramerRole = "desktop"
	FramerClient  FramerRole = "client"
)

// EncryptedFrame 只包含公开元数据和 AES-GCM ciphertext，不承载 key 或 tag 字段。
type EncryptedFrame struct {
	Version    string         `json:"version"`
	Direction  FrameDirection `json:"direction"`
	Sequence   uint64         `json:"sequence"`
	Type       string         `json:"type"`
	Nonce      string         `json:"nonce"`
	Ciphertext string         `json:"ciphertext"`
}

// Framer 管理一个已确认会话的独立发送和接收序列及 key 副本。
type Framer struct {
	sendMu        sync.Mutex
	recvMu        sync.Mutex
	stateMu       sync.Mutex
	role          FramerRole
	transcript    [32]byte
	sendDirection FrameDirection
	recvDirection FrameDirection
	sendAEAD      cipher.AEAD
	recvAEAD      cipher.AEAD
	sendKey       [32]byte
	recvKey       [32]byte
	sendSequence  uint64
	recvSequence  uint64
	destroyed     bool
}

var frameTypePattern = regexp.MustCompile(`^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$`)

// NewFramer 从 SessionKeys 复制角色所需双向 key，不共享可变 key slice。
func NewFramer(role FramerRole, keys *SessionKeys, transcript [32]byte) (*Framer, error) {
	if err := ensureKeysAlive(keys); err != nil {
		return nil, wrap(CodeSessionClosed, "session keys are unavailable", err)
	}
	framer := &Framer{role: role, transcript: transcript}
	keys.mu.Lock()
	defer keys.mu.Unlock()
	if keys.destroyed {
		return nil, fail(CodeSessionClosed, "session keys were destroyed")
	}
	switch role {
	case FramerDesktop:
		framer.sendDirection = DirectionDesktopToClient
		framer.recvDirection = DirectionClientToDesktop
		copy(framer.sendKey[:], keys.desktopToClient[:])
		copy(framer.recvKey[:], keys.clientToDesktop[:])
	case FramerClient:
		framer.sendDirection = DirectionClientToDesktop
		framer.recvDirection = DirectionDesktopToClient
		copy(framer.sendKey[:], keys.clientToDesktop[:])
		copy(framer.recvKey[:], keys.desktopToClient[:])
	default:
		return nil, fail(CodeFrameInvalid, "framer role is invalid")
	}
	sendBlock, err := aes.NewCipher(framer.sendKey[:])
	if err != nil {
		framer.Destroy()
		return nil, wrap(CodeFrameInvalid, "send cipher could not be created", err)
	}
	framer.sendAEAD, err = cipher.NewGCM(sendBlock)
	if err != nil {
		framer.Destroy()
		return nil, wrap(CodeFrameInvalid, "send AEAD could not be created", err)
	}
	recvBlock, err := aes.NewCipher(framer.recvKey[:])
	if err != nil {
		framer.Destroy()
		return nil, wrap(CodeFrameInvalid, "receive cipher could not be created", err)
	}
	framer.recvAEAD, err = cipher.NewGCM(recvBlock)
	if err != nil {
		framer.Destroy()
		return nil, wrap(CodeFrameInvalid, "receive AEAD could not be created", err)
	}
	return framer, nil
}

// Seal 加密下一个严格单调发送 frame，nonce 不随机复用且达到上限后关闭。
func (framer *Framer) Seal(frameType string, plaintext []byte) (EncryptedFrame, error) {
	if framer == nil {
		return EncryptedFrame{}, fail(CodeSessionClosed, "framer is closed")
	}
	framer.sendMu.Lock()
	framer.stateMu.Lock()
	destroyed := framer.destroyed
	framer.stateMu.Unlock()
	if destroyed {
		framer.sendMu.Unlock()
		return EncryptedFrame{}, fail(CodeSessionClosed, "framer is closed")
	}
	if len(plaintext) > MaxFramePlaintextBytes {
		framer.sendMu.Unlock()
		framer.Destroy()
		return EncryptedFrame{}, fail(CodeFrameTooLarge, "frame plaintext exceeds the limit")
	}
	if !validFrameType(frameType) {
		framer.sendMu.Unlock()
		framer.Destroy()
		return EncryptedFrame{}, fail(CodeFrameInvalid, "frame type is invalid")
	}
	if framer.sendSequence == math.MaxUint64 {
		framer.sendMu.Unlock()
		framer.Destroy()
		return EncryptedFrame{}, fail(CodeSessionClosed, "frame sequence is exhausted")
	}
	nonce := frameNonce(framer.sendDirection, framer.sendSequence)
	aad := frameAAD(framer.transcript, framer.sendDirection, framer.sendSequence, frameType)
	ciphertext := framer.sendAEAD.Seal(nil, nonce[:], plaintext, aad)
	clear(aad)
	frame := EncryptedFrame{
		Version:    protocolVersion,
		Direction:  framer.sendDirection,
		Sequence:   framer.sendSequence,
		Type:       frameType,
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce[:]),
		Ciphertext: base64.RawURLEncoding.EncodeToString(ciphertext),
	}
	clear(ciphertext)
	framer.sendSequence++
	framer.sendMu.Unlock()
	return frame, nil
}

// Open 验证方向、严格序号、nonce、AAD 和 GCM tag，任一违约均销毁会话 key。
func (framer *Framer) Open(frame EncryptedFrame) ([]byte, error) {
	if framer == nil {
		return nil, fail(CodeSessionClosed, "framer is closed")
	}
	framer.recvMu.Lock()
	framer.stateMu.Lock()
	destroyed := framer.destroyed
	framer.stateMu.Unlock()
	if destroyed {
		framer.recvMu.Unlock()
		return nil, fail(CodeSessionClosed, "framer is closed")
	}
	failClosed := func(code Code, message string, cause error) ([]byte, error) {
		framer.recvMu.Unlock()
		framer.Destroy()
		if cause != nil {
			return nil, wrap(code, message, cause)
		}
		return nil, fail(code, message)
	}
	if frame.Version != protocolVersion ||
		frame.Direction != framer.recvDirection ||
		!validFrameType(frame.Type) {
		return failClosed(CodeFrameInvalid, "frame metadata is invalid", nil)
	}
	if frame.Sequence < framer.recvSequence {
		return failClosed(CodeFrameReplay, "frame sequence was already consumed", nil)
	}
	if frame.Sequence > framer.recvSequence {
		return failClosed(CodeFrameOutOfOrder, "frame sequence is out of order", nil)
	}
	receivedNonce, err := base64.RawURLEncoding.DecodeString(frame.Nonce)
	if err != nil || len(receivedNonce) != framer.recvAEAD.NonceSize() {
		clear(receivedNonce)
		return failClosed(CodeFrameInvalid, "frame nonce is invalid", err)
	}
	expectedNonce := frameNonce(framer.recvDirection, frame.Sequence)
	if !bytes.Equal(receivedNonce, expectedNonce[:]) {
		clear(receivedNonce)
		return failClosed(CodeFrameInvalid, "frame nonce does not match its direction and sequence", nil)
	}
	clear(receivedNonce)
	ciphertext, err := base64.RawURLEncoding.DecodeString(frame.Ciphertext)
	if err != nil ||
		len(ciphertext) < framer.recvAEAD.Overhead() ||
		len(ciphertext) > MaxFramePlaintextBytes+framer.recvAEAD.Overhead() {
		clear(ciphertext)
		code := CodeFrameInvalid
		if len(ciphertext) > MaxFramePlaintextBytes+framer.recvAEAD.Overhead() {
			code = CodeFrameTooLarge
		}
		return failClosed(code, "frame ciphertext is invalid", err)
	}
	aad := frameAAD(framer.transcript, frame.Direction, frame.Sequence, frame.Type)
	plaintext, err := framer.recvAEAD.Open(nil, expectedNonce[:], ciphertext, aad)
	clear(ciphertext)
	clear(aad)
	if err != nil {
		clear(plaintext)
		return failClosed(CodeFrameInvalid, "frame authentication failed", err)
	}
	framer.recvSequence++
	framer.recvMu.Unlock()
	return plaintext, nil
}

// Destroy 清零 framer 自有方向 key 与 transcript，并禁止后续收发。
func (framer *Framer) Destroy() {
	if framer == nil {
		return
	}
	// 固定 send -> recv -> state 顺序，方向操作只短暂读取 state，不形成循环等待。
	framer.sendMu.Lock()
	framer.recvMu.Lock()
	framer.stateMu.Lock()
	defer framer.stateMu.Unlock()
	defer framer.recvMu.Unlock()
	defer framer.sendMu.Unlock()
	if framer.destroyed {
		return
	}
	framer.destroyed = true
	clear(framer.sendKey[:])
	clear(framer.recvKey[:])
	clear(framer.transcript[:])
	framer.sendAEAD = nil
	framer.recvAEAD = nil
}

// Destroyed 报告 framer 是否已经失败关闭或显式销毁。
func (framer *Framer) Destroyed() bool {
	if framer == nil {
		return true
	}
	framer.stateMu.Lock()
	defer framer.stateMu.Unlock()
	return framer.destroyed
}

func validFrameType(frameType string) bool {
	return len(frameType) >= 1 &&
		len(frameType) <= 128 &&
		frameTypePattern.MatchString(frameType)
}

func frameNonce(direction FrameDirection, sequence uint64) [12]byte {
	var nonce [12]byte
	switch direction {
	case DirectionClientToDesktop:
		copy(nonce[:4], []byte{0x43, 0x32, 0x44, 0x01})
	case DirectionDesktopToClient:
		copy(nonce[:4], []byte{0x44, 0x32, 0x43, 0x01})
	}
	binary.BigEndian.PutUint64(nonce[4:], sequence)
	return nonce
}

func frameAAD(
	transcript [32]byte,
	direction FrameDirection,
	sequence uint64,
	frameType string,
) []byte {
	result := make([]byte, 0, len(frameAADDomain)+1+32+1+len(direction)+1+8+1+len(frameType))
	result = append(result, frameAADDomain...)
	result = append(result, 0)
	result = append(result, transcript[:]...)
	result = append(result, 0)
	result = append(result, direction...)
	result = append(result, 0)
	var sequenceBytes [8]byte
	binary.BigEndian.PutUint64(sequenceBytes[:], sequence)
	result = append(result, sequenceBytes[:]...)
	clear(sequenceBytes[:])
	result = append(result, 0)
	result = append(result, frameType...)
	return result
}
