package adb

// 功能用途：本文件持久化不含秘密的可信无线设备档案，并按精确 mDNS 身份安全重连。

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

// TrustedDeviceProfile 保存重连所需的非秘密设备身份和最近传输元数据。
type TrustedDeviceProfile struct {
	Identity      string    `json:"identity"`
	Serial        string    `json:"serial"`
	Model         string    `json:"model,omitempty"`
	LastTransport string    `json:"lastTransport"`
	LastEndpoint  string    `json:"lastEndpoint"`
	Capabilities  []string  `json:"capabilities"`
	MDNSInstance  string    `json:"mdnsInstance"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

// TrustedDeviceStore 定义可信设备档案的原子存取契约。
type TrustedDeviceStore interface {
	Save(TrustedDeviceProfile) error
	Load(identity string) (TrustedDeviceProfile, error)
	List() ([]TrustedDeviceProfile, error)
	Delete(identity string) error
}

// FileTrustedDeviceStore 将每个可信身份保存为权限受限的独立 JSON 文件。
type FileTrustedDeviceStore struct {
	directory string
}

// ReconnectResult 描述一次按可信身份恢复无线连接的结果。
type ReconnectResult struct {
	Identity         string `json:"identity"`
	Serial           string `json:"serial"`
	PreviousEndpoint string `json:"previousEndpoint,omitempty"`
	Endpoint         string `json:"endpoint"`
	Message          string `json:"message"`
}

// DefaultTrustedDeviceStore 返回用户配置目录中的默认可信设备档案存储。
func DefaultTrustedDeviceStore() (*FileTrustedDeviceStore, error) {
	configDirectory, err := os.UserConfigDir()
	if err != nil {
		return nil, trustedStoreError("resolve", err)
	}
	return NewFileTrustedDeviceStore(filepath.Join(configDirectory, "aactl", "trusted-devices")), nil
}

// NewFileTrustedDeviceStore 创建使用指定目录的可信设备档案存储。
func NewFileTrustedDeviceStore(directory string) *FileTrustedDeviceStore {
	return &FileTrustedDeviceStore{directory: directory}
}

// Save 使用临时文件原子替换档案，并强制目录 0700、文件 0600 权限。
func (s *FileTrustedDeviceStore) Save(profile TrustedDeviceProfile) error {
	if err := validateTrustedDeviceProfile(profile); err != nil {
		return err
	}
	if err := os.MkdirAll(s.directory, 0o700); err != nil {
		return trustedStoreError("create", err)
	}
	if err := os.Chmod(s.directory, 0o700); err != nil {
		return trustedStoreError("secure", err)
	}
	payload, err := json.Marshal(profile)
	if err != nil {
		return trustedStoreError("encode", err)
	}
	temporary, err := os.CreateTemp(s.directory, ".trusted-*")
	if err != nil {
		return trustedStoreError("create", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		_ = temporary.Close()
		return trustedStoreError("secure", err)
	}
	if _, err := temporary.Write(payload); err != nil {
		_ = temporary.Close()
		return trustedStoreError("write", err)
	}
	if err := temporary.Sync(); err != nil {
		_ = temporary.Close()
		return trustedStoreError("sync", err)
	}
	if err := temporary.Close(); err != nil {
		return trustedStoreError("close", err)
	}
	path := s.path(profile.Identity)
	if err := os.Rename(temporaryPath, path); err != nil {
		return trustedStoreError("replace", err)
	}
	if err := os.Chmod(path, 0o600); err != nil {
		return trustedStoreError("secure", err)
	}
	return nil
}

// Load 读取并严格校验一个可信身份的档案。
func (s *FileTrustedDeviceStore) Load(identity string) (TrustedDeviceProfile, error) {
	if err := ValidateTrustedIdentity(identity); err != nil {
		return TrustedDeviceProfile{}, err
	}
	return s.loadPath(s.path(identity), identity)
}

// List 返回按稳定身份排序的全部可信设备档案。
func (s *FileTrustedDeviceStore) List() ([]TrustedDeviceProfile, error) {
	entries, err := os.ReadDir(s.directory)
	if errors.Is(err, os.ErrNotExist) {
		return []TrustedDeviceProfile{}, nil
	}
	if err != nil {
		return nil, trustedStoreError("list", err)
	}
	profiles := make([]TrustedDeviceProfile, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		profile, loadErr := s.loadPath(filepath.Join(s.directory, entry.Name()), "")
		if loadErr != nil {
			return nil, loadErr
		}
		profiles = append(profiles, profile)
	}
	sort.Slice(profiles, func(left, right int) bool {
		return profiles[left].Identity < profiles[right].Identity
	})
	return profiles, nil
}

// Delete 删除指定可信身份，不存在时视为已清理。
func (s *FileTrustedDeviceStore) Delete(identity string) error {
	if err := ValidateTrustedIdentity(identity); err != nil {
		return err
	}
	err := os.Remove(s.path(identity))
	if err == nil || errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return trustedStoreError("delete", err)
}

func (s *FileTrustedDeviceStore) loadPath(
	path string,
	expectedIdentity string,
) (TrustedDeviceProfile, error) {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return TrustedDeviceProfile{}, apperr.New(
			apperr.CodeDeviceNotFound,
			"No trusted wireless device profile exists for this identity.",
			false,
			map[string]any{"identity": expectedIdentity},
		)
	}
	if err != nil {
		return TrustedDeviceProfile{}, trustedStoreError("inspect", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return TrustedDeviceProfile{}, trustedStoreError(
			"inspect",
			errors.New("trusted device path is not a regular file"),
		)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return TrustedDeviceProfile{}, apperr.New(
			apperr.CodePermissionDenied,
			"The trusted wireless device profile has unsafe permissions.",
			false,
			nil,
		)
	}
	payload, err := os.ReadFile(path)
	if err != nil {
		return TrustedDeviceProfile{}, trustedStoreError("read", err)
	}
	decoder := json.NewDecoder(strings.NewReader(string(payload)))
	decoder.DisallowUnknownFields()
	var profile TrustedDeviceProfile
	if err := decoder.Decode(&profile); err != nil {
		return TrustedDeviceProfile{}, trustedStoreError("decode", err)
	}
	if expectedIdentity != "" && profile.Identity != expectedIdentity {
		return TrustedDeviceProfile{}, trustedStoreError(
			"validate",
			errors.New("trusted device identity mismatch"),
		)
	}
	if err := validateTrustedDeviceProfile(profile); err != nil {
		return TrustedDeviceProfile{}, err
	}
	return profile, nil
}

func (s *FileTrustedDeviceStore) path(identity string) string {
	sum := sha256.Sum256([]byte(identity))
	return filepath.Join(s.directory, hex.EncodeToString(sum[:])+".json")
}

// TrustWirelessDevice 将当前在线 mDNS Wi-Fi 设备保存为非秘密可信档案。
func (c *Client) TrustWirelessDevice(
	ctx context.Context,
	serial string,
	store TrustedDeviceStore,
) (TrustedDeviceProfile, error) {
	if store == nil {
		return TrustedDeviceProfile{}, trustedStoreError("open", errors.New("trusted store is nil"))
	}
	device, err := c.DeviceInfo(ctx, serial)
	if err != nil {
		return TrustedDeviceProfile{}, err
	}
	if device.Transport != "wifi" {
		return TrustedDeviceProfile{}, apperr.New(
			apperr.CodeInvalidArgument,
			"Only an online Wi-Fi ADB device can be trusted for wireless recovery.",
			false,
			map[string]any{"device": serial},
		)
	}
	services, err := c.MDNSServices(ctx)
	if err != nil {
		return TrustedDeviceProfile{}, err
	}
	service, err := matchDeviceService(device, services)
	if err != nil {
		return TrustedDeviceProfile{}, err
	}
	profile := profileFromDevice(device, service, c.now().UTC())
	if err := store.Save(profile); err != nil {
		return TrustedDeviceProfile{}, err
	}
	return profile, nil
}

// ReconnectTrustedDevice 按稳定身份寻找唯一私有端点并验证 ADB TLS 连接结果。
func (c *Client) ReconnectTrustedDevice(
	ctx context.Context,
	identity string,
	store TrustedDeviceStore,
) (ReconnectResult, error) {
	if store == nil {
		return ReconnectResult{}, trustedStoreError("open", errors.New("trusted store is nil"))
	}
	profile, err := store.Load(identity)
	if err != nil {
		return ReconnectResult{}, err
	}
	services, err := c.MDNSServices(ctx)
	if err != nil {
		return ReconnectResult{}, err
	}
	candidates := connectServicesForIdentity(services, profile.Identity)
	if len(candidates) == 0 {
		return ReconnectResult{}, apperr.New(
			apperr.CodeDeviceNotFound,
			"The trusted wireless device is not currently advertised by ADB mDNS.",
			true,
			map[string]any{"identity": profile.Identity},
		)
	}
	if len(candidates) != 1 {
		return ReconnectResult{}, apperr.New(
			apperr.CodeMultipleDevices,
			"The trusted identity is advertised on multiple network interfaces.",
			false,
			map[string]any{"identity": profile.Identity, "count": len(candidates)},
		)
	}
	candidate := candidates[0]
	connection, err := c.Connect(ctx, candidate.Endpoint)
	if err != nil {
		return ReconnectResult{}, err
	}
	createdConnection := !strings.Contains(
		strings.ToLower(connection.Message),
		"already connected",
	)
	devices, err := c.Devices(ctx)
	if err != nil {
		c.cleanupFailedReconnect(ctx, candidate.Endpoint, createdConnection)
		return ReconnectResult{}, err
	}
	device, err := verifyReconnectedDevice(devices, candidate, profile)
	if err != nil {
		c.cleanupFailedReconnect(ctx, candidate.Endpoint, createdConnection)
		return ReconnectResult{}, err
	}
	previousEndpoint := profile.LastEndpoint
	profile.Serial = device.Serial
	profile.LastEndpoint = candidate.Endpoint
	profile.LastTransport = "wifi"
	profile.MDNSInstance = candidate.Instance
	profile.Capabilities = capabilityNames(device.Capabilities)
	profile.UpdatedAt = c.now().UTC()
	if err := store.Save(profile); err != nil {
		c.cleanupFailedReconnect(ctx, candidate.Endpoint, createdConnection)
		return ReconnectResult{}, err
	}
	return ReconnectResult{
		Identity:         profile.Identity,
		Serial:           device.Serial,
		PreviousEndpoint: previousEndpoint,
		Endpoint:         candidate.Endpoint,
		Message:          connection.Message,
	}, nil
}

func (c *Client) cleanupFailedReconnect(
	ctx context.Context,
	endpoint string,
	createdConnection bool,
) {
	if !createdConnection {
		return
	}
	cleanupContext, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	_, _ = c.Disconnect(cleanupContext, endpoint)
}

func matchDeviceService(
	device protocol.Device,
	services []MDNSService,
) (MDNSService, error) {
	identity := identityFromDeviceSerial(device.Serial)
	candidates := make([]MDNSService, 0, 1)
	for _, service := range services {
		if service.Service != MDNSConnectService {
			continue
		}
		if identity != "" && service.Identity == identity {
			candidates = append(candidates, service)
			continue
		}
		if device.Connection.Endpoint != "" &&
			service.Endpoint == device.Connection.Endpoint {
			candidates = append(candidates, service)
		}
	}
	if len(candidates) == 1 {
		return candidates[0], nil
	}
	code := apperr.CodeDeviceNotFound
	message := "The selected Wi-Fi device has no matching ADB mDNS identity."
	if len(candidates) > 1 {
		code = apperr.CodeMultipleDevices
		message = "The selected Wi-Fi device matches multiple ADB mDNS identities."
	}
	return MDNSService{}, apperr.New(
		code,
		message,
		false,
		map[string]any{"device": device.Serial},
	)
}

func verifyReconnectedDevice(
	devices []protocol.Device,
	service MDNSService,
	profile TrustedDeviceProfile,
) (protocol.Device, error) {
	matches := make([]protocol.Device, 0, 1)
	for _, device := range devices {
		exactIdentity := identityFromDeviceSerial(device.Serial) == profile.Identity
		exactEndpoint := device.Serial == service.Endpoint ||
			device.Connection.Endpoint == service.Endpoint
		if exactIdentity || exactEndpoint {
			if device.State != "device" || device.Transport != "wifi" {
				continue
			}
			matches = append(matches, device)
		}
	}
	if len(matches) == 1 {
		if profile.Model != "" && matches[0].Model != "" && profile.Model != matches[0].Model {
			return protocol.Device{}, apperr.New(
				apperr.CodeDeviceUnreachable,
				"The reconnected device identity has an unexpected model.",
				false,
				map[string]any{"identity": profile.Identity},
			)
		}
		return matches[0], nil
	}
	if len(matches) > 1 {
		return protocol.Device{}, apperr.New(
			apperr.CodeMultipleDevices,
			"Multiple connected transports match the trusted wireless identity.",
			false,
			map[string]any{"identity": profile.Identity},
		)
	}
	return protocol.Device{}, apperr.New(
		apperr.CodeDeviceUnreachable,
		"ADB connected an endpoint that did not match the trusted device identity.",
		false,
		map[string]any{"identity": profile.Identity},
	)
}

func connectServicesForIdentity(
	services []MDNSService,
	identity string,
) []MDNSService {
	candidates := make([]MDNSService, 0, 1)
	for _, service := range services {
		if service.Service == MDNSConnectService && service.Identity == identity {
			candidates = append(candidates, service)
		}
	}
	return candidates
}

func profileFromDevice(
	device protocol.Device,
	service MDNSService,
	now time.Time,
) TrustedDeviceProfile {
	return TrustedDeviceProfile{
		Identity:      service.Identity,
		Serial:        device.Serial,
		Model:         device.Model,
		LastTransport: "wifi",
		LastEndpoint:  service.Endpoint,
		Capabilities:  capabilityNames(device.Capabilities),
		MDNSInstance:  service.Instance,
		UpdatedAt:     now,
	}
}

func capabilityNames(capabilities []protocol.Capability) []string {
	names := make([]string, 0, len(capabilities))
	for _, capability := range capabilities {
		if capability.Available {
			names = append(names, capability.Name)
		}
	}
	sort.Strings(names)
	return names
}

func identityFromDeviceSerial(serial string) string {
	normalized := strings.TrimSuffix(serial, ".")
	suffix := "." + MDNSConnectService
	if strings.HasSuffix(strings.ToLower(normalized), strings.ToLower(suffix)) {
		return normalized[:len(normalized)-len(suffix)]
	}
	return ""
}

func validateTrustedDeviceProfile(profile TrustedDeviceProfile) error {
	if err := ValidateTrustedIdentity(profile.Identity); err != nil {
		return err
	}
	if err := ValidateSerial(profile.Serial); err != nil {
		return err
	}
	if profile.LastTransport != "wifi" ||
		profile.MDNSInstance != profile.Identity ||
		profile.UpdatedAt.IsZero() {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Trusted wireless device metadata is invalid.",
			false,
			nil,
		)
	}
	if err := validateLocalMDNSEndpoint(profile.LastEndpoint); err != nil {
		return err
	}
	seen := make(map[string]struct{}, len(profile.Capabilities))
	for _, capability := range profile.Capabilities {
		if capability == "" || len(capability) > 128 {
			return apperr.New(
				apperr.CodeInvalidArgument,
				"Trusted device capability is invalid.",
				false,
				nil,
			)
		}
		if _, duplicate := seen[capability]; duplicate {
			return apperr.New(
				apperr.CodeInvalidArgument,
				"Trusted device capabilities must be unique.",
				false,
				nil,
			)
		}
		seen[capability] = struct{}{}
	}
	return nil
}

// ValidateTrustedIdentity 限制可信身份字符集，避免路径和命令参数注入。
func ValidateTrustedIdentity(identity string) error {
	if !mdnsIdentityPattern.MatchString(identity) {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Trusted wireless device identity is invalid.",
			false,
			map[string]any{"field": "identity"},
		)
	}
	return nil
}

func trustedStoreError(operation string, err error) error {
	return apperr.Wrap(
		apperr.CodeInternal,
		fmt.Sprintf("Could not %s the trusted wireless device profile.", operation),
		false,
		err,
	)
}
