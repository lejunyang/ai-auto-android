package adb

// 功能用途：按显式配置、随包文件、PATH 和标准 SDK 目录定位并诊断官方 ADB。

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
	LookPath   func(string) (string, error)
	Getenv     func(string) string
	UserHome   func() (string, error)
	Executable func() (string, error)
	GOOS       string
}

// Resolution 保存本次解析的实际路径和来源，避免诊断重新搜索导致来源漂移。
type Resolution struct {
	Path   string
	Source string
}

// DefaultLocator 返回使用当前操作系统环境的 ADB 定位器。
func DefaultLocator() Locator {
	return Locator{
		LookPath:   exec.LookPath,
		Getenv:     os.Getenv,
		UserHome:   os.UserHomeDir,
		Executable: os.Executable,
		GOOS:       runtime.GOOS,
	}
}

// Resolve 返回已校验的绝对 ADB 路径，并优先尊重 AACTL_ADB_PATH。
func (l Locator) Resolve() (string, error) {
	r, err := l.ResolveWithSource()
	return r.Path, err
}

// ResolveWithSource 在一次查找中同时返回路径及 explicit/bundled/path/sdk 来源。
func (l Locator) ResolveWithSource() (Resolution, error) {
	executable := "adb"
	if l.GOOS == "windows" {
		executable = "adb.exe"
	}

	if configured := l.Getenv(envADBPath); configured != "" {
		resolved, err := l.validate(configured)
		if err != nil {
			return Resolution{}, apperr.Wrap(
				apperr.CodeADBNotFound,
				"ADB configured by AACTL_ADB_PATH is unavailable. "+
					"Point AACTL_ADB_PATH to the official Platform-Tools ADB executable, "+
					"or unset it to enable automatic discovery.",
				false,
				err,
			)
		}
		return Resolution{Path: resolved, Source: "explicit"}, nil
	}
	// 随包查找只依赖真实可执行文件的位置；损坏候选必须明确失败，不能悄悄换版本。
	executablePath := l.Executable
	if executablePath == nil {
		executablePath = os.Executable
	}
	self, err := executablePath()
	if err == nil {
		self, err = filepath.EvalSymlinks(self)
	}
	if err != nil {
		return Resolution{}, apperr.Wrap(apperr.CodeADBNotFound, "Cannot resolve the aactl executable directory. Set AACTL_ADB_PATH to an official ADB executable.", false, err)
	}
	candidate := filepath.Join(filepath.Dir(self), "platform-tools", executable)
	if _, err := os.Lstat(candidate); err == nil || !os.IsNotExist(err) {
		resolved, err := l.validate(candidate)
		if err != nil {
			return Resolution{}, apperr.Wrap(apperr.CodeADBNotFound, "The bundled ADB file is unavailable: "+candidate+". Re-extract the full bundle, or override it with AACTL_ADB_PATH pointing to an official Platform-Tools ADB executable.", false, err)
		}
		return Resolution{Path: resolved, Source: "bundled"}, nil
	}
	if found, err := l.LookPath(executable); err == nil {
		if resolved, err := l.validate(found); err == nil {
			return Resolution{Path: resolved, Source: "path"}, nil
		}
	}

	for _, rootVariable := range []string{"ANDROID_HOME", "ANDROID_SDK_ROOT"} {
		if root := l.Getenv(rootVariable); root != "" {
			candidate := filepath.Join(root, "platform-tools", executable)
			if resolved, err := l.validate(candidate); err == nil {
				return Resolution{Path: resolved, Source: "sdk"}, nil
			}
		}
	}

	if home, err := l.UserHome(); err == nil {
		for _, candidate := range defaultCandidates(home, l.GOOS, executable) {
			if resolved, err := l.validate(candidate); err == nil {
				return Resolution{Path: resolved, Source: "sdk"}, nil
			}
		}
	}

	return Resolution{}, apperr.New(
		apperr.CodeADBNotFound,
		"ADB was not found. Use the full aactl bundle, or install official Android SDK Platform-Tools and set AACTL_ADB_PATH to its adb executable.",
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
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("adb path is not a regular file")
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
