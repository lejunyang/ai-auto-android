// Package visual 提供短生命周期图片 observation 和只读视觉候选服务。
package visual

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"image"
	_ "image/png"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

const (
	desktopObservationTTL = 10 * time.Second
	desktopMaxPixels      = 16 * 1024 * 1024
	desktopConfidence     = 0.95
)

var desktopBoundsPattern = regexp.MustCompile(
	`^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$`,
)

// DesktopBackend 只暴露可信视觉采集需要的设备元数据、PNG 和 UIAutomator 层级。
type DesktopBackend interface {
	DeviceInfo(context.Context, string) (protocol.Device, error)
	Hierarchy(context.Context, string) (adb.Hierarchy, error)
	Screenshot(context.Context, string) (adb.Screenshot, error)
}

// DesktopTarget 是显式只读模板目标；role 与 label 至少提供一项。
type DesktopTarget struct {
	Role  string `json:"role,omitempty"`
	Label string `json:"label,omitempty"`
}

// DesktopProposalRequest 固定明确设备、预期前台包和模板目标。
type DesktopProposalRequest struct {
	Device          string        `json:"device"`
	ExpectedPackage string        `json:"expectedPackage"`
	Target          DesktopTarget `json:"target"`
}

// DesktopOptions 注入可测试时钟与 observation 身份，不开放采集命令。
type DesktopOptions struct {
	Now           func() time.Time
	ObservationID func() string
}

// DesktopProposalService 绑定同轮桌面采集并只返回模板候选。
type DesktopProposalService struct {
	backend       DesktopBackend
	now           func() time.Time
	observationID func() string
}

// DesktopProposalLease 持有结构化候选和唯一可转移图片所有权。
type DesktopProposalLease struct {
	mu       sync.Mutex
	registry *Registry
	bundle   ProposalBundle
	closed   bool
	taken    bool
}

// NewDesktopProposalService 创建失败关闭的桌面可信视觉采集服务。
func NewDesktopProposalService(
	backend DesktopBackend,
	options DesktopOptions,
) *DesktopProposalService {
	now := options.Now
	if now == nil {
		now = time.Now
	}
	observationID := options.ObservationID
	if observationID == nil {
		observationID = newDesktopObservationID
	}
	return &DesktopProposalService{
		backend:       backend,
		now:           now,
		observationID: observationID,
	}
}

// Propose 按固定顺序采集、复核并注册一次 observation，不包含动作执行能力。
func (service *DesktopProposalService) Propose(
	ctx context.Context,
	request DesktopProposalRequest,
) (*DesktopProposalLease, error) {
	if service == nil || service.backend == nil ||
		adb.ValidateSerial(request.Device) != nil ||
		adb.ValidatePackageName(request.ExpectedPackage) != nil ||
		!validDesktopTarget(request.Target) {
		return nil, NewError(CodeObservationInvalid)
	}

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
	if desktopBlackPNG(screenshot.Bytes) {
		return nil, NewError(CodeSecureWindow)
	}

	screen := desktopScreen(config.Width, config.Height, parsed.rotation)
	crop := PixelBounds{Left: 0, Top: 0, Right: config.Width, Bottom: config.Height}
	hierarchy, matches, err := mapDesktopHierarchy(
		parsed.root,
		request.ExpectedPackage,
		request.Target,
		screen,
		crop,
	)
	if err != nil {
		return nil, err
	}
	switch len(matches) {
	case 0:
		return nil, NewError(CodeCandidateNotFound)
	case 1:
	default:
		return nil, NewError(CodeCandidateAmbiguous)
	}

	now := service.now().UTC()
	observationID := service.observationID()
	if now.IsZero() || !uuidPattern.MatchString(observationID) {
		return nil, NewError(CodeObservationInvalid)
	}
	attestation := desktopAttestation{
		observationID: observationID,
		device:        initialDevice,
		hierarchyHash: before.SHA256,
		pngHash:       screenshot.SHA256,
		pngSize:       len(screenshot.Bytes),
	}
	registry := NewRegistry(RegistryOptions{
		TrustedVerifier: attestation.verify,
	})
	observation, err := registry.Register(Capture{
		ID:                observationID,
		ForegroundPackage: request.ExpectedPackage,
		Screen:            screen,
		Crop:              crop,
		CapturedAt:        now,
		ExpiresAt:         now.Add(desktopObservationTTL),
		PNGBytes:          screenshot.Bytes,
		Hierarchy:         hierarchy,
	})
	if err != nil {
		registry.Close()
		return nil, err
	}

	raw := matches[0]
	proposalService := NewProposalService(
		registry,
		ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
			return []RawCandidate{raw}, nil
		}),
		ProposalOptions{MinimumConfidence: 0.70},
	)
	proposal, err := proposalService.Propose(ctx, ProposalRequest{
		ObservationID:   observationID,
		ExpectedPackage: request.ExpectedPackage,
		Now:             now,
	})
	if err != nil {
		registry.Close()
		return nil, err
	}
	return &DesktopProposalLease{
		registry: registry,
		bundle: ProposalBundle{
			Observation:       *observation,
			Candidates:        proposal.Candidates,
			ActionCommitCount: proposal.ActionCommitCount,
		},
	}, nil
}

