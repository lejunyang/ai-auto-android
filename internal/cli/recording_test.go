package cli

// 测试用途：本文件验证录制列表依赖已建立 Bridge 会话并始终要求明确设备。

import (
	"context"
	"encoding/json"
	"os"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/bridge"
)

func TestRecordingListUsesStoredBridgeSessionAndParsesAppResponse(t *testing.T) {
	fixture, err := os.ReadFile("../bridge/testdata/recording-list-result.json")
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
	executor := &scriptedExecutor{}
	rpc := &cliBridgeClient{callResult: json.RawMessage(fixture)}
	store := &cliSessionStore{session: cliValidSession(), exists: true}
	app, stdout := testApp(executor, "")
	app.BridgeClient = rpc
	app.BridgeStore = store

	exitCode := app.Run(context.Background(), []string{
		"recording", "list",
		"--device", "SERIAL",
		"--json",
	})
	if exitCode != 0 {
		t.Fatalf("exit code = %d, output = %s", exitCode, stdout.String())
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	data := envelope.Data.(map[string]any)
	if data["device"] != "SERIAL" || data["count"] != float64(1) {
		t.Fatalf("data = %#v", data)
	}
	recordings := data["recordings"].([]any)
	recording := recordings[0].(map[string]any)
	if recording["name"] != "Save a note" || recording["stepCount"] != float64(3) {
		t.Fatalf("recording = %#v", recording)
	}
	if rpc.lastMethod != "recording.list" {
		t.Fatalf("method = %q", rpc.lastMethod)
	}
	if len(executor.calls) != 0 {
		t.Fatalf("unexpected ADB calls = %#v", executor.calls)
	}
}

func TestRecordingListRequiresExplicitDeviceBeforeBridgeCall(t *testing.T) {
	rpc := &cliBridgeClient{}
	app, _ := testApp(&scriptedExecutor{}, "")
	app.BridgeClient = rpc
	app.BridgeStore = &cliSessionStore{
		session: bridge.Session{},
		exists:  true,
	}

	if exitCode := app.Run(context.Background(), []string{
		"recording", "list", "--json",
	}); exitCode == 0 {
		t.Fatal("recording list without a device succeeded")
	}
	if rpc.callCount != 0 {
		t.Fatalf("RPC calls = %d, want none", rpc.callCount)
	}
}
