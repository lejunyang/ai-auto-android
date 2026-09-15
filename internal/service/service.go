// Package service 为 CLI 与 MCP 提供共享的类型化设备自动化语义。
package service

import (
	"context"
	"encoding/json"
	"regexp"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

// ObserveScreenshot 等常量定义共享服务支持的观察类型和直接动作白名单。
const (
	ObserveScreenshot = "screenshot"
	ObserveHierarchy  = "hierarchy"
	ObserveSemantic   = "semantic"

	ActionTap      = "ui.tap"
	ActionSwipe    = "ui.swipe"
	ActionSetText  = "ui.setText"
	ActionPressKey = "ui.pressKey"
	ActionLaunch   = "app.launch"
	ActionStop     = "app.stop"
	// ActionLongClick 与 ActionScroll 由 ADB input swipe 合成，
	// 因为 `adb shell input` 没有独立的长按或滚动命令。
	ActionLongClick = "ui.longClick"
	ActionScroll    = "ui.scroll"

	// 以下为动作面扩展新增的动作名。
	//
	// 通道归属不是任意选择，而是由平台能力决定的：
	//
	// 直连（ADB）可完成：drag 走 `input draganddrop`，连击与路径手势用
	// `input motionevent` 逐点合成，屏幕方向用 `settings put system`，
	// 唤醒解锁用 `input keyevent`，亮屏检测读 `dumpsys display`，
	// IME 切换用 `ime set`，deeplink 用 `am start`，
	// 文件传输用 `adb push`/`pull`。
	//
	// 只能走 App Bridge（无障碍）：
	// - 多指手势（pinch / rotate / twoFingerScroll）。实测确认
	//   `input motionevent` 只接受一个坐标对且静默忽略多余参数，
	//   直连通道无法合成多指，只有无障碍的多 stroke dispatchGesture 可以。
	// - 需要语义节点定位的能力（scrollIntoView、文本选区、复制粘贴、
	//   扩展节点动作、进度设值、按位置滚动）。直连通道拿不到稳定的元素引用。
	// - 剪贴板读写。设备上不存在 `cmd clipboard` shell 命令（实测返回
	//   "No shell command implementation"），只能经 ClipboardManager。
	//
	// 这里只声明已经接线到 ExecuteAction 的动作名：提前声明一批没有分派的
	// 常量会让「白名单」失去意义，也会让调用方以为动作已可用。
	ActionDrag        = "ui.drag"
	ActionMultiTap    = "ui.multiTap"
	ActionPathGesture = "ui.pathGesture"

	ActionDeviceRotate = "device.rotate"
	ActionDeviceWake   = "device.wake"
	ActionDeviceUnlock = "device.unlock"

	ActionImeHide   = "ime.hide"
	ActionImeSelect = "ime.select"

	ActionDeeplink = "app.deeplink"
	ActionFilePush = "file.push"
	ActionFilePull = "file.pull"

	defaultSwipeDurationMS = 300
	defaultSnapshotDepth   = 64
	// defaultLongPressMS 取 600ms：高于 Android 默认长按阈值 500ms 并留出余量，
	// 各 ROM 的阈值有差异，贴着 500 会在部分设备上退化成普通点击。
	defaultLongPressMS = 600
	// defaultScrollFraction 取 0.5：滚动半屏，既有明显位移又不至于跨过整页内容。
	defaultScrollFraction = 0.5
	// defaultDragMoveMS 只覆盖移动阶段。按住阶段不在此设默认值：
	// `input draganddrop` 用运行设备真实的 ViewConfiguration 长按阈值，
	// 由设备决定比我们猜一个常量更准。
	defaultDragMoveMS = 500
	// defaultPathGestureMS 与 Android 侧 CompositeGesturePlanner 的默认值对齐，
	// 两侧对同一个动作必须有同一套时序定义。
	defaultPathGestureMS = 800
	defaultMultiTaps     = 2
)

var scriptIDPattern = regexp.MustCompile(
	`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$`,
)

// DirectBackend 是自动化客户端可使用的安全、类型化 ADB 子集。
type DirectBackend interface {
	Devices(context.Context) ([]protocol.Device, error)
	DeviceInfo(context.Context, string) (protocol.Device, error)
	Screenshot(context.Context, string) (adb.Screenshot, error)
	Hierarchy(context.Context, string) (adb.Hierarchy, error)
	Tap(context.Context, string, int, int) (adb.ActionResult, error)
	Swipe(context.Context, string, int, int, int, int, int) (adb.ActionResult, error)
	Text(context.Context, string, string) (adb.ActionResult, error)
	Key(context.Context, string, string) (adb.ActionResult, error)
	Launch(context.Context, string, string, string) (adb.ActionResult, error)
	Stop(context.Context, string, string) (adb.ActionResult, error)
	// LongPress 与 Scroll 由 input swipe 合成，见 internal/adb/gesture.go。
	LongPress(context.Context, string, int, int, int) (adb.ActionResult, error)
	Scroll(context.Context, string, adb.ScrollDirection, float64) (adb.ActionResult, error)
	// Drag、MultiTap 与 PathGesture 见 internal/adb/composite_gesture.go。
	// 多指手势不在这里：实测确认 `input motionevent` 只接受一个坐标对，
	// 直连通道无法合成多指，那些能力只存在于 BridgeBackend。
	Drag(context.Context, string, adb.Point, adb.Point, int) (adb.ActionResult, error)
	MultiTap(context.Context, string, adb.Point, int) (adb.ActionResult, error)
	PathGesture(context.Context, string, []adb.Point, int) (adb.ActionResult, error)
	// 设备状态、IME、deeplink 与文件传输见 internal/adb/device_state.go。
	// 剪贴板不在这里：设备上没有对应的 shell 命令，只能走 BridgeBackend。
	Rotate(context.Context, string, adb.Orientation) (adb.ActionResult, error)
	Wake(context.Context, string) (adb.ActionResult, error)
	Unlock(context.Context, string) (adb.ActionResult, error)
	ScreenState(context.Context, string) (adb.ScreenState, error)
	ListInputMethods(context.Context, string) ([]string, error)
	SelectInputMethod(context.Context, string, string) (adb.ActionResult, error)
	HideKeyboard(context.Context, string) (adb.ActionResult, error)
	OpenDeeplink(context.Context, string, string, string) (adb.ActionResult, error)
	PushFile(context.Context, string, string, string) (adb.FileTransfer, error)
	PullFile(context.Context, string, string, string) (adb.FileTransfer, error)
}

// BridgeBackend 只包含已建立会话后的操作；配对和信任管理有意留在接口之外。
type BridgeBackend interface {
	Snapshot(context.Context, string, string, int) (json.RawMessage, error)
	// Foreground 读活动界面的包名与类名，不返回节点树。
	Foreground(context.Context, string, string) (json.RawMessage, error)
	// SetText 走无障碍写入任意 Unicode，绕开 ADB input text 的 ASCII 限制。
	SetText(context.Context, string, bridge.TextTarget, string) (json.RawMessage, error)
	Action(context.Context, string, json.RawMessage) (json.RawMessage, error)
	ListRecordings(context.Context, string) (json.RawMessage, error)
	Replay(context.Context, string, string) (json.RawMessage, error)
}

// Automation 是 CLI 与 MCP 适配器共享的完整服务契约。
type Automation interface {
	ListDevices(context.Context) (DeviceListResult, error)
	GetDevice(context.Context, string) (protocol.Device, error)
	Observe(context.Context, ObserveRequest) (ObserveResult, error)
	ExecuteAction(context.Context, ActionRequest) (adb.ActionResult, error)
	ExecuteBridgeAction(context.Context, string, json.RawMessage) (json.RawMessage, error)
	ListRecordings(context.Context, string) (RecordingListResult, error)
	ReplayRecording(context.Context, ReplayRecordingRequest) (ReplayRecordingResult, error)
	// Foreground、Apps 与 Record 是 UI 自动化自愈与取证所需的观察能力。
	// 与上面几个方法一样由 CLI、MCP 共同使用，保持三侧能力面一致。
	Foreground(context.Context, ForegroundRequest) (ForegroundResult, error)
	Apps(context.Context, AppsRequest) (AppsResult, error)
	Record(context.Context, RecordingRequest) (RecordingResult, error)
	// Wait 与 Assert 是自愈回路的判据执行：Wait 把未成立当正常结果，
	// Assert 视为错误。两者共用同一个求值器，只在失败语义上不同。
	Wait(context.Context, PredicateRequest) (PredicateOutcome, error)
	Assert(context.Context, PredicateRequest) (PredicateOutcome, error)
	// BridgeSetText 是唯一能写入中文等非 ASCII 文本的通道。
	BridgeSetText(context.Context, BridgeTextRequest) (json.RawMessage, error)
	// ScreenState、InputMethods 与文件传输是动作面扩展的观察与传输能力。
	// 它们不走 ExecuteAction：返回值不是 adb.ActionResult，
	// 硬塞进那个签名会让调用方拿不到实际结果（亮屏状态、输入法列表、传输字节数）。
	ScreenState(context.Context, string) (adb.ScreenState, error)
	InputMethods(context.Context, string) (InputMethodListResult, error)
	PushFile(context.Context, FileTransferRequest) (adb.FileTransfer, error)
	PullFile(context.Context, FileTransferRequest) (adb.FileTransfer, error)
}

// InputMethodListResult 返回设备上已启用的输入法与当前默认项。
type InputMethodListResult struct {
	Device string   `json:"device"`
	IDs    []string `json:"ids"`
	Count  int      `json:"count"`
}

// FileTransferRequest 描述一次文件推送或拉取。
type FileTransferRequest struct {
	Device     string `json:"device"`
	LocalPath  string `json:"localPath"`
	RemotePath string `json:"remotePath"`
}

// Service 在调用后端前集中执行设备、动作和 Bridge 响应校验。
type Service struct {
	direct DirectBackend
	bridge BridgeBackend
}

// DeviceListResult 返回规范化设备集合及对应计数。
type DeviceListResult struct {
	Devices []protocol.Device `json:"devices"`
	Count   int               `json:"count"`
}

// ObserveRequest 描述一次明确设备上的直接或语义观察。
type ObserveRequest struct {
	Device        string `json:"device"`
	Kind          string `json:"kind"`
	TargetPackage string `json:"targetPackage,omitempty"`
	MaxDepth      int    `json:"maxDepth,omitempty"`
}

// ObserveResult 统一表示截图、UIAutomator XML 或无障碍语义树。
type ObserveResult struct {
	Device    string            `json:"device"`
	Kind      string            `json:"kind"`
	Format    string            `json:"format"`
	SizeBytes int               `json:"sizeBytes,omitempty"`
	SHA256    string            `json:"sha256,omitempty"`
	XML       string            `json:"xml,omitempty"`
	Image     []byte            `json:"-"`
	Snapshot  *SemanticSnapshot `json:"snapshot,omitempty"`
}

// SemanticSnapshot 保存有深度上限的无障碍节点树。
type SemanticSnapshot struct {
	Root     SemanticNode `json:"root"`
	MaxDepth int          `json:"maxDepth"`
}

// SemanticNode 是可序列化且不持有平台节点对象的语义节点。
type SemanticNode struct {
	PackageName        string         `json:"packageName,omitempty"`
	ClassName          string         `json:"className,omitempty"`
	ResourceID         string         `json:"resourceId,omitempty"`
	Text               string         `json:"text,omitempty"`
	ContentDescription string         `json:"contentDescription,omitempty"`
	Bounds             Bounds         `json:"bounds"`
	Actions            []string       `json:"actions"`
	State              SemanticState  `json:"state"`
	Children           []SemanticNode `json:"children"`
}

// Bounds 表示节点在设备逻辑坐标系中的矩形范围。
type Bounds struct {
	Left   int `json:"left"`
	Top    int `json:"top"`
	Right  int `json:"right"`
	Bottom int `json:"bottom"`
}

// SemanticState 汇总节点可交互性、可见性和敏感状态。
type SemanticState struct {
	Checkable     bool `json:"checkable"`
	Checked       bool `json:"checked"`
	Clickable     bool `json:"clickable"`
	Enabled       bool `json:"enabled"`
	Editable      bool `json:"editable"`
	Focusable     bool `json:"focusable"`
	Focused       bool `json:"focused"`
	LongClickable bool `json:"longClickable"`
	Password      bool `json:"password"`
	Scrollable    bool `json:"scrollable"`
	Selected      bool `json:"selected"`
	VisibleToUser bool `json:"visibleToUser"`
	Sensitive     bool `json:"sensitive"`
}

// ActionRequest 描述一个白名单直接动作及其类型化参数。
type ActionRequest struct {
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
	// Direction 与 Amount 仅 ui.scroll 使用。
	// Amount 是屏幕占比而非像素：调用方不必先查分辨率，
	// 同一份脚本在不同尺寸设备上也能得到相当的滚动幅度。
	Direction string  `json:"direction,omitempty"`
	Amount    float64 `json:"amount,omitempty"`
	// Taps 仅 ui.multiTap 使用。
	Taps int `json:"taps,omitempty"`
	// Points 仅 ui.pathGesture 使用，是折线的完整点列。
	Points []adb.Point `json:"points,omitempty"`
	// Orientation 仅 device.rotate 使用，取 portrait / landscape / auto。
	Orientation string `json:"orientation,omitempty"`
	// InputMethod 仅 ime.select 使用，是 `包名/组件名` 形式的输入法标识。
	InputMethod string `json:"inputMethod,omitempty"`
	// URI 仅 app.deeplink 使用。
	URI string `json:"uri,omitempty"`
}

// RecordingListResult 返回经协议校验和脱敏的录制摘要。
type RecordingListResult struct {
	Device     string             `json:"device"`
	Recordings []RecordingSummary `json:"recordings"`
	Count      int                `json:"count"`
}

// RecordingSummary 只包含列举脚本所需的非秘密元数据。
type RecordingSummary struct {
	ID             string                `json:"id"`
	Name           string                `json:"name"`
	TargetPackages []string              `json:"targetPackages"`
	CreatedAt      string                `json:"createdAt"`
	StepCount      int                   `json:"stepCount"`
	Requirements   RecordingRequirements `json:"requirements"`
}

// RecordingRequirements 描述脚本回放需要的最低 API 与设备能力。
type RecordingRequirements struct {
	MinAPILevel  int      `json:"minApiLevel"`
	Capabilities []string `json:"capabilities"`
}

// ReplayRecordingRequest 标识明确设备和待回放脚本。
type ReplayRecordingRequest struct {
	Device   string `json:"device"`
	ScriptID string `json:"scriptId"`
}

// ReplayRecordingResult 汇总脚本回放状态、人工接管需求和步骤结果。
type ReplayRecordingResult struct {
	Device               string             `json:"device"`
	ScriptID             string             `json:"scriptId"`
	StartedAtMS          int64              `json:"startedAtMs"`
	FinishedAtMS         int64              `json:"finishedAtMs"`
	Succeeded            bool               `json:"succeeded"`
	RequiresIntervention bool               `json:"requiresIntervention"`
	Steps                []ReplayStepResult `json:"steps"`
}

// ReplayStepResult 记录单步尝试次数、执行路由、匹配分数和稳定错误。
type ReplayStepResult struct {
	StepID     string  `json:"stepId"`
	Status     string  `json:"status"`
	Attempts   int     `json:"attempts"`
	Route      string  `json:"route,omitempty"`
	MatchScore float64 `json:"matchScore,omitempty"`
	ErrorCode  string  `json:"errorCode,omitempty"`
	Message    string  `json:"message,omitempty"`
}

// New 使用可选直接后端和 Bridge 后端创建共享服务。
func New(direct DirectBackend, bridge BridgeBackend) *Service {
	return &Service{direct: direct, bridge: bridge}
}

// ListDevices 列出直接后端可见的规范化设备。
func (s *Service) ListDevices(ctx context.Context) (DeviceListResult, error) {
	if s.direct == nil {
		return DeviceListResult{}, unavailable("ADB device discovery")
	}
	devices, err := s.direct.Devices(ctx)
	if err != nil {
		return DeviceListResult{}, err
	}
	return DeviceListResult{Devices: devices, Count: len(devices)}, nil
}

// GetDevice 在校验序列号后读取一个明确设备的信息。
func (s *Service) GetDevice(ctx context.Context, device string) (protocol.Device, error) {
	if err := adb.ValidateSerial(device); err != nil {
		return protocol.Device{}, err
	}
	if s.direct == nil {
		return protocol.Device{}, unavailable("ADB device information")
	}
	return s.direct.DeviceInfo(ctx, device)
}

// Observe 按观察类型选择直接 ADB 或已建立 Bridge，并严格校验选项组合。
func (s *Service) Observe(ctx context.Context, request ObserveRequest) (ObserveResult, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return ObserveResult{}, err
	}
	switch request.Kind {
	case ObserveScreenshot:
		if request.TargetPackage != "" || request.MaxDepth != 0 {
			return ObserveResult{}, invalid("Screenshot observation does not accept semantic options.")
		}
		if s.direct == nil {
			return ObserveResult{}, unavailable("ADB screenshot observation")
		}
		screenshot, err := s.direct.Screenshot(ctx, request.Device)
		if err != nil {
			return ObserveResult{}, err
		}
		return ObserveResult{
			Device:    request.Device,
			Kind:      request.Kind,
			Format:    "png",
			SizeBytes: len(screenshot.Bytes),
			SHA256:    screenshot.SHA256,
			Image:     screenshot.Bytes,
		}, nil
	case ObserveHierarchy:
		if request.TargetPackage != "" || request.MaxDepth != 0 {
			return ObserveResult{}, invalid("Hierarchy observation does not accept semantic options.")
		}
		if s.direct == nil {
			return ObserveResult{}, unavailable("ADB hierarchy observation")
		}
		hierarchy, err := s.direct.Hierarchy(ctx, request.Device)
		if err != nil {
			return ObserveResult{}, err
		}
		return ObserveResult{
			Device:    request.Device,
			Kind:      request.Kind,
			Format:    "uiautomator-xml",
			SizeBytes: hierarchy.Bytes,
			SHA256:    hierarchy.SHA256,
			XML:       hierarchy.XML,
		}, nil
	case ObserveSemantic:
		if request.TargetPackage != "" {
			if err := adb.ValidatePackageName(request.TargetPackage); err != nil {
				return ObserveResult{}, err
			}
		}
		maxDepth := request.MaxDepth
		if maxDepth == 0 {
			maxDepth = defaultSnapshotDepth
		}
		if maxDepth < 1 || maxDepth > 100 {
			return ObserveResult{}, invalid("maxDepth must be between 1 and 100.")
		}
		if s.bridge == nil {
			return ObserveResult{}, unavailable("semantic observation")
		}
		raw, err := s.bridge.Snapshot(ctx, request.Device, request.TargetPackage, maxDepth)
		if err != nil {
			return ObserveResult{}, err
		}
		var snapshot SemanticSnapshot
		if err := json.Unmarshal(raw, &snapshot); err != nil {
			return ObserveResult{}, apperr.Wrap(
				apperr.CodeProtocol,
				"The Android bridge returned an invalid semantic snapshot.",
				false,
				err,
			)
		}
		return ObserveResult{
			Device:   request.Device,
			Kind:     request.Kind,
			Format:   "accessibility-tree",
			Snapshot: &snapshot,
		}, nil
	default:
		return ObserveResult{}, invalid("Observation kind must be screenshot, hierarchy, or semantic.")
	}
}

