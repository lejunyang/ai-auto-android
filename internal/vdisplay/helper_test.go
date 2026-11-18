package vdisplay

// 测试用途：验证辅助 APK 的优先级与可操作缺失错误，避免显式路径失败后静默换产物。

import (
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"os"
	"path/filepath"
	"testing"
)

func TestResolveHelperPrecedence(t *testing.T) {
	directory := t.TempDir()
	explicit := filepath.Join(directory, "explicit.apk")
	environment := filepath.Join(directory, "environment.apk")
	for _, path := range []string{explicit, environment} {
		if err := os.WriteFile(path, []byte("fixture"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("AACTL_DISPLAYD_APK", environment)
	got, err := ResolveHelper(explicit)
	if err != nil || got != explicit {
		t.Fatalf("%q %v", got, err)
	}
	got, err = ResolveHelper("")
	if err != nil || got != environment {
		t.Fatalf("%q %v", got, err)
	}
	_, err = ResolveHelper(filepath.Join(directory, "missing.apk"))
	requireCode(t, err, apperr.CodeDisplaydHelperMissing)
	t.Setenv("AACTL_DISPLAYD_APK", directory)
	_, err = ResolveHelper("")
	requireCode(t, err, apperr.CodeDisplaydHelperMissing)
}

func TestResolveHelperExecutableSibling(t *testing.T) {
	t.Setenv("AACTL_DISPLAYD_APK", "")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(filepath.Dir(exe), "aactl-displayd.apk")
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("test sibling already exists; refusing overwrite")
	}
	if err := os.WriteFile(path, []byte("test-only helper fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Remove(path) })
	got, err := ResolveHelper("")
	if err != nil || got != path {
		t.Fatalf("%q %v", got, err)
	}
}
