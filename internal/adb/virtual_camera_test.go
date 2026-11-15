package adb

// 测试用途：验证固定伴生关联参数、遗留复用、精确匹配与启动失败清理，保护其它包和地址的关联。

import (
	"context"
	"errors"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"reflect"
	"strings"
	"testing"
)

type cameraADBExecutor struct {
	calls                                                [][]string
	associated, failAssociate, truncate, missingReadback bool
	files                                                string
}

func (f *cameraADBExecutor) Run(_ context.Context, _ string, args []string, _ process.Options) (process.Result, error) {
	f.calls = append(f.calls, append([]string(nil), args...))
	result := process.Result{}
	switch {
	case args[0] == "devices":
		result.Stdout = []byte("List of devices attached\nFIRST\tdevice transport_id:1\n")
	case strings.Join(args[3:], " ") == "am get-current-user":
		result.Stdout = []byte("10\n")
	case strings.Join(args[3:], " ") == "ls -1 /data/local/tmp":
		result.Stdout = []byte(f.files)
	case len(args) > 5 && args[4] == "companiondevice":
		switch args[5] {
		case "list":
			text := "Max ID: 90\nAssociation ID | Package Name | Mac Address\n88 | com.other | 00:00:00:00:00:10\n89 | com.android.shell | 00:00:00:00:00:11\n"
			if f.associated && !f.missingReadback {
				text += "90 | com.android.shell | 00:00:00:00:00:10\n"
			}
			result.Stdout = []byte(text)
			result.OutputTruncated = f.truncate
		case "associate":
			f.associated = true
			if f.failAssociate {
				return result, errors.New("association command failed")
			}
		case "disassociate":
			f.associated = false
		default:
			return result, errors.New("unexpected companion mutation")
		}
	}
	return result, nil
}

func TestCameraAssociationExactCreateReuseAndCleanup(t *testing.T) {
	for _, stale := range []bool{false, true} {
		f := &cameraADBExecutor{associated: stale}
		c := NewClient("adb", f)
		got, err := c.EnsureCameraAssociation(context.Background(), "FIRST")
		if err != nil || got.ID != 90 || got.UserID != 10 || got.Created == stale {
			t.Fatal(got, err)
		}
		creates := 0
		for _, args := range f.calls {
			if len(args) > 5 && args[5] == "associate" {
				creates++
				assertArgs(t, args, "-s", "FIRST", "shell", "cmd", "companiondevice", "associate", "10", cameraPackage, cameraMAC, cameraProfile, "true")
			}
		}
		if (creates == 0) != stale {
			t.Fatal("stale association not reused", f.calls)
		}
		if err = c.CleanupCameraAssociation(context.Background(), "FIRST", 10); err != nil || f.associated {
			t.Fatal(err)
		}
		mutations := 0
		for _, args := range f.calls {
			if len(args) > 5 && args[5] == "disassociate" {
				mutations++
				assertArgs(t, args, "-s", "FIRST", "shell", "cmd", "companiondevice", "disassociate", "10", cameraPackage, cameraMAC)
			}
			if strings.Contains(strings.Join(args, " "), "disassociate-all") {
				t.Fatal("global cleanup")
			}
		}
		if mutations != 1 {
			t.Fatal(f.calls)
		}
	}
}
func TestCameraAssociatePartialFailureCleansOnlyMarker(t *testing.T) {
	f := &cameraADBExecutor{failAssociate: true}
	_, err := NewClient("adb", f).EnsureCameraAssociation(context.Background(), "FIRST")
	assertAppErrorCode(t, err, apperr.CodeVirtualCameraUnavailable)
	if f.associated {
		t.Fatal("partial association leaked")
	}
}
func TestCameraAssociationListStrictAndLegacy(t *testing.T) {
	for _, tt := range []struct {
		text    string
		id      int
		invalid bool
	}{
		{"com.android.shell 00:00:00:00:00:10 71\n", 71, false},
		{"71 | com.android.shell.other | 00:00:00:00:00:10\n", 0, false},
		{"71 | com.android.shell | 00:00:00:00:00:100\n", 0, false},
		{"71 | com.android.shell | 00:00:00:00:00:10\n72 | com.android.shell | 00:00:00:00:00:10", 0, true},
		{"com.android.shell 00:00:00:00:00:10", 0, true},
		{"Service companiondevice not found", 0, true},
		{"-1 | com.android.shell | 00:00:00:00:00:10", 0, true},
	} {
		id, err := parseCameraAssociation(tt.text)
		if (err != nil) != tt.invalid || id != tt.id {
			t.Fatal(tt, id, err)
		}
	}
	f := &cameraADBExecutor{truncate: true}
	_, err := NewClient("adb", f).EnsureCameraAssociation(context.Background(), "FIRST")
	assertAppErrorCode(t, err, apperr.CodeVirtualCameraUnavailable)
	for _, args := range f.calls {
		if len(args) > 5 && args[5] == "associate" {
			t.Fatal("truncated output mutated device")
		}
	}
}
func TestCameraPathsRejectInjectionBeforeADB(t *testing.T) {
	f := &cameraADBExecutor{}
	c := NewClient("adb", f)
	for _, path := range []string{"/data/local/tmp/x", "/data/local/tmp/aactl-camera-*", "/data/local/tmp/aactl-camera-../../x.img"} {
		if c.PushCameraImage(context.Background(), "FIRST", "image.png", path) == nil || c.RemoveCameraImage(context.Background(), "FIRST", path) == nil {
			t.Fatal(path)
		}
	}
	if !reflect.DeepEqual(f.calls, [][]string(nil)) {
		t.Fatal("invalid path reached adb")
	}
	if c.StartDisplaydCamera(context.Background(), "FIRST", CameraAssociation{ID: -1}) == nil || len(f.calls) != 0 {
		t.Fatal("invalid association reached adb")
	}
}

