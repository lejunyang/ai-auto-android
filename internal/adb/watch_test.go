package adb

// 测试用途：验证设备观察流有界、可取消，并报告设备与 mDNS 端点变化。

import (
	"context"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/process"
)

// blockingFakeADB 模拟默认10秒阻塞的ADB调用，验证watch deadline会约束底层调用
type blockingFakeADB struct {
	calls    int
	ctxDone  chan struct{}
	deadline time.Time
	ok       bool
}

func (f *blockingFakeADB) Run(ctx context.Context, _ string, args []string, options process.Options) (process.Result, error) {
	f.calls++
	f.deadline, f.ok = ctx.Deadline()
	done := make(chan struct{})
	f.ctxDone = done
	go func() {
		<-ctx.Done()
		close(done)
	}()
	<-ctx.Done()
	return process.Result{}, ctx.Err()
}

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

// TestWatchDevicesShortDurationBoundsUnderlyingCalls 验证1ms duration会约束底层Devices/MDNSServices调用，不会阻塞10秒默认超时，且无goroutine泄漏
func TestWatchDevicesShortDurationBoundsUnderlyingCalls(t *testing.T) {
	fake := &blockingFakeADB{}
	client := NewClient("/fake/adb", fake)
	start := time.Now()
	result, err := client.WatchDevices(context.Background(), WatchOptions{
		Duration:  time.Millisecond,
		Interval:  time.Millisecond,
		MaxEvents: 8,
	})
	elapsed := time.Since(start)
	// 总耗时必须远小于默认10s超时
	if elapsed > 100*time.Millisecond {
		t.Fatalf("WatchDevices took %v, expected <100ms for 1ms duration", elapsed)
	}
	if err != nil {
		t.Fatalf("WatchDevices() error = %v", err)
	}
	// 结果不是cancelled，是正常deadline到期
	if result.Cancelled {
		t.Fatalf("result.Cancelled = true, expected normal deadline expiry")
	}
	// 底层调用的ctx必须有deadline，且deadline不超过watch duration+容忍时间
	if !fake.ok {
		t.Fatalf("underlying call context has no deadline")
	}
	if time.Until(fake.deadline) > 2*time.Millisecond {
		t.Fatalf("underlying call deadline is too far in future: %v", time.Until(fake.deadline))
	}
	// 等待goroutine退出，验证无泄漏
	select {
	case <-fake.ctxDone:
	case <-time.After(50 * time.Millisecond):
		t.Fatalf("underlying call goroutine leaked after watch exit")
	}
}
