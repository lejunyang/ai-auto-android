package cli

// 测试用途：本文件验证 CLI 与 MCP 共享相同服务语义，且 MCP stdout 不混入日志。

import (
	"context"
	"encoding/json"
	"reflect"
	"strings"
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
	// 用 reflect.DeepEqual 而非 !=：ActionRequest 自 ui.pathGesture 起带
	// []adb.Point 字段，含切片的结构体不可用 == 比较。
	if !reflect.DeepEqual(automation.actions[0], automation.actions[1]) {
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
	// 断言暴露的工具集与 mcpserver.ToolNames 逐项一致，而不是写死数量。
	// 原先硬编码 7 会在每次新增工具时失败，却无法说明是"多暴露了不该暴露的"
	// 还是"正常新增"——按名单比对能区分这两种情况。
	if len(listed.Tools) != len(mcpserver.ToolNames) {
		names := make([]string, 0, len(listed.Tools))
		for _, tool := range listed.Tools {
			names = append(names, tool.Name)
		}
		t.Fatalf("tools = %d %v, want %d %v",
			len(listed.Tools), names, len(mcpserver.ToolNames), mcpserver.ToolNames)
	}
	exposed := make(map[string]bool, len(listed.Tools))
	for _, tool := range listed.Tools {
		exposed[tool.Name] = true
	}
	for _, expected := range mcpserver.ToolNames {
		if !exposed[expected] {
			t.Fatalf("tool %q is declared in ToolNames but not exposed", expected)
		}
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

func TestRecordingListCLIUsesSharedServiceWithExplicitDevice(t *testing.T) {
	automation := &actionCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = automation

	exitCode := app.Run(context.Background(), []string{
		"recording", "list",
		"--device", "SERIAL",
		"--json",
	})
	if exitCode != 0 {
		t.Fatalf("CLI exit code = %d, output = %s", exitCode, stdout.String())
	}
	envelope := decodeEnvelope(t, stdout.Bytes())
	if !envelope.OK {
		t.Fatalf("CLI envelope = %#v", envelope)
	}
	if len(automation.recordingLists) != 1 ||
		automation.recordingLists[0] != "SERIAL" {
		t.Fatalf("recording list calls = %#v", automation.recordingLists)
	}
}

type actionCapturingAutomation struct {
	actions            []service.ActionRequest
	recordingLists     []string
	replays            []service.ReplayRecordingRequest
	foregrounds        []service.ForegroundRequest
	appQueries         []service.AppsRequest
	recordRequests     []service.RecordingRequest
	predicateRequests  []service.PredicateRequest
	bridgeTexts        []service.BridgeTextRequest
	screenStates       []string
	inputMethodQueries []string
	fileTransfers      []service.FileTransferRequest
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

func (a *actionCapturingAutomation) ListRecordings(
	_ context.Context,
	device string,
) (service.RecordingListResult, error) {
	a.recordingLists = append(a.recordingLists, device)
	return service.RecordingListResult{
		Device:     device,
		Recordings: []service.RecordingSummary{},
		Count:      0,
	}, nil
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

// Foreground、Apps 与 Record 补齐 service.Automation 的观察扩展。
// 与本 fake 的其它方法一致：记录入参供断言，并回可预期的最小结果。
func (a *actionCapturingAutomation) Foreground(
	_ context.Context,
	request service.ForegroundRequest,
) (service.ForegroundResult, error) {
	a.foregrounds = append(a.foregrounds, request)
	return service.ForegroundResult{
		Device:   request.Device,
		Package:  "com.example.app",
		Activity: "com.example.app.MainActivity",
		Source:   "resumedActivity",
	}, nil
}

func (a *actionCapturingAutomation) Apps(
	_ context.Context,
	request service.AppsRequest,
) (service.AppsResult, error) {
	a.appQueries = append(a.appQueries, request)
	return service.AppsResult{
		Device:          request.Device,
		Apps:            []adb.InstalledApp{},
		Count:           0,
		IncludesSystem:  request.IncludeSystem,
		DetailsIncluded: request.WithDetails,
	}, nil
}

// Wait 与 Assert 补齐判据执行。记录入参供断言，并回可预期的结果。
// Assert 在 fake 里不制造失败：失败语义由 service 层负责，这里只验 CLI 路由。
// BridgeSetText 补齐桥侧文本输入。记录入参供断言。
func (a *actionCapturingAutomation) BridgeSetText(
	_ context.Context,
	request service.BridgeTextRequest,
) (json.RawMessage, error) {
	a.bridgeTexts = append(a.bridgeTexts, request)
	return json.RawMessage(`{"route":"nodeAction","matchScore":1}`), nil
}

func (a *actionCapturingAutomation) Wait(
	_ context.Context,
	request service.PredicateRequest,
) (service.PredicateOutcome, error) {
	a.predicateRequests = append(a.predicateRequests, request)
	return service.PredicateOutcome{
		Device:    request.Device,
		Kind:      request.Kind,
		Operator:  request.Operator,
		Satisfied: true,
		Polls:     1,
	}, nil
}

func (a *actionCapturingAutomation) Assert(
	_ context.Context,
	request service.PredicateRequest,
) (service.PredicateOutcome, error) {
	a.predicateRequests = append(a.predicateRequests, request)
	return service.PredicateOutcome{
		Device:    request.Device,
		Kind:      request.Kind,
		Operator:  request.Operator,
		Satisfied: true,
		Polls:     1,
	}, nil
}

func (a *actionCapturingAutomation) Record(
	_ context.Context,
	request service.RecordingRequest,
) (service.RecordingResult, error) {
	a.recordRequests = append(a.recordRequests, request)
	seconds := request.Seconds
	if seconds == 0 {
		seconds = 10
	}
	// 返回带 ftyp box 的最小 MP4 头：CLI 会把 Video 写文件，
	// 用真实容器头可以让写文件路径与生产行为一致。
	return service.RecordingResult{
		Device:      request.Device,
		Format:      "mp4",
		SizeBytes:   12,
		SHA256:      strings.Repeat("a", 64),
		DurationSec: seconds,
		Video:       []byte{0, 0, 0, 12, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'},
	}, nil
}

// ScreenState、InputMethods 与文件传输补齐动作面扩展的观察与传输能力。
// 与本 fake 的其它方法一致：记录入参供断言，并回可预期的最小结果。
func (a *actionCapturingAutomation) ScreenState(
	_ context.Context,
	device string,
) (adb.ScreenState, error) {
	a.screenStates = append(a.screenStates, device)
	return adb.ScreenState{Device: device, State: "ON", On: true}, nil
}

func (a *actionCapturingAutomation) InputMethods(
	_ context.Context,
	device string,
) (service.InputMethodListResult, error) {
	a.inputMethodQueries = append(a.inputMethodQueries, device)
	return service.InputMethodListResult{Device: device, IDs: []string{}, Count: 0}, nil
}

func (a *actionCapturingAutomation) PushFile(
	_ context.Context,
	request service.FileTransferRequest,
) (adb.FileTransfer, error) {
	a.fileTransfers = append(a.fileTransfers, request)
	return adb.FileTransfer{
		Device:     request.Device,
		Action:     "file.push",
		LocalPath:  request.LocalPath,
		RemotePath: request.RemotePath,
	}, nil
}

func (a *actionCapturingAutomation) PullFile(
	_ context.Context,
	request service.FileTransferRequest,
) (adb.FileTransfer, error) {
	a.fileTransfers = append(a.fileTransfers, request)
	return adb.FileTransfer{
		Device:     request.Device,
		Action:     "file.pull",
		LocalPath:  request.LocalPath,
		RemotePath: request.RemotePath,
	}, nil
}
