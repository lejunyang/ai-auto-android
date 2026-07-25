// Package visual 测试视觉 observation 的可信注册、并发绑定和图片清零边界。
// 测试用途：验证 observation 防漂移、可信 verifier、并发注册和租约撤销。
package visual

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"reflect"
	"sync"
	"testing"
	"time"
)

func TestRegistryBindsObservationAndClearsEveryBorrowedBuffer(t *testing.T) {
	callerBytes := testPNG(0)
	var verifierBytes []byte
	registry := NewRegistry(RegistryOptions{
		TrustedVerifier: func(image []byte, context VerificationContext) (bool, error) {
			verifierBytes = image
			return context.ObservationID == observationID &&
				context.PNGSHA256 == hash(image), nil
		},
	})
	t.Cleanup(registry.Close)

	observation, err := registry.Register(testCapture(callerBytes))
	if err != nil {
		t.Fatalf("Register() error = %v", err)
	}
	if observation.ID != observationID ||
		observation.ForegroundPackage != targetPackage ||
		observation.Screen.Rotation != 90 ||
		observation.Crop != (PixelBounds{Left: 200, Top: 100, Right: 1200, Bottom: 900}) {
		t.Fatalf("observation = %#v", observation)
	}
	if observation.PNG.SHA256 != hash(testPNG(0)) {
		t.Fatalf("png metadata = %#v", observation.PNG)
	}
	if !allZero(callerBytes) || !allZero(verifierBytes) {
		t.Fatal("caller or verifier image buffer was not cleared")
	}

	var borrowed []byte
	err = registry.WithImage(observationID, func(image []byte) error {
		borrowed = image
		if allZero(image) {
			t.Fatal("image was cleared before callback completed")
		}
		return nil
	})
	if err != nil {
		t.Fatalf("WithImage() error = %v", err)
	}
	if !allZero(borrowed) {
		t.Fatal("borrowed image was not cleared after callback")
	}
	if !registry.Revoke(observationID) {
		t.Fatal("Revoke() = false, want true")
	}
	if err := registry.WithImage(observationID, func([]byte) error {
		t.Fatal("revoked image must not be available")
		return nil
	}); ErrorCode(err) != CodeObservationNotFound {
		t.Fatalf("error code = %q, want %q", ErrorCode(err), CodeObservationNotFound)
	}
}

func TestRegistryRejectsMetadataDriftWithoutReplacingOriginal(t *testing.T) {
	registry := trustedRegistry()
	t.Cleanup(registry.Close)
	original, err := registry.Register(testCapture(testPNG(0)))
	if err != nil {
		t.Fatalf("Register(original) error = %v", err)
	}

	drifted := []Capture{
		testCapture(testPNG(0)),
		testCapture(testPNG(0)),
		testCapture(testPNG(0)),
		testCapture(testPNG(0)),
		testCapture(testPNG(1)),
		testCapture(testPNG(0)),
	}
	drifted[0].ForegroundPackage = "com.example.changed"
	drifted[1].Screen.Width++
	drifted[2].Crop.Left++
	drifted[3].ExpiresAt = drifted[3].ExpiresAt.Add(time.Second)
	drifted[5].Hierarchy[0].Label = "Changed"

	for index := range drifted {
		input := &drifted[index]
		if _, registerErr := registry.Register(*input); ErrorCode(registerErr) != CodeObservationIDDrift {
			t.Fatalf("drift %d error code = %q, want %q", index, ErrorCode(registerErr), CodeObservationIDDrift)
		}
		if !allZero(input.PNGBytes) {
			t.Fatalf("drift %d input image was not cleared", index)
		}
	}
	got, ok := registry.Lookup(observationID)
	if !ok || !reflect.DeepEqual(got, original) {
		t.Fatalf("stored observation = %#v, want %#v", got, original)
	}
	original.Hierarchy[0].Label = "caller-tampered"
	unchanged, ok := registry.Lookup(observationID)
	if !ok || unchanged.Hierarchy[0].Label != "Save" {
		t.Fatalf("caller mutation changed registry observation: %#v", unchanged)
	}
}

