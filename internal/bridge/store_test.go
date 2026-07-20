package bridge

// 本文件验证短期 token 文件权限、路径类型校验和缺失会话错误语义。

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

func TestFileSessionStoreUsesRestrictedPermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows ACLs are not represented by Unix permission bits")
	}
	directory := filepath.Join(t.TempDir(), "sessions")
	store := NewFileSessionStore(directory)
	session := validSession()

	if err := store.Save(session); err != nil {
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
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("session mode = %o, want 600", info.Mode().Perm())
	}

	loaded, err := store.Load(session.Device)
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.Token != session.Token || loaded.LocalPort != session.LocalPort {
		t.Fatalf("loaded = %#v", loaded)
	}
}

func TestFileSessionStoreRejectsUnsafePermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows ACLs are not represented by Unix permission bits")
	}
	directory := filepath.Join(t.TempDir(), "sessions")
	store := NewFileSessionStore(directory)
	session := validSession()
	if err := store.Save(session); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(directory)
	path := filepath.Join(directory, entries[0].Name())
	if err := os.Chmod(path, 0o644); err != nil {
		t.Fatal(err)
	}

	_, err := store.Load(session.Device)
	assertBridgeErrorCode(t, err, apperr.CodePermissionDenied)
}

func TestFileSessionStoreMissingSessionIsAuthRequired(t *testing.T) {
	_, err := NewFileSessionStore(t.TempDir()).Load("SERIAL")
	assertBridgeErrorCode(t, err, apperr.CodeAuthRequired)
}

func validSession() Session {
	return Session{
		Device:     "SERIAL",
		LocalPort:  41237,
		RemotePort: AndroidBridgePort,
		Token:      "random-token-value-that-is-longer-than-32-bytes",
		ExpiresAt:  time.Now().Add(15 * time.Minute),
		OpenedAt:   time.Now(),
		HostName:   "test-host",
	}
}

func assertBridgeErrorCode(t *testing.T, err error, code string) {
	t.Helper()
	var appError *apperr.Error
	if !errors.As(err, &appError) {
		t.Fatalf("error = %v, want *apperr.Error", err)
	}
	if appError.Code != code {
		t.Fatalf("error code = %q, want %q", appError.Code, code)
	}
}
