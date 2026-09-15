package service

// 功能用途：本文件在共享服务层实现前台应用探测、已安装应用清单与屏幕录制的校验与路由，
// 使 CLI、MCP 与 App Bridge 三侧通过同一套语义获得这些观察能力。
//
// 之所以扩展为独立接口 ObserveExtraBackend 而不是直接加进 DirectBackend：
// DirectBackend 被 Service 构造时强制要求，而这三项能力在部分后端（例如仅有桥、
// 无 adb 直连的场景）可能不可用。用独立接口加运行时类型断言，可以在缺失时
// 给出明确的"能力不可用"错误，而不是让整个 Service 无法构造。
import (
	"context"

	"github.com/lejunyang/ai-auto-android/internal/adb"
)

// ObserveExtra 等常量扩展观察类型，与既有 ObserveScreenshot 等并列。
const (
	// ObserveForeground 返回当前前台包名与 Activity。
	ObserveForeground = "foreground"
	// ObserveApps 返回已安装应用清单。
	ObserveApps = "apps"
	// ObserveRecording 返回一段屏幕录制。
	ObserveRecording = "recording"

	// defaultRecordingSeconds 是未指定时长时的录制秒数。
	// 取 10 秒：足够覆盖一次典型 UI 交互，又不会让单次调用长时间阻塞。
	defaultRecordingSeconds = 10
)

// ObserveExtraBackend 是前台探测、应用清单与录屏所需的后端能力。
//
// 该接口刻意与 DirectBackend 分开：这三项能力都依赖 adb shell 的文本解析，
// 在只有 App Bridge 的部署形态下无法提供，分开后可以按能力分别降级。
type ObserveExtraBackend interface {
	Foreground(context.Context, string) (adb.Foreground, error)
	InstalledApps(context.Context, string, bool, bool) (adb.InstalledApps, error)
	RecordScreen(context.Context, string, adb.RecordingOptions) (adb.ScreenRecording, error)
}

// ForegroundRequest 描述一次前台应用查询。
type ForegroundRequest struct {
	Device string `json:"device"`
}

// AppsRequest 描述一次已安装应用查询。
type AppsRequest struct {
	Device string `json:"device"`
	// IncludeSystem 为 true 时同时返回系统应用；默认只返回第三方应用，
	// 因为 UI 自动化的目标几乎总是第三方应用，且系统包数量会淹没结果。
	IncludeSystem bool `json:"includeSystem,omitempty"`
	// WithDetails 为 true 时逐包查询版本号，代价是每个包一次 dumpsys。
	WithDetails bool `json:"withDetails,omitempty"`
}

// RecordingRequest 描述一次屏幕录制。
type RecordingRequest struct {
	Device  string `json:"device"`
	Seconds int    `json:"seconds,omitempty"`
	Width   int    `json:"width,omitempty"`
	Height  int    `json:"height,omitempty"`
	BitRate int    `json:"bitRate,omitempty"`
}

// ForegroundResult 是前台查询的结果。
type ForegroundResult struct {
	Device   string `json:"device"`
	Package  string `json:"package"`
	Activity string `json:"activity,omitempty"`
	Source   string `json:"source"`
}

// AppsResult 是应用清单查询的结果。
type AppsResult struct {
	Device          string             `json:"device"`
	Apps            []adb.InstalledApp `json:"apps"`
	Count           int                `json:"count"`
	IncludesSystem  bool               `json:"includesSystem"`
	DetailsIncluded bool               `json:"detailsIncluded"`
}

// RecordingResult 是屏幕录制的结果。
//
// Video 不参与 JSON 序列化：录制产物可达数十 MB，必须由调用方按各自通道处理
// （CLI 写文件、MCP 只回摘要），不能塞进统一响应体。
type RecordingResult struct {
	Device      string `json:"device"`
	Format      string `json:"format"`
	SizeBytes   int    `json:"sizeBytes"`
	SHA256      string `json:"sha256"`
	DurationSec int    `json:"durationSeconds"`
	Width       int    `json:"width,omitempty"`
	Height      int    `json:"height,omitempty"`
	BitRate     int    `json:"bitRate,omitempty"`
	Video       []byte `json:"-"`
}

// extraBackend 返回可用的扩展观察后端，或说明其不可用。
func (s *Service) extraBackend(capability string) (ObserveExtraBackend, error) {
	backend, ok := s.direct.(ObserveExtraBackend)
	if !ok || s.direct == nil {
		return nil, unavailable(capability)
	}
	return backend, nil
}

// Foreground 返回当前前台应用与 Activity。
func (s *Service) Foreground(
	ctx context.Context,
	request ForegroundRequest,
) (ForegroundResult, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return ForegroundResult{}, err
	}
	backend, err := s.extraBackend("foreground observation")
	if err != nil {
		return ForegroundResult{}, err
	}
	foreground, err := backend.Foreground(ctx, request.Device)
	if err != nil {
		return ForegroundResult{}, err
	}
	return ForegroundResult{
		Device:   foreground.Device,
		Package:  foreground.Package,
		Activity: foreground.Activity,
		Source:   foreground.Source,
	}, nil
}

// Apps 返回已安装应用清单。
func (s *Service) Apps(ctx context.Context, request AppsRequest) (AppsResult, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return AppsResult{}, err
	}
	backend, err := s.extraBackend("installed application listing")
	if err != nil {
		return AppsResult{}, err
	}
	apps, err := backend.InstalledApps(
		ctx, request.Device, request.IncludeSystem, request.WithDetails,
	)
	if err != nil {
		return AppsResult{}, err
	}
	return AppsResult{
		Device:          apps.Device,
		Apps:            apps.Apps,
		Count:           apps.Count,
		IncludesSystem:  request.IncludeSystem,
		DetailsIncluded: apps.DetailsIncluded,
	}, nil
}

// Record 录制屏幕并返回产物与摘要。
func (s *Service) Record(
	ctx context.Context,
	request RecordingRequest,
) (RecordingResult, error) {
	if err := adb.ValidateSerial(request.Device); err != nil {
		return RecordingResult{}, err
	}
	seconds := request.Seconds
	if seconds == 0 {
		seconds = defaultRecordingSeconds
	}
	backend, err := s.extraBackend("screen recording")
	if err != nil {
		return RecordingResult{}, err
	}
	recording, err := backend.RecordScreen(ctx, request.Device, adb.RecordingOptions{
		Seconds: seconds,
		Width:   request.Width,
		Height:  request.Height,
		BitRate: request.BitRate,
	})
	if err != nil {
		return RecordingResult{}, err
	}
	return RecordingResult{
		Device:      request.Device,
		Format:      "mp4",
		SizeBytes:   recording.SizeBytes,
		SHA256:      recording.SHA256,
		DurationSec: recording.DurationSec,
		Width:       recording.Width,
		Height:      recording.Height,
		BitRate:     recording.BitRate,
		Video:       recording.Bytes,
	}, nil
}
