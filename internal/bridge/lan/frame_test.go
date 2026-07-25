package lan

// 测试用途：本文件验证 AES-GCM frame 的方向 nonce、AAD、序号、重放和篡改均失败关闭。

import (
	"bytes"
	"fmt"
	"sort"
	"sync"
	"testing"
)

func TestFrameRoundTripUsesIndependentDirectionKeysAndSequences(t *testing.T) {
	keys := deterministicSessionKeys()
	defer keys.Destroy()
	transcript := [32]byte{0x71}
	desktop, err := NewFramer(FramerDesktop, keys, transcript)
	if err != nil {
		t.Fatalf("NewFramer(desktop) error = %v", err)
	}
	client, err := NewFramer(FramerClient, keys, transcript)
	if err != nil {
		t.Fatalf("NewFramer(client) error = %v", err)
	}
	defer desktop.Destroy()
	defer client.Destroy()

	requestFrame, err := desktop.Seal("bridge.request", []byte(`{"method":"device.info"}`))
	if err != nil {
		t.Fatalf("desktop.Seal() error = %v", err)
	}
	if requestFrame.Direction != DirectionDesktopToClient || requestFrame.Sequence != 0 {
		t.Fatalf("desktop frame metadata = %#v", requestFrame)
	}
	plaintext, err := client.Open(requestFrame)
	if err != nil {
		t.Fatalf("client.Open() error = %v", err)
	}
	defer clear(plaintext)
	if !bytes.Equal(plaintext, []byte(`{"method":"device.info"}`)) {
		t.Fatalf("request plaintext = %s", plaintext)
	}

	responseFrame, err := client.Seal("bridge.response", []byte(`{"ok":true}`))
	if err != nil {
		t.Fatalf("client.Seal() error = %v", err)
	}
	if responseFrame.Direction != DirectionClientToDesktop || responseFrame.Sequence != 0 {
		t.Fatalf("client frame metadata = %#v", responseFrame)
	}
	response, err := desktop.Open(responseFrame)
	if err != nil {
		t.Fatalf("desktop.Open() error = %v", err)
	}
	defer clear(response)
}

func TestFrameRejectsReplayOutOfOrderDirectionTypeNonceAndCiphertextTampering(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*EncryptedFrame)
		code   Code
	}{
		{
			name: "out of order",
			mutate: func(frame *EncryptedFrame) {
				frame.Sequence = 1
			},
			code: CodeFrameOutOfOrder,
		},
		{
			name: "wrong direction",
			mutate: func(frame *EncryptedFrame) {
				frame.Direction = DirectionDesktopToClient
			},
			code: CodeFrameInvalid,
		},
		{
			name: "type AAD tampering",
			mutate: func(frame *EncryptedFrame) {
				frame.Type = "bridge.response.tampered"
			},
			code: CodeFrameInvalid,
		},
		{
			name: "nonce tampering",
			mutate: func(frame *EncryptedFrame) {
				frame.Nonce = "AAAAAAAAAAAAAAAA"
			},
			code: CodeFrameInvalid,
		},
		{
			name: "ciphertext tampering",
			mutate: func(frame *EncryptedFrame) {
				frame.Ciphertext = frame.Ciphertext[:len(frame.Ciphertext)-1] + "A"
			},
			code: CodeFrameInvalid,
		},
	}

	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			sender, receiver := newFramePair(t)
			defer sender.Destroy()
			defer receiver.Destroy()
			frame, err := sender.Seal("bridge.response", []byte(`{"ok":true}`))
			if err != nil {
				t.Fatalf("Seal() error = %v", err)
			}
			test.mutate(&frame)
			if _, err := receiver.Open(frame); ErrorCode(err) != test.code {
				t.Fatalf("Open() error code = %q, want %q (error=%v)", ErrorCode(err), test.code, err)
			}
			if !receiver.Destroyed() {
				t.Fatal("receiver did not fail closed after frame violation")
			}
		})
	}

	t.Run("replay", func(t *testing.T) {
		sender, receiver := newFramePair(t)
		defer sender.Destroy()
		defer receiver.Destroy()
		frame, err := sender.Seal("bridge.response", []byte(`{"ok":true}`))
		if err != nil {
			t.Fatalf("Seal() error = %v", err)
		}
		plaintext, err := receiver.Open(frame)
		if err != nil {
			t.Fatalf("first Open() error = %v", err)
		}
		clear(plaintext)
		if _, err := receiver.Open(frame); ErrorCode(err) != CodeFrameReplay {
			t.Fatalf("replay error code = %q, want %q", ErrorCode(err), CodeFrameReplay)
		}
		if !receiver.Destroyed() {
			t.Fatal("receiver did not fail closed after replay")
		}
	})
}

