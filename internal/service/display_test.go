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
