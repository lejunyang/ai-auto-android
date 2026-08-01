// Package visual 提供原子桌面视觉动作的短期证据绑定与失败关闭编排。
package visual

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"reflect"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

const (
	desktopAttestedMinimumConfidence = 0.70
	desktopAttestedMaxPNGBytes       = 600 * 1024
)

// VisualActionType 是原子视觉端口允许的类型化动作集合。
type VisualActionType string

const (
	VisualActionTap       VisualActionType = "tap"
	VisualActionLongClick VisualActionType = "long-click"
	VisualActionSwipe     VisualActionType = "swipe"
)

// VisualSwipeDirection 是不携带坐标的候选区域内滑动方向。
type VisualSwipeDirection string

const (
	VisualSwipeUp    VisualSwipeDirection = "up"
	VisualSwipeDown  VisualSwipeDirection = "down"
	VisualSwipeLeft  VisualSwipeDirection = "left"
	VisualSwipeRight VisualSwipeDirection = "right"
)

// DesktopVisualAction 描述不暴露坐标的视觉动作意图。
type DesktopVisualAction struct {
	Type       VisualActionType     `json:"type"`
	Direction  VisualSwipeDirection `json:"direction,omitempty"`
	DurationMS int                  `json:"durationMs,omitempty"`
}

// ActionCommitStatus 区分零提交、明确提交和提交状态未知。
type ActionCommitStatus string

const (
	ActionCommitNotCommitted ActionCommitStatus = "not_committed"
	ActionCommitCommitted    ActionCommitStatus = "committed"
	ActionCommitUnknown      ActionCommitStatus = "unknown"
)

// AttestedInsets 表示 Android production screen reader 返回的系统栏 inset。
type AttestedInsets struct {
	Left   int `json:"left"`
	Top    int `json:"top"`
	Right  int `json:"right"`
	Bottom int `json:"bottom"`
}

// AttestedScreenEvidence 绑定动作前后的自然屏幕、密度、窗口、旋转和 inset。
type AttestedScreenEvidence struct {
	NaturalWidth  int            `json:"naturalWidth"`
	NaturalHeight int            `json:"naturalHeight"`
	DensityDPI    int            `json:"densityDpi"`
	Rotation      int            `json:"rotation"`
	Window        PixelBounds    `json:"window"`
	Insets        AttestedInsets `json:"insets"`
}

// AttestedActionPortRequest 是仅在一次 Bridge 调用内存活的完整视觉 evidence。
type AttestedActionPortRequest struct {
	DeviceSerial      string              `json:"deviceSerial"`
	DeviceFingerprint string              `json:"deviceFingerprint"`
	TargetPackage     string              `json:"targetPackage"`
	HierarchySHA256   string              `json:"hierarchySha256"`
	Observation       Observation         `json:"observation"`
	Candidate         Candidate           `json:"candidate"`
	Action            DesktopVisualAction `json:"action"`
	CapturedAt        time.Time           `json:"capturedAt"`
	ExpiresAt         time.Time           `json:"expiresAt"`
	PNGBytes          []byte              `json:"pngBase64"`
}

// AttestedActionPortResult 返回 Android 原子执行和后置验证的最小结果。
type AttestedActionPortResult struct {
	CommitStatus  ActionCommitStatus     `json:"commitStatus"`
	ActionCommits *int                   `json:"actionCommits"`
	Verified      bool                   `json:"verified"`
	Route         string                 `json:"route,omitempty"`
	Screen        AttestedScreenEvidence `json:"screen"`
	ErrorCode     Code                   `json:"errorCode,omitempty"`
}

// AttestedActionPort 只允许完整 evidence 原子执行，不提供独立坐标动作方法。
type AttestedActionPort interface {
	ExecuteAttestedVisualAction(
		context.Context,
		AttestedActionPortRequest,
	) (AttestedActionPortResult, error)
}

// AttestedActionPortError 让 transport 明确报告错误发生时的提交确定性。
type AttestedActionPortError struct {
	status ActionCommitStatus
	code   Code
	cause  error
}

func (err *AttestedActionPortError) Error() string {
	if err == nil || err.cause == nil {
		return string(err.code)
	}
	return err.cause.Error()
}

