package service

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

func TestServiceUsesTypedBackendsAndStableResults(t *testing.T) {
	direct := &fakeDirect{
		devices: []protocol.Device{
			{Serial: "SERIAL", State: "device", Transport: "usb", Capabilities: []protocol.Capability{}},
		},
		actionResult: adb.ActionResult{Device: "SERIAL", Action: ActionTap},
	}
	bridge := &fakeBridge{
		snapshot: json.RawMessage(`{
			"root":{
				"packageName":"com.example.app",
				"bounds":{"left":0,"top":0,"right":100,"bottom":200},
				"actions":[],
				"state":{"enabled":true},
				"children":[]
			},
			"maxDepth":12
		}`),
		replay: json.RawMessage(`{
			"scriptId":"123e4567-e89b-42d3-a456-426614174000",
			"startedAtMs":10,
			"finishedAtMs":20,
			"succeeded":true,
			"requiresIntervention":false,
			"steps":[]
		}`),
	}
	automation := New(direct, bridge)

	devices, err := automation.ListDevices(context.Background())
	if err != nil {
		t.Fatalf("ListDevices() error = %v", err)
	}
	if devices.Count != 1 || devices.Devices[0].Serial != "SERIAL" {
		t.Fatalf("devices = %#v", devices)
	}

	observed, err := automation.Observe(context.Background(), ObserveRequest{
		Device:        "SERIAL",
		Kind:          ObserveSemantic,
		TargetPackage: "com.example.app",
		MaxDepth:      12,
	})
	if err != nil {
		t.Fatalf("Observe() error = %v", err)
	}
	if observed.Snapshot == nil ||
		observed.Snapshot.MaxDepth != 12 ||
		observed.Snapshot.Root.PackageName != "com.example.app" {
		t.Fatalf("observed = %#v", observed)
	}
	if bridge.snapshotDevice != "SERIAL" ||
		bridge.snapshotPackage != "com.example.app" ||
		bridge.snapshotDepth != 12 {
		t.Fatalf("snapshot call = %#v", bridge)
	}

	action, err := automation.ExecuteAction(context.Background(), ActionRequest{
		Device: "SERIAL",
		Action: ActionTap,
		X:      10,
		Y:      20,
	})
	if err != nil {
		t.Fatalf("ExecuteAction() error = %v", err)
	}
	if action != direct.actionResult || direct.tapX != 10 || direct.tapY != 20 {
		t.Fatalf("action = %#v, direct = %#v", action, direct)
	}

	replay, err := automation.ReplayRecording(context.Background(), ReplayRecordingRequest{
		Device:   "SERIAL",
		ScriptID: "123e4567-e89b-42d3-a456-426614174000",
	})
	if err != nil {
		t.Fatalf("ReplayRecording() error = %v", err)
	}
	if !replay.Succeeded || replay.Device != "SERIAL" ||
		bridge.replayScript != replay.ScriptID {
		t.Fatalf("replay = %#v, bridge = %#v", replay, bridge)
	}
}

func TestServiceRejectsUnsafeOrImplicitRequestsBeforeBackends(t *testing.T) {
	direct := &fakeDirect{}
	bridge := &fakeBridge{}
	automation := New(direct, bridge)

	tests := []struct {
		name string
		run  func() error
		code string
	}{
		{
			name: "missing device",
			run: func() error {
				_, err := automation.ExecuteAction(context.Background(), ActionRequest{
					Action: ActionTap,
					X:      1,
					Y:      2,
				})
				return err
			},
			code: apperr.CodeInvalidArgument,
		},
		{
			name: "arbitrary shell",
			run: func() error {
				_, err := automation.ExecuteAction(context.Background(), ActionRequest{
					Device: "SERIAL",
					Action: "shell",
				})
				return err
			},
			code: apperr.CodeActionNotAllowed,
		},
		{
			name: "high risk install",
			run: func() error {
				_, err := automation.ExecuteAction(context.Background(), ActionRequest{
					Device:  "SERIAL",
					Action:  "app.install",
					Package: "com.example.app",
				})
				return err
			},
			code: apperr.CodeActionNotAllowed,
		},
		{
			name: "non UUID script",
			run: func() error {
				_, err := automation.ReplayRecording(context.Background(), ReplayRecordingRequest{
					Device:   "SERIAL",
					ScriptID: "../recording",
				})
				return err
			},
			code: apperr.CodeInvalidArgument,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			assertCode(t, test.run(), test.code)
		})
	}
	if direct.callCount != 0 || bridge.callCount != 0 {
		t.Fatalf("unsafe request reached a backend: direct=%d bridge=%d", direct.callCount, bridge.callCount)
	}
}

type fakeDirect struct {
	devices      []protocol.Device
	actionResult adb.ActionResult
	callCount    int
	tapX         int
	tapY         int
}

func (f *fakeDirect) Devices(context.Context) ([]protocol.Device, error) {
	f.callCount++
	return f.devices, nil
}

func (f *fakeDirect) DeviceInfo(context.Context, string) (protocol.Device, error) {
	f.callCount++
	return protocol.Device{}, nil
}

func (f *fakeDirect) Screenshot(context.Context, string) (adb.Screenshot, error) {
	f.callCount++
	return adb.Screenshot{}, nil
}

func (f *fakeDirect) Hierarchy(context.Context, string) (adb.Hierarchy, error) {
	f.callCount++
	return adb.Hierarchy{}, nil
}

func (f *fakeDirect) Tap(_ context.Context, _ string, x, y int) (adb.ActionResult, error) {
	f.callCount++
	f.tapX = x
	f.tapY = y
	return f.actionResult, nil
}

func (f *fakeDirect) Swipe(context.Context, string, int, int, int, int, int) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

func (f *fakeDirect) Text(context.Context, string, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

func (f *fakeDirect) Key(context.Context, string, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

func (f *fakeDirect) Launch(context.Context, string, string, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

func (f *fakeDirect) Stop(context.Context, string, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

type fakeBridge struct {
	snapshot        json.RawMessage
	replay          json.RawMessage
	callCount       int
	snapshotDevice  string
	snapshotPackage string
	snapshotDepth   int
	replayScript    string
}

func (f *fakeBridge) Snapshot(
	_ context.Context,
	device string,
	targetPackage string,
	maxDepth int,
) (json.RawMessage, error) {
	f.callCount++
	f.snapshotDevice = device
	f.snapshotPackage = targetPackage
	f.snapshotDepth = maxDepth
	return f.snapshot, nil
}

func (f *fakeBridge) Action(context.Context, string, json.RawMessage) (json.RawMessage, error) {
	f.callCount++
	return nil, nil
}

func (f *fakeBridge) Replay(
	_ context.Context,
	_ string,
	scriptID string,
) (json.RawMessage, error) {
	f.callCount++
	f.replayScript = scriptID
	return f.replay, nil
}

func assertCode(t *testing.T, err error, code string) {
	t.Helper()
	var appError *apperr.Error
	if !errors.As(err, &appError) {
		t.Fatalf("error = %v, want *apperr.Error", err)
	}
	if appError.Code != code {
		t.Fatalf("error code = %q, want %q", appError.Code, code)
	}
}
