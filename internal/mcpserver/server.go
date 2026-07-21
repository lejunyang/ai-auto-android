package mcpserver

// 功能用途：本文件注册五个类型化 MCP 工具，并把调用映射到共享自动化服务。

import (
	"context"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ToolDevicesList 等常量是对 MCP 客户端稳定公开的工具名称。
const (
	ToolDevicesList     = "android_devices_list"
	ToolDeviceGet       = "android_device_get"
	ToolObserve         = "android_observe"
	ToolActionExecute   = "android_action_execute"
	ToolRecordingReplay = "android_recording_replay"

	observeKindRecordings = "recordings"
)

// ToolNames 列出服务器允许暴露的完整工具集合。
var ToolNames = []string{
	ToolDevicesList,
	ToolDeviceGet,
	ToolObserve,
	ToolActionExecute,
	ToolRecordingReplay,
}

type devicesListInput struct{}

type deviceGetInput struct {
	Device string `json:"device"`
}

type observeInput struct {
	Device        string `json:"device"`
	Kind          string `json:"kind"`
	TargetPackage string `json:"targetPackage,omitempty"`
	MaxDepth      int    `json:"maxDepth,omitempty"`
}

type observeOutput struct {
	Device     string                      `json:"device"`
	Kind       string                      `json:"kind"`
	Format     string                      `json:"format"`
	SizeBytes  int                         `json:"sizeBytes,omitempty"`
	SHA256     string                      `json:"sha256,omitempty"`
	XML        string                      `json:"xml,omitempty"`
	Snapshot   *service.SemanticSnapshot   `json:"snapshot,omitempty"`
	Recordings *[]service.RecordingSummary `json:"recordings,omitempty"`
	Count      *int                        `json:"count,omitempty"`
}

type actionInput struct {
	Device     string `json:"device"`
	Action     string `json:"action"`
	X          int    `json:"x,omitempty"`
	Y          int    `json:"y,omitempty"`
	StartX     int    `json:"startX,omitempty"`
	StartY     int    `json:"startY,omitempty"`
	EndX       int    `json:"endX,omitempty"`
	EndY       int    `json:"endY,omitempty"`
	DurationMS int    `json:"durationMs,omitempty"`
	Text       string `json:"text,omitempty"`
	Key        string `json:"key,omitempty"`
	Package    string `json:"package,omitempty"`
	Activity   string `json:"activity,omitempty"`
}

type recordingReplayInput struct {
	Device   string `json:"device"`
	ScriptID string `json:"scriptId"`
}

// New 创建只暴露设备查询、观察、低风险动作和受确认保护回放的 MCP 服务器。
func New(automation service.Automation, version string) *mcp.Server {
	server := mcp.NewServer(
		&mcp.Implementation{Name: "aactl", Version: version},
		&mcp.ServerOptions{
			Capabilities: &mcp.ServerCapabilities{
				Tools: &mcp.ToolCapabilities{ListChanged: false},
			},
		},
	)

	mcp.AddTool(server, &mcp.Tool{
		Name:        ToolDevicesList,
		Description: "List normalized Android devices visible to ADB. This tool never pairs or changes device trust.",
		InputSchema: devicesListSchema(),
		Annotations: readOnlyAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		_ devicesListInput,
	) (*mcp.CallToolResult, service.DeviceListResult, error) {
		result, err := automation.ListDevices(ctx)
		return nil, result, err
	})

	mcp.AddTool(server, &mcp.Tool{
		Name:        ToolDeviceGet,
		Description: "Get normalized details for one explicitly selected Android device.",
		InputSchema: deviceGetSchema(),
		Annotations: readOnlyAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input deviceGetInput,
	) (*mcp.CallToolResult, protocol.Device, error) {
		result, err := automation.GetDevice(ctx, input.Device)
		return nil, result, err
	})

	mcp.AddTool(server, &mcp.Tool{
		Name:         ToolObserve,
		Description:  "Observe one explicitly selected Android device using a PNG screenshot, UIAutomator hierarchy, or an established App bridge semantic snapshot.",
		InputSchema:  observeSchema(),
		OutputSchema: observeOutputSchema(),
		Annotations:  readOnlyAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input observeInput,
	) (*mcp.CallToolResult, observeOutput, error) {
		if input.Kind == observeKindRecordings {
			result, err := automation.ListRecordings(ctx, input.Device)
			if err != nil {
				return nil, observeOutput{}, err
			}
			recordings := result.Recordings
			count := result.Count
			return nil, observeOutput{
				Device:     result.Device,
				Kind:       observeKindRecordings,
				Format:     "recording-list",
				Recordings: &recordings,
				Count:      &count,
			}, nil
		}
		result, err := automation.Observe(ctx, service.ObserveRequest{
			Device:        input.Device,
			Kind:          input.Kind,
			TargetPackage: input.TargetPackage,
			MaxDepth:      input.MaxDepth,
		})
		if err != nil {
			return nil, observeOutput{}, err
		}
		output := observeOutput{
			Device:    result.Device,
			Kind:      result.Kind,
			Format:    result.Format,
			SizeBytes: result.SizeBytes,
			SHA256:    result.SHA256,
			XML:       result.XML,
			Snapshot:  result.Snapshot,
		}
		if result.Kind != service.ObserveScreenshot {
			return nil, output, nil
		}
		return &mcp.CallToolResult{
			Content: []mcp.Content{
				&mcp.ImageContent{Data: result.Image, MIMEType: "image/png"},
			},
		}, output, nil
	})

	mcp.AddTool(server, &mcp.Tool{
		Name:        ToolActionExecute,
		Description: "Execute one low-risk typed ADB action on an explicitly selected Android device. The server enforces a model-action allowlist and rejects destructive or high-risk actions.",
		InputSchema: actionSchema(),
		Annotations: actionAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input actionInput,
	) (*mcp.CallToolResult, adb.ActionResult, error) {
		if err := requireModelActionAllowed(input.Action); err != nil {
			return nil, adb.ActionResult{}, err
		}
		result, err := automation.ExecuteAction(ctx, service.ActionRequest{
			Device:     input.Device,
			Action:     input.Action,
			X:          input.X,
			Y:          input.Y,
			StartX:     input.StartX,
			StartY:     input.StartY,
			EndX:       input.EndX,
			EndY:       input.EndY,
			DurationMS: input.DurationMS,
			Text:       input.Text,
			Key:        input.Key,
			Package:    input.Package,
			Activity:   input.Activity,
		})
		return nil, result, err
	})

	mcp.AddTool(server, &mcp.Tool{
		Name:        ToolRecordingReplay,
		Description: "Request replay of one stored semantic recording. This MVP always returns CONFIRMATION_REQUIRED because a model cannot provide a trustworthy human confirmation credential.",
		InputSchema: recordingReplaySchema(),
		Annotations: replayAnnotations(),
	}, func(
		_ context.Context,
		_ *mcp.CallToolRequest,
		_ recordingReplayInput,
	) (*mcp.CallToolResult, service.ReplayRecordingResult, error) {
		return nil, service.ReplayRecordingResult{}, requireReplayConfirmation()
	})

	return server
}

// Run 在指定传输上运行 MCP 服务器，直到上下文取消或传输失败。
func Run(
	ctx context.Context,
	automation service.Automation,
	version string,
	transport mcp.Transport,
) error {
	return New(automation, version).Run(ctx, transport)
}

func readOnlyAnnotations() *mcp.ToolAnnotations {
	closedWorld := false
	return &mcp.ToolAnnotations{
		ReadOnlyHint:  true,
		OpenWorldHint: &closedWorld,
	}
}

func actionAnnotations() *mcp.ToolAnnotations {
	destructive := true
	closedWorld := false
	return &mcp.ToolAnnotations{
		DestructiveHint: &destructive,
		OpenWorldHint:   &closedWorld,
	}
}

func replayAnnotations() *mcp.ToolAnnotations {
	destructive := true
	closedWorld := false
	return &mcp.ToolAnnotations{
		DestructiveHint: &destructive,
		OpenWorldHint:   &closedWorld,
	}
}
