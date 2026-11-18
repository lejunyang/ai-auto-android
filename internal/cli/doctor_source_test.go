package cli

// 测试用途：验证诊断在两种输出格式中准确暴露定位器返回的实际 ADB 来源和路径。

import (
	"context"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

type diagnosticLocator struct {
	fixedLocator
	source string
}

func (l diagnosticLocator) ResolveWithSource() (adb.Resolution, error) {
	return adb.Resolution{Path: l.path, Source: l.source}, l.err
}

func TestDoctorReportsADBSource(t *testing.T) {
	for _, source := range []string{"explicit", "bundled", "path", "sdk"} {
		for _, compact := range []bool{false, true} {
			app, stdout := testApp(&commandExecutor{result: process.Result{Stdout: []byte("Android Debug Bridge version 1.0.41\nVersion 37.0.1\n")}}, "")
			app.Locator = diagnosticLocator{fixedLocator: fixedLocator{path: "/actual/platform-tools/adb"}, source: source}
			args := []string{"doctor"}
			if compact {
				args = append(args, "--json")
			}
			if code := app.Run(context.Background(), args); code != 0 {
				t.Fatalf("exit = %d: %s", code, stdout)
			}
			d := decodeEnvelope(t, stdout.Bytes()).Data.(map[string]any)
			if d["adbSource"] != source || d["adbPath"] != "/actual/platform-tools/adb" {
				t.Fatalf("doctor = %+v", d)
			}
		}
	}
}
