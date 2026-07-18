package adb

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

type fakeADB struct {
	results []process.Result
	errors  []error
	calls   [][]string
	options []process.Options
}

func (f *fakeADB) Run(_ context.Context, _ string, args []string, options process.Options) (process.Result, error) {
	f.calls = append(f.calls, append([]string(nil), args...))
	f.options = append(f.options, options)
	index := len(f.calls) - 1
	if index >= len(f.results) {
		return process.Result{}, nil
	}
	return f.results[index], f.errors[index]
}

func TestDoctorParsesFakeADBPlatformToolsServerPortAndMDNS(t *testing.T) {
	client, fake := healthyDoctorClient()

	report, err := client.Doctor(context.Background())
	if err != nil {
		t.Fatalf("Doctor() error = %v", err)
	}
	if !report.Healthy || report.ADBVersion != "36.0.0-13206524" {
		t.Fatalf("report = %#v", report)
	}
	if len(report.Checks) != 4 {
		t.Fatalf("len(report.Checks) = %d, want 4", len(report.Checks))
	}
	server := report.Checks[1]
	if !server.Passed ||
		server.Details["version"] != "41" ||
		server.Details["serverPort"] != "5037" ||
		server.Details["usbBackend"] != "libusb" ||
		server.Details["mdnsBackend"] != "openscreen" ||
		server.Details["mdnsEnabled"] != "true" {
		t.Fatalf("server check = %#v", server)
	}
	if !report.Checks[2].Passed || report.Checks[2].Name != "adb.port.5037" {
		t.Fatalf("port check = %#v", report.Checks[2])
	}
	if !report.Checks[3].Passed ||
		report.Checks[3].Message != "mdns daemon version [Openscreen discovery 0.0.0]" {
		t.Fatalf("mDNS check = %#v", report.Checks[3])
	}
	assertArgs(t, fake.calls[0], "version")
	assertArgs(t, fake.calls[1], "server-status")
	assertArgs(t, fake.calls[2], "mdns", "check")
}

func TestDoctorProvidesActionableUSBGuidance(t *testing.T) {
	tests := []struct {
		goos             string
		expectedPlatform string
		unexpected       string
	}{
		{goos: "windows", expectedPlatform: "OEM USB driver", unexpected: "udev rules"},
		{goos: "linux", expectedPlatform: "udev rules", unexpected: "OEM USB driver"},
	}
	for _, test := range tests {
		t.Run(test.goos, func(t *testing.T) {
			client, _ := healthyDoctorClient()
			client.goos = test.goos

			report, err := client.Doctor(context.Background())
			if err != nil {
				t.Fatalf("Doctor() error = %v", err)
			}
			encoded, err := json.Marshal(report)
			if err != nil {
				t.Fatalf("json.Marshal() error = %v", err)
			}
			text := string(encoded)
			for _, expected := range []string{"data-capable USB cable", test.expectedPlatform} {
				if !strings.Contains(text, expected) {
					t.Errorf("Doctor() JSON does not contain actionable %q guidance: %s", expected, text)
				}
			}
			if strings.Contains(text, test.unexpected) {
				t.Errorf("Doctor() JSON contains guidance for another platform %q: %s", test.unexpected, text)
			}
		})
	}
}

func TestDoctorReportsServerPortAndMDNSFailuresIndependently(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("Android Debug Bridge version 1.0.41\nVersion 36.0.0-13206524\n")},
			{Stderr: []byte("server-status unavailable"), ExitCode: 1},
			{Stderr: []byte("mdns unavailable"), ExitCode: 1},
		},
		errors: []error{nil, errors.New("exit status 1"), errors.New("exit status 1")},
	}
	client := NewClient("/fake/adb", fake)
	client.dial = func(string, string, time.Duration) (net.Conn, error) {
		return nil, errors.New("connection refused")
	}

	report, err := client.Doctor(context.Background())
	if err != nil {
		t.Fatalf("Doctor() error = %v", err)
	}
	if report.Healthy {
		t.Fatalf("report.Healthy = true, checks = %#v", report.Checks)
	}
	for _, index := range []int{1, 2, 3} {
		if report.Checks[index].Passed {
			t.Errorf("check %q passed, want failure", report.Checks[index].Name)
		}
	}
}

func healthyDoctorClient() (*Client, *fakeADB) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(`Android Debug Bridge version 1.0.41
Version 36.0.0-13206524
Installed as /opt/android-sdk/platform-tools/adb
`)},
			{Stdout: []byte(`ADB Server version: 41
Server port: 5037
USB backend: libusb
mDNS backend: openscreen
mDNS enabled: true
`)},
			{Stdout: []byte("mdns daemon version [Openscreen discovery 0.0.0]\n")},
		},
		errors: []error{nil, nil, nil},
	}
	client := NewClient("/fake/adb", fake)
	client.dial = func(string, string, time.Duration) (net.Conn, error) {
		server, connection := net.Pipe()
		_ = server.Close()
		return connection, nil
	}
	return client, fake
}

