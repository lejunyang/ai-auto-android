package adb

import (
	"context"
	"errors"
	"strings"
	"testing"

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
