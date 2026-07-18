package adb

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
	pairCodePattern             = regexp.MustCompile(`^[0-9]{6}$`)
	getpropPattern              = regexp.MustCompile(`^\[([^\]]+)\]: \[(.*)\]$`)
)

const mdnsConnectService = "_adb-tls-connect._tcp"

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

func ParseVersion(output string) string {
	match := platformToolsVersionPattern.FindStringSubmatch(output)
	if len(match) != 2 {
		return ""
	}
	return match[1]
}

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
	if host == "" || (net.ParseIP(host) == nil && !hostPattern.MatchString(host)) {
		return apperr.New(
			apperr.CodeInvalidArgument,
			"Endpoint host is invalid.",
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
	return nil
}

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
		// ADB uses plain hardware serials for physical USB devices and does not
		// guarantee that devices -l includes a usb: topology attribute.
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