func TestDeviceInfoReadsPropertiesForExplicitOnlineDevice(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nSERIAL123\tdevice usb:1-1 model:Old_Model transport_id:1\n")},
			{Stdout: []byte(`[ro.product.manufacturer]: [Google]
[ro.product.model]: [Pixel 8]
[ro.product.name]: [shiba]
[ro.build.version.release]: [16]
[ro.build.version.sdk]: [36]
`)},
		},
		errors: []error{nil, nil},
	}
	client := NewClient("/fake/adb", fake)
	device, err := client.DeviceInfo(context.Background(), "SERIAL123")
	if err != nil {
		t.Fatalf("DeviceInfo() error = %v", err)
	}
	if device.Manufacturer != "Google" || device.Model != "Pixel 8" || device.APILevel != 36 {
		t.Fatalf("device = %#v", device)
	}
	wantArgs := []string{"-s", "SERIAL123", "shell", "getprop"}
	if strings.Join(fake.calls[1], "\x00") != strings.Join(wantArgs, "\x00") {
		t.Fatalf("getprop args = %#v, want %#v", fake.calls[1], wantArgs)
	}
}

func TestDeviceInfoRejectsUnauthorizedDevice(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{{Stdout: []byte("List of devices attached\nSERIAL123\tunauthorized\n")}},
		errors:  []error{nil},
	}
	_, err := NewClient("/fake/adb", fake).DeviceInfo(context.Background(), "SERIAL123")
	assertAppErrorCode(t, err, apperr.CodeADBUnauthorized)
	if len(fake.calls) != 1 {
		t.Fatalf("calls = %d, getprop must not run for unauthorized device", len(fake.calls))
	}
}

func TestDeviceInfoRejectsOfflineDevice(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{{Stdout: []byte("List of devices attached\nSERIAL123\toffline\n")}},
		errors:  []error{nil},
	}
	_, err := NewClient("/fake/adb", fake).DeviceInfo(context.Background(), "SERIAL123")
	assertAppErrorCode(t, err, apperr.CodeDeviceOffline)
}

func TestSelectDeviceRequiresExplicitSerialForMultipleDevices(t *testing.T) {
	_, err := selectDevice([]protocol.Device{
		{Serial: "A", State: "device"},
		{Serial: "B", State: "device"},
	}, "")
	assertAppErrorCode(t, err, apperr.CodeMultipleDevices)
}

func TestDevicesMapsTimeout(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{{}},
		errors:  []error{process.ErrTimeout},
	}
	_, err := NewClient("/fake/adb", fake).Devices(context.Background())
	assertAppErrorCode(t, err, apperr.CodeDeadlineExceeded)
}

func TestPairSendsCodeOnlyThroughStdinAndMarksItForRedaction(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{{Stdout: []byte("Successfully paired")}},
		errors:  []error{nil},
	}
	_, err := NewClient("/fake/adb", fake).Pair(context.Background(), "192.168.1.8:37123", "123456")
	if err != nil {
		t.Fatalf("Pair() error = %v", err)
	}
	if strings.Contains(strings.Join(fake.calls[0], " "), "123456") {
		t.Fatalf("pairing code leaked into argv: %#v", fake.calls[0])
	}
	if string(fake.options[0].Stdin) != "123456\n" {
		t.Fatalf("stdin = %q", fake.options[0].Stdin)
	}
	if len(fake.options[0].Redactions) != 1 || fake.options[0].Redactions[0] != "123456" {
		t.Fatalf("redactions = %#v", fake.options[0].Redactions)
	}
}

func TestConnectRejectsInjectionBeforeStartingADB(t *testing.T) {
	fake := &fakeADB{}
	_, err := NewClient("/fake/adb", fake).Connect(context.Background(), "host;rm -rf /:5555")
	assertAppErrorCode(t, err, apperr.CodeInvalidArgument)
	if len(fake.calls) != 0 {
		t.Fatalf("ADB was called with invalid endpoint: %#v", fake.calls)
	}
}

func TestADBFailureMapsOfflineOutput(t *testing.T) {
	exitErr := errors.New("exit")
	fake := &fakeADB{
		results: []process.Result{{Stderr: []byte("error: device offline"), ExitCode: 1}},
		errors:  []error{exitErr},
	}
	_, err := NewClient("/fake/adb", fake).Devices(context.Background())
	assertAppErrorCode(t, err, apperr.CodeDeviceOffline)
}

func assertAppErrorCode(t *testing.T, err error, code string) {
	t.Helper()
	var appError *apperr.Error
	if !errors.As(err, &appError) {
		t.Fatalf("error = %v, want *apperr.Error", err)
	}
	if appError.Code != code {
		t.Fatalf("error code = %q, want %q", appError.Code, code)
	}
}
