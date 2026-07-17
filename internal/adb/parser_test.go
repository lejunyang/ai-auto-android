package adb

import (
	"testing"
)

func TestParseDevicesNormalizesTransportsAndStates(t *testing.T) {
	output := `List of devices attached
R58M1234	device usb:1-2 product:b0q model:SM_S908B device:b0q transport_id:1
192.168.1.8:37123	device product:panther model:Pixel_7 device:panther transport_id:2
emulator-5554	offline product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:3
ABC123	unauthorized transport_id:4
`
	devices, err := ParseDevices(output)
	if err != nil {
		t.Fatalf("ParseDevices() error = %v", err)
	}
	if len(devices) != 4 {
		t.Fatalf("len(devices) = %d, want 4", len(devices))
	}
	if devices[0].Transport != "usb" || devices[0].Model != "SM S908B" {
		t.Fatalf("USB device = %#v", devices[0])
	}
	if devices[1].Transport != "wifi" || devices[1].Connection.Endpoint != devices[1].Serial {
		t.Fatalf("Wi-Fi device = %#v", devices[1])
	}
	if devices[2].Transport != "emulator" || devices[2].State != "offline" {
		t.Fatalf("emulator = %#v", devices[2])
	}
	if devices[3].State != "unauthorized" || devices[3].Capabilities[0].Available {
		t.Fatalf("unauthorized device = %#v", devices[3])
	}
}

func TestParseDevicesRejectsMaliciousSerial(t *testing.T) {
	_, err := ParseDevices("List of devices attached\n--help;rm device\n")
	if err == nil {
		t.Fatal("ParseDevices() error = nil, want invalid serial error")
	}
}

func TestValidateEndpoint(t *testing.T) {
	tests := []struct {
		name     string
		endpoint string
		wantErr  bool
	}{
		{name: "IPv4", endpoint: "192.168.1.10:37123"},
		{name: "IPv6", endpoint: "[fe80::1]:37123"},
		{name: "mDNS host", endpoint: "pixel-7.local:37123"},
		{name: "missing port", endpoint: "192.168.1.10", wantErr: true},
		{name: "shell injection", endpoint: "host;touch /tmp/x:1234", wantErr: true},
		{name: "invalid port", endpoint: "localhost:99999", wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := ValidateEndpoint(test.endpoint)
			if (err != nil) != test.wantErr {
				t.Fatalf("ValidateEndpoint(%q) error = %v, wantErr %v", test.endpoint, err, test.wantErr)
			}
		})
	}
}

func TestParseProperties(t *testing.T) {
	properties := ParseProperties(`[ro.product.manufacturer]: [Google]
[ro.product.model]: [Pixel 8]
[ro.build.version.sdk]: [36]
`)
	if properties["ro.product.model"] != "Pixel 8" || properties["ro.build.version.sdk"] != "36" {
		t.Fatalf("properties = %#v", properties)
	}
}
