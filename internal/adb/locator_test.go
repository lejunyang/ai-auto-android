package adb

import (
	"os"
	"path/filepath"
	"testing"
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

func TestLocatorRejectsNonExecutableADB(t *testing.T) {
	directory := t.TempDir()
	adbPath := filepath.Join(directory, "adb")
	if err := os.WriteFile(adbPath, []byte("not executable"), 0o644); err != nil {
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
		UserHome: func() (string, error) { return "", os.ErrNotExist },
		GOOS:     "darwin",
	}
	if _, err := locator.Resolve(); err == nil {
		t.Fatal("Resolve() error = nil, want ADB_NOT_FOUND")
	}
}
