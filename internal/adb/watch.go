package adb

// 功能用途：本文件提供有时限、可取消且有事件预算的设备与 mDNS 变化观察。

import (
	"context"
	"reflect"
	"sort"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

const (
	defaultWatchDuration = 10 * time.Second
	defaultWatchInterval = time.Second
	defaultWatchEvents   = 256
	maxWatchDuration     = 5 * time.Minute
	maxWatchEvents       = 4096
)

// WatchOptions 控制设备观察的总时长、轮询间隔和事件预算。
type WatchOptions struct {
	Duration  time.Duration
	Interval  time.Duration
	MaxEvents int
}

// DeviceWatchEvent 表示设备或 mDNS 服务的新增、变化与移除。
type DeviceWatchEvent struct {
	Sequence int              `json:"sequence"`
	Type     string           `json:"type"`
	At       time.Time        `json:"at"`
	Device   *protocol.Device `json:"device,omitempty"`
	Service  *MDNSService     `json:"service,omitempty"`
}

// DeviceWatchResult 汇总一次有界观察及其结束原因。
type DeviceWatchResult struct {
	StartedAt  time.Time          `json:"startedAt"`
	FinishedAt time.Time          `json:"finishedAt"`
	Events     []DeviceWatchEvent `json:"events"`
	Cancelled  bool               `json:"cancelled"`
	Truncated  bool               `json:"truncated"`
}

// WatchDevices 轮询 ADB 设备与 mDNS 服务，并以稳定身份生成有界差异事件。
func (c *Client) WatchDevices(
	ctx context.Context,
	options WatchOptions,
) (DeviceWatchResult, error) {
	normalized, err := normalizeWatchOptions(options)
	if err != nil {
		return DeviceWatchResult{}, err
	}
	startedAt := c.now().UTC()
	result := DeviceWatchResult{
		StartedAt: startedAt,
		Events:    make([]DeviceWatchEvent, 0),
	}
	if ctx.Err() != nil {
		result.Cancelled = true
		result.FinishedAt = c.now().UTC()
		return result, nil
	}

	deadline := time.NewTimer(normalized.Duration)
	defer deadline.Stop()
	ticker := time.NewTicker(normalized.Interval)
	defer ticker.Stop()

	var previousDevices map[string]protocol.Device
	var previousServices map[string]MDNSService
	for {
		devices, snapshotErr := c.Devices(ctx)
		if snapshotErr != nil {
			if ctx.Err() != nil {
				result.Cancelled = true
				result.FinishedAt = c.now().UTC()
				return result, nil
			}
			return DeviceWatchResult{}, snapshotErr
		}
		services, snapshotErr := c.MDNSServices(ctx)
		if snapshotErr != nil {
			if ctx.Err() != nil {
				result.Cancelled = true
				result.FinishedAt = c.now().UTC()
				return result, nil
			}
			return DeviceWatchResult{}, snapshotErr
		}
		currentDevices := indexDevices(devices)
		currentServices := indexMDNSServices(services)
		events := diffWatchSnapshots(
			previousDevices,
			currentDevices,
			previousServices,
			currentServices,
			c.now().UTC(),
		)
		for _, event := range events {
			if len(result.Events) >= normalized.MaxEvents {
				result.Truncated = true
				result.FinishedAt = c.now().UTC()
				return result, nil
			}
			event.Sequence = len(result.Events) + 1
			result.Events = append(result.Events, event)
		}
		previousDevices = currentDevices
		previousServices = currentServices

		select {
		case <-ctx.Done():
			result.Cancelled = true
			result.FinishedAt = c.now().UTC()
			return result, nil
		case <-deadline.C:
			result.FinishedAt = c.now().UTC()
			return result, nil
		case <-ticker.C:
		}
	}
}

func normalizeWatchOptions(options WatchOptions) (WatchOptions, error) {
	if options.Duration == 0 {
		options.Duration = defaultWatchDuration
	}
	if options.Interval == 0 {
		options.Interval = defaultWatchInterval
	}
	if options.MaxEvents == 0 {
		options.MaxEvents = defaultWatchEvents
	}
	if options.Duration < time.Millisecond || options.Duration > maxWatchDuration {
		return WatchOptions{}, apperr.New(
			apperr.CodeInvalidArgument,
			"Watch duration must be between 1ms and 5m.",
			false,
			map[string]any{"field": "duration"},
		)
	}
	if options.Interval < time.Millisecond || options.Interval > 10*time.Second {
		return WatchOptions{}, apperr.New(
			apperr.CodeInvalidArgument,
			"Watch interval must be between 1ms and 10s.",
			false,
			map[string]any{"field": "interval"},
		)
	}
	if options.Interval > options.Duration {
		return WatchOptions{}, apperr.New(
			apperr.CodeInvalidArgument,
			"Watch interval cannot exceed the total duration.",
			false,
			map[string]any{"field": "interval"},
		)
	}
	if options.MaxEvents < 1 || options.MaxEvents > maxWatchEvents {
		return WatchOptions{}, apperr.New(
			apperr.CodeInvalidArgument,
			"Watch max-events must be between 1 and 4096.",
			false,
			map[string]any{"field": "maxEvents"},
		)
	}
	return options, nil
}

func indexDevices(devices []protocol.Device) map[string]protocol.Device {
	indexed := make(map[string]protocol.Device, len(devices))
	for _, device := range devices {
		indexed[device.Serial] = device
	}
	return indexed
}

func indexMDNSServices(services []MDNSService) map[string]MDNSService {
	indexed := make(map[string]MDNSService, len(services))
	for _, service := range services {
		indexed[service.Service+"\x00"+service.Identity] = service
	}
	return indexed
}

func diffWatchSnapshots(
	previousDevices map[string]protocol.Device,
	currentDevices map[string]protocol.Device,
	previousServices map[string]MDNSService,
	currentServices map[string]MDNSService,
	at time.Time,
) []DeviceWatchEvent {
	events := make([]DeviceWatchEvent, 0)
	events = append(events, diffDevices(previousDevices, currentDevices, at)...)
	events = append(events, diffServices(previousServices, currentServices, at)...)
	sort.SliceStable(events, func(left, right int) bool {
		leftKey := watchEventKey(events[left])
		rightKey := watchEventKey(events[right])
		if leftKey == rightKey {
			return events[left].Type < events[right].Type
		}
		return leftKey < rightKey
	})
	return events
}

func diffDevices(
	previous map[string]protocol.Device,
	current map[string]protocol.Device,
	at time.Time,
) []DeviceWatchEvent {
	events := make([]DeviceWatchEvent, 0)
	for serial, device := range current {
		before, exists := previous[serial]
		eventType := ""
		switch {
		case previous == nil || !exists:
			eventType = "device.added"
		case !reflect.DeepEqual(before, device):
			eventType = "device.changed"
		}
		if eventType != "" {
			copyOfDevice := device
			events = append(events, DeviceWatchEvent{
				Type: eventType, At: at, Device: &copyOfDevice,
			})
		}
	}
	for serial, device := range previous {
		if _, exists := current[serial]; !exists {
			copyOfDevice := device
			events = append(events, DeviceWatchEvent{
				Type: "device.removed", At: at, Device: &copyOfDevice,
			})
		}
	}
	return events
}

func diffServices(
	previous map[string]MDNSService,
	current map[string]MDNSService,
	at time.Time,
) []DeviceWatchEvent {
	events := make([]DeviceWatchEvent, 0)
	for key, service := range current {
		before, exists := previous[key]
		eventType := ""
		switch {
		case previous == nil || !exists:
			eventType = "mdns.added"
		case before.Endpoint != service.Endpoint:
			eventType = "mdns.changed"
		}
		if eventType != "" {
			copyOfService := service
			events = append(events, DeviceWatchEvent{
				Type: eventType, At: at, Service: &copyOfService,
			})
		}
	}
	for key, service := range previous {
		if _, exists := current[key]; !exists {
			copyOfService := service
			events = append(events, DeviceWatchEvent{
				Type: "mdns.removed", At: at, Service: &copyOfService,
			})
		}
	}
	return events
}

func watchEventKey(event DeviceWatchEvent) string {
	if event.Device != nil {
		return "device\x00" + event.Device.Serial
	}
	if event.Service != nil {
		return "mdns\x00" + event.Service.Service + "\x00" + event.Service.Identity
	}
	return ""
}
