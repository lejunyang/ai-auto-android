package vdisplay

// 测试用途：在显式启用的一次性 API 35 模拟器验证 /proc 身份与真实客户端生命周期。

import (
	"context"
	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"os"
	"strings"
	"testing"
	"time"
)

func TestDisplaydEmulatorLifecycle(t *testing.T) {
	serial := os.Getenv("AACTL_DISPLAYD_TEST_SERIAL")
	if serial == "" {
		t.Skip("requires explicitly prepared disposable emulator")
	}
	if serial != "emulator-5554" {
		t.Fatal("only emulator-5554 is allowed")
	}
	path, err := adb.DefaultLocator().Resolve()
	if err != nil {
		t.Fatal(err)
	}
	host := adb.NewClient(path, process.Runner{})
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	devices, err := host.Devices(ctx)
	if err != nil || len(devices) != 1 || devices[0].Serial != serial || devices[0].State != "device" {
		t.Fatalf("unexpected devices: %+v %v", devices, err)
	}
	boot, err := (process.Runner{}).Run(ctx, path, []string{"-s", serial, "shell", "getprop", "sys.boot_completed"}, process.Options{Timeout: 5 * time.Second})
	if err != nil || strings.TrimSpace(string(boot.Stdout)) != "1" {
		t.Fatal("emulator boot is not complete; refusing device actions")
	}
	c := New(host)
	hello, err := c.Start(ctx, serial, os.Getenv("AACTL_DISPLAYD_TEST_APK"))
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("hello: %+v", hello)
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), 15*time.Second)
		defer stop()
		if _, err := c.Stop(cleanup, serial); err != nil {
			t.Errorf("stop: %v", err)
		}
	}()
	screen, err := c.Create(ctx, serial, 720, 1280, 240, "isolated")
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("created: %+v", screen)
	if _, err = host.WithDisplay(screen.DisplayID).Launch(ctx, serial, "com.android.settings", ".Settings"); err != nil {
		t.Fatal(err)
	}
	hierarchy, err := host.WithDisplay(screen.DisplayID).Hierarchy(ctx, serial)
	if err != nil {
		t.Fatal(err)
	}
	if hierarchy.Format != "uiautomator-windows-xml" || !strings.Contains(hierarchy.XML, `package="com.android.settings"`) || strings.Contains(hierarchy.XML, "nexuslauncher") {
		t.Fatal("virtual hierarchy contains the wrong display")
	}
	t.Logf("hierarchy format=%s bytes=%d sha256=%s", hierarchy.Format, hierarchy.Bytes, hierarchy.SHA256)
	status, err := c.Status(ctx, serial)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("status: %+v", status)
	frame, err := c.Capture(ctx, serial, screen.DisplayID)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("PNG bytes=%d sha256=%s", len(frame.Bytes), frame.SHA256)
	if err = c.RequireDisplay(ctx, serial, screen.DisplayID); err != nil {
		t.Fatal(err)
	}
	if _, err = c.Close(ctx, serial, screen.DisplayID); err != nil {
		t.Fatal(err)
	}
}
