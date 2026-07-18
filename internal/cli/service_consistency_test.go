package cli

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/mcpserver"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestCLIAndMCPMapActionsToIdenticalServiceSemantics(t *testing.T) {
	automation := &actionCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = automation

	exitCode := app.Run(context.Background(), []string{
		"action", "tap",
		"--device", "SERIAL",
		"--x", "12",
		"--y", "34",
		"--json",
	})
	if exitCode != 0 {
		t.Fatalf("CLI exit code = %d, output = %s", exitCode, stdout.String())
	}
	if envelope := decodeEnvelope(t, stdout.Bytes()); !envelope.OK {
		t.Fatalf("CLI envelope = %#v", envelope)
	}

	server := mcpserver.New(automation, "test")
	serverTransport, clientTransport := mcp.NewInMemoryTransports()
	serverSession, err := server.Connect(context.Background(), serverTransport, nil)
	if err != nil {
		t.Fatalf("server.Connect() error = %v", err)
	}
	defer serverSession.Close()
	client := mcp.NewClient(
		&mcp.Implementation{Name: "consistency-test", Version: "test"},
		nil,
	)
	clientSession, err := client.Connect(context.Background(), clientTransport, nil)
	if err != nil {
		t.Fatalf("client.Connect() error = %v", err)
	}
	defer clientSession.Close()
	result, err := clientSession.CallTool(context.Background(), &mcp.CallToolParams{
		Name: mcpserver.ToolActionExecute,
		Arguments: map[string]any{
			"device": "SERIAL",
			"action": "ui.tap",
			"x":      12,
			"y":      34,
		},
	})
	if err != nil {
		t.Fatalf("MCP CallTool() error = %v", err)
	}
	if result.IsError {
		t.Fatalf("MCP tool error = %#v", result.Content)
	}

	if len(automation.actions) != 2 {
		t.Fatalf("service action calls = %#v", automation.actions)
	}
	if automation.actions[0] != automation.actions[1] {
		t.Fatalf("CLI action = %#v, MCP action = %#v", automation.actions[0], automation.actions[1])
	}
}

func TestMCPServeReservesCLIStdoutForProtocolTransport(t *testing.T) {
	automation := &actionCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = automation
	serverTransport, clientTransport := mcp.NewInMemoryTransports()
	app.MCPTransport = serverTransport

	exited := make(chan int, 1)
	go func() {
		exited <- app.Run(context.Background(), []string{"mcp", "serve"})
	}()
	client := mcp.NewClient(
		&mcp.Implementation{Name: "stdio-test", Version: "test"},
		nil,
	)
	session, err := client.Connect(context.Background(), clientTransport, nil)
	if err != nil {
		t.Fatalf("client.Connect() error = %v", err)
	}
	listed, err := session.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatalf("ListTools() error = %v", err)
	}
	if len(listed.Tools) != 5 {
		t.Fatalf("tools = %d, want 5", len(listed.Tools))
	}
	if err := session.Close(); err != nil {
		t.Fatalf("session.Close() error = %v", err)
	}
	select {
	case exitCode := <-exited:
		if exitCode != 0 {
			t.Fatalf("MCP serve exit code = %d", exitCode)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("MCP serve did not stop after client close")
	}
	if stdout.Len() != 0 {
		t.Fatalf("CLI wrote non-protocol output to stdout: %q", stdout.String())
	}
}

func TestRecordingReplayCLIUsesSharedServiceWithExplicitDevice(t *testing.T) {
	automation := &actionCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = automation
	scriptID := "123e4567-e89b-42d3-a456-426614174000"

	exitCode := app.Run(context.Background(), []string{
		"recording", "replay",
		"--device", "SERIAL",
		"--script", scriptID,
		"--json",
	})
	if exitCode != 0 {
		t.Fatalf("CLI exit code = %d, output = %s", exitCode, stdout.String())
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if !envelope.OK {
		t.Fatalf("CLI envelope = %#v", envelope)
	}
	if len(automation.replays) != 1 {
		t.Fatalf("replay calls = %#v", automation.replays)
	}
	if automation.replays[0].Device != "SERIAL" ||
		automation.replays[0].ScriptID != scriptID {
		t.Fatalf("replay request = %#v", automation.replays[0])
	}
}

type actionCapturingAutomation struct {
	actions []service.ActionRequest
	replays []service.ReplayRecordingRequest
}

func (a *actionCapturingAutomation) ListDevices(context.Context) (service.DeviceListResult, error) {
	return service.DeviceListResult{}, nil
}

func (a *actionCapturingAutomation) GetDevice(context.Context, string) (protocol.Device, error) {
	return protocol.Device{}, nil
}

func (a *actionCapturingAutomation) Observe(
	context.Context,
	service.ObserveRequest,
) (service.ObserveResult, error) {
	return service.ObserveResult{}, nil
}

func (a *actionCapturingAutomation) ExecuteAction(
	_ context.Context,
	request service.ActionRequest,
) (adb.ActionResult, error) {
	a.actions = append(a.actions, request)
	return adb.ActionResult{Device: request.Device, Action: request.Action}, nil
}

func (a *actionCapturingAutomation) ExecuteBridgeAction(
	context.Context,
	string,
	json.RawMessage,
) (json.RawMessage, error) {
	return nil, nil
}

func (a *actionCapturingAutomation) ReplayRecording(
	_ context.Context,
	request service.ReplayRecordingRequest,
) (service.ReplayRecordingResult, error) {
	a.replays = append(a.replays, request)
	return service.ReplayRecordingResult{
		Device:    request.Device,
		ScriptID:  request.ScriptID,
		Succeeded: true,
		Steps:     []service.ReplayStepResult{},
	}, nil
}
