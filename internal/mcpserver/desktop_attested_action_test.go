package mcpserver

// 测试用途：验证原子桌面视觉动作 MCP 工具不暴露坐标，并保留提交未知语义。

import (
	"bytes"
	"context"
	"encoding/json"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestDesktopAttestedActionToolHasStrictCoordinateFreeSchema(t *testing.T) {
	executor := &attestedExecutorFunc{}
	session := connectAttestedClient(t, executor)

	listed, err := session.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatalf("ListTools() error = %v", err)
	}
	var tool *mcp.Tool
	for _, candidate := range listed.Tools {
		if candidate.Name == ToolVisualActionExecute {
			tool = candidate
			break
		}
	}
	if tool == nil {
		t.Fatal("attested visual action tool is missing")
	}
	if tool.Annotations == nil || tool.Annotations.DestructiveHint == nil ||
		!*tool.Annotations.DestructiveHint {
		t.Fatalf("annotations = %#v", tool.Annotations)
	}
	input := schemaObject(t, tool.InputSchema)
	output := schemaObject(t, tool.OutputSchema)
	assertStrictObjectSchema(t, "attested visual input", input)
	assertStrictObjectSchema(t, "attested visual output", output)
	encoded, err := json.Marshal(input)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}
	for _, forbidden := range []string{
		`"x"`, `"y"`, `"point"`, `"bounds"`, `"candidateId"`,
		`"observationId"`, `"imageSha256"`, `"handle"`, `"pngBytes"`,
	} {
		if bytes.Contains(encoded, []byte(forbidden)) {
			t.Fatalf("input schema exposes %q: %s", forbidden, encoded)
		}
	}
}

func TestDesktopAttestedActionToolReturnsOnlyCommitAttestation(t *testing.T) {
	one := 1
	executor := &attestedExecutorFunc{
		result: visual.DesktopAttestedActionResult{
			Succeeded:         true,
			ObservationID:     "019fbcaa-0000-7000-8000-000000000047",
			DeviceFingerprint: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			ScreenFingerprint: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			Route:             "coordinateGesture",
			Verified:          true,
			CommitStatus:      visual.ActionCommitCommitted,
			ActionCommits:     &one,
		},
	}
	session := connectAttestedClient(t, executor)

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolVisualActionExecute,
		Arguments: map[string]any{
			"device":          "SERIAL",
			"expectedPackage": "com.example.notes",
			"target": map[string]any{
				"role":  "button",
				"label": "Save",
			},
			"action": map[string]any{"type": "tap"},
		},
	})
	if err != nil {
		t.Fatalf("CallTool() error = %v", err)
	}
	if result.IsError || executor.calls != 1 {
		t.Fatalf("result=%#v calls=%d", result, executor.calls)
	}
	structured := structuredObject(t, result)
	if structured["commitStatus"] != "committed" ||
		structured["actionCommits"] != float64(1) ||
		structured["verified"] != true {
		t.Fatalf("structured result = %#v", structured)
	}
	encoded, _ := json.Marshal(structured)
	for _, forbidden := range []string{
		`"candidate"`, `"point"`, `"bounds"`, `"pngBytes"`, `"handle"`,
	} {
		if bytes.Contains(encoded, []byte(forbidden)) {
			t.Fatalf("structured result exposes %q: %s", forbidden, encoded)
		}
	}
}

func TestDesktopAttestedActionInputRejectsCoordinatesAndDuplicateKeys(t *testing.T) {
	valid := `{
		"device":"SERIAL",
		"expectedPackage":"com.example.notes",
		"target":{"role":"button","label":"Save"},
		"action":{"type":"swipe","direction":"up"}
	}`
	for _, payload := range []string{
		`{"device":"SERIAL","device":"SECOND","expectedPackage":"com.example.notes","target":{"label":"Save"},"action":{"type":"tap"}}`,
		`{"device":"SERIAL","expectedPackage":"com.example.notes","target":{"label":"Save"},"action":{"type":"tap","x":1,"y":2}}`,
		valid + ` true`,
	} {
		request := &mcp.CallToolRequest{
			Params: &mcp.CallToolParamsRaw{Arguments: []byte(payload)},
		}
		if _, err := decodeDesktopAttestedActionInput(request); visual.ErrorCode(err) !=
			visual.CodeJSONInvalid {
			t.Fatalf("payload=%s code=%q error=%v", payload, visual.ErrorCode(err), err)
		}
	}
	request := &mcp.CallToolRequest{
		Params: &mcp.CallToolParamsRaw{Arguments: []byte(valid)},
	}
	if _, err := decodeDesktopAttestedActionInput(request); err != nil {
		t.Fatalf("valid input error = %v", err)
	}
}

type attestedExecutorFunc struct {
	result visual.DesktopAttestedActionResult
	calls  int
}

func (executor *attestedExecutorFunc) ProposeAndExecute(
	_ context.Context,
	_ visual.DesktopAttestedActionRequest,
) visual.DesktopAttestedActionResult {
	executor.calls++
	return executor.result
}

func connectAttestedClient(
	t *testing.T,
	executor desktopAttestedExecutor,
) *mcp.ClientSession {
	t.Helper()
	ctx := context.Background()
	server := newServer(
		&fakeAutomation{},
		"test",
		newDesktopVisualRuntime(nil),
		executor,
	)
	serverTransport, clientTransport := mcp.NewInMemoryTransports()
	serverSession, err := server.Connect(ctx, serverTransport, nil)
	if err != nil {
		t.Fatalf("server.Connect() error = %v", err)
	}
	client := mcp.NewClient(
		&mcp.Implementation{Name: "attested-visual-test", Version: "test"},
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
