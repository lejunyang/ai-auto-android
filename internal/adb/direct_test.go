package adb

// 测试用途：本文件验证截图与层级产物完整性、类型化动作 argv 映射和输入白名单。

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/png"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

const twoOnlineDevices = `List of devices attached
FIRST	device usb:1-1 model:Pixel_8 transport_id:1
SECOND	device usb:1-2 model:Pixel_9 transport_id:2
`

func TestScreenshotUsesExecOutAndValidatesPNG(t *testing.T) {
	image := validPNG(t)
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stdout: image},
		},
		errors: []error{nil, nil},
	}
	screenshot, err := NewClient("/fake/adb", fake).Screenshot(context.Background(), "SECOND")
	if err != nil {
		t.Fatalf("Screenshot() error = %v", err)
	}
	if !bytes.Equal(screenshot.Bytes, image) || len(screenshot.SHA256) != 64 {
		t.Fatalf("screenshot = %#v", screenshot)
	}
	assertArgs(t, fake.calls[1], "-s", "SECOND", "exec-out", "screencap", "-p")
	if fake.options[1].MaxOutput != maxScreenshotOutput {
		t.Fatalf("max output = %d", fake.options[1].MaxOutput)
	}
}

func TestScreenshotRejectsInvalidOrTruncatedPNG(t *testing.T) {
	tests := []process.Result{
		{Stdout: append(append([]byte(nil), pngSignature...), []byte("not-png-data")...)},
		{Stdout: validPNG(t), OutputTruncated: true},
	}
	for _, screenshotResult := range tests {
		fake := &fakeADB{
			results: []process.Result{
				{Stdout: []byte(twoOnlineDevices)},
				screenshotResult,
			},
			errors: []error{nil, nil},
		}
		_, err := NewClient("/fake/adb", fake).Screenshot(context.Background(), "FIRST")
		assertAppErrorCode(t, err, apperr.CodeActionFailed)
	}
}

func TestHierarchyUsesFixedRemotePathAndCleansUp(t *testing.T) {
	xmlData := []byte(`<?xml version="1.0"?><hierarchy rotation="0"><node text="Settings"/></hierarchy>`)
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stdout: []byte("UI hierchary dumped")},
			{Stdout: xmlData},
			{},
		},
		errors: []error{nil, nil, nil, nil},
	}
	hierarchy, err := NewClient("/fake/adb", fake).Hierarchy(context.Background(), "FIRST")
	if err != nil {
		t.Fatalf("Hierarchy() error = %v", err)
	}
	if hierarchy.XML != string(xmlData) || hierarchy.Bytes != len(xmlData) || len(hierarchy.SHA256) != 64 {
		t.Fatalf("hierarchy = %#v", hierarchy)
	}
	assertArgs(t, fake.calls[1], "-s", "FIRST", "shell", "uiautomator", "dump", hierarchyRemotePath)
	assertArgs(t, fake.calls[2], "-s", "FIRST", "exec-out", "cat", hierarchyRemotePath)
	assertArgs(t, fake.calls[3], "-s", "FIRST", "shell", "rm", "-f", hierarchyRemotePath)
}

func TestHierarchyRejectsMalformedXML(t *testing.T) {
	tests := []process.Result{
		{Stdout: []byte(`<hierarchy><node></hierarchy>`)},
		{Stdout: []byte(`<hierarchy/><second-root/>`)},
		{Stdout: []byte(`<hierarchy/>`), OutputTruncated: true},
	}
	for _, hierarchyResult := range tests {
		fake := &fakeADB{
			results: []process.Result{
				{Stdout: []byte(twoOnlineDevices)},
				{},
				hierarchyResult,
				{},
			},
			errors: []error{nil, nil, nil, nil},
		}
		_, err := NewClient("/fake/adb", fake).Hierarchy(context.Background(), "FIRST")
		assertAppErrorCode(t, err, apperr.CodeActionFailed)
	}
}

func TestTapWithMultipleDevicesTargetsExplicitSerial(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{},
		},
		errors: []error{nil, nil},
	}
	result, err := NewClient("/fake/adb", fake).Tap(context.Background(), "SECOND", 120, 450)
	if err != nil {
		t.Fatalf("Tap() error = %v", err)
	}
	if result.Device != "SECOND" || result.Action != "ui.tap" {
		t.Fatalf("result = %#v", result)
	}
	assertArgs(t, fake.calls[1], "-s", "SECOND", "shell", "input", "tap", "120", "450")
}

func TestSwipeMapsExactArguments(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {}},
		errors:  []error{nil, nil},
	}
	_, err := NewClient("/fake/adb", fake).Swipe(context.Background(), "FIRST", 10, 20, 30, 40, 500)
	if err != nil {
		t.Fatalf("Swipe() error = %v", err)
	}
	assertArgs(t, fake.calls[1], "-s", "FIRST", "shell", "input", "swipe", "10", "20", "30", "40", "500")
}