func TestCameraStartupAndTransferUseFixedArgv(t *testing.T) {
	f := &cameraADBExecutor{}
	c := NewClient("adb", f)
	if err := c.StartDisplaydCamera(context.Background(), "FIRST", CameraAssociation{ID: 71, UserID: 10}); err != nil {
		t.Fatal(err)
	}
	assertArgs(t, f.calls[1], "-s", "FIRST", "shell", "CLASSPATH="+displaydAPK+" nohup app_process / --nice-name=aactl-displayd dev.aiauto.displayd.Main --virtual-camera-association 71 10 </dev/null >"+displaydLog+" 2>&1 &")
	remote := "/data/local/tmp/aactl-camera-12345678-1234-4234-8234-123456789abc.img"
	if err := c.PushCameraImage(context.Background(), "FIRST", "C:/space path/image.png", remote); err != nil {
		t.Fatal(err)
	}
	assertArgs(t, f.calls[3], "-s", "FIRST", "push", "C:/space path/image.png", remote)
	if err := c.RemoveCameraImage(context.Background(), "FIRST", remote); err != nil {
		t.Fatal(err)
	}
	assertArgs(t, f.calls[5], "-s", "FIRST", "shell", "rm", "-f", remote)
}

func TestCameraOrphanImageCleanupMatchesWholeFilename(t *testing.T) {
	name := "aactl-camera-12345678-1234-4234-8234-123456789abc.img"
	f := &cameraADBExecutor{files: strings.Join([]string{name, "other.img", name + ".bak", "aactl-camera-*.img"}, "\n")}
	if err := NewClient("adb", f).CleanupCameraImages(context.Background(), "FIRST"); err != nil {
		t.Fatal(err)
	}
	if len(f.calls) != 4 {
		t.Fatal("cleanup touched unrelated file", f.calls)
	}
	assertArgs(t, f.calls[3], "-s", "FIRST", "shell", "rm", "-f", "/data/local/tmp/"+name)
}
