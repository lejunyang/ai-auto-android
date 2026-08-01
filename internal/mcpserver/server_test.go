package mcpserver

// 测试用途：本文件验证工具数量、严格 Schema、结构化结果及服务调用前的风险拒绝。

import (
	"context"
	"encoding/json"
	"sort"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestServerExposesExactlySevenStrictStructuredTools(t *testing.T) {
	automation := &fakeAutomation{}
	session := connectClient(t, automation)

	listed, err := session.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatalf("ListTools() error = %v", err)
	}
	gotNames := make([]string, 0, len(listed.Tools))
	for _, tool := range listed.Tools {
		gotNames = append(gotNames, tool.Name)
		if tool.InputSchema == nil || tool.OutputSchema == nil {
			t.Fatalf("%s has incomplete schemas: input=%#v output=%#v", tool.Name, tool.InputSchema, tool.OutputSchema)
		}
		assertStrictInputSchema(t, tool)
		assertStrictObjectSchema(t, tool.Name+" output", schemaObject(t, tool.OutputSchema))
	}
	sort.Strings(gotNames)
	wantNames := append([]string(nil), ToolNames...)
	sort.Strings(wantNames)
	if strings.Join(gotNames, "\n") != strings.Join(wantNames, "\n") {
		t.Fatalf("tool names = %#v, want %#v", gotNames, wantNames)
	}
}

func TestServerCallsProduceStructuredResults(t *testing.T) {
	automation := &fakeAutomation{
		devices: service.DeviceListResult{
			Devices: []protocol.Device{
				{
					Serial:       "SERIAL",
					State:        "device",
					Transport:    "usb",
					Capabilities: []protocol.Capability{},
				},
			},
			Count: 1,
		},
		device: protocol.Device{
			Serial:       "SERIAL",
			State:        "device",
			Transport:    "usb",
			Model:        "Pixel 8",
			Capabilities: []protocol.Capability{},
		},
		observe: service.ObserveResult{
			Device:    "SERIAL",
			Kind:      service.ObserveScreenshot,
			Format:    "png",
			SizeBytes: 3,
			SHA256:    strings.Repeat("a", 64),
			Image:     []byte("png"),
		},
		recordings: service.RecordingListResult{
			Device: "SERIAL",
			Recordings: []service.RecordingSummary{
				{
					ID:             "123e4567-e89b-42d3-a456-426614174000",
					Name:           "Save a note",
					TargetPackages: []string{"com.example.notes"},
					CreatedAt:      "2026-07-18T01:30:00Z",
					StepCount:      3,
					Requirements: service.RecordingRequirements{
						MinAPILevel:  30,
						Capabilities: []string{"accessibility.snapshot", "accessibility.action"},
					},
				},
			},
			Count: 1,
		},
		action: adb.ActionResult{Device: "SERIAL", Action: service.ActionTap},
	}
	session := connectClient(t, automation)

	tests := []struct {
		name      string
		arguments map[string]any
		assert    func(*testing.T, *mcp.CallToolResult)
	}{
		{
			name:      ToolDevicesList,
			arguments: map[string]any{},
			assert: func(t *testing.T, result *mcp.CallToolResult) {
				structured := structuredObject(t, result)
				if structured["count"] != float64(1) {
					t.Fatalf("structured result = %#v", structured)
				}
			},
		},
		{
			name:      ToolDeviceGet,
			arguments: map[string]any{"device": "SERIAL"},
			assert: func(t *testing.T, result *mcp.CallToolResult) {
				structured := structuredObject(t, result)
				if structured["model"] != "Pixel 8" {
					t.Fatalf("structured result = %#v", structured)
				}
			},
		},
		{
			name: ToolObserve,
			arguments: map[string]any{
				"device": "SERIAL",
				"kind":   "screenshot",
			},
			assert: func(t *testing.T, result *mcp.CallToolResult) {
				structured := structuredObject(t, result)
				if structured["sha256"] != strings.Repeat("a", 64) {
					t.Fatalf("structured result = %#v", structured)
				}
				if len(result.Content) != 1 {
					t.Fatalf("content = %#v", result.Content)
				}
				image, ok := result.Content[0].(*mcp.ImageContent)
				if !ok || image.MIMEType != "image/png" || string(image.Data) != "png" {
					t.Fatalf("image content = %#v", result.Content[0])
				}
			},
		},
		{
			name: ToolObserve,
			arguments: map[string]any{
				"device": "SERIAL",
				"kind":   "recordings",
			},
			assert: func(t *testing.T, result *mcp.CallToolResult) {
				structured := structuredObject(t, result)
				if structured["count"] != float64(1) {
					t.Fatalf("structured result = %#v", structured)
				}
				recordings, ok := structured["recordings"].([]any)
				if !ok || len(recordings) != 1 {
					t.Fatalf("recordings = %#v", structured["recordings"])
				}
				recording := recordings[0].(map[string]any)
				if recording["name"] != "Save a note" ||
					recording["stepCount"] != float64(3) {
					t.Fatalf("recording = %#v", recording)
				}
				requirements := recording["requirements"].(map[string]any)
				if requirements["minApiLevel"] != float64(30) {
					t.Fatalf("requirements = %#v", requirements)
				}
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device": "SERIAL",
				"action": "ui.tap",
				"x":      12,
				"y":      34,
			},
			assert: func(t *testing.T, result *mcp.CallToolResult) {
				structured := structuredObject(t, result)
				if structured["action"] != "ui.tap" {
					t.Fatalf("structured result = %#v", structured)
				}
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
				Name:      test.name,
				Arguments: test.arguments,
			})
			if err != nil {
				t.Fatalf("CallTool() error = %v", err)
			}
			if result.IsError {
				t.Fatalf("tool error = %#v", result.Content)
			}
			if result.StructuredContent == nil {
				t.Fatal("structuredContent is missing")
			}
			test.assert(t, result)
		})
	}
}