// ExecuteAction 只把白名单动作映射到类型化直接后端。
func (s *Service) ExecuteAction(
	ctx context.Context,
	request ActionRequest,
) (adb.ActionResult, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return adb.ActionResult{}, err
	}
	if s.direct == nil {
		return adb.ActionResult{}, unavailable("ADB action execution")
	}
	switch request.Action {
	case ActionTap:
		return s.direct.Tap(ctx, request.Device, request.X, request.Y)
	case ActionSwipe:
		duration := request.DurationMS
		if duration == 0 {
			duration = defaultSwipeDurationMS
		}
		return s.direct.Swipe(
			ctx,
			request.Device,
			request.StartX,
			request.StartY,
			request.EndX,
			request.EndY,
			duration,
		)
	case ActionSetText:
		return s.direct.Text(ctx, request.Device, request.Text)
	case ActionLongClick:
		// 长按未指定时长时用默认值：低于系统长按阈值会被识别成普通点击，
		// 让动作看似成功却没有长按效果，因此不允许落到 0。
		duration := request.DurationMS
		if duration == 0 {
			duration = defaultLongPressMS
		}
		return s.direct.LongPress(ctx, request.Device, request.X, request.Y, duration)
	case ActionScroll:
		amount := request.Amount
		if amount == 0 {
			amount = defaultScrollFraction
		}
		return s.direct.Scroll(
			ctx, request.Device, adb.ScrollDirection(request.Direction), amount,
		)
	case ActionPressKey:
		return s.direct.Key(ctx, request.Device, request.Key)
	case ActionDrag:
		// 按住阶段的时长不由这里决定：`input draganddrop` 用设备真实的
		// 长按阈值，因此 DurationMS 只表示移动阶段。
		duration := request.DurationMS
		if duration == 0 {
			duration = defaultDragMoveMS
		}
		return s.direct.Drag(
			ctx,
			request.Device,
			adb.Point{X: request.StartX, Y: request.StartY},
			adb.Point{X: request.EndX, Y: request.EndY},
			duration,
		)
	case ActionMultiTap:
		taps := request.Taps
		if taps == 0 {
			taps = defaultMultiTaps
		}
		return s.direct.MultiTap(
			ctx,
			request.Device,
			adb.Point{X: request.X, Y: request.Y},
			taps,
		)
	case ActionPathGesture:
		duration := request.DurationMS
		if duration == 0 {
			duration = defaultPathGestureMS
		}
		return s.direct.PathGesture(ctx, request.Device, request.Points, duration)
	case ActionDeviceRotate:
		return s.direct.Rotate(ctx, request.Device, adb.Orientation(request.Orientation))
	case ActionDeviceWake:
		return s.direct.Wake(ctx, request.Device)
	case ActionDeviceUnlock:
		return s.direct.Unlock(ctx, request.Device)
	case ActionImeHide:
		return s.direct.HideKeyboard(ctx, request.Device)
	case ActionImeSelect:
		return s.direct.SelectInputMethod(ctx, request.Device, request.InputMethod)
	case ActionDeeplink:
		return s.direct.OpenDeeplink(ctx, request.Device, request.URI, request.Package)
	case ActionLaunch:
		return s.direct.Launch(ctx, request.Device, request.Package, request.Activity)
	case ActionStop:
		return s.direct.Stop(ctx, request.Device, request.Package)
	default:
		return adb.ActionResult{}, apperr.New(
			apperr.CodeActionNotAllowed,
			"Action is not in the safe typed action allowlist.",
			false,
			map[string]any{"action": request.Action},
		)
	}
}

