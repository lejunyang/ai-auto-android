package cli

// 测试用途：验证虚拟屏命令面、可选显示参数与主屏息屏，并保护无效参数不触达设备。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
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

// CLI使用真实service/adb解析器，空屏错误不能被信封改写或触发普通dump回退。
type emptyHierarchyExecutor struct{ calls [][]string }

func (e *emptyHierarchyExecutor) Run(_ context.Context, _ string, args []string, _ process.Options) (process.Result, error) {
	e.calls = append(e.calls, append([]string(nil), args...))
	line := strings.Join(args, " ")
	switch {
	case strings.HasPrefix(line, "devices "):
		return process.Result{Stdout: []byte("List of devices attached\nSERIAL\tdevice transport_id:1\n")}, nil
	case strings.Contains(line, "getprop ro.build.version.sdk"):
		return process.Result{Stdout: []byte("35")}, nil
	case strings.Contains(line, "exec-out cat"):
		return process.Result{Stdout: []byte("<displays/>")}, nil
	default:
		return process.Result{}, nil
	}
}

type cliOwnedDisplays struct {
	service.DisplayBackend
	calls int
}

func (d *cliOwnedDisplays) RequireDisplay(_ context.Context, _ string, id int) error {
	d.calls++
	if id == 7 {
		return nil
	}
	return apperr.New(apperr.CodeDisplayNotFound, "Not helper-owned", false, nil)
}
func TestCLIEmptyDisplayAndMissingDisplayRemainDistinct(t *testing.T) {
	for _, id := range []string{"0", "7", "9"} {
		executor := &emptyHierarchyExecutor{}
		helper := &cliOwnedDisplays{}
		app, stdout := testApp(executor, "")
		app.Automation = service.NewWithDisplays(adb.NewClient("adb", executor), helper)
		if app.Run(context.Background(), []string{"observe", "hierarchy", "--device", "SERIAL", "--display", id, "--json"}) != apperr.ExitInternal {
			t.Fatal(stdout.String())
		}
		e := decodeEnvelope(t, stdout.Bytes())
		want := "DISPLAY_EMPTY"
		calls := 5
		if id == "9" {
			want = apperr.CodeDisplayNotFound
			calls = 0
		}
		if e.OK || e.Error == nil || e.Error.Code != want || e.Error.Retryable {
			t.Fatalf("%s: %+v", id, e)
		}
		if len(executor.calls) != calls {
			t.Fatal("wrong route or fallback", executor.calls)
		}
		if id != "9" && !strings.Contains(strings.ToLower(e.Error.Message), "launch") {
			t.Fatal("missing recovery instruction")
		}
		if id == "0" && helper.calls != 0 {
			t.Fatal("primary required helper")
		}
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
