package service

// 功能用途：定义电脑侧显示接口；MCP 另设独立风险门，只开放屏幕管理，不暴露 helper 启停。

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
	Command, Device, Helper, Focus string
	Camera, Image                  string
	VirtualCamera                  bool
	KeepCameraAssociation          bool
	Width, Height, DPI, DisplayID  int
}

// CameraDisplayBackend 把操作员启动/图片路径与可由模型请求的摄像头屏创建区分开。
type CameraDisplayBackend interface {
	StartWithCamera(context.Context, string, string) (vdisplay.Hello, error)
	CreateWithCamera(context.Context, string, int, int, int, string, string) (vdisplay.Screen, error)
	SetCameraImage(context.Context, string, int, string) (vdisplay.CameraImageResult, error)
}

// CameraAssociationStopBackend 只供操作员stop选择保留，MCP不注册helper启停入口。
type CameraAssociationStopBackend interface {
	StopKeepingCameraAssociation(context.Context, string) (vdisplay.StopResult, error)
}

// DisplayAutomation 与通用动作分离，由适配器显式决定允许的管理出口。
type DisplayAutomation interface {
	ManageDisplay(context.Context, DisplayRequest) (any, error)
}

// DisplayBackend 限定辅助进程的类型化能力，与 Bridge 后端隔离。
type DisplayBackend interface {
	Start(context.Context, string, string) (vdisplay.Hello, error)
	Status(context.Context, string) (vdisplay.Status, error)
	Stop(context.Context, string) (vdisplay.StopResult, error)
	Create(context.Context, string, int, int, int, string) (vdisplay.Screen, error)
	List(context.Context, string) (vdisplay.ListResult, error)
	Close(context.Context, string, int) (vdisplay.CloseResult, error)
	Capture(context.Context, string, int) (adb.Screenshot, error)
	RequireDisplay(context.Context, string, int) error
}

// NewWithDisplays 为电脑侧入口构建显示能力，并保留可选的既有 Bridge 路由。
func NewWithDisplays(direct *adb.Client, displays DisplayBackend, bridge ...BridgeBackend) *Service {
	var b BridgeBackend
	if len(bridge) > 0 {
		b = bridge[0]
	}
	s := New(direct, b)
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
	if r.KeepCameraAssociation && r.Command != "stop" {
		return nil, invalid("keep-camera-association is only valid for display stop.")
	}
	switch r.Command {
	case "start":
		if r.VirtualCamera {
			camera, ok := s.displays.(CameraDisplayBackend)
			if !ok {
				return nil, apperr.New(apperr.CodeVirtualCameraNotEnabled, "Operator must rebuild and restart helper with --virtual-camera", false, nil)
			}
			return camera.StartWithCamera(ctx, r.Device, r.Helper)
		}
		return s.displays.Start(ctx, r.Device, r.Helper)
	case "status":
		return s.displays.Status(ctx, r.Device)
	case "stop":
		if r.KeepCameraAssociation {
			keeper, ok := s.displays.(CameraAssociationStopBackend)
			if !ok {
				return nil, unavailable("camera association retention")
			}
			return keeper.StopKeepingCameraAssociation(ctx, r.Device)
		}
		return s.displays.Stop(ctx, r.Device)
	case "create":
		focus, err := vdisplay.NormalizeFocus(r.Focus)
		if err != nil {
			return nil, err
		}
		if r.Camera != "" {
			if r.Camera != "back" {
				return nil, invalid("camera must be back")
			}
			camera, ok := s.displays.(CameraDisplayBackend)
			if !ok {
				return nil, apperr.New(apperr.CodeVirtualCameraNotEnabled, "Operator must rebuild and restart helper with --virtual-camera", false, nil)
			}
			return camera.CreateWithCamera(ctx, r.Device, r.Width, r.Height, r.DPI, focus, r.Camera)
		}
		return s.displays.Create(ctx, r.Device, r.Width, r.Height, r.DPI, focus)
	case "camera.set-image":
		camera, ok := s.displays.(CameraDisplayBackend)
		if !ok {
			return nil, apperr.New(apperr.CodeVirtualCameraNotEnabled, "Operator must rebuild and restart helper with --virtual-camera", false, nil)
		}
		return camera.SetCameraImage(ctx, r.Device, r.DisplayID, r.Image)
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
	// 正整数目标先核对归属，随后解析器可安全区分DISPLAY_EMPTY与确实不归属的DISPLAY_NOT_FOUND。
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
	case ActionTap, ActionSwipe, ActionLongClick, ActionSetText, ActionPressKey, ActionLaunch:
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
