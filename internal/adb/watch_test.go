package adb

// 测试用途：验证设备观察流有界、可取消，并报告设备与 mDNS 端点变化。

import (
	"context"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/process"
)

func TestWatchDevicesReportsInitialAndChangedMDNSEvents(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1 model:Pixel_8\n")},
			{Stdout: []byte("adb-device _adb-tls-connect._tcp 192.168.1.8:40117\n")},
			{Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1 model:Pixel_8\n")},
			{Stdout: []byte("adb-device _adb-tls-connect._tcp 192.168.1.8:40222\n")},
		},
		errors: []error{nil, nil, nil, nil},
	}
	client := NewClient("/fake/adb", fake)
	result, err := client.WatchDevices(context.Background(), WatchOptions{
		Duration:  4 * time.Millisecond,
		Interval:  time.Millisecond,
		MaxEvents: 16,
	})
	if err != nil {
		t.Fatalf("WatchDevices() error = %v", err)
	}
	if len(result.Events) < 3 {
		t.Fatalf("events = %#v, want initial device/service and changed service", result.Events)
	}
	foundChanged := false
	for _, event := range result.Events {
		if event.Type == "mdns.changed" && event.Service != nil &&
			event.Service.Endpoint == "192.168.1.8:40222" {
			foundChanged = true
		}
	}
	if !foundChanged {
		t.Fatalf("events = %#v, missing mdns.changed", result.Events)
	}
	if len(fake.calls) > 8 {
		t.Fatalf("ADB calls = %d, watch must remain bounded", len(fake.calls))
	}
}

func TestWatchDevicesReturnsPartialResultWhenCancelled(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte("List of devices attached\n")},
			{Stdout: []byte("")},
		},
		errors: []error{nil, nil},
	}
	client := NewClient("/fake/adb", fake)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	result, err := client.WatchDevices(ctx, WatchOptions{
		Duration:  time.Second,
		Interval:  time.Millisecond,
		MaxEvents: 8,
	})
	if err != nil {
		t.Fatalf("WatchDevices() error = %v", err)
	}
	if !result.Cancelled {
		t.Fatalf("result = %#v, want cancelled partial result", result)
	}
}
