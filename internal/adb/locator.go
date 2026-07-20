package adb

// 本文件按显式配置、PATH 和标准 SDK 目录顺序定位官方 ADB。

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

const envADBPath = "AACTL_ADB_PATH"

// Locator 抽象文件系统和平台依赖，便于验证跨平台发现顺序。
type Locator struct {
	LookPath func(string) (string, error)
	Getenv   func(string) string
	UserHome func() (string, error)
	GOOS     string
}

// DefaultLocator 返回使用当前操作系统环境的 ADB 定位器。
func DefaultLocator() Locator {
	return Locator{
		LookPath: exec.LookPath,
		Getenv:   os.Getenv,
		UserHome: os.UserHomeDir,
		GOOS:     runtime.GOOS,
	}
}

// Resolve 返回已校验的绝对 ADB 路径，并优先尊重 AACTL_ADB_PATH。
func (l Locator) Resolve() (string, error) {
	executable := "adb"
	if l.GOOS == "windows" {
		executable = "adb.exe"
	}

	if configured := l.Getenv(envADBPath); configured != "" {
		resolved, err := l.validate(configured)
		if err != nil {
			return "", apperr.Wrap(
				apperr.CodeADBNotFound,
				"ADB configured by AACTL_ADB_PATH is unavailable. "+
					"Point AACTL_ADB_PATH to the official Platform-Tools ADB executable, "+
					"or unset it to enable automatic discovery.",
				false,
				err,
			)
		}
		return resolved, nil
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
