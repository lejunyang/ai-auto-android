// Package adb 封装官方 ADB 的设备诊断、发现、观察、动作和端口转发能力。
package adb

import (
	"context"
	"errors"
	"fmt"
	"net"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

const (
	defaultTimeout   = 10 * time.Second
	defaultMaxOutput = 1024 * 1024
)

// Client 通过受限进程执行器调用指定 ADB，并统一校验设备状态和错误。
type Client struct {
	path      string
	executor  process.Executor
	timeout   time.Duration
	maxOutput int
	dial      func(network, address string, timeout time.Duration) (net.Conn, error)
	goos      string
}

// DoctorCheck 表示一项可独立定位故障的 ADB 诊断结果。
type DoctorCheck struct {
	Name    string         `json:"name"`
	Passed  bool           `json:"passed"`
	Message string         `json:"message"`
	Details map[string]any `json:"details,omitempty"`
}

// DoctorReport 汇总 ADB 可执行文件、服务端口、mDNS 和平台诊断信息。
type DoctorReport struct {
	Healthy    bool           `json:"healthy"`
	ADBPath    string         `json:"adbPath"`
	ADBVersion string         `json:"adbVersion"`
	Platform   string         `json:"platform"`
	Checks     []DoctorCheck  `json:"checks"`
	Guidance   DoctorGuidance `json:"guidance"`
}

// DoctorGuidance 提供 USB 与当前桌面平台对应的恢复建议。
type DoctorGuidance struct {
	USB      []string `json:"usb"`
	Platform []string `json:"platform,omitempty"`
}

// ConnectionResult 返回无线配对或连接的规范化结果。
type ConnectionResult struct {
	Endpoint string `json:"endpoint"`
	Message  string `json:"message"`
}

// NewClient 使用明确的 ADB 路径和进程执行器创建客户端。
func NewClient(path string, executor process.Executor) *Client {
	return &Client{
		path:      path,
		executor:  executor,
		timeout:   defaultTimeout,
		maxOutput: defaultMaxOutput,
		dial:      net.DialTimeout,
		goos:      runtime.GOOS,
	}
}

// Doctor 检查 ADB 版本、服务状态、5037 端口和 mDNS，不主动重启共享服务。
func (c *Client) Doctor(ctx context.Context) (DoctorReport, error) {
	report := DoctorReport{
		Healthy:  true,
		ADBPath:  c.path,
		Platform: c.goos,
		Checks:   make([]DoctorCheck, 0, 4),
		Guidance: doctorGuidance(c.goos),
	}

	versionResult, err := c.run(ctx, []string{"version"}, process.Options{})
	if err != nil {
		return DoctorReport{}, err
	}
	report.ADBVersion = ParseVersion(string(versionResult.Stdout))
	if report.ADBVersion == "" {
		return DoctorReport{}, apperr.New(
			apperr.CodeInternal,
			"ADB version output was not recognized.",
			false,
			nil,
		)
	}
	report.Checks = append(report.Checks, DoctorCheck{
		Name: "adb.version", Passed: true, Message: "ADB executable is available.",
		Details: map[string]any{"version": report.ADBVersion},
	})

	statusResult, statusErr := c.run(ctx, []string{"server-status"}, process.Options{})
	status := ParseServerStatus(string(statusResult.Stdout))
	if statusErr != nil || len(status) == 0 {
		report.Healthy = false
		message := "ADB server status is unavailable."
		if statusErr == nil {
			message = "ADB server status output was not recognized."
		}
		report.Checks = append(report.Checks, DoctorCheck{
			Name: "adb.server", Passed: false, Message: message,
		})
	} else {
		report.Checks = append(report.Checks, DoctorCheck{
			Name: "adb.server", Passed: true, Message: "ADB server status is available.",
			Details: map[string]any{
				"version":     firstNonEmpty(status["adb_server_version"], status["server_version"], status["version"]),
				"serverPort":  status["server_port"],
				"usbBackend":  status["usb_backend"],
				"mdnsBackend": status["mdns_backend"],
				"mdnsEnabled": status["mdns_enabled"],
			},
		})
	}

	connection, dialErr := c.dial("tcp", "127.0.0.1:5037", 250*time.Millisecond)
	if dialErr == nil && connection != nil {
		_ = connection.Close()
	}
	report.Checks = append(report.Checks, DoctorCheck{
		Name:    "adb.port.5037",
		Passed:  dialErr == nil,
		Message: map[bool]string{true: "ADB server is listening on loopback port 5037.", false: "Nothing is listening on loopback port 5037."}[dialErr == nil],
		Details: map[string]any{"address": "127.0.0.1:5037"},
	})
	if dialErr != nil {
		report.Healthy = false
	}

	mdnsResult, mdnsErr := c.run(ctx, []string{"mdns", "check"}, process.Options{})
	mdnsMessage := strings.TrimSpace(string(mdnsResult.Stdout))
	if mdnsMessage == "" {
		mdnsMessage = strings.TrimSpace(string(mdnsResult.Stderr))
	}
	if mdnsMessage == "" && mdnsErr == nil {
		mdnsMessage = "ADB mDNS check passed."
	}
	report.Checks = append(report.Checks, DoctorCheck{
		Name:    "adb.mdns",
		Passed:  mdnsErr == nil,
		Message: firstNonEmpty(mdnsMessage, "ADB mDNS check is unavailable."),
		Details: map[string]any{
			"backend": status["mdns_backend"],
			"enabled": status["mdns_enabled"],
		},
	})
	if mdnsErr != nil {
		report.Healthy = false
	}

	return report, nil
}

func doctorGuidance(goos string) DoctorGuidance {
	guidance := DoctorGuidance{
		USB: []string{
			"Use a data-capable USB cable, keep the device unlocked, enable USB debugging, select a data-transfer USB mode, and retry another direct USB port.",
		},
	}
	switch goos {
	case "windows":
		guidance.Platform = []string{
			"Install or update the device manufacturer's official OEM USB driver in Device Manager, then reconnect the device.",
		}
	case "linux":
		guidance.Platform = []string{
			"Install the distribution's Android udev rules, add the user to the required device-access group, reload udev rules, and reconnect the device.",
		}
	}
	return guidance
}

// Devices 返回经过传输类型和能力归一化的所有 ADB 设备。
func (c *Client) Devices(ctx context.Context) ([]protocol.Device, error) {
	result, err := c.run(ctx, []string{"devices", "-l"}, process.Options{})
	if err != nil {
		return nil, err
	}
	if result.OutputTruncated {
		return nil, apperr.New(apperr.CodeInternal, "ADB device output exceeded the safety limit.", false, nil)
	}
	devices, parseErr := ParseDevices(string(result.Stdout))
	if parseErr != nil {
		return nil, apperr.Wrap(apperr.CodeInternal, "ADB device output could not be parsed.", false, parseErr)
	}
	return devices, nil
}

// Pair 通过标准输入向 ADB 传递一次性无线配对码，并确保输出脱敏。
func (c *Client) Pair(ctx context.Context, endpoint, pairingCode string) (ConnectionResult, error) {
	if err := ValidateEndpoint(endpoint); err != nil {
		return ConnectionResult{}, err
	}
	if err := ValidatePairingCode(pairingCode); err != nil {
		return ConnectionResult{}, err
	}
	result, err := c.run(ctx, []string{"pair", endpoint}, process.Options{
		Stdin:      []byte(pairingCode + "\n"),
		Redactions: []string{pairingCode},
		Timeout:    30 * time.Second,
	})
	if err != nil {
		return ConnectionResult{}, err
	}
	message := strings.TrimSpace(string(result.Stdout))
	if message == "" {
		message = strings.TrimSpace(string(result.Stderr))
	}
	if strings.Contains(strings.ToLower(message), "failed") {
		return ConnectionResult{}, apperr.New(
			apperr.CodeDeviceUnreachable,
			"ADB wireless pairing failed.",
			true,
			map[string]any{"endpoint": endpoint},
		)
	}
	return ConnectionResult{Endpoint: endpoint, Message: firstNonEmpty(message, "Paired successfully.")}, nil
}

// Connect 连接已完成信任建立的 ADB 无线调试端点。
func (c *Client) Connect(ctx context.Context, endpoint string) (ConnectionResult, error) {
	if err := ValidateEndpoint(endpoint); err != nil {
		return ConnectionResult{}, err
	}
	result, err := c.run(ctx, []string{"connect", endpoint}, process.Options{})
	if err != nil {
		return ConnectionResult{}, err
	}
	message := strings.TrimSpace(string(result.Stdout))
	if message == "" {
		message = strings.TrimSpace(string(result.Stderr))
	}
	lowerMessage := strings.ToLower(message)
	if strings.Contains(lowerMessage, "failed") || strings.Contains(lowerMessage, "cannot") {
		return ConnectionResult{}, apperr.New(
			apperr.CodeDeviceUnreachable,
			"ADB could not connect to the wireless device.",
			true,
			map[string]any{"endpoint": endpoint},
		)
	}
	return ConnectionResult{Endpoint: endpoint, Message: firstNonEmpty(message, "Connected successfully.")}, nil
}

// DeviceInfo 获取明确选定且在线设备的系统属性和规范化能力。
func (c *Client) DeviceInfo(ctx context.Context, serial string) (protocol.Device, error) {
	if err := ValidateSerial(serial); err != nil {
		return protocol.Device{}, err
	}
	devices, err := c.Devices(ctx)
	if err != nil {
		return protocol.Device{}, err
	}
	device, err := selectDevice(devices, serial)
	if err != nil {
		return protocol.Device{}, err
	}
	if err := ensureOnline(device); err != nil {
		return protocol.Device{}, err
	}

	result, err := c.run(ctx, []string{"-s", serial, "shell", "getprop"}, process.Options{})
	if err != nil {
		return protocol.Device{}, err
	}
	properties := ParseProperties(string(result.Stdout))
	device.Manufacturer = properties["ro.product.manufacturer"]
	if model := properties["ro.product.model"]; model != "" {
		device.Model = model
	}
	if product := properties["ro.product.name"]; product != "" {
		device.Product = product
	}
	device.AndroidVersion = properties["ro.build.version.release"]
	if apiLevel, parseErr := strconv.Atoi(properties["ro.build.version.sdk"]); parseErr == nil {
		device.APILevel = apiLevel
	}
	return device, nil
}

func selectDevice(devices []protocol.Device, serial string) (protocol.Device, error) {
	if serial == "" {
		switch len(devices) {
		case 0:
			return protocol.Device{}, apperr.New(apperr.CodeDeviceNotFound, "No ADB device was found.", true, nil)
		case 1:
			return devices[0], nil
		default:
			return protocol.Device{}, apperr.New(
				apperr.CodeMultipleDevices,
				"Multiple devices are connected; specify --device.",
				false,
				map[string]any{"count": len(devices)},
			)
		}
	}
	for _, device := range devices {
		if device.Serial == serial {
			return device, nil
		}
	}
	return protocol.Device{}, apperr.New(
		apperr.CodeDeviceNotFound,
		"The requested ADB device was not found.",
		true,
		map[string]any{"device": serial},
	)
}

func ensureOnline(device protocol.Device) error {
	switch device.State {
	case "device":
		return nil
	case "unauthorized":
		return apperr.New(
			apperr.CodeADBUnauthorized,
			"Confirm the workstation RSA fingerprint on the Android device.",
			true,
			map[string]any{"device": device.Serial},
		)
	case "offline":
		return apperr.New(
			apperr.CodeDeviceOffline,
			"The ADB device is offline.",
			true,
			map[string]any{"device": device.Serial},
		)
	default:
		return apperr.New(
			apperr.CodeDeviceUnreachable,
			fmt.Sprintf("The ADB device is in state %q.", device.State),
			true,
			map[string]any{"device": device.Serial, "state": device.State},
		)
	}
}

func (c *Client) run(ctx context.Context, args []string, options process.Options) (process.Result, error) {
	if options.Timeout <= 0 {
		options.Timeout = c.timeout
	}
	if options.MaxOutput <= 0 {
		options.MaxOutput = c.maxOutput
	}
	result, err := c.executor.Run(ctx, c.path, args, options)
	if err == nil {
		return result, nil
	}
	if errors.Is(err, process.ErrTimeout) {
		return result, apperr.New(
			apperr.CodeDeadlineExceeded,
			"ADB command exceeded its deadline.",
			true,
			nil,
		)
	}
	combinedOutput := strings.ToLower(string(result.Stdout) + "\n" + string(result.Stderr))
	switch {
	case strings.Contains(combinedOutput, "unauthorized"):
		return result, apperr.New(apperr.CodeADBUnauthorized, "Confirm the workstation RSA fingerprint on the Android device.", true, nil)
	case strings.Contains(combinedOutput, "offline"):
		return result, apperr.New(apperr.CodeDeviceOffline, "The ADB device is offline.", true, nil)
	case strings.Contains(combinedOutput, "no devices") || strings.Contains(combinedOutput, "device not found"):
		return result, apperr.New(apperr.CodeDeviceNotFound, "No matching ADB device was found.", true, nil)
	default:
		return result, apperr.New(
			apperr.CodeDeviceUnreachable,
			"ADB command failed.",
			true,
			map[string]any{"exitCode": result.ExitCode},
		)
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
