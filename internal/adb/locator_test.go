package adb

// 测试用途：本文件验证显式路径和标准 SDK 路径的发现优先级及错误归一化。

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

// hostADBExecutable 返回当前平台上 Locator 期望的 ADB 可执行文件名。
// 正向发现用例必须使用真实 GOOS：Windows 的 os.WriteFile 无法写入 0o111
// 执行位，若把 GOOS 固定成 darwin，validate 的执行位校验会在 Windows 上
// 必然失败，这属于测试自身的平台假设错误，而非被测逻辑缺陷。
func hostADBExecutable() string {
	if runtime.GOOS == "windows" {
		return "adb.exe"
	}
	return "adb"
}

func TestLocatorPrefersExplicitADBPath(t *testing.T) {
	directory := t.TempDir()
	adbPath := filepath.Join(directory, hostADBExecutable())
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
		GOOS:     runtime.GOOS,
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
	adbPath := filepath.Join(directory, "platform-tools", hostADBExecutable())
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
		GOOS:     runtime.GOOS,
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

// TestLocatorBundleOrder 验证完整定位链及来源，防止随包 ADB 被 PATH 或 SDK 抢先。
func TestLocatorBundleOrder(t *testing.T) {
	dir := t.TempDir()
	write := func(name string) string {
		p := filepath.Join(dir, name)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("adb fixture"), 0o755); err != nil {
			t.Fatal(err)
		}
		return p
	}
	exe := write("bundle/aactl")
	bundled := write("bundle/platform-tools/" + hostADBExecutable())
	explicit := write("explicit/" + hostADBExecutable())
	onPath := write("path/" + hostADBExecutable())
	androidHome := write("home-sdk/platform-tools/" + hostADBExecutable())
	sdkRoot := write("root-sdk/platform-tools/" + hostADBExecutable())
	defaultADB := defaultCandidates(filepath.Join(dir, "user"), runtime.GOOS, hostADBExecutable())[0]
	if err := os.MkdirAll(filepath.Dir(defaultADB), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(defaultADB, []byte("fixture"), 0o755); err != nil {
		t.Fatal(err)
	}
	env := map[string]string{envADBPath: explicit, "ANDROID_HOME": filepath.Dir(filepath.Dir(androidHome)), "ANDROID_SDK_ROOT": filepath.Dir(filepath.Dir(sdkRoot))}
	pathAvailable := true
	l := Locator{Executable: func() (string, error) { return exe, nil }, Getenv: func(k string) string { return env[k] }, UserHome: func() (string, error) { return filepath.Join(dir, "user"), nil }, GOOS: runtime.GOOS,
		LookPath: func(string) (string, error) {
			if pathAvailable {
				return onPath, nil
			}
			return "", os.ErrNotExist
		},
	}
	for _, test := range []struct{ path, source, remove string }{
		{explicit, "explicit", "env"}, {bundled, "bundled", "bundle"}, {onPath, "path", "path"}, {androidHome, "sdk", "home"}, {sdkRoot, "sdk", "root"}, {defaultADB, "sdk", ""},
	} {
		r, err := l.ResolveWithSource()
		if err != nil || r.Path != test.path || r.Source != test.source {
			t.Fatalf("resolution = %+v, %v; want %s (%s)", r, err, test.path, test.source)
		}
		switch test.remove {
		case "env":
			delete(env, envADBPath)
		case "bundle":
			if err := os.Remove(bundled); err != nil {
				t.Fatal(err)
			}
		case "path":
			pathAvailable = false
		case "home":
			delete(env, "ANDROID_HOME")
		case "root":
			delete(env, "ANDROID_SDK_ROOT")
		}
	}
}

func TestLocatorRejectsBrokenBundleInsteadOfPATH(t *testing.T) {
	for _, kind := range []string{"directory", "non-executable", "dangling-symlink"} {
		t.Run(kind, func(t *testing.T) {
			dir := t.TempDir()
			candidate := filepath.Join(dir, "platform-tools", "adb")
			if err := os.MkdirAll(filepath.Dir(candidate), 0o755); err != nil {
				t.Fatal(err)
			}
			switch kind {
			case "directory":
				if err := os.Mkdir(candidate, 0o755); err != nil {
					t.Fatal(err)
				}
			case "non-executable":
				if err := os.WriteFile(candidate, []byte("bad"), 0o644); err != nil {
					t.Fatal(err)
				}
			case "dangling-symlink":
				if err := os.Symlink(filepath.Join(dir, "missing"), candidate); err != nil {
					t.Fatal(err)
				}
			}
			l := Locator{Executable: func() (string, error) { return filepath.Join(dir, "aactl"), nil }, GOOS: "linux", Getenv: func(string) string { return "" }, LookPath: func(string) (string, error) { t.Fatal("must not fall back to PATH"); return "", nil }, UserHome: func() (string, error) { return "", os.ErrNotExist }}
			if err := os.WriteFile(filepath.Join(dir, "aactl"), []byte("fixture"), 0o755); err != nil {
				t.Fatal(err)
			}
			_, err := l.Resolve()
			var appErr *apperr.Error
			if !errors.As(err, &appErr) || appErr.Code != apperr.CodeADBNotFound || !strings.Contains(appErr.Message, "bundled") || !strings.Contains(appErr.Message, envADBPath) {
				t.Fatalf("error = %v", err)
			}
		})
	}
}

func TestLocatorResolvesExecutableSymlinkAndIgnoresCWD(t *testing.T) {
	dir := t.TempDir()
	actual := filepath.Join(dir, "actual")
	links := filepath.Join(dir, "links")
	for _, p := range []string{actual, links} {
		if err := os.MkdirAll(filepath.Join(p, "platform-tools"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(p, "platform-tools", hostADBExecutable()), []byte("fixture"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	exe := filepath.Join(actual, "aactl")
	if err := os.WriteFile(exe, []byte("fixture"), 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(links, "aactl")
	if err := os.Symlink(exe, link); err != nil {
		t.Fatal(err)
	}
	t.Chdir(links)
	l := Locator{Executable: func() (string, error) { return link, nil }, GOOS: runtime.GOOS, Getenv: func(string) string { return "" }, LookPath: func(string) (string, error) { return "", os.ErrNotExist }, UserHome: func() (string, error) { return "", os.ErrNotExist }}
	r, err := l.ResolveWithSource()
	if err != nil || r.Path != filepath.Join(actual, "platform-tools", hostADBExecutable()) {
		t.Fatalf("resolution = %+v, %v", r, err)
	}
	if err := os.Remove(r.Path); err != nil {
		t.Fatal(err)
	}
	_, err = l.Resolve()
	if err == nil {
		t.Fatal("must not use CWD bundle")
	}
}