// ExecuteBridgeAction 在明确设备的现有会话中执行语义动作。
func (s *Service) ExecuteBridgeAction(
	ctx context.Context,
	device string,
	action json.RawMessage,
) (json.RawMessage, error) {
	if err := adb.ValidateSerial(device); err != nil {
		return nil, err
	}
	if s.bridge == nil {
		return nil, unavailable("semantic action execution")
	}
	return s.bridge.Action(ctx, device, action)
}

// ListRecordings 校验 App 返回的 UUID、包名、时间和资源上限后再公开摘要。
func (s *Service) ListRecordings(
	ctx context.Context,
	device string,
) (RecordingListResult, error) {
	if err := adb.ValidateSerial(device); err != nil {
		return RecordingListResult{}, err
	}
	if s.bridge == nil {
		return RecordingListResult{}, unavailable("recording list")
	}
	raw, err := s.bridge.ListRecordings(ctx, device)
	if err != nil {
		return RecordingListResult{}, err
	}
	var bridgeResult struct {
		Recordings []RecordingSummary `json:"recordings"`
	}
	if err := json.Unmarshal(raw, &bridgeResult); err != nil ||
		bridgeResult.Recordings == nil {
		return RecordingListResult{}, invalidRecordingList()
	}
	seenIDs := make(map[string]struct{}, len(bridgeResult.Recordings))
	for _, recording := range bridgeResult.Recordings {
		if !scriptIDPattern.MatchString(recording.ID) ||
			strings.TrimSpace(recording.Name) == "" ||
			len(recording.Name) > 128 ||
			len(recording.TargetPackages) == 0 ||
			len(recording.TargetPackages) > 32 ||
			recording.StepCount < 1 ||
			recording.StepCount > 10_000 ||
			recording.Requirements.MinAPILevel < 30 ||
			recording.Requirements.MinAPILevel > 1_000 ||
			len(recording.Requirements.Capabilities) > 64 {
			return RecordingListResult{}, invalidRecordingList()
		}
		if _, duplicate := seenIDs[recording.ID]; duplicate {
			return RecordingListResult{}, invalidRecordingList()
		}
		seenIDs[recording.ID] = struct{}{}
		if _, err := time.Parse(time.RFC3339Nano, recording.CreatedAt); err != nil {
			return RecordingListResult{}, invalidRecordingList()
		}
		seenCapabilities := make(map[string]struct{}, len(recording.Requirements.Capabilities))
		for _, capability := range recording.Requirements.Capabilities {
			if strings.TrimSpace(capability) == "" || len(capability) > 128 {
				return RecordingListResult{}, invalidRecordingList()
			}
			if _, duplicate := seenCapabilities[capability]; duplicate {
				return RecordingListResult{}, invalidRecordingList()
			}
			seenCapabilities[capability] = struct{}{}
		}
		seenPackages := make(map[string]struct{}, len(recording.TargetPackages))
		for _, targetPackage := range recording.TargetPackages {
			if err := adb.ValidatePackageName(targetPackage); err != nil {
				return RecordingListResult{}, invalidRecordingList()
			}
			if _, duplicate := seenPackages[targetPackage]; duplicate {
				return RecordingListResult{}, invalidRecordingList()
			}
			seenPackages[targetPackage] = struct{}{}
		}
	}
	return RecordingListResult{
		Device:     device,
		Recordings: bridgeResult.Recordings,
		Count:      len(bridgeResult.Recordings),
	}, nil
}