func TestTextEncodesSpacesAndRejectsRemoteShellInjection(t *testing.T) {
	validFake := &fakeADB{
		results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {}},
		errors:  []error{nil, nil},
	}
	_, err := NewClient("/fake/adb", validFake).Text(context.Background(), "FIRST", "hello user@example.com")
	if err != nil {
		t.Fatalf("Text() error = %v", err)
	}
	assertArgs(t, validFake.calls[1], "-s", "FIRST", "shell", "input", "text", "hello%suser@example.com")

	injectionFake := &fakeADB{}
	_, err = NewClient("/fake/adb", injectionFake).Text(context.Background(), "FIRST", "hello;rm -rf /")
	assertAppErrorCode(t, err, apperr.CodeInvalidArgument)
	if len(injectionFake.calls) != 0 {
		t.Fatalf("ADB called for invalid text: %#v", injectionFake.calls)
	}
}

func TestCoordinatesAndDurationAreValidatedBeforeADB(t *testing.T) {
	tests := []struct {
		name string
		run  func(*Client) error
	}{
		{
			name: "negative tap x",
			run: func(client *Client) error {
				_, err := client.Tap(context.Background(), "FIRST", -1, 0)
				return err
			},
		},
		{
			name: "swipe end y too large",
			run: func(client *Client) error {
				_, err := client.Swipe(context.Background(), "FIRST", 0, 0, 10, maxCoordinate+1, 300)
				return err
			},
		},
		{
			name: "swipe duration too large",
			run: func(client *Client) error {
				_, err := client.Swipe(context.Background(), "FIRST", 0, 0, 10, 10, maxSwipeDurationMS+1)
				return err
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fake := &fakeADB{}
			assertAppErrorCode(t, test.run(NewClient("/fake/adb", fake)), apperr.CodeInvalidArgument)
			if len(fake.calls) != 0 {
				t.Fatalf("ADB called for invalid action: %#v", fake.calls)
			}
		})
	}
}

func TestKeyAndPackageValidationRunBeforeADB(t *testing.T) {
	fake := &fakeADB{}
	_, keyErr := NewClient("/fake/adb", fake).Key(context.Background(), "FIRST", "POWER")
	assertAppErrorCode(t, keyErr, apperr.CodeInvalidArgument)
	_, launchErr := NewClient("/fake/adb", fake).Launch(context.Background(), "FIRST", "com.good;rm", "")
	assertAppErrorCode(t, launchErr, apperr.CodeInvalidArgument)
	_, activityErr := NewClient("/fake/adb", fake).Launch(
		context.Background(),
		"FIRST",
		"com.example.app",
		"com.other/.MainActivity",
	)
	assertAppErrorCode(t, activityErr, apperr.CodeInvalidArgument)
	_, shellActivityErr := NewClient("/fake/adb", fake).Launch(
		context.Background(),
		"FIRST",
		"com.example.app",
		".Main$Injected",
	)
	assertAppErrorCode(t, shellActivityErr, apperr.CodeInvalidArgument)
	_, stopErr := NewClient("/fake/adb", fake).Stop(context.Background(), "FIRST", "com.good && reboot")
	assertAppErrorCode(t, stopErr, apperr.CodeInvalidArgument)
	if len(fake.calls) != 0 {
		t.Fatalf("ADB called for invalid arguments: %#v", fake.calls)
	}
}

func TestKeyLaunchAndStopMapExactArguments(t *testing.T) {
	tests := []struct {
		name     string
		run      func(*Client) (ActionResult, error)
		expected []string
	}{
		{
			name: "key",
			run: func(client *Client) (ActionResult, error) {
				return client.Key(context.Background(), "FIRST", "back")
			},
			expected: []string{"-s", "FIRST", "shell", "input", "keyevent", "KEYCODE_BACK"},
		},
		{
			name: "launch default activity",
			run: func(client *Client) (ActionResult, error) {
				return client.Launch(context.Background(), "FIRST", "com.example.app", "")
			},
			expected: []string{
				"-s", "FIRST", "shell", "monkey",
				"-p", "com.example.app", "-c", "android.intent.category.LAUNCHER", "1",
			},
		},
		{
			name: "stop",
			run: func(client *Client) (ActionResult, error) {
				return client.Stop(context.Background(), "FIRST", "com.example.app")
			},
			expected: []string{"-s", "FIRST", "shell", "am", "force-stop", "com.example.app"},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fake := &fakeADB{
				results: []process.Result{{Stdout: []byte(twoOnlineDevices)}, {}},
				errors:  []error{nil, nil},
			}
			if _, err := test.run(NewClient("/fake/adb", fake)); err != nil {
				t.Fatalf("action error = %v", err)
			}
			assertArgs(t, fake.calls[1], test.expected...)
		})
	}
}

func TestDirectCommandFailureMapsToActionFailed(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stderr: []byte("Error: activity class does not exist"), ExitCode: 1},
		},
		errors: []error{nil, errors.New("exit status 1")},
	}
	_, err := NewClient("/fake/adb", fake).Launch(
		context.Background(),
		"FIRST",
		"com.example.app",
		".MissingActivity",
	)
	assertAppErrorCode(t, err, apperr.CodeActionFailed)
}

func assertArgs(t *testing.T, actual []string, expected ...string) {
	t.Helper()
	if strings.Join(actual, "\x00") != strings.Join(expected, "\x00") {
		t.Fatalf("args = %#v, want %#v", actual, expected)
	}
}

func validPNG(t *testing.T) []byte {
	t.Helper()
	var output bytes.Buffer
	if err := png.Encode(&output, image.NewRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatalf("encode PNG: %v", err)
	}
	return output.Bytes()
}
