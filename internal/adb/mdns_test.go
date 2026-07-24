package adb

// 测试用途：验证 ADB mDNS 配对与连接服务解析，并拒绝公网伪造和身份冲突。

import "testing"

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
