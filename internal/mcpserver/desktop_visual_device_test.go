package mcpserver

// 测试用途：在明确 disposable emulator 上验证生产 ADB 视觉采集、共享 MCP 返回与写后清零。

import (
	"context"
	"os"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestDesktopVisualProductionMCPDeviceSmoke(t *testing.T) {
	serial := os.Getenv("N44_DEVICE_SERIAL")
	adbPath := os.Getenv("N44_ADB_PATH")
	if serial == "" || adbPath == "" {
		t.Skip("N44 disposable emulator environment is not configured")
	}
	client := adb.NewClient(adbPath, process.Runner{})
	device, err := client.DeviceInfo(context.Background(), serial)
	if err != nil {
		t.Fatalf("DeviceInfo() error = %v", err)
	}
	if device.Serial != serial || device.State != "device" || device.Transport != "emulator" {
		t.Fatalf("device = %#v", device)
	}
	runtime := newDesktopVisualRuntime(
		visual.NewDesktopProposalService(client, visual.DesktopOptions{}),
	)
	session := connectVisualClient(t, service.New(client, nil), runtime)

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolVisualTargetPropose,
		Arguments: map[string]any{
			"device":          serial,
			"expectedPackage": "dev.aiauto.fixture",
			"target": map[string]any{
				"role":  "button",
				"label": "Fixture click target",
			},
		},
	})

	if err != nil {
		t.Fatalf("CallTool() error = %v", err)
	}
	if result.IsError {
		t.Fatalf("visual tool error = %#v", result.Content)
	}
	structured := structuredObject(t, result)
	observation := structured["observation"].(map[string]any)
	candidates := structured["candidates"].([]any)
	if observation["foregroundPackage"] != "dev.aiauto.fixture" ||
		observation["schemaVersion"] != "visual-observation/1.0" ||
		len(candidates) != 1 ||
		structured["actionCommitCount"] != float64(0) {
		t.Fatalf("structured visual result = %#v", structured)
	}
	candidate := candidates[0].(map[string]any)
	if candidate["observationId"] != observation["id"] ||
		candidate["source"] != "template" ||
		candidate["confidence"].(float64) < 0.7 {
		t.Fatalf("candidate = %#v observation = %#v", candidate, observation)
	}
	if len(result.Content) != 1 {
		t.Fatalf("content = %#v", result.Content)
	}
	image, ok := result.Content[0].(*mcp.ImageContent)
	if !ok || image.MIMEType != "image/png" || len(image.Data) == 0 {
		t.Fatalf("image content = %#v", result.Content[0])
	}
	if runtime.pendingCount() != 0 {
		t.Fatalf("pending visual cleanups = %d", runtime.pendingCount())
	}
}
