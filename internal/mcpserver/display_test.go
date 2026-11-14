package mcpserver

// 测试用途：验证显示目标的路由、风险面不扩张、管理边界和未接入工具的严格拒绝。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/lejunyang/ai-auto-android/internal/vdisplay"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type displayAutomationFake struct {
	fakeAutomation
	observed service.ObserveRequest
	executed service.ActionRequest
	managed  []service.DisplayRequest
	failure  error
}

func (f *displayAutomationFake) Observe(_ context.Context, r service.ObserveRequest) (service.ObserveResult, error) {
	f.observed = r
	f.calls++
	format := "uiautomator-xml"
	if r.DisplayID != nil {
		format = "uiautomator-windows-xml"
	}
	if r.Kind == "screenshot" {
		format = "png"
	}
	return service.ObserveResult{Device: r.Device, Kind: r.Kind, Format: format, XML: "<displays/>", Image: []byte("png")}, f.failure
}
func (f *displayAutomationFake) ExecuteAction(_ context.Context, r service.ActionRequest) (adb.ActionResult, error) {
	f.executed = r
	f.calls++
	return adb.ActionResult{Device: r.Device, Action: r.Action}, f.failure
}
func (f *displayAutomationFake) ManageDisplay(_ context.Context, r service.DisplayRequest) (any, error) {
	f.managed = append(f.managed, r)
	switch r.Command {
	case "create":
		return vdisplay.Screen{DisplayID: 7, Width: r.Width, Height: r.Height, DPI: r.DPI}, f.failure
	case "close":
		return vdisplay.CloseResult{DisplayID: r.DisplayID, Closed: true}, f.failure
	case "status":
		return vdisplay.Status{Displays: []vdisplay.ScreenStatus{}, KeepAwakeIntervalMS: 2000}, f.failure
	default:
		return vdisplay.ListResult{Displays: []vdisplay.Screen{}}, f.failure
	}
}
func callDisplayTool(t *testing.T, s *mcp.ClientSession, name string, args map[string]any, success bool) *mcp.CallToolResult {
	t.Helper()
	r, e := s.CallTool(context.Background(), &mcp.CallToolParams{Name: name, Arguments: args})
	if success {
		if e != nil || r.IsError {
			t.Fatalf("%s: result=%+v err=%v", name, r, e)
		}
	} else if e == nil && !r.IsError {
		t.Fatalf("%s accepted forbidden input: %+v", name, args)
	}
	return r
}
func TestMCPDisplayTargetsReachService(t *testing.T) {
	f := &displayAutomationFake{}
	s := connectClient(t, f)
	for _, id := range []int{0, 7} {
		for _, kind := range []string{"screenshot", "hierarchy"} {
			r := callDisplayTool(t, s, ToolObserve, map[string]any{"device": "SERIAL", "kind": kind, "displayId": id}, true)
			if f.observed.DisplayID == nil || *f.observed.DisplayID != id || structuredObject(t, r)["displayId"] != float64(id) {
				t.Fatal("display target lost", r)
			}
		}
		for _, a := range []map[string]any{
			{"action": "ui.tap", "x": 1, "y": 2}, {"action": "ui.swipe", "startX": 1, "startY": 2, "endX": 3, "endY": 4},
			{"action": "ui.longClick", "x": 1, "y": 2}, {"action": "ui.setText", "text": "hello"},
			{"action": "ui.pressKey", "key": "BACK"}, {"action": "app.launch", "package": "dev.aiauto.fixture"},
		} {
			a["device"] = "SERIAL"
			a["displayId"] = id
			callDisplayTool(t, s, ToolActionExecute, a, true)
			if f.executed.DisplayID == nil || *f.executed.DisplayID != id {
				t.Fatal("action target lost")
			}
		}
	}
	// 省略目标时不添加输出字段，避免改变既有工具结果。
	r := callDisplayTool(t, s, ToolObserve, map[string]any{"device": "SERIAL", "kind": "hierarchy"}, true)
	if _, ok := structuredObject(t, r)["displayId"]; ok {
		t.Fatal("legacy output changed")
	}
}
func TestMCPDisplayTargetDoesNotExpandActionSurface(t *testing.T) {
	f := &displayAutomationFake{}
	s := connectClient(t, f)
	for _, action := range []string{"app.stop", "device.wake", "device.sleep", "device.unlock", "shell", "ime.show", "file.push", "ui.drag", "ui.scroll"} {
		callDisplayTool(t, s, ToolActionExecute, map[string]any{"device": "SERIAL", "action": action, "displayId": 7}, false)
	}
	for _, id := range []any{-1, 1.5, "0", 2147483648.0} {
		callDisplayTool(t, s, ToolObserve, map[string]any{"device": "SERIAL", "kind": "hierarchy", "displayId": id}, false)
	}
	if f.calls != 0 {
		t.Fatal("forbidden input reached service")
	}
}
func TestMCPUnsupportedToolsRejectDisplayTarget(t *testing.T) {
	f := &displayAutomationFake{}
	s := connectClient(t, f)
	inputs := map[string]map[string]any{
		ToolVisualTargetPropose: {"device": "SERIAL", "expectedPackage": "dev.aiauto.fixture", "target": map[string]any{"label": "test"}},
		ToolVisualActionExecute: {"device": "SERIAL", "expectedPackage": "dev.aiauto.fixture", "target": map[string]any{"label": "test"}, "action": map[string]any{"type": "tap"}},
		ToolWait:                {"device": "SERIAL", "kind": "package", "operator": "equals", "expected": "dev.aiauto.fixture"},
		ToolAssert:              {"device": "SERIAL", "kind": "package", "operator": "equals", "expected": "dev.aiauto.fixture"},
		ToolScrollUntil:         {"device": "SERIAL", "kind": "package", "operator": "equals", "expected": "dev.aiauto.fixture", "direction": "up"},
		ToolBridgeAction:        {"device": "SERIAL", "action": "ui.click", "resourceId": "id"},
		ToolBridgeSetText:       {"device": "SERIAL", "text": "test", "resourceId": "id"},
		ToolImeShow:             {"device": "SERIAL"}, ToolScreenRecord: {"device": "SERIAL", "seconds": 1},
		ToolRecordingReplay: {"device": "SERIAL", "scriptId": "123e4567-e89b-42d3-a456-426614174000"},
	}
	for name, args := range inputs {
		args["displayId"] = 0
		callDisplayTool(t, s, name, args, false)
	}
	for _, kind := range []string{"semantic", "recordings", "foreground", "apps", "windows", "transientMessages"} {
		callDisplayTool(t, s, ToolObserve, map[string]any{"device": "SERIAL", "kind": kind, "displayId": 0}, false)
	}
	if f.calls != 0 {
		t.Fatal("unsupported target reached service")
	}
}
func TestMCPDisplayManagementSurfaceAndErrors(t *testing.T) {
	f := &displayAutomationFake{}
	s := connectClient(t, f)
	for name, args := range map[string]map[string]any{
		"android_display_list": {"device": "SERIAL"}, "android_display_status": {"device": "SERIAL"},
		"android_display_create": {"device": "SERIAL", "width": 720, "height": 1280, "dpi": 240},
		"android_display_close":  {"device": "SERIAL", "displayId": 7},
	} {
		callDisplayTool(t, s, name, args, true)
	}
	if len(f.managed) != 4 {
		t.Fatal("missing management calls")
	}
	for _, name := range []string{"android_display_start", "android_display_stop"} {
		callDisplayTool(t, s, name, map[string]any{"device": "SERIAL"}, false)
	}
	for _, field := range []string{"flags", "socket", "helper", "method", "command", "force"} {
		callDisplayTool(t, s, "android_display_create", map[string]any{"device": "SERIAL", "width": 720, "height": 1280, "dpi": 240, field: "x"}, false)
	}
	callDisplayTool(t, s, "android_display_close", map[string]any{"device": "SERIAL", "displayId": 0}, false)
	for _, code := range []string{apperr.CodeDisplaydNotRunning, apperr.CodeDisplayNotFound, apperr.CodeDeviceLocked, apperr.CodeDisplayAsleep, "DISPLAY_LIMIT"} {
		f.failure = apperr.New(code, "test", false, nil)
		for _, name := range []string{"android_display_list", ToolObserve, ToolActionExecute} {
			args := map[string]any{"device": "SERIAL"}
			if name == ToolObserve {
				args["kind"] = "hierarchy"
				args["displayId"] = 7
			}
			if name == ToolActionExecute {
				args["action"] = "ui.tap"
				args["x"] = 1
				args["y"] = 2
				args["displayId"] = 7
			}
			r := callDisplayTool(t, s, name, args, false)
			if r == nil || !strings.Contains(toolErrorText(t, r), code) {
				t.Fatal("lost error code", code, r)
			}
		}
	}
}

