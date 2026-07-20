package cli

// 本文件验证统一响应信封、稳定退出码、显式设备选择和标准输入配对契约。

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

type fixedLocator struct {
	path string
	err  error
}

func (l fixedLocator) Resolve() (string, error) {
	return l.path, l.err
}

type commandExecutor struct {
	result process.Result
	err    error
	calls  [][]string
}

func (e *commandExecutor) Run(_ context.Context, _ string, args []string, _ process.Options) (process.Result, error) {
	e.calls = append(e.calls, append([]string(nil), args...))
	return e.result, e.err
}

func TestDevicesListProducesProtocolEnvelope(t *testing.T) {
	executor := &commandExecutor{result: process.Result{Stdout: []byte(`List of devices attached
SERIAL	device usb:1-1 model:Pixel_8 transport_id:1
OTHER	unauthorized transport_id:2
`)}}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{"devices", "list", "--json"})
	if exitCode != 0 {
		t.Fatalf("exit code = %d", exitCode)
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if !envelope.OK || envelope.Error != nil {
		t.Fatalf("envelope = %#v", envelope)
	}
	data, ok := envelope.Data.(map[string]any)
	if !ok || data["count"].(float64) != 2 {
		t.Fatalf("data = %#v", envelope.Data)
	}
}

func TestDeviceInfoRequiresExplicitDeviceBeforeCallingADB(t *testing.T) {
	executor := &commandExecutor{}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{"device", "info", "--json"})
	if exitCode != apperr.ExitArgument {
		t.Fatalf("exit code = %d, want %d", exitCode, apperr.ExitArgument)
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if envelope.Error == nil || envelope.Error.Code != apperr.CodeInvalidArgument {
		t.Fatalf("envelope error = %#v", envelope.Error)
	}
	if len(executor.calls) != 0 {
		t.Fatalf("ADB calls = %#v, want none", executor.calls)
	}
}

func TestDeviceInfoRejectsInjectionSerialBeforeCallingADB(t *testing.T) {
	executor := &commandExecutor{}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{"device", "info", "--device", "SERIAL;rm", "--json"})
	if exitCode != apperr.ExitArgument {
		t.Fatalf("exit code = %d", exitCode)
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if envelope.Error == nil || envelope.Error.Code != apperr.CodeInvalidArgument {
		t.Fatalf("envelope error = %#v", envelope.Error)
	}
	if len(executor.calls) != 0 {
		t.Fatalf("ADB calls = %#v, want none", executor.calls)
	}
}

func TestDevicesListTimeoutUsesStableExitAndError(t *testing.T) {
	executor := &commandExecutor{err: process.ErrTimeout}
	app, stdout := testApp(executor, "")
	exitCode := app.Run(context.Background(), []string{"devices", "list", "--json"})
	if exitCode != apperr.ExitTimeout {
		t.Fatalf("exit code = %d, want %d", exitCode, apperr.ExitTimeout)
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if envelope.Error == nil || envelope.Error.Code != apperr.CodeDeadlineExceeded || !envelope.Error.Retryable {
		t.Fatalf("envelope error = %#v", envelope.Error)
	}
}

func TestPairReadsCodeFromStdinWithoutWritingIt(t *testing.T) {
	executor := &commandExecutor{result: process.Result{Stdout: []byte("Successfully paired")}}
	app, stdout := testApp(executor, "123456\n")
	exitCode := app.Run(context.Background(), []string{"devices", "pair", "192.168.1.8:37123", "--json"})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	if strings.Contains(stdout.String(), "123456") {
		t.Fatalf("pairing code leaked into output: %s", stdout.String())
	}
}

func TestVersionDoesNotResolveADB(t *testing.T) {
	var stdout bytes.Buffer
	app := &App{
		Locator:  fixedLocator{err: context.Canceled},
		Executor: &commandExecutor{},
		Stdin:    strings.NewReader(""),
		Stdout:   &stdout,
		Stderr:   &bytes.Buffer{},
	}
	if exitCode := app.Run(context.Background(), []string{"version", "--json"}); exitCode != 0 {
		t.Fatalf("exit code = %d", exitCode)
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if !envelope.OK {
		t.Fatalf("envelope = %#v", envelope)
	}
}

func testApp(executor process.Executor, stdin string) (*App, *bytes.Buffer) {
	var stdout bytes.Buffer
	return &App{
		Locator:  fixedLocator{path: "/fake/adb"},
		Executor: executor,
		Stdin:    strings.NewReader(stdin),
		Stdout:   &stdout,
		Stderr:   &bytes.Buffer{},
	}, &stdout
}

func decodeEnvelope(t *testing.T, data []byte) protocol.Envelope {
	t.Helper()
	var envelope protocol.Envelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		t.Fatalf("decode envelope %q: %v", data, err)
	}
	if envelope.SchemaVersion != "1.0" || envelope.RequestID == "" {
		t.Fatalf("invalid envelope metadata: %#v", envelope)
	}
	return envelope
}
