package cli

// 测试用途：验证虚拟屏命令面、可选显示参数与主屏息屏，并保护无效参数不触达设备。

import (
	"context"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"strings"
	"testing"
)

// 真实CLI/service/vdisplay/adb链路验证API33/34的伴生服务失败不阻止普通文件清理。
type stopCleanupExecutor struct {
	api, list                   string
	unavailable                 bool
	filesCleaned, imagesChecked bool
}

func (f *stopCleanupExecutor) Run(_ context.Context, _ string, args []string, _ process.Options) (process.Result, error) {
	r := process.Result{}
	line := strings.Join(args, " ")
	switch {
	case args[0] == "devices":
		r.Stdout = []byte("List of devices attached\nSERIAL\tdevice transport_id:1\n")
	case strings.HasSuffix(line, "getprop ro.build.version.sdk"):
		r.Stdout = []byte(f.api)
	case strings.HasSuffix(line, "am get-current-user"):
		r.Stdout = []byte("0")
	case strings.Contains(line, "cmd companiondevice list"):
		if f.unavailable {
			r.ExitCode = 1
			r.Stderr = []byte("companion service unavailable")
			return r, errors.New("companion service unavailable")
		}
		r.Stdout = []byte(f.list)
	case strings.Contains(line, "rm -f /data/local/tmp/aactl-displayd.apk /data/local/tmp/aactl-displayd.log"):
		f.filesCleaned = true
	case strings.HasSuffix(line, "ls -1 /data/local/tmp"):
		f.imagesChecked = true
	}
	return r, nil
}
func TestCLIStopAbsentHelperReportsCameraCleanupAndStillRemovesFiles(t *testing.T) {
	for _, api := range []string{"33", "34"} {
		for _, kind := range []string{"unavailable", "unknown-format", "no-association"} {
			t.Run(api+"/"+kind, func(t *testing.T) {
				f := &stopCleanupExecutor{api: api, unavailable: kind == "unavailable"}
				if kind == "unknown-format" {
					f.list = "unknown vendor companion output"
				}
				app, out := testApp(f, "")
				exit := app.Run(context.Background(), []string{"display", "stop", "--device", "SERIAL", "--json"})
				if !f.filesCleaned || !f.imagesChecked {
					t.Fatal("ordinary cleanup was skipped", out.String())
				}
				if kind == "no-association" {
					if exit != 0 || !strings.Contains(out.String(), `"data":{"stopped":true}`) {
						t.Fatal(out.String())
					}
					return
				}
				e := decodeEnvelope(t, out.Bytes())
				if exit == 0 || e.OK || e.Error == nil || e.Error.Code != apperr.CodeVirtualCameraUnavailable || !strings.Contains(out.String(), `"helperFiles":{"ok":true}`) || !strings.Contains(out.String(), `"cameraAssociation":{"ok":false`) || !strings.Contains(out.String(), `"cameraImages":{"ok":true}`) {
					t.Fatal(out.String())
				}
			})
		}
	}
}

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
type emptyHierarchyExecutor struct {
	calls    [][]string
	noOutput bool
}

