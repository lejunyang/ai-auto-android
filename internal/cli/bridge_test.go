package cli

// 测试用途：本文件验证 Bridge CLI 不泄露配对秘密、失败时清理转发并拒绝非法参数。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

func TestBridgeOpenCreatesForwardWithoutLeakingSecrets(t *testing.T) {
	executor := &scriptedExecutor{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1\n")},
			{Stdout: []byte("41237\n")},
		},
		errors: []error{nil, nil},
	}
	rpc := &cliBridgeClient{
		hello: bridge.HelloResult{
			ServerVersion:           "0.1.0",
			SelectedProtocolVersion: "1.0",
		},
		opened: bridge.SessionOpenResult{
			Token:           "random-token-value-that-is-longer-than-32-bytes",
			ExpiresAt:       time.Now().Add(15 * time.Minute).UTC().Format(time.RFC3339),
			ProtocolVersion: "1.0",
		},
	}
	store := &cliSessionStore{}
	app, stdout := testApp(executor, "")
	app.BridgeClient = rpc
	app.BridgeStore = store

	exitCode := app.Run(context.Background(), []string{
		"bridge", "open",
		"--device", "SERIAL",
		"--code", "123456",
		"--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	if strings.Contains(stdout.String(), "123456") ||
		strings.Contains(stdout.String(), rpc.opened.Token) {
		t.Fatalf("secret leaked into output: %s", stdout.String())
	}
	if strings.Contains(strings.Join(executor.calls[1], " "), "123456") {
		t.Fatalf("pairing code leaked into ADB argv: %#v", executor.calls[1])
	}
	assertCLICall(
		t,
		executor.calls[1],
		"-s", "SERIAL", "forward", "tcp:0", "tcp:38383",
	)
}

func TestBridgeOpenFailureRemovesForward(t *testing.T) {
	executor := &scriptedExecutor{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1\n")},
			{Stdout: []byte("41237\n")},
			{},
		},
		errors: []error{nil, nil, nil},
	}
	app, stdout := testApp(executor, "")
	app.BridgeClient = &cliBridgeClient{
		openErr: apperr.New(apperr.CodeAuthInvalid, "Invalid pairing code.", false, nil),
	}
	app.BridgeStore = &cliSessionStore{}

	exitCode := app.Run(context.Background(), []string{
		"bridge", "open",
		"--device", "SERIAL",
		"--code", "999999",
		"--json",
	})
	if exitCode != apperr.ExitAuth {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	assertCLICall(
		t,
		executor.calls[2],
		"-s", "SERIAL", "forward", "--remove", "tcp:41237",
	)
}

func TestBridgeInfoUsesStoredSessionAndStructuredResult(t *testing.T) {
	executor := &scriptedExecutor{}
	store := &cliSessionStore{session: cliValidSession(), exists: true}
	rpc := &cliBridgeClient{
		callResult: json.RawMessage(`{"model":"Pixel 8","apiLevel":36}`),
	}
	app, stdout := testApp(executor, "")
	app.BridgeClient = rpc
	app.BridgeStore = store

	exitCode := app.Run(context.Background(), []string{
		"bridge", "info", "--device", "SERIAL", "--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	data := envelope.Data.(map[string]any)
	if data["model"] != "Pixel 8" || data["apiLevel"].(float64) != 36 {
		t.Fatalf("data = %#v", data)
	}
	if rpc.lastMethod != "device.info" {
		t.Fatalf("method = %q", rpc.lastMethod)
	}
	if len(executor.calls) != 0 {
		t.Fatalf("unexpected ADB calls = %#v", executor.calls)
	}
}

func TestBridgeActionRejectsMalformedJSONBeforeRPC(t *testing.T) {
	executor := &scriptedExecutor{}
	rpc := &cliBridgeClient{}
	app, stdout := testApp(executor, "")
	app.BridgeClient = rpc
	app.BridgeStore = &cliSessionStore{session: cliValidSession(), exists: true}

	exitCode := app.Run(context.Background(), []string{
		"bridge", "action",
		"--device", "SERIAL",
		"--action", `{"type":"ui.back"`,
		"--json",
	})
	if exitCode != apperr.ExitArgument {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	if rpc.callCount != 0 {
		t.Fatalf("RPC calls = %d", rpc.callCount)
	}
}

func TestBridgeActionReadsStrictObjectFromStdinWithoutEchoingInput(t *testing.T) {
	const sensitiveText = "private fixture input"
	action := `{"type":"ui.setText","params":{"target":{"packageName":"com.example.app","selectorCandidates":[{"strategy":"contentDescription","value":"Fixture text input","weight":1,"required":true}]},"text":"` + sensitiveText + `"}}`
	rpc := &cliBridgeClient{
		callResult: json.RawMessage(`{"route":"nodeAction","matchedPath":[0],"matchScore":1}`),
	}
	app, stdout := testApp(&scriptedExecutor{}, action+"\n")
	app.BridgeClient = rpc
	app.BridgeStore = &cliSessionStore{session: cliValidSession(), exists: true}

	exitCode := app.Run(context.Background(), []string{
		"bridge", "action",
		"--device", "SERIAL",
		"--stdin",
		"--json",
	})

	if exitCode != 0 {
		t.Fatalf("exit code = %d", exitCode)
	}
	if rpc.callCount != 1 || rpc.lastMethod != "action.execute" {
		t.Fatalf("RPC count=%d method=%q", rpc.callCount, rpc.lastMethod)
	}
	if !bytes.Contains(rpc.lastParams, []byte(sensitiveText)) {
		t.Fatal("stdin action did not reach the Bridge params")
	}
	if strings.Contains(stdout.String(), sensitiveText) {
		t.Fatal("stdin action text leaked into CLI output")
	}
}

func TestBridgeActionInlineModeRemainsCompatible(t *testing.T) {
	action := `{"type":"ui.back","params":{}}`
	rpc := &cliBridgeClient{
		callResult: json.RawMessage(`{"route":"globalAction"}`),
	}
	app, stdout := testApp(&scriptedExecutor{}, "")
	app.BridgeClient = rpc
	app.BridgeStore = &cliSessionStore{session: cliValidSession(), exists: true}

	exitCode := app.Run(context.Background(), []string{
		"bridge", "action",
		"--device", "SERIAL",
		"--action", action,
		"--json",
	})

	if exitCode != 0 {
		t.Fatalf("exit code=%d output=%s", exitCode, stdout.String())
	}
	if rpc.callCount != 1 || !bytes.Contains(rpc.lastParams, []byte(`"ui.back"`)) {
		t.Fatalf("RPC count=%d params=%s", rpc.callCount, rpc.lastParams)
	}
}

func TestBridgeActionRequiresExactlyOneInlineOrStdinSource(t *testing.T) {
	tests := [][]string{
		{"bridge", "action", "--device", "SERIAL", "--json"},
		{
			"bridge", "action", "--device", "SERIAL",
			"--action", `{"type":"ui.back","params":{}}`,
			"--stdin", "--json",
		},
		{
			"bridge", "action", "--device", "SERIAL",
			"--stdin", "--stdin", "--json",
		},
	}
	for _, args := range tests {
		rpc := &cliBridgeClient{}
		app, _ := testApp(&scriptedExecutor{}, `{"type":"ui.back","params":{}}`)
		app.BridgeClient = rpc
		app.BridgeStore = &cliSessionStore{session: cliValidSession(), exists: true}

		if exitCode := app.Run(context.Background(), args); exitCode != apperr.ExitArgument {
			t.Fatalf("args=%#v exit code=%d", args, exitCode)
		}
		if rpc.callCount != 0 {
			t.Fatalf("args=%#v RPC count=%d", args, rpc.callCount)
		}
	}
}

func TestBridgeActionStdinRejectsUnsafeJSONBeforeRPC(t *testing.T) {
	oversized := strings.Repeat("x", bridge.MaxMessageBytes)
	invalidUTF8 := string([]byte{0xff, 0xfe})
	tests := []string{
		"",
		"[]",
		`"scalar"`,
		`{"type":"ui.back","type":"ui.home","params":{}}`,
		`{"type":"ui.back","params":{"nested":{"key":1,"key":2}}}`,
		"{\"type\":\"ui.back\",\"params\":{}}\n{\"type\":\"ui.home\",\"params\":{}}",
		`{"type":"ui.back","params":{}} trailing`,
		"{\"type\":\"ui.back\",\"params\":{}}\x00",
		invalidUTF8,
		oversized,
	}
	for index, input := range tests {
		rpc := &cliBridgeClient{}
		app, stdout := testApp(&scriptedExecutor{}, input)
		app.BridgeClient = rpc
		app.BridgeStore = &cliSessionStore{session: cliValidSession(), exists: true}

		exitCode := app.Run(context.Background(), []string{
			"bridge", "action", "--device", "SERIAL", "--stdin", "--json",
		})
		if exitCode != apperr.ExitArgument {
			t.Fatalf("case=%d exit code=%d", index, exitCode)
		}
		if rpc.callCount != 0 {
			t.Fatalf("case=%d RPC count=%d", index, rpc.callCount)
		}
		if input != "" && strings.Contains(stdout.String(), input) {
			t.Fatalf("case=%d input leaked into output", index)
		}
	}
}

func TestBridgeActionStdinReadFailureStopsBeforeRPC(t *testing.T) {
	rpc := &cliBridgeClient{}
	app, _ := testApp(&scriptedExecutor{}, "")
	app.Stdin = failingActionReader{}
	app.BridgeClient = rpc
	app.BridgeStore = &cliSessionStore{session: cliValidSession(), exists: true}

	if exitCode := app.Run(context.Background(), []string{
		"bridge", "action", "--device", "SERIAL", "--stdin", "--json",
	}); exitCode != apperr.ExitArgument {
		t.Fatalf("exit code=%d", exitCode)
	}
	if rpc.callCount != 0 {
		t.Fatalf("RPC count=%d", rpc.callCount)
	}
}

func TestBridgeSnapshotValidatesPackageAndDepthBeforeRPC(t *testing.T) {
	tests := [][]string{
		{
			"bridge", "snapshot", "--device", "SERIAL",
			"--package", "com.good;reboot", "--json",
		},
		{
			"bridge", "snapshot", "--device", "SERIAL",
			"--max-depth", "101", "--json",
		},
	}
	for _, args := range tests {
		rpc := &cliBridgeClient{}
		app, _ := testApp(&scriptedExecutor{}, "")
		app.BridgeClient = rpc
		app.BridgeStore = &cliSessionStore{session: cliValidSession(), exists: true}
		if exitCode := app.Run(context.Background(), args); exitCode != apperr.ExitArgument {
			t.Fatalf("args = %#v, exit code = %d", args, exitCode)
		}
		if rpc.callCount != 0 {
			t.Fatalf("args = %#v, RPC calls = %d", args, rpc.callCount)
		}
	}
}

type cliBridgeClient struct {
	hello      bridge.HelloResult
	opened     bridge.SessionOpenResult
	openErr    error
	callResult json.RawMessage
	callErr    error
	callCount  int
	lastMethod string
	lastParams json.RawMessage
}

func (c *cliBridgeClient) Open(
	_ context.Context,
	_ int,
	_ string,
	_ string,
) (bridge.HelloResult, bridge.SessionOpenResult, error) {
	return c.hello, c.opened, c.openErr
}

func (c *cliBridgeClient) Call(
	_ context.Context,
	_ int,
	_ string,
	method string,
	params any,
	result any,
) error {
	c.callCount++
	c.lastMethod = method
	if method == "action.execute" {
		encoded, err := json.Marshal(params)
		if err != nil {
			return err
		}
		c.lastParams = encoded
	}
	if c.callErr != nil {
		return c.callErr
	}
	switch destination := result.(type) {
	case *json.RawMessage:
		*destination = append((*destination)[:0], c.callResult...)
	case *bridge.SessionCloseResult:
		destination.Closed = true
	}
	return nil
}

type failingActionReader struct{}

func (failingActionReader) Read([]byte) (int, error) {
	return 0, io.ErrUnexpectedEOF
}

type cliSessionStore struct {
	session bridge.Session
	exists  bool
}

func (s *cliSessionStore) Save(session bridge.Session) error {
	s.session = session
	s.exists = true
	return nil
}

func (s *cliSessionStore) Load(device string) (bridge.Session, error) {
	if !s.exists {
		return bridge.Session{}, apperr.New(
			apperr.CodeAuthRequired,
			"missing",
			false,
			nil,
		)
	}
	if s.session.Device != device {
		return bridge.Session{}, errors.New("device mismatch")
	}
	return s.session, nil
}

func (s *cliSessionStore) Delete(string) error {
	s.exists = false
	return nil
}

func cliValidSession() bridge.Session {
	return bridge.Session{
		Device:     "SERIAL",
		LocalPort:  41237,
		RemotePort: bridge.AndroidBridgePort,
		Token:      "random-token-value-that-is-longer-than-32-bytes",
		ExpiresAt:  time.Now().Add(15 * time.Minute),
		OpenedAt:   time.Now(),
		HostName:   "test-host",
	}
}
