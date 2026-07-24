package adb

// 测试用途：验证 ADB mDNS 配对与连接服务解析，并拒绝公网伪造和身份冲突。

import (
	"errors"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

func TestParseMDNSServicesNormalizesPairingAndConnectRecords(t *testing.T) {
	services, err := ParseMDNSServices(`List of discovered mdns services
adb-R58M1234-workstation _adb-tls-pairing._tcp 192.168.1.8:37123
adb-R58M1234-workstation._adb-tls-connect._tcp. 192.168.1.8:40117
`)
	if err != nil {
		t.Fatalf("ParseMDNSServices() error = %v", err)
	}
	if len(services) != 2 {
		t.Fatalf("len(services) = %d, want 2", len(services))
	}
	foundPairing := false
	foundConnect := false
	for _, service := range services {
		if service.Identity != "adb-R58M1234-workstation" {
			t.Fatalf("service = %#v", service)
		}
		switch service.Service {
		case MDNSPairingService:
			foundPairing = service.Endpoint == "192.168.1.8:37123"
		case MDNSConnectService:
			foundConnect = service.Endpoint == "192.168.1.8:40117"
		}
	}
	if !foundPairing || !foundConnect {
		t.Fatalf("services = %#v, pairing=%v connect=%v", services, foundPairing, foundConnect)
	}
}

func TestParseMDNSServicesRejectsPublicAndDuplicateIdentityEndpoints(t *testing.T) {
	tests := []string{
		"adb-device _adb-tls-connect._tcp 203.0.113.8:40117\n",
		`adb-device _adb-tls-connect._tcp 192.168.1.8:40117
adb-device _adb-tls-connect._tcp 192.168.1.9:40118
`,
	}
	for _, input := range tests {
		if _, err := ParseMDNSServices(input); err == nil {
			t.Fatalf("ParseMDNSServices(%q) error = nil", input)
		}
	}
}

// TestParseMDNSServicesAcceptsIPv6LinkLocalScopedEndpoint 验证支持IPv6 link-local带zone的端点格式
func TestParseMDNSServicesAcceptsIPv6LinkLocalScopedEndpoint(t *testing.T) {
	services, err := ParseMDNSServices(`List of discovered mdns services
adb-device _adb-tls-connect._tcp [fe80::1%en0]:40117
`)
	if err != nil {
		t.Fatalf("ParseMDNSServices() error = %v, expected valid IPv6 scoped endpoint", err)
	}
	if len(services) != 1 {
		t.Fatalf("len(services) = %d, want 1", len(services))
	}
	if services[0].Endpoint != "[fe80::1%en0]:40117" {
		t.Fatalf("endpoint = %q, want [fe80::1%%en0]:40117", services[0].Endpoint)
	}
}

// TestParseMDNSServicesRejectsMaliciousEndpoints 验证拒绝公网IPv6、恶意zone和命令注入
func TestParseMDNSServicesRejectsMaliciousEndpoints(t *testing.T) {
	tests := []struct {
		input string
		code  string
	}{
		// 公网IPv6地址
		{"adb-device _adb-tls-connect._tcp [2001:db8::1]:40117\n", apperr.CodePermissionDenied},
		// zone包含命令注入字符
		{"adb-device _adb-tls-connect._tcp [fe80::1%en0;rm -rf /]:40117\n", apperr.CodeInvalidArgument},
		// zone包含特殊字符
		{"adb-device _adb-tls-connect._tcp [fe80::1%en0$(id)]:40117\n", apperr.CodeInvalidArgument},
		// zone为空
		{"adb-device _adb-tls-connect._tcp [fe80::1%]:40117\n", apperr.CodeInvalidArgument},
		// 非link-local IPv6带zone
		{"adb-device _adb-tls-connect._tcp [2001:db8::1%en0]:40117\n", apperr.CodePermissionDenied},
		// 主机名带命令注入
		{"adb-device _adb-tls-connect._tcp device.local;rm -rf /:40117\n", apperr.CodeInvalidArgument},
	}
	for _, test := range tests {
		_, err := ParseMDNSServices(test.input)
		if err == nil {
			t.Fatalf("ParseMDNSServices(%q) error = nil, expected rejection", test.input)
		}
		var appErr *apperr.Error
		if !errors.As(err, &appErr) {
			t.Fatalf("error = %v, want *apperr.Error for input %q", err, test.input)
		}
		if appErr.Code != test.code {
			t.Fatalf("error code = %q, want %q for input %q", appErr.Code, test.code, test.input)
		}
	}
}
