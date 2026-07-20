package adb

// 本文件验证显式路径和标准 SDK 路径的发现优先级及错误归一化。

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

func TestLocatorPrefersExplicitADBPath(t *testing.T) {
	directory := t.TempDir()
	adbPath := filepath.Join(directory, "adb")
	if err := os.WriteFile(adbPath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	locator := Locator{
		LookPath: func(string) (string, error) { return "", os.ErrNotExist },
		Getenv: func(key string) string {
			if key == envADBPath {
				return adbPath
			}
			return ""
		},
		UserHome: func() (string, error) { return directory, nil },
		GOOS:     "darwin",
	}
	resolved, err := locator.Resolve()
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if resolved != adbPath {
		t.Fatalf("Resolve() = %q, want %q", resolved, adbPath)
	}
}

func TestLocatorFindsAndroidHomePlatformTools(t *testing.T) {
	directory := t.TempDir()
	adbPath := filepath.Join(directory, "platform-tools", "adb")
	if err := os.MkdirAll(filepath.Dir(adbPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(adbPath, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	locator := Locator{
		LookPath: func(string) (string, error) { return "", os.ErrNotExist },
		Getenv: func(key string) string {
			if key == "ANDROID_HOME" {
				return directory
			}
			return ""
		},
		UserHome: func() (string, error) { return "", os.ErrNotExist },
		GOOS:     "darwin",
	}
	resolved, err := locator.Resolve()
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if resolved != adbPath {
		t.Fatalf("Resolve() = %q, want %q", resolved, adbPath)
	}
}

func TestLocatorMapsInvalidExplicitADBPathToADBNotFound(t *testing.T) {
	directory := t.TempDir()
	nonExecutablePath := filepath.Join(directory, "non-executable-adb")
	if err := os.WriteFile(nonExecutablePath, []byte("not executable"), 0o644); err != nil {
		t.Fatal(err)
	}
	directoryPath := filepath.Join(directory, "adb-directory")
	if err := os.Mkdir(directoryPath, 0o755); err != nil {
		t.Fatal(err)
	}

	tests := []struct {
		name          string
		configured    string
		expectedCause error
	}{
		{name: "missing", configured: filepath.Join(directory, "missing-adb"), expectedCause: os.ErrNotExist},
		{name: "directory", configured: directoryPath},
		{name: "not executable", configured: nonExecutablePath},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			locator := Locator{
				LookPath: func(string) (string, error) { return "", os.ErrNotExist },
				Getenv: func(key string) string {
					if key == envADBPath {
						return test.configured
					}
					return ""
				},
				UserHome: func() (string, error) { return "", os.ErrNotExist },
				GOOS:     "darwin",
			}

			_, err := locator.Resolve()
			var appError *apperr.Error
			if !errors.As(err, &appError) {
				t.Fatalf("Resolve() error = %T %v, want *apperr.Error", err, err)
			}
			if appError.Code != apperr.CodeADBNotFound {
				t.Errorf("Resolve() error code = %q, want %q", appError.Code, apperr.CodeADBNotFound)
			}
			if !strings.Contains(appError.Message, envADBPath) || !strings.Contains(appError.Message, "unset") {
				t.Errorf("Resolve() message = %q, want actionable %s guidance", appError.Message, envADBPath)
			}
			if errors.Unwrap(appError) == nil {
				t.Fatal("Resolve() error cause = nil, want validation cause")
			}
			if test.expectedCause != nil && !errors.Is(appError, test.expectedCause) {
				t.Errorf("Resolve() error = %v, want cause %v", appError, test.expectedCause)
			}
		})
	}
}