// Bundle 返回不包含图片字节的深拷贝结构化结果。
func (lease *DesktopProposalLease) Bundle() ProposalBundle {
	if lease == nil {
		return ProposalBundle{}
	}
	lease.mu.Lock()
	defer lease.mu.Unlock()
	return cloneDesktopBundle(lease.bundle)
}

// TakeImage 将唯一图片副本转交给调用方，并立即清空 registry。
func (lease *DesktopProposalLease) TakeImage() ([]byte, error) {
	if lease == nil {
		return nil, NewError(CodeObservationNotFound)
	}
	lease.mu.Lock()
	defer lease.mu.Unlock()
	if lease.closed || lease.taken || lease.registry == nil {
		return nil, NewError(CodeObservationNotFound)
	}
	var transferred []byte
	err := lease.registry.WithImage(lease.bundle.Observation.ID, func(image []byte) error {
		transferred = bytes.Clone(image)
		return nil
	})
	if err != nil {
		clearBytes(transferred)
		return nil, err
	}
	lease.registry.Close()
	lease.registry = nil
	lease.taken = true
	lease.closed = true
	return transferred, nil
}

// Close 撤销 observation 并清零未转移图片；重复调用安全。
func (lease *DesktopProposalLease) Close() {
	if lease == nil {
		return
	}
	lease.mu.Lock()
	defer lease.mu.Unlock()
	if lease.closed {
		return
	}
	lease.closed = true
	if lease.registry != nil {
		lease.registry.Close()
		lease.registry = nil
	}
}

type desktopAttestation struct {
	observationID string
	device        protocol.Device
	hierarchyHash string
	pngHash       string
	pngSize       int
}

func (attestation desktopAttestation) verify(
	image []byte,
	context VerificationContext,
) (bool, error) {
	return attestation.observationID == context.ObservationID &&
		attestation.device.Serial != "" &&
		attestation.device.State == "device" &&
		attestation.hierarchyHash != "" &&
		attestation.pngHash == context.PNGSHA256 &&
		attestation.pngSize == context.PNGSizeBytes &&
		hashDesktopBytes(image) == attestation.pngHash, nil
}

type desktopHierarchyDocument struct {
	rotation          int
	foregroundPackage string
	root              desktopXMLNode
}

type desktopXMLNode struct {
	Class              string           `xml:"class,attr"`
	Package            string           `xml:"package,attr"`
	Text               string           `xml:"text,attr"`
	ContentDescription string           `xml:"content-desc,attr"`
	Password           bool             `xml:"password,attr"`
	Bounds             string           `xml:"bounds,attr"`
	Children           []desktopXMLNode `xml:"node"`
}

func parseDesktopHierarchy(source string) (desktopHierarchyDocument, error) {
	decoder := xml.NewDecoder(strings.NewReader(source))
	var root struct {
		XMLName  xml.Name         `xml:"hierarchy"`
		Rotation string           `xml:"rotation,attr"`
		Nodes    []desktopXMLNode `xml:"node"`
	}
	if err := decoder.Decode(&root); err != nil ||
		root.XMLName.Local != "hierarchy" ||
		len(root.Nodes) != 1 {
		return desktopHierarchyDocument{}, NewError(CodeObservationInvalid)
	}
	rotationValue, err := strconv.Atoi(root.Rotation)
	if err != nil || rotationValue < 0 || rotationValue > 3 {
		return desktopHierarchyDocument{}, NewError(CodeScreenInvalid)
	}
	foreground := root.Nodes[0].Package
	if adb.ValidatePackageName(foreground) != nil {
		return desktopHierarchyDocument{}, NewError(CodeObservationInvalid)
	}
	return desktopHierarchyDocument{
		rotation:          rotationValue * 90,
		foregroundPackage: foreground,
		root:              root.Nodes[0],
	}, nil
}