func TestFrameRejectsOversizePlaintextBeforeEncryption(t *testing.T) {
	keys := deterministicSessionKeys()
	defer keys.Destroy()
	framer, err := NewFramer(FramerDesktop, keys, [32]byte{0x33})
	if err != nil {
		t.Fatalf("NewFramer() error = %v", err)
	}
	defer framer.Destroy()
	if _, err := framer.Seal("bridge.request", make([]byte, MaxFramePlaintextBytes+1)); ErrorCode(err) != CodeFrameTooLarge {
		t.Fatalf("oversize error code = %q, want %q", ErrorCode(err), CodeFrameTooLarge)
	}
}

func TestFramerConcurrentSealUsesUniqueSequenceAndNonce(t *testing.T) {
	keys := deterministicSessionKeys()
	defer keys.Destroy()
	framer, err := NewFramer(FramerDesktop, keys, [32]byte{0x29})
	if err != nil {
		t.Fatalf("NewFramer() error = %v", err)
	}
	defer framer.Destroy()

	const count = 128
	frames := make(chan EncryptedFrame, count)
	errors := make(chan error, count)
	var group sync.WaitGroup
	for index := 0; index < count; index++ {
		group.Add(1)
		go func(index int) {
			defer group.Done()
			frame, sealErr := framer.Seal(
				"bridge.request",
				[]byte(fmt.Sprintf(`{"index":%d}`, index)),
			)
			if sealErr != nil {
				errors <- sealErr
				return
			}
			frames <- frame
		}(index)
	}
	group.Wait()
	close(frames)
	close(errors)
	for sealErr := range errors {
		t.Fatalf("concurrent Seal() error = %v", sealErr)
	}

	collected := make([]EncryptedFrame, 0, count)
	nonces := make(map[string]struct{}, count)
	for frame := range frames {
		if _, duplicate := nonces[frame.Nonce]; duplicate {
			t.Fatalf("duplicate nonce %q", frame.Nonce)
		}
		nonces[frame.Nonce] = struct{}{}
		collected = append(collected, frame)
	}
	sort.Slice(collected, func(left, right int) bool {
		return collected[left].Sequence < collected[right].Sequence
	})
	if len(collected) != count {
		t.Fatalf("frame count = %d, want %d", len(collected), count)
	}
	for index, frame := range collected {
		if frame.Sequence != uint64(index) {
			t.Fatalf("sequence[%d] = %d, want %d", index, frame.Sequence, index)
		}
		if frame.Direction != DirectionDesktopToClient {
			t.Fatalf("direction[%d] = %q", index, frame.Direction)
		}
	}
}

func TestFramerConcurrentDestroyAndSealFailsClosedWithoutNonceReuse(t *testing.T) {
	keys := deterministicSessionKeys()
	defer keys.Destroy()
	framer, err := NewFramer(FramerDesktop, keys, [32]byte{0x2a})
	if err != nil {
		t.Fatalf("NewFramer() error = %v", err)
	}

	const count = 64
	frames := make(chan EncryptedFrame, count)
	errors := make(chan error, count)
	start := make(chan struct{})
	var group sync.WaitGroup
	for index := 0; index < count; index++ {
		group.Add(1)
		go func() {
			defer group.Done()
			<-start
			frame, sealErr := framer.Seal("bridge.request", []byte(`{"ok":true}`))
			if sealErr != nil {
				errors <- sealErr
				return
			}
			frames <- frame
		}()
	}
	close(start)
	framer.Destroy()
	group.Wait()
	close(frames)
	close(errors)

	nonces := make(map[string]struct{}, count)
	for frame := range frames {
		if _, duplicate := nonces[frame.Nonce]; duplicate {
			t.Fatalf("duplicate successful nonce %q", frame.Nonce)
		}
		nonces[frame.Nonce] = struct{}{}
	}
	for sealErr := range errors {
		if code := ErrorCode(sealErr); code != CodeSessionClosed {
			t.Fatalf("concurrent destroy error code = %q, want %q", code, CodeSessionClosed)
		}
	}
	if !framer.Destroyed() {
		t.Fatal("framer did not remain destroyed")
	}
}

func deterministicSessionKeys() *SessionKeys {
	keys := &SessionKeys{}
	for index := range keys.clientToDesktop {
		keys.clientToDesktop[index] = byte(index + 1)
		keys.desktopToClient[index] = byte(index + 65)
		keys.confirmation[index] = byte(index + 129)
		keys.tokenBinding[index] = byte(index + 193)
	}
	return keys
}

func newFramePair(t *testing.T) (*Framer, *Framer) {
	t.Helper()
	keys := deterministicSessionKeys()
	defer keys.Destroy()
	transcript := [32]byte{0x52}
	sender, err := NewFramer(FramerClient, keys, transcript)
	if err != nil {
		t.Fatalf("NewFramer(sender) error = %v", err)
	}
	receiver, err := NewFramer(FramerDesktop, keys, transcript)
	if err != nil {
		t.Fatalf("NewFramer(receiver) error = %v", err)
	}
	return sender, receiver
}
