package service

// 测试用途：验证虚拟屏观察与动作的路由，辅助进程失败时绝不调用主屏后端。

import (
	"context"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/vdisplay"
	"testing"
)

func assertServiceCode(t *testing.T, err error, code string) {
	t.Helper()
	var e *apperr.Error
	if !errors.As(err, &e) || e.Code != code {
		t.Fatalf("error=%v, want %s", err, code)
	}
}

type fakeDisplays struct {
	DisplayBackend
	err                error
	captures, requires int
}

// 管理层独立校验并规范默认策略，不能依赖CLI或MCP恰好检查过参数。
type focusDisplays struct {
	DisplayBackend
	focus string
	calls int
}

type cameraDisplays struct {
	focusDisplays
	cameraCalls   int
	camera, image string
}

func (f *cameraDisplays) StartWithCamera(context.Context, string, string) (vdisplay.Hello, error) {
	f.cameraCalls++
	return vdisplay.Hello{}, nil
}
func (f *cameraDisplays) CreateWithCamera(_ context.Context, _ string, _, _, _ int, focus, camera string) (vdisplay.Screen, error) {
	f.cameraCalls++
	f.focus = focus
	f.camera = camera
	return vdisplay.Screen{}, nil
}
func (f *cameraDisplays) SetCameraImage(_ context.Context, _ string, _ int, image string) (vdisplay.CameraImageResult, error) {
	f.cameraCalls++
	f.image = image
	return vdisplay.CameraImageResult{}, nil
}
func TestVirtualCameraServiceRoutingAndValidation(t *testing.T) {
	f := &cameraDisplays{}
	s := New(&fakeDirect{}, nil)
	s.displays = f
	for _, r := range []DisplayRequest{
		{Command: "start", Device: "SERIAL", VirtualCamera: true},
		{Command: "create", Device: "SERIAL", Camera: "back"},
		{Command: "camera.set-image", Device: "SERIAL", DisplayID: 7, Image: "image.png"},
	} {
		if _, err := s.ManageDisplay(context.Background(), r); err != nil {
			t.Fatal(err)
		}
	}
	if f.cameraCalls != 3 || f.calls != 0 || f.focus != "isolated" || f.camera != "back" || f.image != "image.png" {
		t.Fatal(f)
	}
	_, err := s.ManageDisplay(context.Background(), DisplayRequest{Command: "create", Device: "SERIAL", Camera: "front"})
	assertServiceCode(t, err, apperr.CodeInvalidArgument)
	if f.cameraCalls != 3 {
		t.Fatal("invalid camera reached backend")
	}
	s.displays = &focusDisplays{}
	_, err = s.ManageDisplay(context.Background(), DisplayRequest{Command: "create", Device: "SERIAL", Camera: "back"})
	assertServiceCode(t, err, apperr.CodeVirtualCameraNotEnabled)
}

func (f *focusDisplays) Create(_ context.Context, _ string, _, _, _ int, focus string) (vdisplay.Screen, error) {
	f.focus = focus
	f.calls++
	return vdisplay.Screen{Focus: focus}, nil
}
func TestDisplayCreateFocusReachesBackend(t *testing.T) {
	for _, focus := range []string{"", "isolated", "steal", "bad"} {
		backend := &focusDisplays{}
		s := New(&fakeDirect{}, nil)
		s.displays = backend
		_, err := s.ManageDisplay(context.Background(), DisplayRequest{Command: "create", Device: "SERIAL", Width: 720, Height: 1280, DPI: 240, Focus: focus})
		if focus == "bad" {
			assertServiceCode(t, err, apperr.CodeInvalidArgument)
			if backend.calls != 0 {
				t.Fatal("invalid focus forwarded")
			}
			continue
		}
		want := focus
		if want == "" {
			want = "isolated"
		}
		if err != nil || backend.focus != want || backend.calls != 1 {
			t.Fatal(backend, err)
		}
	}
}

type windowsDirect struct{ fakeDirect }

func (f *windowsDirect) Hierarchy(context.Context, string) (adb.Hierarchy, error) {
	return adb.Hierarchy{Format: "uiautomator-windows-xml", XML: "<displays/>"}, nil
}
func TestDisplayHierarchyFormatPassesThroughService(t *testing.T) {
	s := New(&windowsDirect{}, nil)
	result, err := s.Observe(context.Background(), ObserveRequest{Device: "SERIAL", Kind: ObserveHierarchy})
	if err != nil || result.Format != "uiautomator-windows-xml" {
		t.Fatalf("format lost: %+v %v", result, err)
	}
}

func (f *fakeDisplays) Capture(context.Context, string, int) (adb.Screenshot, error) {
	f.captures++
	return adb.Screenshot{Bytes: []byte("virtual-frame")}, f.err
}
func (f *fakeDisplays) RequireDisplay(context.Context, string, int) error { f.requires++; return f.err }

