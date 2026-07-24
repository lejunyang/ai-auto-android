package adb

// 测试用途：验证可信无线设备档案不含秘密、使用受限权限并按稳定身份安全恢复。

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

func TestTrustedDeviceStoreRoundTripContainsNoSecretFields(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "trusted")
	store := NewFileTrustedDeviceStore(directory)
	profile := TrustedDeviceProfile{
		Identity:      "adb-R58M1234-workstation",
		Serial:        "adb-R58M1234-workstation._adb-tls-connect._tcp",
		Model:         "Pixel 8",
		LastTransport: "wifi",
		LastEndpoint:  "192.168.1.8:40117",
		Capabilities:  []string{"adb.direct"},
		MDNSInstance:  "adb-R58M1234-workstation",
		UpdatedAt:     time.Date(2026, 7, 25, 1, 2, 3, 0, time.UTC),
	}
	if err := store.Save(profile); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("entries = %d, want 1", len(entries))
	}
	info, err := entries[0].Info()
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %o, want 600", info.Mode().Perm())
	}
	payload, err := os.ReadFile(filepath.Join(directory, entries[0].Name()))
	if err != nil {
		t.Fatal(err)
	}
	lower := strings.ToLower(string(payload))
	for _, forbidden := range []string{"pairingcode", "token", "apikey", "privatekey"} {
		if strings.Contains(lower, forbidden) {
			t.Fatalf("trusted profile contains forbidden field %q: %s", forbidden, payload)
		}
	}
	loaded, err := store.Load(profile.Identity)
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.Identity != profile.Identity || loaded.LastEndpoint != profile.LastEndpoint {
		t.Fatalf("loaded = %#v", loaded)
	}
}

func TestMDNSServicesMapsUnavailableBackendWithoutRetryingServer(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{{Stderr: []byte("mdns unavailable"), ExitCode: 1}},
		errors:  []error{errors.New("exit status 1")},
	}
	_, err := NewClient("/fake/adb", fake).MDNSServices(context.Background())
	assertAppErrorCode(t, err, apperr.CodeCapabilityMissing)
	if len(fake.calls) != 1 {
		t.Fatalf("calls = %#v", fake.calls)
	}
	assertArgs(t, fake.calls[0], "mdns", "services")
}

func TestReconnectTrustedDeviceUsesExactIdentityAndUpdatesPort(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("adb-device _adb-tls-connect._tcp 192.168.1.8:40222\n")},
			{Stdout: []byte("connected to 192.168.1.8:40222\n")},
			{Stdout: []byte("List of devices attached\nadb-device._adb-tls-connect._tcp\tdevice model:Pixel_8 transport_id:2\n")},
		},
		errors: []error{nil, nil, nil},
	}
	store := newMemoryTrustedDeviceStore(TrustedDeviceProfile{
		Identity:      "adb-device",
		Serial:        "adb-device._adb-tls-connect._tcp",
		Model:         "Pixel 8",
		LastTransport: "wifi",
		LastEndpoint:  "192.168.1.8:40117",
		MDNSInstance:  "adb-device",
		UpdatedAt:     time.Now().Add(-time.Hour),
	})
	client := NewClient("/fake/adb", fake)
	result, err := client.ReconnectTrustedDevice(context.Background(), "adb-device", store)
	if err != nil {
		t.Fatalf("ReconnectTrustedDevice() error = %v", err)
	}
	if result.Endpoint != "192.168.1.8:40222" || result.PreviousEndpoint != "192.168.1.8:40117" {
		t.Fatalf("result = %#v", result)
	}
	if store.profile.LastEndpoint != result.Endpoint {
		t.Fatalf("stored profile = %#v", store.profile)
	}
	for _, call := range fake.calls {
		if strings.Join(call, " ") == "kill-server" {
			t.Fatalf("unexpected shared ADB restart: %#v", fake.calls)
		}
	}
}

func TestReconnectTrustedDeviceRejectsSameModelWithoutExactIdentity(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("adb-device _adb-tls-connect._tcp 192.168.1.8:40222\n")},
			{Stdout: []byte("connected to 192.168.1.8:40222\n")},
			{Stdout: []byte("List of devices attached\nadb-other._adb-tls-connect._tcp\tdevice model:Pixel_8 transport_id:2\n")},
		},
		errors: []error{nil, nil, nil},
	}
	store := newMemoryTrustedDeviceStore(TrustedDeviceProfile{
		Identity:      "adb-device",
		Serial:        "adb-device._adb-tls-connect._tcp",
		Model:         "Pixel 8",
		LastTransport: "wifi",
		LastEndpoint:  "192.168.1.8:40117",
		MDNSInstance:  "adb-device",
		UpdatedAt:     time.Now(),
	})
	_, err := NewClient("/fake/adb", fake).ReconnectTrustedDevice(
		context.Background(),
		"adb-device",
		store,
	)
	assertAppErrorCode(t, err, apperr.CodeDeviceUnreachable)
	if store.profile.LastEndpoint != "192.168.1.8:40117" {
		t.Fatalf("profile changed after identity mismatch: %#v", store.profile)
	}
	if len(fake.calls) != 4 {
		t.Fatalf("calls = %#v, want failed connection cleanup", fake.calls)
	}
	assertArgs(t, fake.calls[3], "disconnect", "192.168.1.8:40222")
}

func TestReconnectIdentityMismatchKeepsPreexistingConnection(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("adb-device _adb-tls-connect._tcp 192.168.1.8:40222\n")},
			{Stdout: []byte("already connected to 192.168.1.8:40222\n")},
			{Stdout: []byte("List of devices attached\nadb-other._adb-tls-connect._tcp\tdevice model:Pixel_8\n")},
		},
		errors: []error{nil, nil, nil},
	}
	store := newMemoryTrustedDeviceStore(TrustedDeviceProfile{
		Identity:      "adb-device",
		Serial:        "adb-device._adb-tls-connect._tcp",
		Model:         "Pixel 8",
		LastTransport: "wifi",
		LastEndpoint:  "192.168.1.8:40117",
		MDNSInstance:  "adb-device",
		UpdatedAt:     time.Now(),
	})
	_, err := NewClient("/fake/adb", fake).ReconnectTrustedDevice(
		context.Background(),
		"adb-device",
		store,
	)
	assertAppErrorCode(t, err, apperr.CodeDeviceUnreachable)
	if len(fake.calls) != 3 {
		t.Fatalf("preexisting connection was removed: %#v", fake.calls)
	}
}

type memoryTrustedDeviceStore struct {
	profile TrustedDeviceProfile
}

func newMemoryTrustedDeviceStore(profile TrustedDeviceProfile) *memoryTrustedDeviceStore {
	return &memoryTrustedDeviceStore{profile: profile}
}

func (s *memoryTrustedDeviceStore) Save(profile TrustedDeviceProfile) error {
	s.profile = profile
	return nil
}

func (s *memoryTrustedDeviceStore) Load(identity string) (TrustedDeviceProfile, error) {
	if s.profile.Identity != identity {
		return TrustedDeviceProfile{}, os.ErrNotExist
	}
	return s.profile, nil
}

func (s *memoryTrustedDeviceStore) List() ([]TrustedDeviceProfile, error) {
	if s.profile.Identity == "" {
		return nil, nil
	}
	return []TrustedDeviceProfile{s.profile}, nil
}

func (s *memoryTrustedDeviceStore) Delete(identity string) error {
	if s.profile.Identity == identity {
		s.profile = TrustedDeviceProfile{}
	}
	return nil
}
