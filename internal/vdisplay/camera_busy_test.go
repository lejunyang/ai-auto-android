package vdisplay

// 测试用途：验证BUSY可重试错误与待释放诊断，不让stop删除仍出流helper的任何资源。

import (
	"context"
	"errors"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
)

const busyCameraWire = `{"id":1,"ok":false,"error":{"code":"VIRTUAL_CAMERA_BUSY","message":"Application is still using the camera; release it then retry. Do not kill the helper."}}`

func TestCameraBusyCloseAndStopPreserveLiveResources(t *testing.T) {
	for _, operation := range []string{"close", "stop", "keep-stop"} {
		t.Run(operation, func(t *testing.T) {
			c, base := cameraWire(t, true, func(request map[string]any) string { return busyCameraWire })
			h := &keptStopHost{stopCleanupHost: &stopCleanupHost{cameraHostFake: base}, found: adb.CameraAssociation{UserID: 10, ID: 71}}
			c.host = h
			var err error
			switch operation {
			case "close":
				_, err = c.Close(context.Background(), "SERIAL", 7)
			case "stop":
				_, err = c.Stop(context.Background(), "SERIAL")
			default:
				_, err = c.StopKeepingCameraAssociation(context.Background(), "SERIAL")
			}
			var app *apperr.Error
			if !errors.As(err, &app) || app.Code != apperr.CodeVirtualCameraBusy || !app.Retryable || !strings.Contains(app.Message, "Do not kill") {
				t.Fatal(err)
			}
			if h.filesRemoved != 0 || h.imagesCleaned != 0 || h.cleaned != 0 || h.reads != 0 || h.state.PID != 42 || h.forwarded != h.removed {
				t.Fatal("BUSY caused cleanup or process loss", h)
			}
		})
	}
}

func TestPendingCameraStateIsVisibleButNotAnActionTarget(t *testing.T) {
	pending := strings.Replace(cameraScreenJSON, `"streams":[]`, `"streams":[],"releasePending":true`, 1)
	c, _ := cameraWire(t, true, func(request map[string]any) string {
		if request["method"] == "status" {
			return cameraOK(`{"credentialLocked":false,"displays":[` + strings.TrimSuffix(pending, "}") + `,"state":0,"displayGroupId":-1,"groupAwake":null}],"pendingCameraReleases":1,"keepAwakeIntervalMs":2000,"lastActivityUptimeMs":0,"keepAwakeError":null}`)
		}
		return cameraOK(`{"displays":[` + pending + `]}`)
	})
	listed, err := c.List(context.Background(), "SERIAL")
	if err != nil || !listed.Displays[0].CameraInfo.ReleasePending {
		t.Fatal(listed, err)
	}
	status, err := c.Status(context.Background(), "SERIAL")
	if err != nil || status.PendingCameraReleases != 1 || status.Displays[0].DisplayGroupID != -1 || status.Displays[0].GroupAwake != nil {
		t.Fatal(status, err)
	}
	requireCode(t, c.RequireDisplay(context.Background(), "SERIAL", 7), apperr.CodeVirtualCameraBusy)
}

func TestWireBudgetCoversSharedCameraDrain(t *testing.T) {
	source, err := os.ReadFile("../../android/displayd/src/main/java/dev/aiauto/displayd/CameraRelease.java")
	if err != nil {
		t.Fatal(err)
	}
	match := regexp.MustCompile(`WAIT_TIMEOUT_MS = ([0-9]+);`).FindSubmatch(source)
	if len(match) != 2 {
		t.Fatal("helper drain budget missing")
	}
	millis, err := strconv.Atoi(string(match[1]))
	if err != nil {
		t.Fatal(err)
	}
	if requestTimeout < time.Duration(millis)*time.Millisecond+3*time.Second {
		t.Fatal("wire deadline must cover helper drain plus margin", requestTimeout, millis)
	}
}

func TestStatusRejectsPendingCameraReleaseOverBudget(t *testing.T) {
	for _, count := range []int{-1, 3} {
		c, _ := cameraWire(t, true, func(map[string]any) string {
			return cameraOK(`{"credentialLocked":false,"displays":[],"pendingCameraReleases":` + strconv.Itoa(count) + `,"keepAwakeIntervalMs":2000,"lastActivityUptimeMs":0,"keepAwakeError":null}`)
		})
		_, err := c.Status(context.Background(), "SERIAL")
		requireCode(t, err, apperr.CodeProtocol)
	}
}