// 真实 helper 客户端与真实 service 共同证明未启动时不执行输入或回退。
func TestMCPMissingDisplaydFailsBeforeInput(t *testing.T) {
	fake := &scriptedADB{results: []process.Result{onlineDevices(), {Stdout: []byte("35")}, {}, {}}, errors: make([]error, 4)}
	c := adb.NewClient("adb", fake)
	s := connectClient(t, service.NewWithDisplays(c, vdisplay.New(c)))
	r := callDisplayTool(t, s, ToolActionExecute, map[string]any{"device": "SERIAL", "displayId": 7, "action": "ui.tap", "x": 1, "y": 2}, false)
	text := toolErrorText(t, r)
	if !strings.Contains(text, "DISPLAYD_NOT_RUNNING") || !strings.Contains(text, "aactl display start") {
		t.Fatal(text)
	}
	for _, args := range fake.calls {
		if strings.Contains(strings.Join(args, " "), " input ") {
			t.Fatal("input after failed ownership")
		}
	}
}

// 独立门只收四种管理操作，不能成为启停、任意方法或其它风险门的旁路。
func TestDisplayManagementGateIsIndependent(t *testing.T) {
	for _, kind := range []displayManagementKind{"list", "status", "create", "close"} {
		if err := requireDisplayManagementAllowed(kind); err != nil {
			t.Fatal(err)
		}
		if requireModelActionAllowed("display."+string(kind)) == nil || requireSemanticBridgeActionAllowed("display."+string(kind)) == nil || requireFileTransferAllowed(fileTransferKind("display."+string(kind))) == nil {
			t.Fatal("management escaped its own gate")
		}
	}
	for _, kind := range []displayManagementKind{"start", "stop", "shutdown", "shell", "display.create", ""} {
		if requireDisplayManagementAllowed(kind) == nil {
			t.Fatal("unsafe management allowed", kind)
		}
	}
}