func (err *AttestedActionPortError) Unwrap() error {
	if err == nil {
		return nil
	}
	return err.cause
}

// NewAttestedActionPortError 创建不回显敏感请求内容的提交状态错误。
func NewAttestedActionPortError(
	status ActionCommitStatus,
	code Code,
	cause error,
) error {
	return &AttestedActionPortError{status: status, code: code, cause: cause}
}

// AttestedActionPortErrorStatus 读取 transport 已知的提交状态和稳定错误码。
func AttestedActionPortErrorStatus(err error) (ActionCommitStatus, Code, bool) {
	var typed *AttestedActionPortError
	if !errors.As(err, &typed) {
		return "", "", false
	}
	return typed.status, typed.code, true
}

// DesktopAttestedActionRequest 只接受设备、目标描述和无坐标动作意图。
type DesktopAttestedActionRequest struct {
	Device          string              `json:"device"`
	ExpectedPackage string              `json:"expectedPackage"`
	Target          DesktopTarget       `json:"target"`
	Action          DesktopVisualAction `json:"action"`
}

// DesktopAttestedActionResult 不返回候选 geometry、图片或可复用租约。
type DesktopAttestedActionResult struct {
	Succeeded         bool               `json:"succeeded"`
	ObservationID     string             `json:"observationId,omitempty"`
	DeviceFingerprint string             `json:"deviceFingerprint,omitempty"`
	ScreenFingerprint string             `json:"screenFingerprint,omitempty"`
	Route             string             `json:"route,omitempty"`
	Verified          bool               `json:"verified"`
	CommitStatus      ActionCommitStatus `json:"commitStatus"`
	ActionCommits     *int               `json:"actionCommits"`
	ErrorCode         Code               `json:"errorCode,omitempty"`
}

// DesktopAttestedActionOptions 注入可测试时钟和 observation 身份。
type DesktopAttestedActionOptions struct {
	Now           func() time.Time
	ObservationID func() string
	// CandidateConfidence 仅用于受控 provider/test 注入；低于安全阈值仍会失败关闭。
	CandidateConfidence float64
}

// DesktopAttestedActionService 编排 fresh propose、原子执行和动作后观察。
type DesktopAttestedActionService struct {
	backend             DesktopBackend
	port                AttestedActionPort
	now                 func() time.Time
	observationID       func() string
	candidateConfidence float64
}

// NewDesktopAttestedActionService 创建不复用 N44 返回租约的原子视觉动作服务。
func NewDesktopAttestedActionService(
	backend DesktopBackend,
	port AttestedActionPort,
	options DesktopAttestedActionOptions,
) *DesktopAttestedActionService {
	now := options.Now
	if now == nil {
		now = time.Now
	}
	observationID := options.ObservationID
	if observationID == nil {
		observationID = newDesktopObservationID
	}
	candidateConfidence := options.CandidateConfidence
	if candidateConfidence == 0 {
		candidateConfidence = desktopConfidence
	}
	return &DesktopAttestedActionService{
		backend:             backend,
		port:                port,
		now:                 now,
		observationID:       observationID,
		candidateConfidence: candidateConfidence,
	}
}

