package service

// 测试用途：本文件验证共享服务的后端路由、前置安全校验和 Bridge 数据最小化契约。

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/bridge"
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
	devices             []protocol.Device
	actionResult        adb.ActionResult
	callCount           int
	tapX                int
	tapY                int
	longPressDuration   int
	scrollDirection     adb.ScrollDirection
	scrollFraction      float64
	dragStart           adb.Point
	dragEnd             adb.Point
	dragDuration        int
	multiTapPoint       adb.Point
	multiTaps           int
	pathPoints          []adb.Point
	pathDuration        int
	orientation         adb.Orientation
	inputMethods        []string
	selectedInputMethod string
	deeplinkURI         string
	deeplinkPackage     string
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

// LongPress 与 Scroll 补齐 DirectBackend 的合成手势。
// 记录入参供断言：scroll 的方向与占比是 service 层做默认值填充的地方，
// 不记就无法验证"未指定时用默认值"这条行为。
func (f *fakeDirect) LongPress(
	_ context.Context,
	_ string,
	_ int,
	_ int,
	durationMS int,
) (adb.ActionResult, error) {
	f.callCount++
	f.longPressDuration = durationMS
	return f.actionResult, nil
}

func (f *fakeDirect) Scroll(
	_ context.Context,
	_ string,
	direction adb.ScrollDirection,
	fraction float64,
) (adb.ActionResult, error) {
	f.callCount++
	f.scrollDirection = direction
	f.scrollFraction = fraction
	return f.actionResult, nil
}

// Drag、MultiTap 与 PathGesture 记录入参供断言：这三者的时长与次数
// 都由 service 层做默认值填充，不记就无法验证"未指定时用默认值"。
func (f *fakeDirect) Drag(
	_ context.Context,
	_ string,
	start adb.Point,
	end adb.Point,
	durationMS int,
) (adb.ActionResult, error) {
	f.callCount++
	f.dragStart = start
	f.dragEnd = end
	f.dragDuration = durationMS
	return f.actionResult, nil
}

func (f *fakeDirect) MultiTap(
	_ context.Context,
	_ string,
	point adb.Point,
	taps int,
) (adb.ActionResult, error) {
	f.callCount++
	f.multiTapPoint = point
	f.multiTaps = taps
	return f.actionResult, nil
}

func (f *fakeDirect) PathGesture(
	_ context.Context,
	_ string,
	points []adb.Point,
	durationMS int,
) (adb.ActionResult, error) {
	f.callCount++
	f.pathPoints = points
	f.pathDuration = durationMS
	return f.actionResult, nil
}

func (f *fakeDirect) Text(context.Context, string, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

// 设备状态、IME、deeplink 与文件传输的替身。记录入参供断言：
// 这几项的默认值填充与参数透传都在 service 层，不记就无法验证。
func (f *fakeDirect) Rotate(
	_ context.Context,
	_ string,
	orientation adb.Orientation,
) (adb.ActionResult, error) {
	f.callCount++
	f.orientation = orientation
	return f.actionResult, nil
}

func (f *fakeDirect) Wake(context.Context, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

func (f *fakeDirect) Unlock(context.Context, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

func (f *fakeDirect) ScreenState(
	_ context.Context,
	serial string,
) (adb.ScreenState, error) {
	f.callCount++
	return adb.ScreenState{Device: serial, State: "ON", On: true}, nil
}

func (f *fakeDirect) ListInputMethods(context.Context, string) ([]string, error) {
	f.callCount++
	return f.inputMethods, nil
}

func (f *fakeDirect) SelectInputMethod(
	_ context.Context,
	_ string,
	identifier string,
) (adb.ActionResult, error) {
	f.callCount++
	f.selectedInputMethod = identifier
	return f.actionResult, nil
}

func (f *fakeDirect) HideKeyboard(context.Context, string) (adb.ActionResult, error) {
	f.callCount++
	return f.actionResult, nil
}

func (f *fakeDirect) OpenDeeplink(
	_ context.Context,
	_ string,
	uri, packageName string,
) (adb.ActionResult, error) {
	f.callCount++
	f.deeplinkURI = uri
	f.deeplinkPackage = packageName
	return f.actionResult, nil
}

func (f *fakeDirect) PushFile(
	_ context.Context,
	serial, localPath, remotePath string,
) (adb.FileTransfer, error) {
	f.callCount++
	return adb.FileTransfer{
		Device:     serial,
		Action:     "file.push",
		LocalPath:  localPath,
		RemotePath: remotePath,
	}, nil
}

func (f *fakeDirect) PullFile(
	_ context.Context,
	serial, remotePath, localPath string,
) (adb.FileTransfer, error) {
	f.callCount++
	return adb.FileTransfer{
		Device:     serial,
		Action:     "file.pull",
		LocalPath:  localPath,
		RemotePath: remotePath,
	}, nil
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
	snapshot          json.RawMessage
	recordings        json.RawMessage
	replay            json.RawMessage
	callCount         int
	snapshotDevice    string
	snapshotPackage   string
	snapshotDepth     int
	foregroundDevice  string
	foregroundPackage string
	textDevice        string
	textTarget        bridge.TextTarget
	textValue         string
	listDevice        string
	replayScript      string
}

// Foreground 补齐 BridgeBackend 的活动界面查询。
// 与 Snapshot 一样记录入参供断言，回固定 JSON。
func (f *fakeBridge) Foreground(
	_ context.Context,
	device string,
	targetPackage string,
) (json.RawMessage, error) {
	f.callCount++
	f.foregroundDevice = device
	f.foregroundPackage = targetPackage
	return json.RawMessage(
		`{"package":"com.example.app","className":"android.widget.FrameLayout","source":"activeWindow"}`,
	), nil
}

// SetText 补齐 BridgeBackend 的文本输入。
// 记录入参供断言：定位字段与文本是服务层转换的产物，不记就无法验证转换正确。
func (f *fakeBridge) SetText(
	_ context.Context,
	device string,
	target bridge.TextTarget,
	text string,
) (json.RawMessage, error) {
	f.callCount++
	f.textDevice = device
	f.textTarget = target
	f.textValue = text
	return json.RawMessage(`{"route":"nodeAction","matchScore":1}`), nil
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

// Windows 与 TransientMessages 补齐 BridgeBackend 的多窗口与瞬时提示能力。
// 这里只回可预期的最小结果：相关语义的真实验证在 App 侧单测里。
func (f *fakeBridge) Windows(
	_ context.Context,
	_ string,
	_ string,
	_ int,
) (json.RawMessage, error) {
	return json.RawMessage(`{"windows":[],"maxDepth":10}`), nil
}

func (f *fakeBridge) TransientMessages(
	_ context.Context,
	_ string,
	toastsOnly bool,
) (json.RawMessage, error) {
	if toastsOnly {
		return json.RawMessage(`{"messages":[],"toastsOnly":true}`), nil
	}
	return json.RawMessage(`{"messages":[],"toastsOnly":false}`), nil
}
