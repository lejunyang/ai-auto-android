// Package visual 提供短生命周期图片 observation 和只读视觉候选服务。
package visual

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"reflect"
	"regexp"
	"sort"
	"sync"
	"time"
)

const (
	defaultMaxPNGBytes       = 900 * 1024
	defaultMinimumConfidence = 0.70
	defaultAmbiguityDelta    = 0.02
	defaultNearbyDistance    = 0.05
)

var (
	uuidPattern    = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	packagePattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$`)
	pngSignature   = []byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
)

// Code 是视觉服务稳定、可机器识别的失败码。
type Code string

const (
	CodeObservationNotFound      Code = "OBSERVATION_NOT_FOUND"
	CodeObservationIDDrift       Code = "OBSERVATION_ID_DRIFT"
	CodeObservationInvalid       Code = "OBSERVATION_INVALID"
	CodeObservationExpired       Code = "OBSERVATION_EXPIRED"
	CodeForegroundPackageChanged Code = "FOREGROUND_PACKAGE_CHANGED"
	CodeImageUnverified          Code = "IMAGE_UNVERIFIED"
	CodeImageEmpty               Code = "IMAGE_EMPTY"
	CodeImageInvalid             Code = "IMAGE_INVALID"
	CodeImageBudgetExceeded      Code = "IMAGE_BUDGET_EXCEEDED"
	CodeSecureWindow             Code = "SECURE_WINDOW"
	CodeScreenInvalid            Code = "SCREEN_INVALID"
	CodeCropInvalid              Code = "CROP_INVALID"
	CodeCandidateNotFound        Code = "CANDIDATE_NOT_FOUND"
	CodeCandidateInvalid         Code = "CANDIDATE_INVALID"
	CodeCandidateLowConfidence   Code = "CANDIDATE_LOW_CONFIDENCE"
	CodeCandidateAmbiguous       Code = "CANDIDATE_AMBIGUOUS"
	CodeProviderFailed           Code = "PROVIDER_FAILED"
	CodeJSONInvalid              Code = "JSON_INVALID"
	CodeDeviceFingerprintChanged Code = "DEVICE_FINGERPRINT_CHANGED"
	CodeScreenMetadataChanged    Code = "SCREEN_METADATA_CHANGED"
	CodeActionFailed             Code = "ACTION_FAILED"
	CodeActionNotAuthorized      Code = "ACTION_NOT_AUTHORIZED"
	CodeActionCommitUnknown      Code = "ACTION_COMMIT_UNKNOWN"
	CodePostActionVerification   Code = "POST_ACTION_VERIFICATION_FAILED"
)

type visualError struct {
	code Code
}

func (err *visualError) Error() string {
	return string(err.code)
}

func (err *visualError) Is(target error) bool {
	other, ok := target.(*visualError)
	return ok && err.code == other.code
}

// NewError 返回不包含底层敏感细节的稳定视觉错误。
func NewError(code Code) error {
	return &visualError{code: code}
}

// ErrorCode 从错误链中提取视觉错误码。
func ErrorCode(err error) Code {
	var typed *visualError
	if errors.As(err, &typed) {
		return typed.code
	}
	return ""
}

// Screen 使用设备自然方向的宽高，并记录采集时顺时针旋转角度。
type Screen struct {
	Width    int `json:"width"`
	Height   int `json:"height"`
	Rotation int `json:"rotation"`
}

// PixelBounds 表示当前旋转后 capture plane 中的半开像素边界。
type PixelBounds struct {
	Left   int `json:"left"`
	Top    int `json:"top"`
	Right  int `json:"right"`
	Bottom int `json:"bottom"`
}

// NormalizedPoint 表示归一化到自然方向全屏的点。
type NormalizedPoint struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
}

// NormalizedBounds 表示归一化到自然方向全屏的矩形。
type NormalizedBounds struct {
	Left   float64 `json:"left"`
	Top    float64 `json:"top"`
	Right  float64 `json:"right"`
	Bottom float64 `json:"bottom"`
}

// HierarchyNode 是已脱敏的最小语义树节点。
type HierarchyNode struct {
	Role      string           `json:"role"`
	Label     string           `json:"label,omitempty"`
	Bounds    NormalizedBounds `json:"bounds"`
	Sensitive bool             `json:"sensitive"`
	Children  []HierarchyNode  `json:"children"`
}

// PNGMetadata 只暴露图片完整性元数据，不暴露字节或路径。
type PNGMetadata struct {
	Format       string `json:"format"`
	SizeBytes    int    `json:"sizeBytes"`
	SHA256       string `json:"sha256"`
	Verification string `json:"verification"`
}

// Observation 将图片、屏幕、crop、前台包和 hierarchy 绑定到同一身份。
type Observation struct {
	SchemaVersion     string          `json:"schemaVersion"`
	ID                string          `json:"id"`
	ForegroundPackage string          `json:"foregroundPackage"`
	Screen            Screen          `json:"screen"`
	Crop              PixelBounds     `json:"crop"`
	CapturedAt        time.Time       `json:"capturedAt"`
	ExpiresAt         time.Time       `json:"expiresAt"`
	PNG               PNGMetadata     `json:"png"`
	Hierarchy         []HierarchyNode `json:"hierarchy"`
}

// Capture 是注册 observation 时一次性消费并清零的输入。
type Capture struct {
	ID                string
	ForegroundPackage string
	Screen            Screen
	Crop              PixelBounds
	CapturedAt        time.Time
	ExpiresAt         time.Time
	PNGBytes          []byte
	Hierarchy         []HierarchyNode
	SecureWindow      bool
}

// VerificationContext 由 registry 生成并交给进程内可信校验器。
type VerificationContext struct {
	ObservationID string
	PNGSizeBytes  int
	PNGSHA256     string
}

// TrustedVerifier 独立判断图片是否满足调用进程的可信脱敏上下文。
type TrustedVerifier func([]byte, VerificationContext) (bool, error)

// RegistryOptions 配置图片预算和可信校验边界。
type RegistryOptions struct {
	TrustedVerifier TrustedVerifier
	MaxPNGBytes     int
}

type registryEntry struct {
	observation *Observation
	image       []byte
}

// Registry 并发安全地持有短生命周期 observation 与图片租约。
type Registry struct {
	mu              sync.RWMutex
	entries         map[string]*registryEntry
	trustedVerifier TrustedVerifier
	maxPNGBytes     int
	closed          bool
}

// NewRegistry 创建默认 900 KiB 图片预算的内存 registry。
func NewRegistry(options RegistryOptions) *Registry {
	maxBytes := options.MaxPNGBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxPNGBytes
	}
	return &Registry{
		entries:         make(map[string]*registryEntry),
		trustedVerifier: options.TrustedVerifier,
		maxPNGBytes:     maxBytes,
	}
}

// Register 验证、脱敏并注册 observation；调用方和 verifier 图片副本始终被清零。
func (registry *Registry) Register(capture Capture) (*Observation, error) {
	defer clearBytes(capture.PNGBytes)
	if capture.SecureWindow {
		return nil, NewError(CodeSecureWindow)
	}
	if len(capture.PNGBytes) == 0 {
		return nil, NewError(CodeImageEmpty)
	}
	if len(capture.PNGBytes) > registry.maxPNGBytes {
		return nil, NewError(CodeImageBudgetExceeded)
	}
	if len(capture.PNGBytes) < len(pngSignature) ||
		!bytes.Equal(capture.PNGBytes[:len(pngSignature)], pngSignature) {
		return nil, NewError(CodeImageInvalid)
	}
	if err := validateCapture(capture); err != nil {
		return nil, err
	}

	digest := sha256.Sum256(capture.PNGBytes)
	digestText := hex.EncodeToString(digest[:])
	if registry.trustedVerifier == nil {
		return nil, NewError(CodeImageUnverified)
	}
	verifierCopy := bytes.Clone(capture.PNGBytes)
	defer clearBytes(verifierCopy)
	verified, verifyErr := callTrustedVerifier(registry.trustedVerifier, verifierCopy, VerificationContext{
		ObservationID: capture.ID,
		PNGSizeBytes:  len(capture.PNGBytes),
		PNGSHA256:     digestText,
	})
	if verifyErr != nil || !verified {
		return nil, NewError(CodeImageUnverified)
	}

	observation := &Observation{
		SchemaVersion:     "visual-observation/1.0",
		ID:                capture.ID,
		ForegroundPackage: capture.ForegroundPackage,
		Screen:            capture.Screen,
		Crop:              capture.Crop,
		CapturedAt:        capture.CapturedAt.UTC(),
		ExpiresAt:         capture.ExpiresAt.UTC(),
		PNG: PNGMetadata{
			Format:       "png",
			SizeBytes:    len(capture.PNGBytes),
			SHA256:       digestText,
			Verification: "trusted-context",
		},
		Hierarchy: redactHierarchy(capture.Hierarchy, false),
	}
	ownedImage := bytes.Clone(capture.PNGBytes)

	registry.mu.Lock()
	defer registry.mu.Unlock()
	if registry.closed {
		clearBytes(ownedImage)
		return nil, NewError(CodeObservationNotFound)
	}
	if existing, ok := registry.entries[capture.ID]; ok {
		if !reflect.DeepEqual(existing.observation, observation) {
			clearBytes(ownedImage)
			return nil, NewError(CodeObservationIDDrift)
		}
		clearBytes(ownedImage)
		return cloneObservation(existing.observation), nil
	}
	registry.entries[capture.ID] = &registryEntry{
		observation: observation,
		image:       ownedImage,
	}
	return cloneObservation(observation), nil
}

// Lookup 返回仍有效注册的结构化 observation，不包含图片字节。
func (registry *Registry) Lookup(id string) (*Observation, bool) {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	entry, ok := registry.entries[id]
	if !ok || registry.closed {
		return nil, false
	}
	return cloneObservation(entry.observation), true
}

// WithImage 在回调期间借出图片副本，并在全部返回路径清零该副本。
func (registry *Registry) WithImage(id string, use func([]byte) error) error {
	if use == nil {
		return NewError(CodeObservationInvalid)
	}
	registry.mu.RLock()
	entry, ok := registry.entries[id]
	if !ok || registry.closed {
		registry.mu.RUnlock()
		return NewError(CodeObservationNotFound)
	}
	borrowed := bytes.Clone(entry.image)
	registry.mu.RUnlock()
	defer clearBytes(borrowed)
	return use(borrowed)
}

// Revoke 删除 observation 并清零 registry 持有的图片。
func (registry *Registry) Revoke(id string) bool {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	entry, ok := registry.entries[id]
	if !ok {
		return false
	}
	delete(registry.entries, id)
	clearBytes(entry.image)
	return true
}

// Close 清零全部图片并关闭 registry；重复调用安全。
func (registry *Registry) Close() {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if registry.closed {
		return
	}
	registry.closed = true
	for id, entry := range registry.entries {
		clearBytes(entry.image)
		delete(registry.entries, id)
	}
}

func validateCapture(capture Capture) error {
	if !uuidPattern.MatchString(capture.ID) ||
		!packagePattern.MatchString(capture.ForegroundPackage) ||
		capture.CapturedAt.IsZero() ||
		!capture.ExpiresAt.After(capture.CapturedAt) {
		return NewError(CodeObservationInvalid)
	}
	if capture.Screen.Width <= 0 || capture.Screen.Height <= 0 ||
		capture.Screen.Width > 32768 || capture.Screen.Height > 32768 ||
		!validRotation(capture.Screen.Rotation) {
		return NewError(CodeScreenInvalid)
	}
	planeWidth, planeHeight := capturePlane(capture.Screen)
	if capture.Crop.Left < 0 || capture.Crop.Top < 0 ||
		capture.Crop.Right <= capture.Crop.Left ||
		capture.Crop.Bottom <= capture.Crop.Top ||
		capture.Crop.Right > planeWidth ||
		capture.Crop.Bottom > planeHeight {
		return NewError(CodeCropInvalid)
	}
	if len(capture.Hierarchy) > 256 {
		return NewError(CodeObservationInvalid)
	}
	for _, node := range capture.Hierarchy {
		if !validHierarchy(node, 0) {
			return NewError(CodeObservationInvalid)
		}
	}
	return nil
}

func validHierarchy(node HierarchyNode, depth int) bool {
	if depth > 64 || node.Role == "" || len(node.Role) > 128 ||
		len(node.Label) > 256 || !validBounds(node.Bounds) ||
		len(node.Children) > 256 {
		return false
	}
	for _, child := range node.Children {
		if !validHierarchy(child, depth+1) {
			return false
		}
	}
	return true
}

func redactHierarchy(nodes []HierarchyNode, inheritedSensitive bool) []HierarchyNode {
	result := make([]HierarchyNode, len(nodes))
	for index, node := range nodes {
		sensitive := inheritedSensitive || node.Sensitive
		result[index] = HierarchyNode{
			Role:      node.Role,
			Label:     node.Label,
			Bounds:    node.Bounds,
			Sensitive: sensitive,
			Children:  redactHierarchy(node.Children, sensitive),
		}
		if sensitive {
			result[index].Label = ""
		}
		if result[index].Children == nil {
			result[index].Children = []HierarchyNode{}
		}
	}
	if result == nil {
		return []HierarchyNode{}
	}
	return result
}

func cloneObservation(observation *Observation) *Observation {
	if observation == nil {
		return nil
	}
	cloned := *observation
	cloned.Hierarchy = cloneHierarchy(observation.Hierarchy)
	return &cloned
}

func cloneHierarchy(nodes []HierarchyNode) []HierarchyNode {
	result := make([]HierarchyNode, len(nodes))
	for index, node := range nodes {
		result[index] = node
		result[index].Children = cloneHierarchy(node.Children)
	}
	if result == nil {
		return []HierarchyNode{}
	}
	return result
}

func clearBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}

func callTrustedVerifier(
	verifier TrustedVerifier,
	image []byte,
	context VerificationContext,
) (verified bool, err error) {
	defer func() {
		if recover() != nil {
			verified = false
			err = NewError(CodeImageUnverified)
		}
	}()
	return verifier(image, context)
}

// CandidateSource 标识候选由 OCR、模板、模型或人工产生。
type CandidateSource string

const (
	SourceOCR      CandidateSource = "ocr"
	SourceTemplate CandidateSource = "template"
	SourceModel    CandidateSource = "model"
	SourceManual   CandidateSource = "manual"
)

// RawCandidate 使用 crop 局部归一化坐标表示 provider 输出。
type RawCandidate struct {
	ID         string
	Source     CandidateSource
	Point      NormalizedPoint
	Bounds     NormalizedBounds
	Confidence float64
}

// Candidate 是映射到自然方向全屏坐标的只读候选。
type Candidate struct {
	ID            string           `json:"id"`
	ObservationID string           `json:"observationId"`
	Source        CandidateSource  `json:"source"`
	Point         NormalizedPoint  `json:"point"`
	Bounds        NormalizedBounds `json:"bounds"`
	Confidence    float64          `json:"confidence"`
}

// ProposalRequest 绑定 observation、预期前台包和调用时间。
type ProposalRequest struct {
	ObservationID   string
	ExpectedPackage string
	Now             time.Time
}

// ProposalResponse 始终声明动作提交数为零。
type ProposalResponse struct {
	Candidates        []Candidate `json:"candidates"`
	ActionCommitCount int         `json:"actionCommitCount"`
}

// Provider 只读取 observation 和租约图片并提出 crop 局部候选。
type Provider interface {
	Propose(context.Context, Observation, []byte) ([]RawCandidate, error)
}

// ProviderFunc 把函数适配为只读候选 provider。
type ProviderFunc func(context.Context, Observation, []byte) ([]RawCandidate, error)

// Propose 调用底层只读函数。
func (function ProviderFunc) Propose(
	ctx context.Context,
	observation Observation,
	image []byte,
) ([]RawCandidate, error) {
	return function(ctx, observation, image)
}

// ProposalOptions 配置置信度与候选歧义门槛。
type ProposalOptions struct {
	MinimumConfidence float64
	AmbiguityDelta    float64
	NearbyDistance    float64
}

// ProposalService 只提出候选，类型上不包含动作执行入口。
type ProposalService struct {
	registry          *Registry
	provider          Provider
	minimumConfidence float64
	ambiguityDelta    float64
	nearbyDistance    float64
}

// NewProposalService 创建失败关闭的只读视觉候选服务。
func NewProposalService(
	registry *Registry,
	provider Provider,
	options ProposalOptions,
) *ProposalService {
	minimum := options.MinimumConfidence
	if minimum <= 0 {
		minimum = defaultMinimumConfidence
	}
	delta := options.AmbiguityDelta
	if delta <= 0 {
		delta = defaultAmbiguityDelta
	}
	distance := options.NearbyDistance
	if distance <= 0 {
		distance = defaultNearbyDistance
	}
	return &ProposalService{
		registry:          registry,
		provider:          provider,
		minimumConfidence: minimum,
		ambiguityDelta:    delta,
		nearbyDistance:    distance,
	}
}

// Propose 验证 observation 后借出图片并返回全屏候选；任一风险都会撤销 observation。
func (service *ProposalService) Propose(
	ctx context.Context,
	request ProposalRequest,
) (ProposalResponse, error) {
	failed := func(code Code) (ProposalResponse, error) {
		if service.registry != nil {
			service.registry.Revoke(request.ObservationID)
		}
		return ProposalResponse{Candidates: []Candidate{}, ActionCommitCount: 0}, NewError(code)
	}
	if service.registry == nil || service.provider == nil ||
		!uuidPattern.MatchString(request.ObservationID) ||
		!packagePattern.MatchString(request.ExpectedPackage) ||
		request.Now.IsZero() {
		return failed(CodeObservationInvalid)
	}
	observation, ok := service.registry.Lookup(request.ObservationID)
	if !ok {
		return failed(CodeObservationNotFound)
	}
	if !request.Now.Before(observation.ExpiresAt) {
		return failed(CodeObservationExpired)
	}
	if observation.ForegroundPackage != request.ExpectedPackage {
		return failed(CodeForegroundPackageChanged)
	}

	var raw []RawCandidate
	err := service.registry.WithImage(request.ObservationID, func(image []byte) error {
		provided, providerErr := callProvider(service.provider, ctx, *observation, image)
		if providerErr != nil {
			return NewError(CodeProviderFailed)
		}
		raw = provided
		return nil
	})
	if err != nil {
		if ErrorCode(err) == CodeObservationNotFound {
			return failed(CodeObservationNotFound)
		}
		return failed(CodeProviderFailed)
	}
	if len(raw) == 0 {
		return failed(CodeCandidateNotFound)
	}
	if len(raw) > 64 {
		return failed(CodeCandidateInvalid)
	}
	for _, candidate := range raw {
		if !validRawCandidate(candidate) {
			return failed(CodeCandidateInvalid)
		}
	}

	ranked := append([]RawCandidate(nil), raw...)
	sort.SliceStable(ranked, func(left, right int) bool {
		return ranked[left].Confidence > ranked[right].Confidence
	})
	if ranked[0].Confidence < service.minimumConfidence {
		return failed(CodeCandidateLowConfidence)
	}
	if len(ranked) > 1 &&
		ranked[0].Source != SourceManual &&
		ranked[1].Source != SourceManual &&
		ranked[0].Confidence-ranked[1].Confidence <= service.ambiguityDelta &&
		pointDistance(ranked[0].Point, ranked[1].Point) <= service.nearbyDistance {
		return failed(CodeCandidateAmbiguous)
	}

	candidates := make([]Candidate, len(raw))
	for index, candidate := range raw {
		point, bounds := mapCandidate(observation.Screen, observation.Crop, candidate)
		candidates[index] = Candidate{
			ID:            candidate.ID,
			ObservationID: observation.ID,
			Source:        candidate.Source,
			Point:         point,
			Bounds:        bounds,
			Confidence:    candidate.Confidence,
		}
	}
	return ProposalResponse{Candidates: candidates, ActionCommitCount: 0}, nil
}

func validRawCandidate(candidate RawCandidate) bool {
	return uuidPattern.MatchString(candidate.ID) &&
		validSource(candidate.Source) &&
		validPoint(candidate.Point) &&
		validBounds(candidate.Bounds) &&
		finite(candidate.Confidence) &&
		candidate.Confidence >= 0 &&
		candidate.Confidence <= 1
}

func validSource(source CandidateSource) bool {
	return source == SourceOCR || source == SourceTemplate ||
		source == SourceModel || source == SourceManual
}

func validPoint(point NormalizedPoint) bool {
	return finite(point.X) && finite(point.Y) &&
		point.X >= 0 && point.X <= 1 &&
		point.Y >= 0 && point.Y <= 1
}

func validBounds(bounds NormalizedBounds) bool {
	return finite(bounds.Left) && finite(bounds.Top) &&
		finite(bounds.Right) && finite(bounds.Bottom) &&
		bounds.Left >= 0 && bounds.Top >= 0 &&
		bounds.Right <= 1 && bounds.Bottom <= 1 &&
		bounds.Right > bounds.Left && bounds.Bottom > bounds.Top
}

func finite(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0)
}

func pointDistance(left, right NormalizedPoint) float64 {
	return math.Hypot(left.X-right.X, left.Y-right.Y)
}

func callProvider(
	provider Provider,
	ctx context.Context,
	observation Observation,
	image []byte,
) (candidates []RawCandidate, err error) {
	defer func() {
		if recover() != nil {
			candidates = nil
			err = NewError(CodeProviderFailed)
		}
	}()
	return provider.Propose(ctx, observation, image)
}

func capturePlane(screen Screen) (int, int) {
	if screen.Rotation == 90 || screen.Rotation == 270 {
		return screen.Height, screen.Width
	}
	return screen.Width, screen.Height
}

func validRotation(rotation int) bool {
	return rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270
}

func mapCandidate(
	screen Screen,
	crop PixelBounds,
	candidate RawCandidate,
) (NormalizedPoint, NormalizedBounds) {
	mapPoint := func(point NormalizedPoint) NormalizedPoint {
		captureX := float64(crop.Left) + point.X*float64(crop.Right-crop.Left)
		captureY := float64(crop.Top) + point.Y*float64(crop.Bottom-crop.Top)
		switch screen.Rotation {
		case 90:
			return NormalizedPoint{
				X: captureY / float64(screen.Width),
				Y: 1 - captureX/float64(screen.Height),
			}
		case 180:
			return NormalizedPoint{
				X: 1 - captureX/float64(screen.Width),
				Y: 1 - captureY/float64(screen.Height),
			}
		case 270:
			return NormalizedPoint{
				X: 1 - captureY/float64(screen.Width),
				Y: captureX / float64(screen.Height),
			}
		default:
			return NormalizedPoint{
				X: captureX / float64(screen.Width),
				Y: captureY / float64(screen.Height),
			}
		}
	}
	point := mapPoint(candidate.Point)
	corners := []NormalizedPoint{
		mapPoint(NormalizedPoint{X: candidate.Bounds.Left, Y: candidate.Bounds.Top}),
		mapPoint(NormalizedPoint{X: candidate.Bounds.Right, Y: candidate.Bounds.Top}),
		mapPoint(NormalizedPoint{X: candidate.Bounds.Left, Y: candidate.Bounds.Bottom}),
		mapPoint(NormalizedPoint{X: candidate.Bounds.Right, Y: candidate.Bounds.Bottom}),
	}
	bounds := NormalizedBounds{
		Left:   corners[0].X,
		Top:    corners[0].Y,
		Right:  corners[0].X,
		Bottom: corners[0].Y,
	}
	for _, corner := range corners[1:] {
		bounds.Left = math.Min(bounds.Left, corner.X)
		bounds.Top = math.Min(bounds.Top, corner.Y)
		bounds.Right = math.Max(bounds.Right, corner.X)
		bounds.Bottom = math.Max(bounds.Bottom, corner.Y)
	}
	return point, bounds
}

// ProposalBundle 是独立 visual schema 的结构化输出。
type ProposalBundle struct {
	Observation       Observation `json:"observation"`
	Candidates        []Candidate `json:"candidates"`
	ActionCommitCount int         `json:"actionCommitCount"`
}

// EncodeBundle 使用标准 JSON 编码只读 proposal bundle。
func EncodeBundle(bundle ProposalBundle) ([]byte, error) {
	if bundle.ActionCommitCount != 0 {
		return nil, NewError(CodeJSONInvalid)
	}
	payload, err := json.Marshal(bundle)
	if err != nil {
		return nil, NewError(CodeJSONInvalid)
	}
	return payload, nil
}

// DecodeBundle 严格拒绝未知字段和尾随 JSON。
func DecodeBundle(payload []byte, target *ProposalBundle) error {
	if target == nil {
		return NewError(CodeJSONInvalid)
	}
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return NewError(CodeJSONInvalid)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return NewError(CodeJSONInvalid)
	}
	if target.ActionCommitCount != 0 ||
		!uuidPattern.MatchString(target.Observation.ID) ||
		!packagePattern.MatchString(target.Observation.ForegroundPackage) {
		return NewError(CodeJSONInvalid)
	}
	return nil
}

func (code Code) String() string { return string(code) }