// ProposeAndExecute 在单个调用栈内消费视觉 evidence，任何失败都不返回坐标。
func (service *DesktopAttestedActionService) ProposeAndExecute(
	ctx context.Context,
	request DesktopAttestedActionRequest,
) DesktopAttestedActionResult {
	zero := 0
	rejected := func(code Code) DesktopAttestedActionResult {
		return DesktopAttestedActionResult{
			CommitStatus:  ActionCommitNotCommitted,
			ActionCommits: &zero,
			ErrorCode:     code,
		}
	}
	if service == nil || service.backend == nil || service.port == nil ||
		adb.ValidateSerial(request.Device) != nil ||
		adb.ValidatePackageName(request.ExpectedPackage) != nil ||
		!validDesktopTarget(request.Target) ||
		!validDesktopVisualAction(request.Action) {
		return rejected(CodeObservationInvalid)
	}

	source, err := service.capture(ctx, request, true)
	if err != nil {
		return rejected(errorCodeOr(err, CodeObservationInvalid))
	}
	defer source.clear()
	now := service.now().UTC()
	observationID := service.observationID()
	if now.IsZero() || !uuidPattern.MatchString(observationID) {
		return rejected(CodeObservationInvalid)
	}
	observation := source.observation(observationID, now)
	candidate := source.candidates[0]
	candidate.ObservationID = observationID
	deviceFingerprint := fingerprintDesktopDevice(source.device)

	preCommit, err := service.capture(ctx, request, true)
	if err != nil {
		return rejected(errorCodeOr(err, CodeObservationInvalid))
	}
	defer preCommit.clear()
	if fingerprintDesktopDevice(preCommit.device) != deviceFingerprint {
		return rejected(CodeDeviceFingerprintChanged)
	}
	if preCommit.foregroundPackage != source.foregroundPackage {
		return rejected(CodeForegroundPackageChanged)
	}
	if preCommit.screen != source.screen ||
		preCommit.crop != source.crop {
		return rejected(CodeScreenMetadataChanged)
	}
	if preCommit.hierarchyHash != source.hierarchyHash ||
		preCommit.pngHash != source.pngHash {
		return rejected(CodeImageUnverified)
	}
	if len(preCommit.candidates) != 1 ||
		preCommit.candidates[0].ID != source.candidates[0].ID {
		return rejected(CodeCandidateAmbiguous)
	}
	if !service.now().UTC().Before(observation.ExpiresAt) {
		return rejected(CodeObservationExpired)
	}

	portImage := bytes.Clone(source.image)
	defer clearBytes(portImage)
	portResult, portErr := service.port.ExecuteAttestedVisualAction(
		ctx,
		AttestedActionPortRequest{
			DeviceSerial:      request.Device,
			DeviceFingerprint: deviceFingerprint,
			TargetPackage:     request.ExpectedPackage,
			HierarchySHA256:   source.hierarchyHash,
			Observation:       observation,
			Candidate:         candidate,
			Action:            request.Action,
			CapturedAt:        observation.CapturedAt,
			ExpiresAt:         observation.ExpiresAt,
			PNGBytes:          portImage,
		},
	)

	post, postErr := service.capture(ctx, request, false)
	if postErr == nil {
		defer post.clear()
	}
	base := DesktopAttestedActionResult{
		ObservationID:     observationID,
		DeviceFingerprint: deviceFingerprint,
	}
	if portErr != nil {
		status, code, known := AttestedActionPortErrorStatus(portErr)
		if !known {
			status = ActionCommitUnknown
			code = CodeActionCommitUnknown
		}
		base.CommitStatus = status
		base.ErrorCode = firstCode(code, CodeActionCommitUnknown)
		if status == ActionCommitNotCommitted {
			base.ActionCommits = &zero
		}
		return base
	}
	if !validPortResult(portResult) {
		base.CommitStatus = ActionCommitUnknown
		base.ErrorCode = CodeActionCommitUnknown
		return base
	}
	if portResult.CommitStatus == ActionCommitNotCommitted {
		base.CommitStatus = portResult.CommitStatus
		base.ActionCommits = portResult.ActionCommits
		base.ErrorCode = portResult.ErrorCode
		if base.ErrorCode == "" {
			base.ErrorCode = CodeActionFailed
		}
		return base
	}
	if portResult.CommitStatus == ActionCommitUnknown {
		base.CommitStatus = ActionCommitUnknown
		base.ErrorCode = firstCode(portResult.ErrorCode, CodeActionCommitUnknown)
		return base
	}
	if postErr != nil ||
		fingerprintDesktopDevice(post.device) != deviceFingerprint ||
		post.foregroundPackage != request.ExpectedPackage ||
		post.screen != source.screen ||
		post.crop != source.crop ||
		post.hierarchyHash == source.hierarchyHash && post.pngHash == source.pngHash ||
		!portResult.Verified ||
		!validAttestedScreen(portResult.Screen, source.screen) {
		base.CommitStatus = ActionCommitUnknown
		base.ErrorCode = CodePostActionVerification
		return base
	}
	base.Succeeded = true
	base.Verified = true
	base.CommitStatus = ActionCommitCommitted
	base.ActionCommits = portResult.ActionCommits
	base.Route = portResult.Route
	base.ScreenFingerprint = fingerprintAttestedScreen(portResult.Screen)
	return base
}