// 每个未接入工具从有效基础输入出发添加字段，避免因缺必填字段而得到空洞的拒绝结论。
func TestMCPDisplaySchemasRejectOutOfRangeAndExtraFields(t *testing.T) {
	f := &displayAutomationFake{}
	s := connectClient(t, f)
	for _, dims := range [][3]int{{127, 1280, 240}, {2049, 1280, 240}, {720, 127, 240}, {720, 2049, 240}, {720, 1280, 71}, {720, 1280, 641}} {
		callDisplayTool(t, s, ToolDisplayCreate, map[string]any{"device": "SERIAL", "width": dims[0], "height": dims[1], "dpi": dims[2]}, false)
	}
	if len(f.managed) != 0 {
		t.Fatal("invalid dimensions reached management")
	}
	for _, name := range []string{ToolDisplayList, ToolDisplayStatus} {
		for _, field := range []string{"displayId", "helper", "flags", "command"} {
			callDisplayTool(t, s, name, map[string]any{"device": "SERIAL", field: 7}, false)
		}
	}
	if len(f.managed) != 0 {
		t.Fatal("extra fields reached management")
	}
}

// 经过真实service与ADB解析器验证新错误映射；不归属的屏仍在读取窗口之前拒绝。
type mcpOwnedDisplays struct{ service.DisplayBackend }

func (*mcpOwnedDisplays) RequireDisplay(_ context.Context, _ string, id int) error {
	if id == 7 {
		return nil
	}
	return apperr.New(apperr.CodeDisplayNotFound, "Not helper-owned", false, nil)
}
func TestMCPEmptyDisplayAndMissingDisplayRemainDistinct(t *testing.T) {
	for _, id := range []int{0, 7, 9} {
		fake := &scriptedADB{results: []process.Result{onlineDevices(), {Stdout: []byte("35")}, {}, {Stdout: []byte("<displays/>")}, {}}, errors: make([]error, 5)}
		client := adb.NewClient("adb", fake)
		session := connectClient(t, service.NewWithDisplays(client, &mcpOwnedDisplays{}))
		r := callDisplayTool(t, session, ToolObserve, map[string]any{"device": "SERIAL", "kind": "hierarchy", "displayId": id}, false)
		want := "DISPLAY_EMPTY"
		calls := 5
		if id == 9 {
			want = apperr.CodeDisplayNotFound
			calls = 0
		}
		if !strings.Contains(toolErrorText(t, r), `"code":"`+want+`"`) || len(fake.calls) != calls {
			t.Fatal("wrong error/route", toolErrorText(t, r), fake.calls)
		}
	}
}
