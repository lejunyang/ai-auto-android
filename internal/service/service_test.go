package service

// 本文件验证共享服务的后端路由、前置安全校验和 Bridge 数据最小化契约。

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
)

func TestServiceUsesTypedBackendsAndStableResults(t *testing.T) {
	recordingsFixture, err := os.ReadFile("../bridge/testdata/recording-list-result.json")
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
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
		recordings: recordingsFixture,
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

	recordings, err := automation.ListRecordings(context.Background(), "SERIAL")
	if err != nil {
		t.Fatalf("ListRecordings() error = %v", err)
	}
	if recordings.Device != "SERIAL" ||
		recordings.Count != 1 ||
		len(recordings.Recordings) != 1 ||
		recordings.Recordings[0].Name != "Save a note" ||
		recordings.Recordings[0].StepCount != 3 ||
		recordings.Recordings[0].Requirements.MinAPILevel != 30 ||
		len(recordings.Recordings[0].Requirements.Capabilities) != 2 ||
		bridge.listDevice != "SERIAL" {
		t.Fatalf("recordings = %#v, bridge = %#v", recordings, bridge)
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
		{
			name: "recording list missing device",
			run: func() error {
				_, err := automation.ListRecordings(context.Background(), "")
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

func TestServiceRejectsMalformedRecordingListBeforeReturningData(t *testing.T) {
	tests := []string{
		`{}`,
		`{"recordings":null}`,
		`{"recordings":[{"id":"not-a-uuid","name":"Bad","targetPackages":["com.example.app"],"createdAt":"2026-07-18T01:30:00Z","stepCount":1,"requirements":{"minApiLevel":30,"capabilities":[]}}]}`,
		`{"recordings":[{"id":"123e4567-e89b-42d3-a456-426614174000","name":"Bad","targetPackages":["com.example;bad"],"createdAt":"2026-07-18T01:30:00Z","stepCount":1,"requirements":{"minApiLevel":30,"capabilities":[]}}]}`,
		`{"recordings":[{"id":"123e4567-e89b-42d3-a456-426614174000","name":"Bad","targetPackages":["com.example.app"],"createdAt":"not-a-time","stepCount":1,"requirements":{"minApiLevel":30,"capabilities":[]}}]}`,
		`{"recordings":[{"id":"123e4567-e89b-42d3-a456-426614174000","name":"Bad","targetPackages":["com.example.app"],"createdAt":"2026-07-18T01:30:00Z","stepCount":0,"requirements":{"minApiLevel":30,"capabilities":[]}}]}`,
		`{"recordings":[{"id":"123e4567-e89b-42d3-a456-426614174000","name":"Bad","targetPackages":["com.example.app"],"createdAt":"2026-07-18T01:30:00Z","stepCount":1,"requirements":{"minApiLevel":29,"capabilities":[]}}]}`,
		`{"recordings":[{"id":"123e4567-e89b-42d3-a456-426614174000","name":"Bad","targetPackages":["com.example.app"],"createdAt":"2026-07-18T01:30:00Z","stepCount":1,"requirements":{"minApiLevel":30,"capabilities":["accessibility.action","accessibility.action"]}}]}`,
	}
	for _, response := range tests {
		t.Run(response, func(t *testing.T) {
			bridge := &fakeBridge{recordings: json.RawMessage(response)}
			automation := New(&fakeDirect{}, bridge)

			_, err := automation.ListRecordings(context.Background(), "SERIAL")
			assertCode(t, err, apperr.CodeProtocol)
			if bridge.callCount != 1 {
				t.Fatalf("bridge calls = %d, want 1", bridge.callCount)
			}
		})
	}
}

func TestServiceReturnsOnlySanitizedRecordingSummaryFields(t *testing.T) {
	bridge := &fakeBridge{recordings: json.RawMessage(`{
		"recordings":[{
			"id":"123e4567-e89b-42d3-a456-426614174000",
			"name":"Safe summary",
			"targetPackages":["com.example.app"],
			"createdAt":"2026-07-18T01:30:00Z",
			"stepCount":1,
			"requirements":{"minApiLevel":30,"capabilities":["accessibility.action"]},
			"secret":"must-not-return",
			"variables":[{"name":"account.password","sensitive":true}]
		}]
	}`)}
	automation := New(&fakeDirect{}, bridge)

	result, err := automation.ListRecordings(context.Background(), "SERIAL")
	if err != nil {
		t.Fatalf("ListRecordings() error = %v", err)
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}
	for _, forbidden := range []string{"must-not-return", "account.password", `"secret"`, `"variables"`} {
		if strings.Contains(string(encoded), forbidden) {
			t.Fatalf("sanitized result contains %q: %s", forbidden, encoded)
		}
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
	recordings      json.RawMessage
	replay          json.RawMessage
	callCount       int
	snapshotDevice  string
	snapshotPackage string
	snapshotDepth   int
	listDevice      string
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

func (f *fakeBridge) ListRecordings(
	_ context.Context,
	device string,
) (json.RawMessage, error) {
	f.callCount++
	f.listDevice = device
	return f.recordings, nil
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