type desktopActionCapture struct {
	device            protocol.Device
	foregroundPackage string
	hierarchyHash     string
	pngHash           string
	image             []byte
	screen            Screen
	crop              PixelBounds
	hierarchy         []HierarchyNode
	candidates        []Candidate
}

func (service *DesktopAttestedActionService) capture(
	ctx context.Context,
	request DesktopAttestedActionRequest,
	requireCandidate bool,
) (*desktopActionCapture, error) {
	initialDevice, err := service.backend.DeviceInfo(ctx, request.Device)
	if err != nil {
		return nil, err
	}
	if !validDesktopDevice(initialDevice, request.Device) {
		return nil, NewError(CodeObservationInvalid)
	}
	before, err := service.backend.Hierarchy(ctx, request.Device)
	if err != nil {
		return nil, err
	}
	screenshot, err := service.backend.Screenshot(ctx, request.Device)
	if err != nil {
		return nil, err
	}
	defer clearBytes(screenshot.Bytes)
	after, err := service.backend.Hierarchy(ctx, request.Device)
	if err != nil {
		return nil, err
	}
	finalDevice, err := service.backend.DeviceInfo(ctx, request.Device)
	if err != nil {
		return nil, err
	}
	if !reflect.DeepEqual(initialDevice, finalDevice) ||
		before.SHA256 == "" ||
		before.SHA256 != hashDesktopBytes([]byte(before.XML)) ||
		after.SHA256 != hashDesktopBytes([]byte(after.XML)) ||
		before.SHA256 != after.SHA256 ||
		before.XML != after.XML ||
		before.Bytes != after.Bytes {
		return nil, NewError(CodeObservationIDDrift)
	}
	parsed, err := parseDesktopHierarchy(before.XML)
	if err != nil {
		return nil, err
	}
	if parsed.foregroundPackage != request.ExpectedPackage {
		return nil, NewError(CodeForegroundPackageChanged)
	}
	config, err := decodeDesktopPNGConfig(screenshot.Bytes)
	if err != nil {
		return nil, err
	}
	if screenshot.SHA256 != hashDesktopBytes(screenshot.Bytes) {
		return nil, NewError(CodeImageUnverified)
	}
	if len(screenshot.Bytes) > desktopAttestedMaxPNGBytes {
		return nil, NewError(CodeImageBudgetExceeded)
	}
	if desktopBlackPNG(screenshot.Bytes) {
		return nil, NewError(CodeSecureWindow)
	}
	screen := desktopScreen(config.Width, config.Height, parsed.rotation)
	crop := PixelBounds{Left: 0, Top: 0, Right: config.Width, Bottom: config.Height}
	hierarchy, rawCandidates, err := mapDesktopHierarchy(
		parsed.root,
		request.ExpectedPackage,
		request.Target,
		screen,
		crop,
	)
	if err != nil {
		return nil, err
	}
	if requireCandidate {
		switch len(rawCandidates) {
		case 0:
			return nil, NewError(CodeCandidateNotFound)
		case 1:
		default:
			return nil, NewError(CodeCandidateAmbiguous)
		}
	}
	candidates := make([]Candidate, 0, len(rawCandidates))
	for _, raw := range rawCandidates {
		raw.Confidence = service.candidateConfidence
		if raw.Confidence < desktopAttestedMinimumConfidence {
			return nil, NewError(CodeCandidateLowConfidence)
		}
		point, bounds := mapCandidate(screen, crop, raw)
		candidates = append(candidates, Candidate{
			ID:         raw.ID,
			Source:     raw.Source,
			Point:      point,
			Bounds:     bounds,
			Confidence: raw.Confidence,
		})
	}
	return &desktopActionCapture{
		device:            initialDevice,
		foregroundPackage: request.ExpectedPackage,
		hierarchyHash:     before.SHA256,
		pngHash:           screenshot.SHA256,
		image:             bytes.Clone(screenshot.Bytes),
		screen:            screen,
		crop:              crop,
		hierarchy:         hierarchy,
		candidates:        candidates,
	}, nil
}