// ReplayRecording 校验脚本标识，并确保回放报告对应原请求。
func (s *Service) ReplayRecording(
	ctx context.Context,
	request ReplayRecordingRequest,
) (ReplayRecordingResult, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return ReplayRecordingResult{}, err
	}
	if !scriptIDPattern.MatchString(request.ScriptID) {
		return ReplayRecordingResult{}, invalid("scriptId must be a UUID.")
	}
	if s.bridge == nil {
		return ReplayRecordingResult{}, unavailable("recording replay")
	}
	raw, err := s.bridge.Replay(ctx, request.Device, request.ScriptID)
	if err != nil {
		return ReplayRecordingResult{}, err
	}
	var result ReplayRecordingResult
	if err := json.Unmarshal(raw, &result); err != nil {
		return ReplayRecordingResult{}, apperr.Wrap(
			apperr.CodeProtocol,
			"The Android bridge returned an invalid replay report.",
			false,
			err,
		)
	}
	if result.ScriptID != request.ScriptID {
		return ReplayRecordingResult{}, apperr.New(
			apperr.CodeProtocol,
			"The Android bridge replay report did not match the requested script.",
			false,
			nil,
		)
	}
	result.Device = request.Device
	return result, nil
}

func invalidRecordingList() error {
	return apperr.New(
		apperr.CodeProtocol,
		"The Android bridge returned an invalid recording list.",
		false,
		nil,
	)
}

func invalid(message string) error {
	return apperr.New(apperr.CodeInvalidArgument, message, false, nil)
}

func unavailable(capability string) error {
	return apperr.New(
		apperr.CodeCapabilityMissing,
		capability+" is unavailable.",
		false,
		nil,
	)
}