func mapDesktopHierarchy(
	root desktopXMLNode,
	expectedPackage string,
	target DesktopTarget,
	screen Screen,
	crop PixelBounds,
) ([]HierarchyNode, []RawCandidate, error) {
	captureWidth := crop.Right - crop.Left
	captureHeight := crop.Bottom - crop.Top
	nodeCount := 0
	var walk func(desktopXMLNode, int, bool) (HierarchyNode, []RawCandidate, error)
	walk = func(
		source desktopXMLNode,
		depth int,
		parentSensitive bool,
	) (HierarchyNode, []RawCandidate, error) {
		nodeCount++
		if nodeCount > 256 || depth > 64 ||
			source.Package != expectedPackage {
			return HierarchyNode{}, nil, NewError(CodeObservationInvalid)
		}
		captureBounds, ok := desktopNormalizedBounds(
			source.Bounds,
			captureWidth,
			captureHeight,
		)
		if !ok {
			return HierarchyNode{}, nil, NewError(CodeObservationInvalid)
		}
		_, bounds := mapCandidate(screen, crop, RawCandidate{
			Point: NormalizedPoint{
				X: (captureBounds.Left + captureBounds.Right) / 2,
				Y: (captureBounds.Top + captureBounds.Bottom) / 2,
			},
			Bounds: captureBounds,
		})
		role := desktopRole(source.Class)
		label := strings.TrimSpace(source.ContentDescription)
		if label == "" {
			label = strings.TrimSpace(source.Text)
		}
		if len(role) == 0 || len(role) > 128 || len(label) > 256 {
			return HierarchyNode{}, nil, NewError(CodeObservationInvalid)
		}
		sensitive := parentSensitive || source.Password
		node := HierarchyNode{
			Role:      role,
			Label:     label,
			Bounds:    bounds,
			Sensitive: sensitive,
			Children:  make([]HierarchyNode, 0, len(source.Children)),
		}
		if sensitive {
			node.Label = ""
		}
		matches := make([]RawCandidate, 0, 1)
		if !sensitive && desktopTargetMatches(target, role, label) {
			matches = append(matches, RawCandidate{
				ID:     desktopCandidateID(role, label, captureBounds),
				Source: SourceTemplate,
				Point: NormalizedPoint{
					X: (captureBounds.Left + captureBounds.Right) / 2,
					Y: (captureBounds.Top + captureBounds.Bottom) / 2,
				},
				Bounds:     captureBounds,
				Confidence: desktopConfidence,
			})
		}
		for _, child := range source.Children {
			mapped, childMatches, err := walk(child, depth+1, sensitive)
			if err != nil {
				return HierarchyNode{}, nil, err
			}
			node.Children = append(node.Children, mapped)
			matches = append(matches, childMatches...)
		}
		return node, matches, nil
	}

	mapped, matches, err := walk(root, 0, false)
	if err != nil {
		return nil, nil, err
	}
	return []HierarchyNode{mapped}, matches, nil
}

func desktopNormalizedBounds(
	source string,
	width int,
	height int,
) (NormalizedBounds, bool) {
	match := desktopBoundsPattern.FindStringSubmatch(source)
	if len(match) != 5 || width <= 0 || height <= 0 {
		return NormalizedBounds{}, false
	}
	values := make([]int, 4)
	for index := range values {
		value, err := strconv.Atoi(match[index+1])
		if err != nil {
			return NormalizedBounds{}, false
		}
		values[index] = value
	}
	if values[0] < 0 || values[1] < 0 ||
		values[2] <= values[0] || values[3] <= values[1] ||
		values[2] > width || values[3] > height {
		return NormalizedBounds{}, false
	}
	return NormalizedBounds{
		Left:   float64(values[0]) / float64(width),
		Top:    float64(values[1]) / float64(height),
		Right:  float64(values[2]) / float64(width),
		Bottom: float64(values[3]) / float64(height),
	}, true
}