func (e *emptyHierarchyExecutor) Run(_ context.Context, _ string, args []string, _ process.Options) (process.Result, error) {
	e.calls = append(e.calls, append([]string(nil), args...))
	line := strings.Join(args, " ")
	switch {
	case strings.HasPrefix(line, "devices "):
		return process.Result{Stdout: []byte("List of devices attached\nSERIAL\tdevice transport_id:1\n")}, nil
	case strings.Contains(line, "getprop ro.build.version.sdk"):
		return process.Result{Stdout: []byte("35")}, nil
	case strings.Contains(line, "exec-out cat"):
		if e.noOutput {
			return process.Result{}, nil
		}
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

func TestCLIMissingWindowDumpPreservesRecovery(t *testing.T) {
	for _, id := range []string{"0", "7"} {
		e := &emptyHierarchyExecutor{noOutput: true}
		app, out := testApp(e, "")
		app.Automation = service.NewWithDisplays(adb.NewClient("adb", e), &cliOwnedDisplays{})
		if app.Run(context.Background(), []string{"observe", "hierarchy", "--device", "SERIAL", "--display", id, "--json"}) != apperr.ExitInternal {
			t.Fatal(out.String())
		}
		r := decodeEnvelope(t, out.Bytes())
		if r.Error == nil || r.Error.Code != "WINDOWS_UNAVAILABLE" || r.Error.Retryable || !strings.Contains(r.Error.Message, "Wake") || !strings.Contains(r.Error.Message, "steal") || len(e.calls) != 5 {
			t.Fatal(out.String(), e.calls)
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

func TestVirtualCameraCLIContracts(t *testing.T) {
	for _, args := range [][]string{
		{"display", "start", "--virtual-camera"},
		{"display", "create", "--width", "640", "--height", "480", "--dpi", "240", "--camera", "back"},
		{"camera", "set-image", "--display", "7", "--image", "C:/space path/image.png"},
	} {
		fake := &displayCapturingAutomation{}
		app, out := testApp(&commandExecutor{}, "")
		app.Automation = fake
		if app.Run(context.Background(), append(args, "--device", "SERIAL", "--json")) != 0 || len(fake.managed) != 1 {
			t.Fatal(out.String(), fake.managed)
		}
		r := fake.managed[0]
		switch args[1] {
		case "start":
			if !r.VirtualCamera {
				t.Fatal(r)
			}
		case "create":
			if r.Camera != "back" || r.Focus != "isolated" {
				t.Fatal(r)
			}
		case "set-image":
			if r.Command != "camera.set-image" || r.DisplayID != 7 || r.Image != "C:/space path/image.png" {
				t.Fatal(r)
			}
		}
	}
	for _, args := range [][]string{
		{"display", "start", "--virtual-camera", "--virtual-camera"},
		{"display", "start", "--virtual-camera=false"},
		{"display", "list", "--virtual-camera"},
		{"display", "create", "--width", "640", "--height", "480", "--dpi", "240", "--camera", "front"},
		{"camera", "set-image", "--display", "0", "--image", "image.png"},
		{"camera", "set-image", "--display", "7", "--image", "image.png", "--force", "true"},
	} {
		fake := &displayCapturingAutomation{}
		app, _ := testApp(&commandExecutor{}, "")
		app.Automation = fake
		if app.Run(context.Background(), append(args, "--device", "SERIAL")) == 0 || len(fake.managed) != 0 {
			t.Fatal("invalid camera command reached backend", args)
		}
	}
}

type cameraCLIError struct {
	displayCapturingAutomation
	code string
}

func (f *cameraCLIError) ManageDisplay(context.Context, service.DisplayRequest) (any, error) {
	return nil, apperr.New(f.code, "camera diagnostic", false, nil)
}
func TestVirtualCameraCLIErrors(t *testing.T) {
	for _, code := range []string{apperr.CodeVirtualCameraNotEnabled, apperr.CodeVirtualCameraUnavailable, apperr.CodeCameraImageInvalid} {
		fake := &cameraCLIError{code: code}
		app, out := testApp(&commandExecutor{}, "")
		app.Automation = fake
		want := apperr.ExitUnsupported
		if code == apperr.CodeCameraImageInvalid {
			want = apperr.ExitArgument
		}
		if app.Run(context.Background(), []string{"camera", "set-image", "--device", "SERIAL", "--display", "7", "--image", "image.png", "--json"}) != want {
			t.Fatal(out.String())
		}
		e := decodeEnvelope(t, out.Bytes())
		if e.OK || e.Error == nil || e.Error.Code != code || e.Error.Retryable {
			t.Fatal(e)
		}
	}
}

// 默认与两种显式策略均到达共享服务，非法值和非创建命令不得携带策略。
func TestDisplayCreateFocusOption(t *testing.T) {
	for _, focus := range []string{"", "isolated", "steal", "bad", "ISOLATED"} {
		fake := &displayCapturingAutomation{}
		app, out := testApp(&commandExecutor{}, "")
		app.Automation = fake
		args := []string{"display", "create", "--device", "SERIAL", "--width", "720", "--height", "1280", "--dpi", "240", "--json"}
		if focus != "" {
			args = append(args, "--focus", focus)
		}
		code := app.Run(context.Background(), args)
		if focus == "bad" || focus == "ISOLATED" {
			if code == 0 || len(fake.managed) != 0 {
				t.Fatal("invalid focus accepted")
			}
			continue
		}
		want := focus
		if want == "" {
			want = "isolated"
		}
		if code != 0 || len(fake.managed) != 1 || fake.managed[0].Focus != want {
			t.Fatal(out.String(), fake.managed)
		}
	}
	fake := &displayCapturingAutomation{}
	app, _ := testApp(&commandExecutor{}, "")
	app.Automation = fake
	if app.Run(context.Background(), []string{"display", "list", "--device", "SERIAL", "--focus", "steal"}) == 0 || len(fake.managed) != 0 {
		t.Fatal("focus allowed outside create")
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
