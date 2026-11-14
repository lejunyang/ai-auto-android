package mcpserver

// 功能用途：为虚拟屏管理建立独立风险门；部署与进程启停始终只由操作员经 CLI 执行。
// 管理会分配显示资源或销毁其上内容，与指针动作、语义桥及只新增文件传输的准入条件不同。
// 本门只接受列出、状态、有界创建和关闭；尺寸及最多两块的限制继续由真实 helper 强制。

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ToolDisplayList 等常量只开放屏幕管理，不提供辅助进程启停或部署路径。
const (
	ToolDisplayList   = "android_display_list"
	ToolDisplayStatus = "android_display_status"
	ToolDisplayCreate = "android_display_create"
	ToolDisplayClose  = "android_display_close"
)

type displayManagementKind string

// requireDisplayManagementAllowed 与三道既有风险门并列，不改变它们的名单与准入标准。
func requireDisplayManagementAllowed(kind displayManagementKind) error {
	switch kind {
	case "list", "status", "create", "close":
		return nil
	}
	return modelToolError{Code: apperr.CodeActionNotAllowed, Message: "Only list, status, bounded create and close are allowed; helper start/stop require the operator CLI.", Retryable: false}
}

type displayManagementInput struct {
	Device    string `json:"device"`
	Width     int    `json:"width,omitempty"`
	Height    int    `json:"height,omitempty"`
	DPI       int    `json:"dpi,omitempty"`
	DisplayID int    `json:"displayId,omitempty"`
}

// displayToolError 仅为新显示入口保留稳定错误码，省略目标的既有工具错误行为不变。
func displayToolError(err error) error {
	if err == nil {
		return nil
	}
	var app *apperr.Error
	if errors.As(err, &app) {
		message := app.Message
		if app.Code == apperr.CodeDisplaydNotRunning {
			message = "displayd is not running. An operator must first run aactl display start --device SERIAL."
		}
		return modelToolError{Code: app.Code, Message: message, Retryable: app.Retryable}
	}
	return err
}

func registerDisplayTools(server *mcp.Server, automation service.Automation) {
	for _, entry := range []struct{ name, command, description string }{
		{ToolDisplayList, "list", "List only helper-owned virtual displays. Requires the operator to run aactl display start first."},
		{ToolDisplayStatus, "status", "Read credential lock, virtual display group and keepalive status. Requires an operator-started helper."},
		{ToolDisplayCreate, "create", "Create a bounded virtual display (128..2048 pixels, 72..640 dpi; at most two). Flags and helper configuration are fixed; an operator must start the helper through CLI first."},
		{ToolDisplayClose, "close", "Close one helper-owned virtual display. This destroys application content on that display; it is not moved to the primary display. The primary display cannot be closed."},
	} {
		annotations := readOnlyAnnotations()
		if entry.command == "create" || entry.command == "close" {
			annotations = actionAnnotations()
		}
		mcp.AddTool(server, &mcp.Tool{Name: entry.name, Description: entry.description, InputSchema: displayManagementSchema(entry.command), OutputSchema: displayManagementOutputSchema(entry.command), Annotations: annotations}, func(ctx context.Context, _ *mcp.CallToolRequest, input displayManagementInput) (*mcp.CallToolResult, any, error) {
			if err := requireDisplayManagementAllowed(displayManagementKind(entry.command)); err != nil {
				return nil, nil, err
			}
			displays, ok := automation.(service.DisplayAutomation)
			if !ok {
				return nil, nil, modelToolError{Code: apperr.CodeDisplaydNotRunning, Message: "Display management is unavailable; an operator must run aactl display start --device SERIAL.", Retryable: false}
			}
			result, err := displays.ManageDisplay(ctx, service.DisplayRequest{Device: input.Device, Command: entry.command, Width: input.Width, Height: input.Height, DPI: input.DPI, DisplayID: input.DisplayID})
			return nil, result, displayToolError(err)
		})
	}
}

func displayIDSchema() string {
	return `{"type":"integer","minimum":0,"maximum":2147483647,"description":"0 explicitly selects the primary display without a helper; positive IDs must belong to the operator-started helper. Failure never falls back to primary."}`
}
func displayManagementSchema(command string) json.RawMessage {
	properties := `"device":` + deviceSchema()
	required := `"device"`
	if command == "create" {
		properties += `,"width":{"type":"integer","minimum":128,"maximum":2048},"height":{"type":"integer","minimum":128,"maximum":2048},"dpi":{"type":"integer","minimum":72,"maximum":640}`
		required += `,"width","height","dpi"`
	}
	if command == "close" {
		properties += `,"displayId":{"type":"integer","minimum":1,"maximum":2147483647}`
		required += `,"displayId"`
	}
	return objectSchema(`"required":[`+required+`]`, `"properties":{`+properties+`}`)
}
func displayManagementOutputSchema(command string) json.RawMessage {
	screenProperties := `"displayId":{"type":"integer","minimum":1},"width":{"type":"integer","minimum":128,"maximum":2048},"height":{"type":"integer","minimum":128,"maximum":2048},"dpi":{"type":"integer","minimum":72,"maximum":640}`
	screen := `{"type":"object","required":["displayId","width","height","dpi"],"properties":{` + screenProperties + `},"additionalProperties":false}`
	switch command {
	case "create":
		return json.RawMessage(screen)
	case "close":
		return objectSchema(`"required":["displayId","closed"]`, `"properties":{"displayId":{"type":"integer","minimum":1},"closed":{"const":true}}`)
	case "list":
		return objectSchema(`"required":["displays"]`, `"properties":{"displays":{"type":"array","maxItems":2,"items":`+screen+`}}`)
	default:
		statusScreen := `{"type":"object","required":["displayId","width","height","dpi","state","displayGroupId","groupAwake"],"properties":{` + screenProperties + `,"state":{"type":"integer"},"displayGroupId":{"type":"integer"},"groupAwake":{"type":["boolean","null"]}},"additionalProperties":false}`
		return objectSchema(`"required":["credentialLocked","displays","keepAwakeIntervalMs","lastActivityUptimeMs","keepAwakeError"]`, `"properties":{"credentialLocked":{"type":"boolean"},"displays":{"type":"array","maxItems":2,"items":`+statusScreen+`},"keepAwakeIntervalMs":{"const":2000},"lastActivityUptimeMs":{"type":"integer","minimum":0},"keepAwakeError":{"type":["string","null"]}}`)
	}
}
