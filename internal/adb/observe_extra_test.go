package adb

// 测试用途：本文件验证前台探测、应用清单与录屏的解析规则和参数校验，
// 覆盖真实设备输出格式、字段缺失的降级路径以及非法参数被拒的边界。

import (
	"context"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/process"
)

// realResumedActivityDump 取自真实 API 35 模拟器的 dumpsys activity activities。
// 保留原始缩进与字段顺序，避免"测试通过而真实输出解析失败"。
const realResumedActivityDump = `
  ResumedActivity: ActivityRecord{dfa8abc u0 com.android.camera2/com.android.camera.CameraActivity t15}
    topResumedActivity=ActivityRecord{dfa8abc u0 com.android.camera2/com.android.camera.CameraActivity t15}
`

func TestForegroundParsesResumedActivity(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stdout: []byte(realResumedActivityDump)},
		},
		errors: []error{nil, nil},
	}
	foreground, err := NewClient("/fake/adb", fake).Foreground(context.Background(), "FIRST")
	if err != nil {
		t.Fatalf("Foreground() error = %v", err)
	}
	if foreground.Package != "com.android.camera2" {
		t.Fatalf("package = %q, want com.android.camera2", foreground.Package)
	}
	if foreground.Activity != "com.android.camera.CameraActivity" {
		t.Fatalf("activity = %q", foreground.Activity)
	}
	if foreground.Source != "resumedActivity" {
		t.Fatalf("source = %q, want resumedActivity", foreground.Source)
	}
	assertArgs(t, fake.calls[1], "-s", "FIRST", "shell", "dumpsys", "activity", "activities")
}

// Activity 字段全缺时应退回焦点窗口，并如实标注来源。
// 必须标注的理由：焦点窗口可能是对话框或输入法，与"当前应用"并不等价，
// 调用方需要能区分这两种可信度，否则弹窗出现时会得到误导性的包名。
func TestForegroundFallsBackToCurrentFocus(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stdout: []byte("  no activity fields here\n")},
			{Stdout: []byte("  mCurrentFocus=Window{1a2b u0 com.example.app/com.example.app.MainActivity}\n")},
		},
		errors: []error{nil, nil, nil},
	}
	foreground, err := NewClient("/fake/adb", fake).Foreground(context.Background(), "FIRST")
	if err != nil {
		t.Fatalf("Foreground() error = %v", err)
	}
	if foreground.Package != "com.example.app" {
		t.Fatalf("package = %q", foreground.Package)
	}
	if foreground.Source != "currentFocus" {
		t.Fatalf("source = %q, want currentFocus", foreground.Source)
	}
}

func TestForegroundFailsWhenNothingIsResumed(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stdout: []byte("nothing useful\n")},
			{Stdout: []byte("nothing useful either\n")},
		},
		errors: []error{nil, nil, nil},
	}
	_, err := NewClient("/fake/adb", fake).Foreground(context.Background(), "FIRST")
	if err == nil {
		t.Fatal("Foreground() error = nil, want failure when no activity is resumed")
	}
}

// realPackageListing 取自真实模拟器：APK 路径本身含 == 片段，
// 因此包名必须按最后一个等号切分，不能按第一个。
const realPackageListing = `package:/data/app/~~d4EjGjxJ-s_UpRAqgdi_hg==/dev.aiauto.android-Uu60iSv2WJOMshW8R8rgpA==/base.apk=dev.aiauto.android
package:/system/priv-app/Settings/Settings.apk=com.android.settings
`

func TestInstalledAppsParsesPathsContainingEquals(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stdout: []byte(realPackageListing)},
		},
		errors: []error{nil, nil},
	}
	apps, err := NewClient("/fake/adb", fake).InstalledApps(
		context.Background(), "FIRST", false, false,
	)
	if err != nil {
		t.Fatalf("InstalledApps() error = %v", err)
	}
	if apps.Count != 2 {
		t.Fatalf("count = %d, want 2", apps.Count)
	}
	// 结果按包名排序：com.android.settings 在 dev.aiauto.android 之前。
	if apps.Apps[0].Package != "com.android.settings" {
		t.Fatalf("apps[0].package = %q", apps.Apps[0].Package)
	}
	if apps.Apps[1].Package != "dev.aiauto.android" {
		t.Fatalf("apps[1].package = %q", apps.Apps[1].Package)
	}
	if !strings.HasSuffix(apps.Apps[1].APKPath, "/base.apk") {
		t.Fatalf("apk path lost its suffix: %q", apps.Apps[1].APKPath)
	}
	if apps.DetailsIncluded {
		t.Fatal("detailsIncluded = true, want false when details were not requested")
	}
	// 未要求系统应用时应带 -3，否则会把数百个系统包混进结果。
	assertArgs(t, fake.calls[1], "-s", "FIRST", "shell", "pm", "list", "packages", "-f", "-3")
}

func TestInstalledAppsSkipsMalformedLines(t *testing.T) {
	fake := &fakeADB{
		results: []process.Result{
			{Stdout: []byte(twoOnlineDevices)},
			{Stdout: []byte("garbage\npackage:/a/b.apk=com.example.ok\n\npackage:no-equals\n")},
		},
		errors: []error{nil, nil},
	}
	apps, err := NewClient("/fake/adb", fake).InstalledApps(
		context.Background(), "FIRST", false, false,
	)
	if err != nil {
		t.Fatalf("InstalledApps() error = %v", err)
	}
	if apps.Count != 1 || apps.Apps[0].Package != "com.example.ok" {
		t.Fatalf("apps = %#v, want only com.example.ok", apps.Apps)
	}
}

func TestRecordScreenRejectsInvalidOptions(t *testing.T) {
	cases := map[string]RecordingOptions{
		"zero seconds":     {Seconds: 0},
		"too many seconds": {Seconds: recordingMaxSeconds + 1},
		"width only":       {Seconds: 5, Width: 720},
		"height only":      {Seconds: 5, Height: 1280},
		"negative bitrate": {Seconds: 5, BitRate: -1},
	}
	for name, options := range cases {
		// 每个用例用独立 fake：参数校验应发生在任何 adb 调用之前，
		// 共用 fake 会让"是否真的没调用 adb"无法分辨。
		fake := &fakeADB{
			results: []process.Result{{Stdout: []byte(twoOnlineDevices)}},
			errors:  []error{nil},
		}
		_, err := NewClient("/fake/adb", fake).RecordScreen(
			context.Background(), "FIRST", options,
		)
		if err == nil {
			t.Fatalf("%s: RecordScreen() error = nil, want rejection", name)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("%s: adb was called %d times before validation", name, len(fake.calls))
		}
	}
}

// validMP4Structure 只确认容器头，用于区分"拿到录制产物"与"拿到错误信息或空文件"。
func TestValidMP4Structure(t *testing.T) {
	// 实测自 API 35 模拟器 screenrecord 产物的前 12 字节。
	realHeader := []byte{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'}
	if !validMP4Structure(realHeader) {
		t.Fatal("validMP4Structure(real header) = false, want true")
	}
	for name, data := range map[string][]byte{
		"empty":     {},
		"too short": {0x00, 0x00, 0x00},
		"not mp4":   []byte("this is an error message from adb"),
		// PNG 头不得被当成 MP4：两个观察通道的产物不能混淆。
		"png": {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0},
	} {
		if validMP4Structure(data) {
			t.Fatalf("%s: validMP4Structure() = true, want false", name)
		}
	}
}
