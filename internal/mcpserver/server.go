package mcpserver

// 功能用途：本文件注册类型化 MCP 工具，并映射共享自动化、只读视觉和原子视觉动作。
// 工具集合以 ToolNames 为准，新增工具须同时登记到该列表。

import (
	"context"
	"encoding/json"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ToolDevicesList 等常量是对 MCP 客户端稳定公开的工具名称。
const (
	ToolDevicesList     = "android_devices_list"
	ToolDeviceGet       = "android_device_get"
	ToolObserve         = "android_observe"
	ToolActionExecute   = "android_action_execute"
	ToolRecordingReplay = "android_recording_replay"
	// ToolScreenRecord 单独成工具而非 observe 的一个 kind：录制会阻塞数秒到数分钟，
	// 与其余观察的即时返回语义差别过大，混在一起会让调用方无法合理设置超时。
	ToolScreenRecord = "android_screen_record"
	// ToolWait 与 ToolAssert 是自愈回路的判据执行。分成两个工具而非一个带 mode 参数：
	// 二者失败语义相反（wait 返回 satisfied=false，assert 报错），
	// 合成一个会让模型难以预判调用失败与否。
	ToolWait   = "android_wait"
	ToolAssert = "android_assert"
	// ToolBridgeSetText 是唯一能写入中文等非 ASCII 文本的工具。
	// 与 android_action_execute 的 ui.setText 并存而非替代：后者走 adb input text，
	// 只接受 ASCII；本工具走 App Bridge 的无障碍 setText，接受任意 Unicode，
	// 但要求桥已连接。模型应按文本内容与桥状态选择。
	ToolBridgeSetText = "android_bridge_set_text"

	// ToolImeShow 聚焦当前输入控件。名字用 ime_show 与协议动作对齐，
	// 但语义是「聚焦」而非「显示键盘」，见工具 description。
	ToolImeShow = "android_ime_show"

	observeKindRecordings = "recordings"
	observeKindForeground = "foreground"
	observeKindApps       = "apps"
	// observeKindWindows 与 observeKindMessages 走桥：窗口枚举与 Toast 捕获
	// 都依赖无障碍事件流，直连通道拿不到。
	observeKindWindows  = "windows"
	observeKindMessages = "transientMessages"
)

// ToolNames 列出服务器允许暴露的完整工具集合。
var ToolNames = []string{
	ToolDevicesList,
	ToolDeviceGet,
	ToolObserve,
	ToolVisualTargetPropose,
	ToolVisualActionExecute,
	ToolActionExecute,
	ToolBridgeAction,
	ToolRecordingReplay,
	ToolScreenRecord,
	ToolWait,
	ToolAssert,
	ToolBridgeSetText,
	ToolImeShow,
	ToolFilePush,
	ToolFilePull,
}

type devicesListInput struct{}

type deviceGetInput struct {
	Device string `json:"device"`
}

// imeShowInput 只需要设备：聚焦的是系统当前输入焦点，不接受选择器。
type imeShowInput struct {
	Device string `json:"device"`
}

// bridgeImeShowOutput 只回执行路径。
//
// 不含任何键盘可见性字段——那是无障碍无法验证的事实，声明即是虚假承诺。
type bridgeImeShowOutput struct {
	Route string `json:"route,omitempty"`
}

type observeInput struct {
	Device        string `json:"device"`
	Kind          string `json:"kind"`
	TargetPackage string `json:"targetPackage,omitempty"`
	MaxDepth      int    `json:"maxDepth,omitempty"`
	IncludeSystem bool   `json:"includeSystem,omitempty"`
	WithDetails   bool   `json:"withDetails,omitempty"`
	// ToastsOnly 只在 kind=transientMessages 时有意义：Toast 几乎总是当前操作的
	// 直接反馈，而通知可能来自任何后台应用，混在一起会让判据不可靠。
	ToastsOnly bool `json:"toastsOnly,omitempty"`
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
	// Foreground 与 Apps 只在对应 kind 下出现，用指针以便省略而非返回空值，
	// 避免调用方把"字段存在但为空"误读成"查到了但结果为空"。
	Foreground      *foregroundPayload  `json:"foreground,omitempty"`
	Apps            *[]adb.InstalledApp `json:"apps,omitempty"`
	IncludesSystem  *bool               `json:"includesSystem,omitempty"`
	DetailsIncluded *bool               `json:"detailsIncluded,omitempty"`
	// Windows 与 Messages 原样透传 App 侧应答：窗口的 layer/active/focused 与
	// 消息的 eventTimeMs 都是调用方判断所必需的，二次封装容易丢字段。
	Windows  json.RawMessage `json:"windows,omitempty"`
	Messages json.RawMessage `json:"messages,omitempty"`
}

// foregroundPayload 是前台观察的返回体。
// 不直接复用 service.ForegroundResult：那里带 Device 字段，
// 而 observeOutput 顶层已有 Device，重复会让 schema 出现两个同义字段。
type foregroundPayload struct {
	Package  string `json:"package"`
	Activity string `json:"activity,omitempty"`
	Source   string `json:"source"`
}

type screenRecordInput struct {
	Device  string `json:"device"`
	Seconds int    `json:"seconds"`
	Width   int    `json:"width,omitempty"`
	Height  int    `json:"height,omitempty"`
	BitRate int    `json:"bitRate,omitempty"`
}

// screenRecordOutput 只回摘要，不含视频字节，理由见 screenRecordOutputSchema。
type screenRecordOutput struct {
	Device      string `json:"device"`
	Format      string `json:"format"`
	SizeBytes   int    `json:"sizeBytes"`
	SHA256      string `json:"sha256"`
	DurationSec int    `json:"durationSeconds"`
	Width       int    `json:"width,omitempty"`
	Height      int    `json:"height,omitempty"`
	BitRate     int    `json:"bitRate,omitempty"`
}

// bridgeSetTextInput 是桥侧文本输入的入参。
// 没有字符集限制，理由见 bridgeSetTextSchema。
type bridgeSetTextInput struct {
	Device      string `json:"device"`
	Text        string `json:"text"`
	Package     string `json:"package,omitempty"`
	ResourceID  string `json:"resourceId,omitempty"`
	Description string `json:"description,omitempty"`
	MatchText   string `json:"matchText,omitempty"`
}

// bridgeSetTextOutput 是桥侧文本写入的结果。
//
// 显式声明 App 侧的匹配证据而不是回不透明的 JSON：route 说明走了哪条执行路径，
// matchedPath 是命中节点在树中的位置，matchScore 是匹配确定度。
// 调用方据此判断文本是否落到了预期元素上——只回一个成功标志无法区分
// "写对了" 与 "写进了另一个同类输入框"。
type bridgeSetTextOutput struct {
	Route       string  `json:"route,omitempty"`
	MatchedPath []int   `json:"matchedPath,omitempty"`
	MatchScore  float64 `json:"matchScore,omitempty"`
}

// predicateInput 是等待与断言共用的入参。
// 不含任何坐标字段，理由见 predicateSchema 注释。
type predicateInput struct {
	Device           string `json:"device"`
	Kind             string `json:"kind"`
	Operator         string `json:"operator"`
	ResourceID       string `json:"resourceId,omitempty"`
	Text             string `json:"text,omitempty"`
	Description      string `json:"description,omitempty"`
	ClassName        string `json:"className,omitempty"`
	Package          string `json:"package,omitempty"`
	Expected         string `json:"expected,omitempty"`
	TimeoutMS        int    `json:"timeoutMs,omitempty"`
	StableDurationMS int    `json:"stableDurationMs,omitempty"`
}

// toRequest 把 MCP 入参转换为服务层请求。
func (input predicateInput) toRequest() service.PredicateRequest {
	return service.PredicateRequest{
		Device:           input.Device,
		Kind:             input.Kind,
		Operator:         input.Operator,
		ResourceID:       input.ResourceID,
		Text:             input.Text,
		Description:      input.Description,
		ClassName:        input.ClassName,
		Package:          input.Package,
		Expected:         input.Expected,
		TimeoutMS:        input.TimeoutMS,
		StableDurationMS: input.StableDurationMS,
	}
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
	// Direction 与 Amount 仅 ui.scroll 使用；Amount 是屏幕占比而非像素。
	Direction string  `json:"direction,omitempty"`
	Amount    float64 `json:"amount,omitempty"`
}

type recordingReplayInput struct {
	Device   string `json:"device"`
	ScriptID string `json:"scriptId"`
}

// New 创建只暴露设备查询、观察、低风险动作和受确认保护回放的 MCP 服务器。
func New(automation service.Automation, version string) *mcp.Server {
	return newServer(automation, version, nil)
}

func newServer(
	automation service.Automation,
	version string,
	desktopVisual *desktopVisualRuntime,
	attestedVisual ...desktopAttestedExecutor,
) *mcp.Server {
	server := mcp.NewServer(
		&mcp.Implementation{Name: "aactl", Version: version},
		&mcp.ServerOptions{
			Capabilities: &mcp.ServerCapabilities{
				Tools: &mcp.ToolCapabilities{ListChanged: false},
			},
		},
	)
	addDesktopVisualTool(server, desktopVisual)
	var attestedExecutor desktopAttestedExecutor
	if len(attestedVisual) > 0 {
		attestedExecutor = attestedVisual[0]
	}
	addDesktopAttestedActionTool(server, attestedExecutor)
	addBridgeActionTool(server, automation)

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
		if input.Kind == observeKindForeground {
			result, err := automation.Foreground(ctx, service.ForegroundRequest{
				Device: input.Device,
			})
			if err != nil {
				return nil, observeOutput{}, err
			}
			return nil, observeOutput{
				Device: result.Device,
				Kind:   observeKindForeground,
				Format: "foreground",
				Foreground: &foregroundPayload{
					Package:  result.Package,
					Activity: result.Activity,
					Source:   result.Source,
				},
			}, nil
		}
		if input.Kind == observeKindApps {
			result, err := automation.Apps(ctx, service.AppsRequest{
				Device:        input.Device,
				IncludeSystem: input.IncludeSystem,
				WithDetails:   input.WithDetails,
			})
			if err != nil {
				return nil, observeOutput{}, err
			}
			apps := result.Apps
			count := result.Count
			includesSystem := result.IncludesSystem
			detailsIncluded := result.DetailsIncluded
			return nil, observeOutput{
				Device:          result.Device,
				Kind:            observeKindApps,
				Format:          "app-list",
				Apps:            &apps,
				Count:           &count,
				IncludesSystem:  &includesSystem,
				DetailsIncluded: &detailsIncluded,
			}, nil
		}
		if input.Kind == observeKindWindows {
			raw, err := automation.BridgeWindows(
				ctx, input.Device, input.TargetPackage, input.MaxDepth,
			)
			if err != nil {
				return nil, observeOutput{}, err
			}
			return nil, observeOutput{
				Device:  input.Device,
				Kind:    observeKindWindows,
				Format:  "window-list",
				Windows: raw,
			}, nil
		}
		if input.Kind == observeKindMessages {
			raw, err := automation.BridgeTransientMessages(
				ctx, input.Device, input.ToastsOnly,
			)
			if err != nil {
				return nil, observeOutput{}, err
			}
			return nil, observeOutput{
				Device:   input.Device,
				Kind:     observeKindMessages,
				Format:   "transient-message-list",
				Messages: raw,
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
			Direction:  input.Direction,
			Amount:     input.Amount,
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

	mcp.AddTool(server, &mcp.Tool{
		Name: ToolScreenRecord,
		Description: "Record the screen of one explicitly selected Android device for a bounded duration " +
			"and return only a digest. The video itself is not returned over MCP because a single " +
			"recording can exceed client message limits.",
		InputSchema:  screenRecordSchema(),
		OutputSchema: screenRecordOutputSchema(),
		Annotations:  readOnlyAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input screenRecordInput,
	) (*mcp.CallToolResult, screenRecordOutput, error) {
		result, err := automation.Record(ctx, service.RecordingRequest{
			Device:  input.Device,
			Seconds: input.Seconds,
			Width:   input.Width,
			Height:  input.Height,
			BitRate: input.BitRate,
		})
		if err != nil {
			return nil, screenRecordOutput{}, err
		}
		return nil, screenRecordOutput{
			Device:      result.Device,
			Format:      result.Format,
			SizeBytes:   result.SizeBytes,
			SHA256:      result.SHA256,
			DurationSec: result.DurationSec,
			Width:       result.Width,
			Height:      result.Height,
			BitRate:     result.BitRate,
		}, nil
	})

	mcp.AddTool(server, &mcp.Tool{
		Name: ToolWait,
		Description: "Wait until an interface predicate holds on one explicitly selected Android device. " +
			"Returns satisfied=false on timeout instead of failing, so the caller can try another path. " +
			"Predicates match by describable attributes only; no coordinates are accepted.",
		InputSchema:  predicateSchema(),
		OutputSchema: predicateOutputSchema(),
		Annotations:  readOnlyAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input predicateInput,
	) (*mcp.CallToolResult, service.PredicateOutcome, error) {
		result, err := automation.Wait(ctx, input.toRequest())
		if err != nil {
			return nil, service.PredicateOutcome{}, err
		}
		return nil, result, nil
	})

	mcp.AddTool(server, &mcp.Tool{
		Name: ToolAssert,
		Description: "Assert that an interface predicate holds on one explicitly selected Android device. " +
			"Fails with the observed state attached when the predicate does not hold before the timeout, " +
			"so the caller can correct course. Predicates match by describable attributes only.",
		InputSchema:  predicateSchema(),
		OutputSchema: predicateOutputSchema(),
		Annotations:  readOnlyAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input predicateInput,
	) (*mcp.CallToolResult, service.PredicateOutcome, error) {
		result, err := automation.Assert(ctx, input.toRequest())
		if err != nil {
			return nil, service.PredicateOutcome{}, err
		}
		return nil, result, nil
	})

	mcp.AddTool(server, &mcp.Tool{
		Name: ToolBridgeSetText,
		Description: "Write text into one element through the App Bridge accessibility service. " +
			"Unlike android_action_execute with ui.setText, this accepts any Unicode including " +
			"Chinese, because it does not go through the ADB input text shell escaping. " +
			"Requires an established bridge session. Locate the element by resourceId " +
			"(most reliable), description, or matchText.",
		InputSchema:  bridgeSetTextSchema(),
		Annotations:  actionAnnotations(),
		OutputSchema: bridgeSetTextOutputSchema(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input bridgeSetTextInput,
	) (*mcp.CallToolResult, bridgeSetTextOutput, error) {
		raw, err := automation.BridgeSetText(ctx, service.BridgeTextRequest{
			Device:      input.Device,
			Text:        input.Text,
			Package:     input.Package,
			ResourceID:  input.ResourceID,
			Description: input.Description,
			MatchText:   input.MatchText,
		})
		if err != nil {
			return nil, bridgeSetTextOutput{}, err
		}
		var output bridgeSetTextOutput
		// App 侧应答的字段集合由桥协议决定，可能多于这里声明的三个；
		// 解析失败不应让已经成功的写入变成错误，因此忽略解析错误只丢证据。
		_ = json.Unmarshal(raw, &output)
		return nil, output, nil
	})

	mcp.AddTool(server, &mcp.Tool{
		Name: ToolImeShow,
		Description: "Move input focus to the currently focused editable control on one " +
			"explicitly selected Android device. This focuses a control; it is not a keyboard " +
			"visibility switch. The soft keyboard usually appears once a control takes focus, " +
			"but that depends on the input method and cannot be queried or guaranteed. " +
			"A success result means the control was focused, not that a keyboard is visible. " +
			"Returns ACTION_NOT_SUPPORTED when no editable control can be focused.",
		InputSchema:  imeShowSchema(),
		OutputSchema: bridgeImeShowOutputSchema(),
		Annotations:  actionAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input imeShowInput,
	) (*mcp.CallToolResult, bridgeImeShowOutput, error) {
		// ime.show 只改变焦点归属，不改变应用生命周期也不写入数据，
		// 符合 risk.go 白名单注释的既有标准，因此走同一道门而非独立门。
		if err := requireModelActionAllowed(service.ActionImeShow); err != nil {
			return nil, bridgeImeShowOutput{}, err
		}
		raw, err := automation.BridgeImeShow(ctx, input.Device)
		if err != nil {
			return nil, bridgeImeShowOutput{}, err
		}
		var output bridgeImeShowOutput
		// 与 BridgeSetText 同理：证据解析失败不应让已成功的聚焦变成错误。
		_ = json.Unmarshal(raw, &output)
		return nil, output, nil
	})

	registerFileTransferTools(server, automation)

	return server
}

// Run 在指定传输上运行 MCP 服务器，直到上下文取消或传输失败。
func Run(
	ctx context.Context,
	automation service.Automation,
	version string,
	transport mcp.Transport,
	desktopVisual *visual.DesktopProposalService,
	attestedVisual *visual.DesktopAttestedActionService,
) error {
	runtime := newDesktopVisualRuntime(desktopVisual)
	defer runtime.close()
	return newServer(automation, version, runtime, attestedVisual).Run(
		ctx,
		runtime.wrapTransport(transport),
	)
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
