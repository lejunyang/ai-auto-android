package cli

// 测试用途：验证虚拟屏命令面、可选显示参数与主屏息屏，并保护无效参数不触达设备。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"strings"
	"testing"
)

func TestDisplayActionArgumentsReachSharedService(t *testing.T) {
	for _, args := range [][]string{
		{"action", "tap", "--x", "1", "--y", "2"},
		{"action", "swipe", "--x1", "1", "--y1", "2", "--x2", "3", "--y2", "4"},
		{"action", "text", "--text", "hello"},
		{"action", "key", "--key", "BACK"},
		{"action", "launch", "--package", "dev.aiauto.fixture"},
	} {
		t.Run(args[1], func(t *testing.T) {
			request := runActionCommand(t, append(args, "--device", "SERIAL", "--display", "7")...)
			if request.DisplayID == nil || *request.DisplayID != 7 {
				t.Fatalf("target missing: %+v", request)
			}
		})
	}
	request := runActionCommand(t, "action", "sleep", "--device", "SERIAL")
	if request.Action != service.ActionDeviceSleep {
		t.Fatal(request)
	}
}

func TestDisplayObserveParametersReachService(t *testing.T) {
	for _, kind := range []string{"screenshot", "hierarchy"} {
		t.Run(kind, func(t *testing.T) {
			fake := &displayCapturingAutomation{}
			app, stdout := testApp(&commandExecutor{}, "")
			app.Automation = fake
			args := []string{"observe", kind, "--device", "SERIAL", "--display", "7", "--json"}
			if kind == "screenshot" {
				args = append(args, "--output", t.TempDir()+"/frame.png")
			}
			if app.Run(context.Background(), args) != 0 {
				t.Fatal(stdout.String())
			}
			if fake.observed.DisplayID == nil || *fake.observed.DisplayID != 7 {
				t.Fatal(fake.observed)
			}
		})
	}
}

type displayCapturingAutomation struct {
	actionCapturingAutomation
	managed  []service.DisplayRequest
	observed service.ObserveRequest
}

func (a *displayCapturingAutomation) ManageDisplay(_ context.Context, r service.DisplayRequest) (any, error) {
	a.managed = append(a.managed, r)
	return map[string]any{"command": r.Command}, nil
}
func (a *displayCapturingAutomation) Observe(_ context.Context, r service.ObserveRequest) (service.ObserveResult, error) {
	a.observed = r
	format := "uiautomator-xml"
	if r.DisplayID != nil {
		format = "uiautomator-windows-xml"
	}
	return service.ObserveResult{Format: format, Image: []byte("test-only"), XML: "<hierarchy/>"}, nil
}

func TestDisplayHierarchyFormatPassesThroughCLI(t *testing.T) {
	fake := &displayCapturingAutomation{}
	app, stdout := testApp(&commandExecutor{}, "")
	app.Automation = fake
	if app.Run(context.Background(), []string{"observe", "hierarchy", "--device", "SERIAL", "--display", "7", "--json"}) != 0 {
		t.Fatal(stdout.String())
	}
	if !strings.Contains(stdout.String(), `"format":"uiautomator-windows-xml"`) {
		t.Fatal(stdout.String())
	}
}

func TestDisplayManagementCommandSurface(t *testing.T) {
	for _, command := range []string{"start", "status", "stop", "create", "list", "close"} {
		t.Run(command, func(t *testing.T) {
			fake := &displayCapturingAutomation{}
			app, stdout := testApp(&commandExecutor{}, "")
			app.Automation = fake
			args := []string{"display", command, "--device", "SERIAL", "--json"}
			if command == "start" {
				args = append(args, "--helper", "helper.apk")
			}
			if command == "create" {
				args = append(args, "--width", "720", "--height", "1280", "--dpi", "240")
			}
			if command == "close" {
				args = append(args, "--display", "7")
			}
			if app.Run(context.Background(), args) != 0 {
				t.Fatal(stdout.String())
			}
			if len(fake.managed) != 1 || fake.managed[0].Command != command {
				t.Fatal(fake.managed)
			}
		})
	}
}

func TestDisplayRejectsInvalidAndUnsupportedOptions(t *testing.T) {
	for _, args := range [][]string{
		{"action", "tap", "--device", "SERIAL", "--x", "1", "--y", "2", "--display", "-1"},
		{"action", "sleep", "--device", "SERIAL", "--display", "7"},
		{"observe", "hierarchy", "--device", "SERIAL", "--display", "bad"},
		{"display", "create", "--device", "SERIAL", "--width", "1", "--height", "1280", "--dpi", "240"},
		{"display", "close", "--device", "SERIAL", "--display", "0"},
		{"display", "stop", "--device", "SERIAL", "--helper", "arbitrary.apk"},
	} {
		t.Run(args[0]+args[1], func(t *testing.T) {
			fake := &displayCapturingAutomation{}
			app, _ := testApp(&commandExecutor{}, "")
			app.Automation = fake
			if app.Run(context.Background(), args) == 0 {
				t.Fatal("invalid args accepted")
			}
			if len(fake.actions) != 0 || len(fake.managed) != 0 {
				t.Fatal("invalid args reached service")
			}
		})
	}
}

// 显式主屏与未指定目标不同，零值必须完整传到服务层；关闭操作仍仅限虚拟屏。
func TestExplicitPrimaryDisplayReachesService(t *testing.T) {
	for _, args := range [][]string{
		{"action", "tap", "--x", "1", "--y", "2"},
		{"action", "swipe", "--x1", "1", "--y1", "2", "--x2", "3", "--y2", "4"},
		{"action", "text", "--text", "hello"},
		{"action", "key", "--key", "BACK"},
		{"action", "launch", "--package", "dev.aiauto.fixture"},
	} {
		r := runActionCommand(t, append(args, "--device", "SERIAL", "--display", "0")...)
		if r.DisplayID == nil || *r.DisplayID != 0 {
			t.Fatalf("lost explicit primary target: %+v", r)
		}
	}
	for _, kind := range []string{"hierarchy", "screenshot"} {
		fake := &displayCapturingAutomation{}
		app, stdout := testApp(&commandExecutor{}, "")
		app.Automation = fake
		args := []string{"observe", kind, "--device", "SERIAL", "--display", "0", "--json"}
		if kind == "screenshot" {
			args = append(args, "--output", t.TempDir()+"/frame.png")
		}
		if app.Run(context.Background(), args) != 0 {
			t.Fatal(stdout.String())
		}
		if fake.observed.DisplayID == nil || *fake.observed.DisplayID != 0 {
			t.Fatal(fake.observed)
		}
	}
}