func TestServerRiskGateRejectsPairTrustShellStopAndReplayBeforeService(t *testing.T) {
	automation := &fakeAutomation{}
	session := connectClient(t, automation)

	for _, name := range []string{
		"android_devices_pair",
		"android_device_trust",
		"android_shell",
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := session.CallTool(context.Background(), &mcp.CallToolParams{
				Name:      name,
				Arguments: map[string]any{},
			}); err == nil {
				t.Fatalf("unregistered tool %q was callable", name)
			}
		})
	}

	invalidCalls := []struct {
		name      string
		arguments map[string]any
		code      string
	}{
		{
			name:      ToolDeviceGet,
			arguments: map[string]any{},
		},
		{
			name: ToolDeviceGet,
			arguments: map[string]any{
				"device": "SERIAL WITH SPACE",
			},
		},
		{
			name: ToolObserve,
			arguments: map[string]any{
				"kind": "hierarchy",
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device":  "SERIAL",
				"action":  "shell",
				"command": "id",
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device":  "SERIAL",
				"action":  "app.install",
				"package": "com.example.app",
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device": "SERIAL",
				"action": "permission.grant",
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device": "SERIAL",
				"action": "payment.submit",
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device": "SERIAL",
				"action": "data.delete",
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device":  "SERIAL",
				"action":  "app.stop",
				"package": "com.example;reboot",
			},
		},
		{
			name: ToolActionExecute,
			arguments: map[string]any{
				"device":  "SERIAL",
				"action":  "app.stop",
				"package": "com.example.app",
			},
			code: "ACTION_NOT_ALLOWED",
		},
		{
			name: ToolRecordingReplay,
			arguments: map[string]any{
				"scriptId": "123e4567-e89b-42d3-a456-426614174000",
			},
		},
		{
			name: ToolRecordingReplay,
			arguments: map[string]any{
				"device":   "SERIAL",
				"scriptId": "123e4567-e89b-42d3-a456-426614174000",
			},
			code: "CONFIRMATION_REQUIRED",
		},
	}
	for _, call := range invalidCalls {
		t.Run(call.name, func(t *testing.T) {
			result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
				Name:      call.name,
				Arguments: call.arguments,
			})
			if err != nil {
				t.Fatalf("CallTool() protocol error = %v", err)
			}
			if !result.IsError {
				t.Fatalf("unsafe arguments were accepted: %#v", call.arguments)
			}
			if call.code != "" && !strings.Contains(toolErrorText(t, result), call.code) {
				t.Fatalf("tool error = %q, want code %q", toolErrorText(t, result), call.code)
			}
		})
	}
	if automation.calls != 0 {
		t.Fatalf("invalid calls reached service %d times", automation.calls)
	}
}

