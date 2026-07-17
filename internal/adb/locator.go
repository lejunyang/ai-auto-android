package adb

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

const envADBPath = "AACTL_ADB_PATH"

type Locator struct {
	LookPath func(string) (string, error)
	Getenv   func(string) string
	UserHome func() (string, error)
	GOOS     string
}

func DefaultLocator() Locator {
	return Locator{
		LookPath: exec.LookPath,
		Getenv:   os.Getenv,
		UserHome: os.UserHomeDir,
		GOOS:     runtime.GOOS,
	}
}

func (l Locator) Resolve() (string, error) {
	executable := "adb"
	if l.GOOS == "windows" {
		executable = "adb.exe"
	}

	if configured := l.Getenv(envADBPath); configured != "" {
		return l.validate(configured)
	}
	if found, err := l.LookPath(executable); err == nil {
		return filepath.Abs(found)
	}

	for _, rootVariable := range []string{"ANDROID_HOME", "ANDROID_SDK_ROOT"} {
		if root := l.Getenv(rootVariable); root != "" {
			candidate := filepath.Join(root, "platform-tools", executable)
			if resolved, err := l.validate(candidate); err == nil {
				return resolved, nil
			}
		}
	}

	if home, err := l.UserHome(); err == nil {
		for _, candidate := range defaultCandidates(home, l.GOOS, executable) {
			if resolved, err := l.validate(candidate); err == nil {
				return resolved, nil
			}
		}
	}

	return "", apperr.New(
		apperr.CodeADBNotFound,
		"ADB was not found. Install official Android SDK Platform-Tools or set AACTL_ADB_PATH.",
		false,
		nil,
	)
}

func (l Locator) validate(path string) (string, error) {
	absolutePath, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("resolve adb path: %w", err)
	}
	info, err := os.Stat(absolutePath)
	if err != nil {
		return "", err
	}
	if info.IsDir() {
		return "", fmt.Errorf("adb path is a directory")
	}
	if l.GOOS != "windows" && info.Mode().Perm()&0o111 == 0 {
		return "", fmt.Errorf("adb path is not executable")
	}
	return absolutePath, nil
}

func defaultCandidates(home, goos, executable string) []string {
	switch goos {
	case "darwin":
		return []string{filepath.Join(home, "Library", "Android", "sdk", "platform-tools", executable)}
	case "windows":
		return []string{
			filepath.Join(home, "AppData", "Local", "Android", "Sdk", "platform-tools", executable),
		}
	default:
		return []string{filepath.Join(home, "Android", "Sdk", "platform-tools", executable)}
	}
}
