package cli

// 本文件验证截图文件权限、直接动作映射及未知或注入选项的前置拒绝。

import (
	"bytes"
	"context"
	"image"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

type scriptedExecutor struct {
	results []process.Result
	errors  []error
	calls   [][]string
}

func (e *scriptedExecutor) Run(
	_ context.Context,
	_ string,
	args []string,
	_ process.Options,
) (process.Result, error) {
	e.calls = append(e.calls, append([]string(nil), args...))
	index := len(e.calls) - 1
	if index >= len(e.results) {
		return process.Result{}, nil
	}
	return e.results[index], e.errors[index]
}

func TestObserveScreenshotWritesValidatedPNGAndMetadata(t *testing.T) {
	png := validPNG(t)
	executor := &scriptedExecutor{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nONE\tdevice usb:1-1\nTWO\tdevice usb:1-2\n")},
			{Stdout: png},
		},
		errors: []error{nil, nil},
	}
	app, stdout := testApp(executor, "")
	outputPath := filepath.Join(t.TempDir(), "screen.png")
	exitCode := app.Run(context.Background(), []string{
		"observe", "screenshot",
		"--device", "TWO",
		"--output", outputPath,
		"--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	written, err := os.ReadFile(outputPath)
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
	if string(written) != string(png) {
		t.Fatalf("written PNG = %q", written)
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	data := envelope.Data.(map[string]any)
	if data["format"] != "png" || data["sizeBytes"].(float64) != float64(len(png)) || len(data["sha256"].(string)) != 64 {
		t.Fatalf("metadata = %#v", data)
	}
	assertCLICall(t, executor.calls[1], "-s", "TWO", "exec-out", "screencap", "-p")
}

func TestActionTapUsesExactADBArgumentsWithMultipleDevices(t *testing.T) {
	executor := &scriptedExecutor{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nONE\tdevice usb:1-1\nTWO\tdevice usb:1-2\n")},
			{},
		},
		errors: []error{nil, nil},
	}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{
		"action", "tap", "--device=TWO", "--x", "12", "--y", "34", "--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	assertCLICall(t, executor.calls[1], "-s", "TWO", "shell", "input", "tap", "12", "34")
}

func TestActionLaunchUsesExplicitComponentArguments(t *testing.T) {
	executor := &scriptedExecutor{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nONE\tdevice usb:1-1\n")},
			{},
		},
		errors: []error{nil, nil},
	}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{
		"action", "launch",
		"--device", "ONE",
		"--package", "com.example.app",
		"--activity", ".MainActivity",
		"--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	assertCLICall(
		t,
		executor.calls[1],
		"-s", "ONE", "shell", "am", "start", "-W", "-n", "com.example.app/.MainActivity",
	)
}

func TestDirectCommandsRequireExplicitDeviceBeforeADB(t *testing.T) {
	executor := &scriptedExecutor{}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{
		"action", "key", "--key", "BACK", "--json",
	})
	if exitCode != apperr.ExitArgument {
		t.Fatalf("exit code = %d", exitCode)
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if envelope.Error == nil || envelope.Error.Code != apperr.CodeInvalidArgument {
		t.Fatalf("error = %#v", envelope.Error)
	}
	if len(executor.calls) != 0 {
		t.Fatalf("ADB calls = %#v", executor.calls)
	}
}

func TestDirectCommandsRejectUnknownAndInjectedOptionsBeforeADB(t *testing.T) {
	tests := [][]string{
		{"action", "tap", "--device", "ONE", "--x", "1", "--y", "2", "--shell", "id", "--json"},
		{"action", "text", "--device", "ONE", "--text", "hello;reboot", "--json"},
		{"action", "stop", "--device", "ONE", "--package", "com.example;reboot", "--json"},
		{"action", "key", "--device", "ONE", "--key", "POWER", "--json"},
	}
	for _, args := range tests {
		t.Run(strings.Join(args[:2], "-")+"-"+args[len(args)-2], func(t *testing.T) {
			executor := &scriptedExecutor{}
			app, _ := testApp(executor, "")
			if exitCode := app.Run(context.Background(), args); exitCode != apperr.ExitArgument {
				t.Fatalf("exit code = %d", exitCode)
			}
			if len(executor.calls) != 0 {
				t.Fatalf("ADB calls = %#v", executor.calls)
			}
		})
	}
}

func assertCLICall(t *testing.T, actual []string, expected ...string) {
	t.Helper()
	if strings.Join(actual, "\x00") != strings.Join(expected, "\x00") {
		t.Fatalf("args = %#v, want %#v", actual, expected)
	}
}

func validPNG(t *testing.T) []byte {
	t.Helper()
	var output bytes.Buffer
	if err := png.Encode(&output, image.NewRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatalf("encode PNG: %v", err)
	}
	return output.Bytes()
}