func (capture *desktopActionCapture) observation(
	id string,
	capturedAt time.Time,
) Observation {
	return Observation{
		SchemaVersion:     "visual-observation/1.0",
		ID:                id,
		ForegroundPackage: capture.foregroundPackage,
		Screen:            capture.screen,
		Crop:              capture.crop,
		CapturedAt:        capturedAt,
		ExpiresAt:         capturedAt.Add(desktopObservationTTL),
		PNG: PNGMetadata{
			Format:       "png",
			SizeBytes:    len(capture.image),
			SHA256:       capture.pngHash,
			Verification: "trusted-context",
		},
		Hierarchy: cloneHierarchy(capture.hierarchy),
	}
}

func (capture *desktopActionCapture) clear() {
	if capture == nil {
		return
	}
	clearBytes(capture.image)
	capture.image = nil
	capture.hierarchy = nil
	capture.candidates = nil
}

func validDesktopVisualAction(action DesktopVisualAction) bool {
	switch action.Type {
	case VisualActionTap:
		return action.Direction == "" && action.DurationMS == 0
	case VisualActionLongClick:
		return action.Direction == "" &&
			(action.DurationMS == 0 ||
				action.DurationMS >= 1 && action.DurationMS <= 10_000)
	case VisualActionSwipe:
		return (action.Direction == VisualSwipeUp ||
			action.Direction == VisualSwipeDown ||
			action.Direction == VisualSwipeLeft ||
			action.Direction == VisualSwipeRight) &&
			(action.DurationMS == 0 ||
				action.DurationMS >= 1 && action.DurationMS <= 10_000)
	default:
		return false
	}
}

func validPortResult(result AttestedActionPortResult) bool {
	switch result.CommitStatus {
	case ActionCommitNotCommitted:
		return result.ActionCommits != nil &&
			*result.ActionCommits == 0 &&
			!result.Verified
	case ActionCommitCommitted:
		return result.ActionCommits != nil &&
			*result.ActionCommits == 1 &&
			result.Verified &&
			result.Route != "" &&
			result.ErrorCode == ""
	case ActionCommitUnknown:
		return result.ActionCommits == nil && !result.Verified
	default:
		return false
	}
}

func validAttestedScreen(evidence AttestedScreenEvidence, source Screen) bool {
	if evidence.NaturalWidth != source.Width ||
		evidence.NaturalHeight != source.Height ||
		evidence.Rotation != source.Rotation ||
		evidence.DensityDPI <= 0 {
		return false
	}
	planeWidth, planeHeight := capturePlane(source)
	if evidence.Window.Left < 0 || evidence.Window.Top < 0 ||
		evidence.Window.Right <= evidence.Window.Left ||
		evidence.Window.Bottom <= evidence.Window.Top ||
		evidence.Window.Right > planeWidth ||
		evidence.Window.Bottom > planeHeight ||
		evidence.Insets.Left < 0 || evidence.Insets.Top < 0 ||
		evidence.Insets.Right < 0 || evidence.Insets.Bottom < 0 ||
		evidence.Insets.Left+evidence.Insets.Right >=
			evidence.Window.Right-evidence.Window.Left ||
		evidence.Insets.Top+evidence.Insets.Bottom >=
			evidence.Window.Bottom-evidence.Window.Top {
		return false
	}
	return true
}

func fingerprintDesktopDevice(device protocol.Device) string {
	encoded, err := json.Marshal(device)
	if err != nil {
		return ""
	}
	sum := sha256.Sum256(encoded)
	clearBytes(encoded)
	return hex.EncodeToString(sum[:])
}

func fingerprintAttestedScreen(screen AttestedScreenEvidence) string {
	encoded, err := json.Marshal(screen)
	if err != nil {
		return ""
	}
	sum := sha256.Sum256(encoded)
	clearBytes(encoded)
	return hex.EncodeToString(sum[:])
}

func errorCodeOr(err error, fallback Code) Code {
	if code := ErrorCode(err); code != "" {
		return code
	}
	return fallback
}

func firstCode(value Code, fallback Code) Code {
	if value != "" {
		return value
	}
	return fallback
}