func decodeDesktopPNGConfig(data []byte) (image.Config, error) {
	config, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || format != "png" ||
		config.Width <= 0 || config.Height <= 0 ||
		config.Width > 32768 || config.Height > 32768 ||
		config.Width*config.Height > desktopMaxPixels {
		return image.Config{}, NewError(CodeScreenInvalid)
	}
	return config, nil
}

func desktopBlackPNG(data []byte) bool {
	decoded, format, err := image.Decode(bytes.NewReader(data))
	if err != nil || format != "png" {
		return true
	}
	bounds := decoded.Bounds()
	for y := bounds.Min.Y; y < bounds.Max.Y; y++ {
		for x := bounds.Min.X; x < bounds.Max.X; x++ {
			red, green, blue, _ := decoded.At(x, y).RGBA()
			if red > 0x0202 || green > 0x0202 || blue > 0x0202 {
				return false
			}
		}
	}
	return true
}

func desktopScreen(captureWidth, captureHeight, rotation int) Screen {
	if rotation == 90 || rotation == 270 {
		return Screen{Width: captureHeight, Height: captureWidth, Rotation: rotation}
	}
	return Screen{Width: captureWidth, Height: captureHeight, Rotation: rotation}
}

func desktopRole(className string) string {
	name := className
	if index := strings.LastIndex(name, "."); index >= 0 {
		name = name[index+1:]
	}
	return strings.ToLower(strings.TrimSpace(name))
}

func validDesktopTarget(target DesktopTarget) bool {
	role := strings.TrimSpace(target.Role)
	label := strings.TrimSpace(target.Label)
	return (role != "" || label != "") &&
		len(role) <= 128 &&
		len(label) <= 256
}

func desktopTargetMatches(target DesktopTarget, role, label string) bool {
	return (strings.TrimSpace(target.Role) == "" ||
		strings.EqualFold(strings.TrimSpace(target.Role), role)) &&
		(strings.TrimSpace(target.Label) == "" ||
			strings.TrimSpace(target.Label) == label)
}

func validDesktopDevice(device protocol.Device, serial string) bool {
	return device.Serial == serial &&
		device.State == "device" &&
		(device.Transport == "usb" ||
			device.Transport == "wifi" ||
			device.Transport == "emulator")
}

func desktopCandidateID(
	role string,
	label string,
	bounds NormalizedBounds,
) string {
	payload := []byte(role + "\x00" + label + "\x00" +
		strconv.FormatFloat(bounds.Left, 'f', 9, 64) + "\x00" +
		strconv.FormatFloat(bounds.Top, 'f', 9, 64) + "\x00" +
		strconv.FormatFloat(bounds.Right, 'f', 9, 64) + "\x00" +
		strconv.FormatFloat(bounds.Bottom, 'f', 9, 64))
	sum := sha256.Sum256(payload)
	sum[6] = (sum[6] & 0x0f) | 0x50
	sum[8] = (sum[8] & 0x3f) | 0x80
	return formatDesktopUUID(sum[:16])
}

func newDesktopObservationID() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return ""
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	return formatDesktopUUID(value)
}

func formatDesktopUUID(value []byte) string {
	encoded := hex.EncodeToString(value)
	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" +
		encoded[16:20] + "-" + encoded[20:32]
}

func hashDesktopBytes(value []byte) string {
	sum := sha256.Sum256(value)
	return hex.EncodeToString(sum[:])
}

func cloneDesktopBundle(bundle ProposalBundle) ProposalBundle {
	return ProposalBundle{
		Observation: Observation{
			SchemaVersion:     bundle.Observation.SchemaVersion,
			ID:                bundle.Observation.ID,
			ForegroundPackage: bundle.Observation.ForegroundPackage,
			Screen:            bundle.Observation.Screen,
			Crop:              bundle.Observation.Crop,
			CapturedAt:        bundle.Observation.CapturedAt,
			ExpiresAt:         bundle.Observation.ExpiresAt,
			PNG:               bundle.Observation.PNG,
			Hierarchy:         cloneHierarchy(bundle.Observation.Hierarchy),
		},
		Candidates:        append([]Candidate(nil), bundle.Candidates...),
		ActionCommitCount: bundle.ActionCommitCount,
	}
}
