package service

// 功能用途：定义仅电脑侧 CLI 使用的虚拟显示管理接口，不扩展 Automation 或 MCP 协议。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/vdisplay"
)

// ActionDeviceSleep 是与唤醒对称的主屏息屏动作。
const ActionDeviceSleep = "device.sleep"

// DisplayRequest 描述固定管理子命令，不允许任意 shell 或框架标志。
type DisplayRequest struct {
	Command, Device, Helper       string
	Width, Height, DPI, DisplayID int
}

// DisplayAutomation 与通用 Automation 分离，避免自动成为 MCP 出口。
type DisplayAutomation interface {
	ManageDisplay(context.Context, DisplayRequest) (any, error)
}

// DisplayBackend 限定辅助进程的类型化能力，与 Bridge 后端隔离。
type DisplayBackend interface {
	Start(context.Context, string, string) (vdisplay.Hello, error)
	Status(context.Context, string) (vdisplay.Status, error)
	Stop(context.Context, string) (vdisplay.StopResult, error)
	Create(context.Context, string, int, int, int) (vdisplay.Screen, error)
	List(context.Context, string) (vdisplay.ListResult, error)
	Close(context.Context, string, int) (vdisplay.CloseResult, error)
	Capture(context.Context, string, int) (adb.Screenshot, error)
	RequireDisplay(context.Context, string, int) error
}

// NewWithDisplays 只供电脑侧直连入口构建显示能力。
func NewWithDisplays(direct *adb.Client, displays DisplayBackend) *Service {
	s := New(direct, nil)
	s.displays = displays
	s.displayTarget = func(id int) DirectBackend { return direct.WithDisplay(id) }
	return s
}

// ManageDisplay 将 CLI 的固定命令映射到辅助进程；不纳入通用 Automation 接口。
func (s *Service) ManageDisplay(ctx context.Context, r DisplayRequest) (any, error) {
	if err := adb.ValidateSerial(r.Device); err != nil {
		return nil, err
	}
	if s.displays == nil {
		return nil, unavailable("virtual display management")
	}
	switch r.Command {
	case "start":
		return s.displays.Start(ctx, r.Device, r.Helper)
	case "status":
		return s.displays.Status(ctx, r.Device)
	case "stop":
		return s.displays.Stop(ctx, r.Device)
	case "create":
		return s.displays.Create(ctx, r.Device, r.Width, r.Height, r.DPI)
	case "list":
		return s.displays.List(ctx, r.Device)
	case "close":
		return s.displays.Close(ctx, r.Device, r.DisplayID)
	default:
		return nil, invalid("Unknown virtual display command.")
	}
}

func (s *Service) observeDisplay(ctx context.Context, r ObserveRequest) (ObserveResult, error) {
	if *r.DisplayID < 0 || *r.DisplayID > 2147483647 {
		return ObserveResult{}, apperr.New(apperr.CodeDisplayNotFound, "Expected non-negative display ID.", false, nil)
	}
	if r.TargetPackage != "" || r.MaxDepth != 0 {
		return ObserveResult{}, invalid("Virtual display observation does not accept semantic options.")
	}
	if r.Kind != ObserveScreenshot && r.Kind != ObserveHierarchy {
		return ObserveResult{}, invalid("Virtual displays support screenshot and hierarchy observations only.")
	}
	if r.Kind == ObserveScreenshot && *r.DisplayID > 0 {
		if s.displays == nil {
			return ObserveResult{}, unavailable("virtual display observation")
		}
		frame, err := s.displays.Capture(ctx, r.Device, *r.DisplayID)
		if err != nil {
			return ObserveResult{}, err
		}
		return ObserveResult{Device: r.Device, Kind: r.Kind, Format: "png", SizeBytes: len(frame.Bytes), SHA256: frame.SHA256, Image: frame.Bytes}, nil
	}
	target, err := s.targetDisplay(ctx, r.Device, *r.DisplayID)
	if err != nil {
		return ObserveResult{}, err
	}
	copy := *s
	copy.direct = target
	r.DisplayID = nil
	return copy.Observe(ctx, r)
}

func (s *Service) targetDisplay(ctx context.Context, device string, id int) (DirectBackend, error) {
	if id < 0 || id > 2147483647 {
		return nil, apperr.New(apperr.CodeDisplayNotFound, "Expected non-negative display ID.", false, nil)
	}
	if s.displayTarget == nil {
		return nil, unavailable("virtual display actions")
	}
	// 零值明确指向主屏，不能向只管理虚拟屏的 helper 查询归属。
	if id > 0 {
		if s.displays == nil {
			return nil, unavailable("virtual display actions")
		}
		if err := s.displays.RequireDisplay(ctx, device, id); err != nil {
			return nil, err
		}
	}
	target := s.displayTarget(id)
	if target == nil {
		return nil, unavailable("virtual display target")
	}
	return target, nil
}

func (s *Service) actionDisplay(ctx context.Context, r ActionRequest) (adb.ActionResult, error) {
	switch r.Action {
	case ActionTap, ActionSwipe, ActionSetText, ActionPressKey, ActionLaunch:
	default:
		return adb.ActionResult{}, invalid("This action does not support a virtual display target.")
	}
	target, err := s.targetDisplay(ctx, r.Device, *r.DisplayID)
	if err != nil {
		return adb.ActionResult{}, err
	}
	// 复制路由并移除显示参数；动作默认值与白名单继续由既有 ExecuteAction 唯一维护。
	copy := *s
	copy.direct = target
	r.DisplayID = nil
	return copy.ExecuteAction(ctx, r)
}
