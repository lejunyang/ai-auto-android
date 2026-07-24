package adb

// 功能用途：本文件解析 ADB 文本输出，并在任何进程调用前校验外部参数。

import (
	"bufio"
	"fmt"
	"net"
	"regexp"
	"strconv"
	"strings"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

var (
	platformToolsVersionPattern = regexp.MustCompile(`(?m)^Version[ \t]+([^\s]+)`)
	statusKeySeparatorPattern   = regexp.MustCompile(`[^a-z0-9]+`)
	serialPattern               = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:%+\-\[\]]{0,254}$`)
	hostPattern                 = regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$`)
	zonePattern                 = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`)
	pairCodePattern             = regexp.MustCompile(`^[0-9]{6}$`)
	getpropPattern              = regexp.MustCompile(`^\[([^\]]+)\]: \[(.*)\]$`)
)

const mdnsConnectService = "_adb-tls-connect._tcp"

// ParseDevices 将 adb devices -l 输出归一化为稳定设备模型。
func ParseDevices(output string) ([]protocol.Device, error) {
	devices := make([]protocol.Device, 0)
	scanner := bufio.NewScanner(strings.NewReader(output))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "List of devices attached") || strings.HasPrefix(line, "* daemon") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		serial := fields[0]
		if err := ValidateSerial(serial); err != nil {
			return nil, fmt.Errorf("parse adb device serial: %w", err)
		}
		state := normalizeState(fields[1])
		attributes := parseAttributes(fields[2:])
		device := protocol.Device{
			Serial:       serial,
			State:        state,
			Transport:    detectTransport(serial, attributes),
			Model:        humanizeADBValue(attributes["model"]),
			Product:      humanizeADBValue(attributes["product"]),
			Capabilities: directCapabilities(state),
		}
		if transportID, err := strconv.Atoi(attributes["transport_id"]); err == nil {
			device.Connection.TransportID = transportID
		}
		switch {
		case isMDNSServiceSerial(serial):
			device.Connection.Endpoint = serial
			device.Connection.MDNSService = mdnsConnectService
		case device.Transport == "wifi":
			device.Connection.Endpoint = serial
		}
		devices = append(devices, device)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read adb devices output: %w", err)
	}
	return devices, nil
}

// ParseVersion 从 adb version 输出提取 Platform-Tools 版本。
func ParseVersion(output string) string {
	match := platformToolsVersionPattern.FindStringSubmatch(output)
	if len(match) != 2 {
		return ""
	}
	return match[1]
}

// ParseServerStatus 将 adb server-status 的键名归一化为稳定格式。
func ParseServerStatus(output string) map[string]string {
	status := make(map[string]string)
	scanner := bufio.NewScanner(strings.NewReader(output))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		key, value, found := strings.Cut(line, ":")
		if !found {
			continue
		}
		normalizedKey := normalizeStatusKey(key)
		if normalizedKey != "" {
			status[normalizedKey] = strings.Trim(strings.TrimSpace(value), `"`)
		}
	}
	return status
}

// ParseProperties 解析 Android getprop 的方括号键值格式。
func ParseProperties(output string) map[string]string {
	properties := make(map[string]string)
	scanner := bufio.NewScanner(strings.NewReader(output))
	for scanner.Scan() {
		match := getpropPattern.FindStringSubmatch(strings.TrimSpace(scanner.Text()))
		if len(match) == 3 {
			properties[match[1]] = match[2]
		}
	}
	return properties
}

// ValidateSerial 限制设备序列号字符集，防止其成为命令选项或注入载荷。
func ValidateSerial(serial string) error {
	if !serialPattern.MatchString(serial) {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Device serial contains unsupported characters.",
			false,
			map[string]any{"field": "device"},
		)
	}
	return nil
}

// ValidateEndpoint 校验无线调试 HOST:PORT 端点及有效端口范围，支持IPv6 link-local scoped地址。
func ValidateEndpoint(endpoint string) error {
	host, portText, err := net.SplitHostPort(endpoint)
	if err != nil {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Endpoint must use HOST:PORT format.",
			false,
			map[string]any{"field": "endpoint"},
		)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Endpoint port must be between 1 and 65535.",
			false,
			map[string]any{"field": "endpoint"},
		)
	}
	if host == "" {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Endpoint host is invalid.",
			false,
			map[string]any{"field": "endpoint"},
		)
	}
	// 处理IPv6 zone（链路本地地址范围）
	rawHost := strings.Trim(host, "[]")
	zone := ""
	if zoneIndex := strings.LastIndex(rawHost, "%"); zoneIndex >= 0 {
		zone = rawHost[zoneIndex+1:]
		rawHost = rawHost[:zoneIndex]
		if !zonePattern.MatchString(zone) {
			return apperr.New(
				apperr.CodeInvalidArgument,
				"Endpoint network zone is invalid.",
				false,
				map[string]any{"field": "endpoint"},
			)
		}
	}
	// 校验IP或hostname
	ip := net.ParseIP(rawHost)
	if ip == nil && !hostPattern.MatchString(rawHost) {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Endpoint host is invalid.",
			false,
			map[string]any{"field": "endpoint"},
		)
	}
	// 保存拆分后的信息供后续私有地址校验使用（通过闭包或返回值？不，当前函数只做格式校验，私有地址校验在validateLocalMDNSEndpoint）
	_ = zone
	return nil
}

// ValidatePairingCode 仅接受 Android 无线调试使用的六位数字配对码。
func ValidatePairingCode(code string) error {
	if !pairCodePattern.MatchString(code) {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Pairing code must contain exactly six digits.",
			false,
			map[string]any{"field": "pairingCode"},
		)
	}
	return nil
}

func parseAttributes(fields []string) map[string]string {
	attributes := make(map[string]string)
	for _, field := range fields {
		key, value, found := strings.Cut(field, ":")
		if found {
			attributes[key] = value
		}
	}
	return attributes
}

func normalizeState(state string) string {
	switch state {
	case "device", "unauthorized", "offline", "bootloader", "recovery", "sideload":
		return state
	default:
		return "unknown"
	}
}

func detectTransport(serial string, attributes map[string]string) string {
	switch {
	case strings.HasPrefix(serial, "emulator-"):
		return "emulator"
	case isMDNSServiceSerial(serial):
		return "wifi"
	case ValidateEndpoint(serial) == nil:
		return "wifi"
	case attributes["usb"] != "":
		return "usb"
	default:
		// ADB 对物理 USB 设备使用硬件序列号，且不保证 devices -l 含 usb: 拓扑属性。
		return "usb"
	}
}

func isMDNSServiceSerial(serial string) bool {
	return strings.Contains(strings.ToLower(serial), mdnsConnectService)
}

func normalizeStatusKey(key string) string {
	normalized := statusKeySeparatorPattern.ReplaceAllString(strings.ToLower(strings.TrimSpace(key)), "_")
	return strings.Trim(normalized, "_")
}

func humanizeADBValue(value string) string {
	return strings.ReplaceAll(value, "_", " ")
}

func directCapabilities(state string) []protocol.Capability {
	available := state == "device"
	permission := "unknown"
	reason := ""
	switch state {
	case "device":
		permission = "granted"
	case "unauthorized":
		permission = "denied"
		reason = "Confirm the workstation RSA fingerprint on the device."
	case "offline":
		reason = "ADB transport exists but the device is not responding."
	default:
		reason = "Device is not in the online ADB state."
	}
	return []protocol.Capability{{
		Name:       "adb.direct",
		Version:    "1.0.0",
		Available:  available,
		Permission: permission,
		Reason:     reason,
	}}
}
