package service

import (
	"context"
	"encoding/json"
	"regexp"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

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

	defaultSwipeDurationMS = 300
	defaultSnapshotDepth   = 64
)

var scriptIDPattern = regexp.MustCompile(
	`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$`,
)

// DirectBackend is the safe, typed subset of ADB used by automation clients.
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
}

// BridgeBackend contains only established-session operations. Pairing and trust
// management intentionally live outside this interface.
type BridgeBackend interface {
	Snapshot(context.Context, string, string, int) (json.RawMessage, error)
	Action(context.Context, string, json.RawMessage) (json.RawMessage, error)
	Replay(context.Context, string, string) (json.RawMessage, error)
}

// Automation is shared by the CLI and MCP adapters.
type Automation interface {
	ListDevices(context.Context) (DeviceListResult, error)
	GetDevice(context.Context, string) (protocol.Device, error)
	Observe(context.Context, ObserveRequest) (ObserveResult, error)
	ExecuteAction(context.Context, ActionRequest) (adb.ActionResult, error)
	ExecuteBridgeAction(context.Context, string, json.RawMessage) (json.RawMessage, error)
	ReplayRecording(context.Context, ReplayRecordingRequest) (ReplayRecordingResult, error)
}

type Service struct {
	direct DirectBackend
	bridge BridgeBackend
}

type DeviceListResult struct {
	Devices []protocol.Device `json:"devices"`
	Count   int               `json:"count"`
}

type ObserveRequest struct {
	Device        string `json:"device"`
	Kind          string `json:"kind"`
	TargetPackage string `json:"targetPackage,omitempty"`
	MaxDepth      int    `json:"maxDepth,omitempty"`
}

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

type SemanticSnapshot struct {
	Root     SemanticNode `json:"root"`
	MaxDepth int          `json:"maxDepth"`
}

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

type Bounds struct {
	Left   int `json:"left"`
	Top    int `json:"top"`
	Right  int `json:"right"`
	Bottom int `json:"bottom"`
}

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
}

type ReplayRecordingRequest struct {
	Device   string `json:"device"`
	ScriptID string `json:"scriptId"`
}

type ReplayRecordingResult struct {
	Device               string             `json:"device"`
	ScriptID             string             `json:"scriptId"`
	StartedAtMS          int64              `json:"startedAtMs"`
	FinishedAtMS         int64              `json:"finishedAtMs"`
	Succeeded            bool               `json:"succeeded"`
	RequiresIntervention bool               `json:"requiresIntervention"`
	Steps                []ReplayStepResult `json:"steps"`
}

type ReplayStepResult struct {
	StepID     string  `json:"stepId"`
	Status     string  `json:"status"`
	Attempts   int     `json:"attempts"`
	Route      string  `json:"route,omitempty"`
	MatchScore float64 `json:"matchScore,omitempty"`
	ErrorCode  string  `json:"errorCode,omitempty"`
	Message    string  `json:"message,omitempty"`
}

func New(direct DirectBackend, bridge BridgeBackend) *Service {
	return &Service{direct: direct, bridge: bridge}
}

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

func (s *Service) GetDevice(ctx context.Context, device string) (protocol.Device, error) {
	if err := adb.ValidateSerial(device); err != nil {
		return protocol.Device{}, err
	}
	if s.direct == nil {
		return protocol.Device{}, unavailable("ADB device information")
	}
	return s.direct.DeviceInfo(ctx, device)
}

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
	case ActionPressKey:
		return s.direct.Key(ctx, request.Device, request.Key)
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