func TestRegistryFailsClosedForUnverifiedSecureEmptyAndOversizedImages(t *testing.T) {
	denied := errors.New("independent trust context denied the image")
	tests := []struct {
		name     string
		options  RegistryOptions
		mutate   func(*Capture)
		wantCode Code
	}{
		{
			name:     "missing verifier despite matching self hash",
			options:  RegistryOptions{},
			mutate:   func(*Capture) {},
			wantCode: CodeImageUnverified,
		},
		{
			name: "verifier returns false",
			options: RegistryOptions{TrustedVerifier: func([]byte, VerificationContext) (bool, error) {
				return false, nil
			}},
			mutate:   func(*Capture) {},
			wantCode: CodeImageUnverified,
		},
		{
			name: "verifier errors",
			options: RegistryOptions{TrustedVerifier: func([]byte, VerificationContext) (bool, error) {
				return false, denied
			}},
			mutate:   func(*Capture) {},
			wantCode: CodeImageUnverified,
		},
		{
			name:     "secure window",
			options:  RegistryOptions{TrustedVerifier: allowVerifier},
			mutate:   func(capture *Capture) { capture.SecureWindow = true },
			wantCode: CodeSecureWindow,
		},
		{
			name:     "empty image",
			options:  RegistryOptions{TrustedVerifier: allowVerifier},
			mutate:   func(capture *Capture) { capture.PNGBytes = []byte{} },
			wantCode: CodeImageEmpty,
		},
		{
			name: "oversized image",
			options: RegistryOptions{
				TrustedVerifier: allowVerifier,
				MaxPNGBytes:     len(testPNG(0)) - 1,
			},
			mutate:   func(*Capture) {},
			wantCode: CodeImageBudgetExceeded,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			registry := NewRegistry(test.options)
			t.Cleanup(registry.Close)
			input := testCapture(testPNG(0))
			test.mutate(&input)

			if _, err := registry.Register(input); ErrorCode(err) != test.wantCode {
				t.Fatalf("error code = %q, want %q (error=%v)", ErrorCode(err), test.wantCode, err)
			}
			if !allZero(input.PNGBytes) {
				t.Fatal("rejected caller image was not cleared")
			}
			if _, ok := registry.Lookup(observationID); ok {
				t.Fatal("rejected observation was retained")
			}
		})
	}
}

func TestRegistryAcceptsUUIDVersionSevenObservationIdentity(t *testing.T) {
	registry := trustedRegistry()
	t.Cleanup(registry.Close)
	capture := testCapture(testPNG(0))
	capture.ID = "019f9a38-21b8-73b2-b5a9-81dff4845375"

	observation, err := registry.Register(capture)

	if err != nil {
		t.Fatalf("Register(UUIDv7) error = %v", err)
	}
	if observation.ID != capture.ID {
		t.Fatalf("observation ID = %q, want %q", observation.ID, capture.ID)
	}
}

func TestRegistryRejectsInvalidNaturalScreenAndRotatedCropBounds(t *testing.T) {
	tests := []struct {
		name     string
		mutate   func(*Capture)
		wantCode Code
	}{
		{
			name:     "zero natural width",
			mutate:   func(capture *Capture) { capture.Screen.Width = 0 },
			wantCode: CodeScreenInvalid,
		},
		{
			name:     "zero natural height",
			mutate:   func(capture *Capture) { capture.Screen.Height = 0 },
			wantCode: CodeScreenInvalid,
		},
		{
			name:     "unsupported rotation",
			mutate:   func(capture *Capture) { capture.Screen.Rotation = 45 },
			wantCode: CodeScreenInvalid,
		},
		{
			name:     "zero crop width",
			mutate:   func(capture *Capture) { capture.Crop.Right = capture.Crop.Left },
			wantCode: CodeCropInvalid,
		},
		{
			name:     "zero crop height",
			mutate:   func(capture *Capture) { capture.Crop.Bottom = capture.Crop.Top },
			wantCode: CodeCropInvalid,
		},
		{
			name:     "reversed crop width",
			mutate:   func(capture *Capture) { capture.Crop.Right = capture.Crop.Left - 1 },
			wantCode: CodeCropInvalid,
		},
		{
			name:     "negative crop origin",
			mutate:   func(capture *Capture) { capture.Crop.Left = -1 },
			wantCode: CodeCropInvalid,
		},
		{
			name: "crop exceeds rotated capture width",
			mutate: func(capture *Capture) {
				// 90 度捕获平面宽高为自然方向高宽，即 2000x1000。
				capture.Crop.Right = 2001
			},
			wantCode: CodeCropInvalid,
		},
		{
			name: "crop exceeds rotated capture height",
			mutate: func(capture *Capture) {
				capture.Crop.Bottom = 1001
			},
			wantCode: CodeCropInvalid,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			registry := trustedRegistry()
			t.Cleanup(registry.Close)
			input := testCapture(testPNG(0))
			test.mutate(&input)

			if _, err := registry.Register(input); ErrorCode(err) != test.wantCode {
				t.Fatalf("error code = %q, want %q (error=%v)", ErrorCode(err), test.wantCode, err)
			}
			if !allZero(input.PNGBytes) {
				t.Fatal("invalid input image was not cleared")
			}
		})
	}
}

