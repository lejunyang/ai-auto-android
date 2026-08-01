package mcpserver

// 功能用途：注册原子桌面视觉动作工具，并严格禁止输入或输出可复用坐标。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"

	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ToolVisualActionExecute 是只接受目标描述和动作意图的原子视觉动作工具。
const ToolVisualActionExecute = "android_visual_action_execute"

type desktopAttestedExecutor interface {
	ProposeAndExecute(
		context.Context,
		visual.DesktopAttestedActionRequest,
	) visual.DesktopAttestedActionResult
}

type desktopAttestedActionInput struct {
	Device          string                     `json:"device"`
	ExpectedPackage string                     `json:"expectedPackage"`
	Target          visual.DesktopTarget       `json:"target"`
	Action          visual.DesktopVisualAction `json:"action"`
}

func addDesktopAttestedActionTool(
	server *mcp.Server,
	executor desktopAttestedExecutor,
) {
	server.AddTool(&mcp.Tool{
		Name:         ToolVisualActionExecute,
		Description:  "Atomically capture fresh visual evidence, select one target, execute one typed Accessibility action, and verify the result without returning coordinates.",
		InputSchema:  desktopAttestedActionInputSchema(),
		OutputSchema: desktopAttestedActionOutputSchema(),
		Annotations:  actionAnnotations(),
	}, func(
		ctx context.Context,
		request *mcp.CallToolRequest,
	) (*mcp.CallToolResult, error) {
		input, err := decodeDesktopAttestedActionInput(request)
		if err != nil {
			return desktopVisualToolError(err), nil
		}
		result := visual.DesktopAttestedActionResult{}
		if executor == nil {
			zero := 0
			result.CommitStatus = visual.ActionCommitNotCommitted
			result.ActionCommits = &zero
			result.ErrorCode = visual.CodeActionNotAuthorized
		} else {
			result = executor.ProposeAndExecute(
				ctx,
				visual.DesktopAttestedActionRequest{
					Device:          input.Device,
					ExpectedPackage: input.ExpectedPackage,
					Target:          input.Target,
					Action:          input.Action,
				},
			)
		}
		encoded, err := json.Marshal(result)
		if err != nil {
			return desktopVisualToolError(
				visual.NewError(visual.CodeJSONInvalid),
			), nil
		}
		defer clearVisualBytes(encoded)
		return &mcp.CallToolResult{
			StructuredContent: json.RawMessage(bytes.Clone(encoded)),
		}, nil
	})
}

func decodeDesktopAttestedActionInput(
	request *mcp.CallToolRequest,
) (desktopAttestedActionInput, error) {
	if request == nil || request.Params == nil ||
		len(request.Params.Arguments) == 0 ||
		len(request.Params.Arguments) > 16*1024 {
		return desktopAttestedActionInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	raw := request.Params.Arguments
	if err := rejectDesktopVisualDuplicateKeys(raw); err != nil {
		return desktopAttestedActionInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var input desktopAttestedActionInput
	if err := decoder.Decode(&input); err != nil {
		return desktopAttestedActionInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return desktopAttestedActionInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	if input.Device == "" || input.ExpectedPackage == "" ||
		(input.Target.Role == "" && input.Target.Label == "") {
		return desktopAttestedActionInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	return input, nil
}

func desktopAttestedActionInputSchema() map[string]any {
	return strictVisualSchema(`{
		"type":"object",
		"required":["device","expectedPackage","target","action"],
		"properties":{
			"device":{
				"type":"string",
				"minLength":1,
				"maxLength":255,
				"pattern":"^[A-Za-z0-9][A-Za-z0-9._:%+\\-\\[\\]]{0,254}$"
			},
			"expectedPackage":{
				"type":"string",
				"minLength":3,
				"maxLength":255,
				"pattern":"^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$"
			},
			"target":{
				"type":"object",
				"properties":{
					"role":{"type":"string","minLength":1,"maxLength":128},
					"label":{"type":"string","minLength":1,"maxLength":256}
				},
				"additionalProperties":false,
				"anyOf":[
					{"required":["role"]},
					{"required":["label"]}
				]
			},
			"action":{
				"oneOf":[
					{
						"type":"object",
						"required":["type"],
						"properties":{"type":{"const":"tap"}},
						"additionalProperties":false
					},
					{
						"type":"object",
						"required":["type"],
						"properties":{
							"type":{"const":"long-click"},
							"durationMs":{"type":"integer","minimum":1,"maximum":10000}
						},
						"additionalProperties":false
					},
					{
						"type":"object",
						"required":["type","direction"],
						"properties":{
							"type":{"const":"swipe"},
							"direction":{"enum":["up","down","left","right"]},
							"durationMs":{"type":"integer","minimum":1,"maximum":10000}
						},
						"additionalProperties":false
					}
				]
			}
		},
		"additionalProperties":false
	}`)
}

func desktopAttestedActionOutputSchema() map[string]any {
	return strictVisualSchema(`{
		"type":"object",
		"required":["succeeded","verified","commitStatus","actionCommits"],
		"properties":{
			"succeeded":{"type":"boolean"},
			"observationId":{"type":"string","format":"uuid"},
			"deviceFingerprint":{"type":"string","pattern":"^[0-9a-f]{64}$"},
			"screenFingerprint":{"type":"string","pattern":"^[0-9a-f]{64}$"},
			"route":{"type":"string","minLength":1,"maxLength":64},
			"verified":{"type":"boolean"},
			"commitStatus":{"enum":["not_committed","committed","unknown"]},
			"actionCommits":{
				"oneOf":[
					{"type":"integer","minimum":0,"maximum":1},
					{"type":"null"}
				]
			},
			"errorCode":{"type":"string","minLength":1,"maxLength":128}
		},
		"additionalProperties":false
	}`)
}