func TestDisplayServiceRoutesScreenshotThroughHelper(t *testing.T) {
	main := &fakeDirect{}
	target := &fakeDirect{}
	helper := &fakeDisplays{}
	s := New(main, nil)
	s.displays = helper
	s.displayTarget = func(int) DirectBackend { return target }
	id := 7
	result, err := s.Observe(context.Background(), ObserveRequest{Device: "SERIAL", Kind: ObserveScreenshot, DisplayID: &id})
	if err != nil {
		t.Fatal(err)
	}
	if string(result.Image) != "virtual-frame" || helper.captures != 1 || main.callCount != 0 {
		t.Fatalf("wrong route: %+v", result)
	}
	if _, err = s.Observe(context.Background(), ObserveRequest{Device: "SERIAL", Kind: ObserveHierarchy, DisplayID: &id}); err != nil {
		t.Fatal(err)
	}
	if helper.requires != 1 || target.callCount != 1 || main.callCount != 0 {
		t.Fatal("hierarchy fell back")
	}
}

func TestDisplayServiceFailuresNeverFallBack(t *testing.T) {
	for _, code := range []string{apperr.CodeDisplayNotFound, apperr.CodeDisplaydNotRunning, apperr.CodeUnsupportedAPI, apperr.CodeDeviceLocked, apperr.CodeDisplayAsleep} {
		t.Run(code, func(t *testing.T) {
			main := &fakeDirect{}
			target := &fakeDirect{}
			helper := &fakeDisplays{err: apperr.New(code, "test", false, nil)}
			s := New(main, nil)
			s.displays = helper
			s.displayTarget = func(int) DirectBackend { return target }
			id := 7
			_, err := s.ExecuteAction(context.Background(), ActionRequest{Device: "SERIAL", Action: ActionTap, DisplayID: &id})
			assertServiceCode(t, err, code)
			_, err = s.Observe(context.Background(), ObserveRequest{Device: "SERIAL", Kind: ObserveScreenshot, DisplayID: &id})
			assertServiceCode(t, err, code)
			_, err = s.Observe(context.Background(), ObserveRequest{Device: "SERIAL", Kind: ObserveHierarchy, DisplayID: &id})
			assertServiceCode(t, err, code)
			if main.callCount != 0 || target.callCount != 0 {
				t.Fatal("failure reached input backend")
			}
		})
	}
}

func TestDisplayServiceActionsUseTargetOnly(t *testing.T) {
	main := &fakeDirect{}
	target := &fakeDirect{}
	s := New(main, nil)
	s.displays = &fakeDisplays{}
	s.displayTarget = func(int) DirectBackend { return target }
	id := 7
	for _, action := range []string{ActionTap, ActionSwipe, ActionSetText, ActionPressKey, ActionLaunch} {
		if _, err := s.ExecuteAction(context.Background(), ActionRequest{Device: "SERIAL", Action: action, DisplayID: &id}); err != nil {
			t.Fatal(err)
		}
	}
	if main.callCount != 0 || target.callCount != 5 {
		t.Fatal("wrong display target")
	}
	if _, err := s.ExecuteAction(context.Background(), ActionRequest{Device: "SERIAL", Action: ActionDeviceSleep, DisplayID: &id}); err == nil {
		t.Fatal("display sleep accepted")
	}
}

type sleeperDirect struct {
	fakeDirect
	slept bool
}

func (f *sleeperDirect) Sleep(context.Context, string) (adb.ActionResult, error) {
	f.slept = true
	return adb.ActionResult{Action: ActionDeviceSleep}, nil
}
func TestSleepServiceUsesDedicatedMainScreenOperation(t *testing.T) {
	backend := &sleeperDirect{}
	_, err := New(backend, nil).ExecuteAction(context.Background(), ActionRequest{Device: "SERIAL", Action: ActionDeviceSleep})
	if err != nil || !backend.slept {
		t.Fatalf("sleep: %v %+v", err, backend)
	}
}

// 保持编译期检查，确保真实客户端实现同一组固定管理方法。
var _ DisplayBackend = (*vdisplay.Client)(nil)

// 主屏必须绕过 helper 的归属门；即使没有 helper 也能读写，负数仍拒绝。
func TestExplicitPrimaryDoesNotRequireHelper(t *testing.T) {
	main, target := &fakeDirect{}, &fakeDirect{}
	s := New(main, nil)
	s.displayTarget = func(id int) DirectBackend {
		if id != 0 {
			t.Fatal(id)
		}
		return target
	}
	id := 0
	for _, kind := range []string{ObserveScreenshot, ObserveHierarchy} {
		if _, err := s.Observe(context.Background(), ObserveRequest{Device: "SERIAL", Kind: kind, DisplayID: &id}); err != nil {
			t.Fatal(err)
		}
	}
	for _, action := range []string{ActionTap, ActionSwipe, ActionSetText, ActionPressKey, ActionLaunch} {
		if _, err := s.ExecuteAction(context.Background(), ActionRequest{Device: "SERIAL", Action: action, DisplayID: &id}); err != nil {
			t.Fatal(err)
		}
	}
	if target.callCount != 7 || main.callCount != 0 {
		t.Fatal("wrong explicit primary route")
	}
	id = -1
	_, err := s.ExecuteAction(context.Background(), ActionRequest{Device: "SERIAL", Action: ActionTap, DisplayID: &id})
	assertServiceCode(t, err, apperr.CodeDisplayNotFound)
}