func TestRegistryRedactsSensitiveHierarchyAndSupportsConcurrentIdempotentRegistration(t *testing.T) {
	registry := trustedRegistry()
	t.Cleanup(registry.Close)
	const workers = 32
	var wait sync.WaitGroup
	errs := make(chan error, workers)
	inputs := make([][]byte, workers)

	for index := range workers {
		inputs[index] = testPNG(0)
		wait.Add(1)
		go func(input []byte) {
			defer wait.Done()
			capture := testCapture(input)
			capture.Hierarchy = []HierarchyNode{
				{
					Role:      "password",
					Label:     "secret-123",
					Bounds:    testBounds(),
					Sensitive: true,
					Children: []HierarchyNode{
						{Role: "text", Label: "nested-secret", Bounds: testBounds()},
					},
				},
			}
			_, err := registry.Register(capture)
			errs <- err
		}(inputs[index])
	}
	wait.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatalf("concurrent Register() error = %v", err)
		}
	}
	for index, input := range inputs {
		if !allZero(input) {
			t.Fatalf("input %d was not cleared", index)
		}
	}
	observation, ok := registry.Lookup(observationID)
	if !ok {
		t.Fatal("observation was not registered")
	}
	node := observation.Hierarchy[0]
	if node.Label != "" || node.Children[0].Label != "" ||
		!node.Sensitive || !node.Children[0].Sensitive {
		t.Fatalf("sensitive hierarchy leaked labels: %#v", node)
	}
}

func trustedRegistry() *Registry {
	return NewRegistry(RegistryOptions{TrustedVerifier: allowVerifier})
}

func allowVerifier(image []byte, context VerificationContext) (bool, error) {
	return context.PNGSHA256 == hash(image), nil
}

func testCapture(image []byte) Capture {
	return Capture{
		ID:                observationID,
		ForegroundPackage: targetPackage,
		Screen:            Screen{Width: 1000, Height: 2000, Rotation: 90},
		Crop:              PixelBounds{Left: 200, Top: 100, Right: 1200, Bottom: 900},
		CapturedAt:        mustTime("2026-07-26T01:00:00Z"),
		ExpiresAt:         mustTime("2026-07-26T01:00:10Z"),
		PNGBytes:          image,
		Hierarchy: []HierarchyNode{
			{Role: "button", Label: "Save", Bounds: testBounds()},
		},
	}
}

func testBounds() NormalizedBounds {
	return NormalizedBounds{Left: 0.3, Top: 0.6, Right: 0.7, Bottom: 0.8}
}

func testPNG(extra byte) []byte {
	return []byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, extra}
}

func hash(value []byte) string {
	sum := sha256.Sum256(value)
	return hex.EncodeToString(sum[:])
}

func allZero(value []byte) bool {
	return bytes.Count(value, []byte{0}) == len(value)
}

func mustTime(value string) time.Time {
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		panic(err)
	}
	return parsed
}

const (
	observationID = "123e4567-e89b-42d3-a456-426614174044"
	targetPackage = "com.example.notes"
)