func connectClient(t *testing.T, automation service.Automation) *mcp.ClientSession {
	t.Helper()
	ctx := context.Background()
	server := New(automation, "test")
	serverTransport, clientTransport := mcp.NewInMemoryTransports()
	serverSession, err := server.Connect(ctx, serverTransport, nil)
	if err != nil {
		t.Fatalf("server.Connect() error = %v", err)
	}
	client := mcp.NewClient(
		&mcp.Implementation{Name: "aactl-test", Version: "test"},
		nil,
	)
	clientSession, err := client.Connect(ctx, clientTransport, nil)
	if err != nil {
		_ = serverSession.Close()
		t.Fatalf("client.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		_ = clientSession.Close()
		_ = serverSession.Close()
	})
	return clientSession
}

func assertStrictInputSchema(t *testing.T, tool *mcp.Tool) {
	t.Helper()
	schema := schemaObject(t, tool.InputSchema)
	if variants, ok := schema["oneOf"].([]any); ok {
		if len(variants) == 0 {
			t.Fatalf("%s input oneOf is empty", tool.Name)
		}
		for _, raw := range variants {
			variant, ok := raw.(map[string]any)
			if !ok {
				t.Fatalf("%s input variant = %#v", tool.Name, raw)
			}
			assertStrictObjectSchema(t, tool.Name+" input variant", variant)
			required, _ := variant["required"].([]any)
			if !containsString(required, "device") {
				t.Fatalf("%s input variant does not require device: %#v", tool.Name, variant)
			}
		}
		return
	}
	assertStrictObjectSchema(t, tool.Name+" input", schema)
	if tool.Name != ToolDevicesList {
		required, _ := schema["required"].([]any)
		if !containsString(required, "device") {
			t.Fatalf("%s input does not require device: %#v", tool.Name, schema)
		}
	}
}

func assertStrictObjectSchema(t *testing.T, name string, schema map[string]any) {
	t.Helper()
	if schema["type"] != "object" {
		t.Fatalf("%s type = %#v", name, schema["type"])
	}
	if schema["additionalProperties"] != false {
		t.Fatalf("%s additionalProperties = %#v, want false", name, schema["additionalProperties"])
	}
}

func schemaObject(t *testing.T, schema any) map[string]any {
	t.Helper()
	payload, err := json.Marshal(schema)
	if err != nil {
		t.Fatalf("marshal schema: %v", err)
	}
	var object map[string]any
	if err := json.Unmarshal(payload, &object); err != nil {
		t.Fatalf("unmarshal schema: %v", err)
	}
	return object
}

func structuredObject(t *testing.T, result *mcp.CallToolResult) map[string]any {
	t.Helper()
	payload, err := json.Marshal(result.StructuredContent)
	if err != nil {
		t.Fatalf("marshal structuredContent: %v", err)
	}
	var object map[string]any
	if err := json.Unmarshal(payload, &object); err != nil {
		t.Fatalf("unmarshal structuredContent: %v", err)
	}
	return object
}

func toolErrorText(t *testing.T, result *mcp.CallToolResult) string {
	t.Helper()
	if len(result.Content) != 1 {
		t.Fatalf("tool error content = %#v", result.Content)
	}
	text, ok := result.Content[0].(*mcp.TextContent)
	if !ok {
		t.Fatalf("tool error content = %#v", result.Content[0])
	}
	return text.Text
}

func containsString(values []any, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}

type fakeAutomation struct {
	devices    service.DeviceListResult
	device     protocol.Device
	observe    service.ObserveResult
	recordings service.RecordingListResult
	action     adb.ActionResult
	replay     service.ReplayRecordingResult
	calls      int
}

func (f *fakeAutomation) ListDevices(context.Context) (service.DeviceListResult, error) {
	f.calls++
	return f.devices, nil
}

func (f *fakeAutomation) GetDevice(context.Context, string) (protocol.Device, error) {
	f.calls++
	return f.device, nil
}

func (f *fakeAutomation) Observe(context.Context, service.ObserveRequest) (service.ObserveResult, error) {
	f.calls++
	return f.observe, nil
}

func (f *fakeAutomation) ExecuteAction(context.Context, service.ActionRequest) (adb.ActionResult, error) {
	f.calls++
	return f.action, nil
}

func (f *fakeAutomation) ExecuteBridgeAction(context.Context, string, json.RawMessage) (json.RawMessage, error) {
	f.calls++
	return nil, nil
}

func (f *fakeAutomation) ListRecordings(
	context.Context,
	string,
) (service.RecordingListResult, error) {
	f.calls++
	return f.recordings, nil
}

func (f *fakeAutomation) ReplayRecording(
	context.Context,
	service.ReplayRecordingRequest,
) (service.ReplayRecordingResult, error) {
	f.calls++
	return f.replay, nil
}
