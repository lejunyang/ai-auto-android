package mcpserver

// 测试用途：验证共享 MCP 桌面视觉工具的严格只读 schema、同轮采集和 transport 写后清零。

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	"image/png"
	"strings"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/protocol"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/jsonrpc"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestSharedServerPublishesReadOnlyDesktopVisualTool(t *testing.T) {
	runtime := newDesktopVisualRuntime(desktopVisualService(t))
	session := connectVisualClient(t, &fakeAutomation{}, runtime)

	listed, err := session.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatalf("ListTools() error = %v", err)
	}
	var visualTool *mcp.Tool
	for _, tool := range listed.Tools {
		if tool.Name == ToolVisualTargetPropose {
			visualTool = tool
			break
		}
	}
	if visualTool == nil {
		t.Fatalf("visual tool missing from %#v", listed.Tools)
	}
	if visualTool.Annotations == nil || !visualTool.Annotations.ReadOnlyHint {
		t.Fatalf("visual annotations = %#v", visualTool.Annotations)
	}
	input := schemaObject(t, visualTool.InputSchema)
	assertStrictObjectSchema(t, "desktop visual input", input)
	encoded, err := json.Marshal(input)
	if err != nil {
		t.Fatalf("marshal visual schema: %v", err)
	}
	for _, forbidden := range []string{"execute", "action", "x", "y", "filePath", "pngBytes"} {
		if bytes.Contains(encoded, []byte(`"`+forbidden+`"`)) {
			t.Fatalf("visual input schema contains %q: %s", forbidden, encoded)
		}
	}
}

func TestSharedServerVisualCallReturnsSameObservationAndCleansAfterWrite(t *testing.T) {
	service, screenshotBytes := desktopVisualServiceWithBytes(t)
	runtime := newDesktopVisualRuntime(service)
	session := connectVisualClient(t, &fakeAutomation{}, runtime)

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolVisualTargetPropose,
		Arguments: map[string]any{
			"device":          "SERIAL",
			"expectedPackage": "com.example.notes",
			"target": map[string]any{
				"role":  "button",
				"label": "Save",
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
	if observation["foregroundPackage"] != "com.example.notes" ||
		len(candidates) != 1 ||
		structured["actionCommitCount"] != float64(0) {
		t.Fatalf("structured visual result = %#v", structured)
	}
	candidate := candidates[0].(map[string]any)
	if candidate["observationId"] != observation["id"] ||
		candidate["source"] != "template" {
		t.Fatalf("candidate = %#v observation = %#v", candidate, observation)
	}
	if len(result.Content) != 1 {
		t.Fatalf("content = %#v", result.Content)
	}
	imageContent, ok := result.Content[0].(*mcp.ImageContent)
	if !ok || imageContent.MIMEType != "image/png" || len(imageContent.Data) == 0 {
		t.Fatalf("image content = %#v", result.Content[0])
	}
	if !allVisualZero(screenshotBytes) {
		t.Fatal("backend screenshot buffer was not cleared")
	}
	if runtime.pendingCount() != 0 {
		t.Fatalf("pending visual cleanups = %d", runtime.pendingCount())
	}
	serialized, err := json.Marshal(structured)
	if err != nil {
		t.Fatalf("marshal structured visual result: %v", err)
	}
	for _, forbidden := range []string{"filePath", "pngBytes", "screenshotBase64", "secret-123"} {
		if bytes.Contains(serialized, []byte(forbidden)) {
			t.Fatalf("structured result contains %q: %s", forbidden, serialized)
		}
	}
}

func TestDesktopVisualInputStrictlyRejectsDuplicateUnknownAndTrailingJSON(t *testing.T) {
	valid := `{
		"device":"SERIAL",
		"expectedPackage":"com.example.notes",
		"target":{"role":"button","label":"Save"}
	}`
	tests := []string{
		`{"device":"SERIAL","device":"SECOND","expectedPackage":"com.example.notes","target":{"label":"Save"}}`,
		`{"device":"SERIAL","expectedPackage":"com.example.notes","target":{"label":"Save","extra":true}}`,
		`{"device":"SERIAL","expectedPackage":"com.example.notes","target":{"label":"Save"},"action":"ui.tap"}`,
		valid + ` true`,
	}
	for _, payload := range tests {
		t.Run(payload, func(t *testing.T) {
			request := &mcp.CallToolRequest{
				Params: &mcp.CallToolParamsRaw{Arguments: []byte(payload)},
			}

			if _, err := decodeDesktopVisualInput(request); visual.ErrorCode(err) !=
				visual.CodeJSONInvalid {
				t.Fatalf("error code = %q, error=%v", visual.ErrorCode(err), err)
			}
		})
	}
	request := &mcp.CallToolRequest{
		Params: &mcp.CallToolParamsRaw{Arguments: []byte(valid)},
	}
	if _, err := decodeDesktopVisualInput(request); err != nil {
		t.Fatalf("valid input error = %v", err)
	}
}

func TestVisualCleanupTransportClearsImagesAfterSuccessfulAndFailedWrites(t *testing.T) {
	for _, writeError := range []error{nil, errors.New("write failed")} {
		t.Run(strings.TrimSpace(errorText(writeError)), func(t *testing.T) {
			runtime := newDesktopVisualRuntime(nil)
			imageBytes := []byte("private-image")
			runtime.register("019fbcaa-0000-7000-8000-000000000044", func() {
				clearVisualBytes(imageBytes)
			})
			connection := &visualCleanupConnection{
				Connection: &fakeVisualConnection{writeError: writeError},
				runtime:    runtime,
			}
			message := &jsonrpc.Response{
				Result: []byte(`{
					"content":[{"type":"image","mimeType":"image/png","data":"cHJpdmF0ZS1pbWFnZQ=="}],
					"structuredContent":{
						"observation":{
							"schemaVersion":"visual-observation/1.0",
							"id":"019fbcaa-0000-7000-8000-000000000044"
						}
					}
				}`),
			}

			err := connection.Write(context.Background(), message)

			if !errors.Is(err, writeError) {
				t.Fatalf("Write() error = %v, want %v", err, writeError)
			}
			if !allVisualZero(imageBytes) || runtime.pendingCount() != 0 {
				t.Fatalf("cleanup failed: image=%q pending=%d", imageBytes, runtime.pendingCount())
			}
		})
	}
}

func TestVisualCleanupConnectionCloseClearsAllPendingImages(t *testing.T) {
	runtime := newDesktopVisualRuntime(nil)
	first := []byte("first-private-image")
	second := []byte("second-private-image")
	runtime.register("019fbcaa-0000-7000-8000-000000000044", func() {
		clearVisualBytes(first)
	})
	runtime.register("019fbcaa-0000-7000-8000-000000000045", func() {
		clearVisualBytes(second)
	})
	connection := &visualCleanupConnection{
		Connection: &fakeVisualConnection{},
		runtime:    runtime,
	}

	if err := connection.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	if !allVisualZero(first) || !allVisualZero(second) || runtime.pendingCount() != 0 {
		t.Fatalf(
			"close cleanup failed: first=%q second=%q pending=%d",
			first,
			second,
			runtime.pendingCount(),
		)
	}
}

type desktopVisualBackend struct {
	device      protocol.Device
	hierarchies []adb.Hierarchy
	screenshot  adb.Screenshot
}

func (backend *desktopVisualBackend) DeviceInfo(context.Context, string) (protocol.Device, error) {
	return backend.device, nil
}

func (backend *desktopVisualBackend) Hierarchy(context.Context, string) (adb.Hierarchy, error) {
	value := backend.hierarchies[0]
	backend.hierarchies = backend.hierarchies[1:]
	return value, nil
}

func (backend *desktopVisualBackend) Screenshot(context.Context, string) (adb.Screenshot, error) {
	return backend.screenshot, nil
}

func desktopVisualService(t *testing.T) *visual.DesktopProposalService {
	t.Helper()
	service, _ := desktopVisualServiceWithBytes(t)
	return service
}

func desktopVisualServiceWithBytes(
	t *testing.T,
) (*visual.DesktopProposalService, []byte) {
	t.Helper()
	pngBytes := desktopVisualPNG(t)
	xmlSource := `<?xml version="1.0"?><hierarchy rotation="0">` +
		`<node class="android.widget.FrameLayout" package="com.example.notes" ` +
		`bounds="[0,0][100,200]" text="" content-desc="" password="false">` +
		`<node class="android.widget.Button" package="com.example.notes" ` +
		`bounds="[10,20][50,60]" text="Save" content-desc="" password="false"/>` +
		`<node class="android.widget.EditText" package="com.example.notes" ` +
		`bounds="[10,70][90,100]" text="secret-123" content-desc="" password="true"/>` +
		`</node></hierarchy>`
	hierarchy := adb.Hierarchy{
		XML:    xmlSource,
		Bytes:  len(xmlSource),
		SHA256: hashDesktopVisualBytes([]byte(xmlSource)),
	}
	backend := &desktopVisualBackend{
		device: protocol.Device{
			Serial:       "SERIAL",
			State:        "device",
			Transport:    "usb",
			Model:        "Pixel",
			Product:      "pixel",
			Connection:   protocol.DeviceConnection{TransportID: 7},
			Capabilities: []protocol.Capability{},
		},
		hierarchies: []adb.Hierarchy{hierarchy, hierarchy},
		screenshot: adb.Screenshot{
			Bytes:  pngBytes,
			SHA256: hashDesktopVisualBytes(pngBytes),
		},
	}
	return visual.NewDesktopProposalService(backend, visual.DesktopOptions{
		Now: func() time.Time {
			return mustVisualTime("2026-08-01T09:00:00Z")
		},
		ObservationID: func() string {
			return "019fbcaa-0000-7000-8000-000000000044"
		},
	}), pngBytes
}

func desktopVisualPNG(t *testing.T) []byte {
	t.Helper()
	canvas := image.NewRGBA(image.Rect(0, 0, 100, 200))
	for y := 0; y < 200; y++ {
		for x := 0; x < 100; x++ {
			canvas.SetRGBA(x, y, color.RGBA{R: 0x44, G: 0x66, B: 0x88, A: 0xff})
		}
	}
	canvas.SetRGBA(0, 0, color.RGBA{R: 0xff, A: 0xff})
	var output bytes.Buffer
	if err := png.Encode(&output, canvas); err != nil {
		t.Fatalf("png.Encode() error = %v", err)
	}
	return output.Bytes()
}

type fakeVisualConnection struct {
	writeError error
}

func (*fakeVisualConnection) Read(context.Context) (jsonrpc.Message, error) {
	return nil, errors.New("not implemented")
}

func (connection *fakeVisualConnection) Write(context.Context, jsonrpc.Message) error {
	return connection.writeError
}

func (*fakeVisualConnection) Close() error      { return nil }
func (*fakeVisualConnection) SessionID() string { return "" }

func connectVisualClient(
	t *testing.T,
	automation service.Automation,
	runtime *desktopVisualRuntime,
) *mcp.ClientSession {
	t.Helper()
	ctx := context.Background()
	server := newServer(automation, "test", runtime)
	serverTransport, clientTransport := mcp.NewInMemoryTransports()
	serverSession, err := server.Connect(ctx, runtime.wrapTransport(serverTransport), nil)
	if err != nil {
		t.Fatalf("server.Connect() error = %v", err)
	}
	client := mcp.NewClient(
		&mcp.Implementation{Name: "desktop-visual-test", Version: "test"},
		nil,
	)
	clientSession, err := client.Connect(ctx, clientTransport, nil)
	if err != nil {
		_ = serverSession.Close()
		t.Fatalf("client.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		_ = clientSession.Close()
		_ = serverSession.Close()
		runtime.close()
	})
	return clientSession
}

func errorText(err error) string {
	if err == nil {
		return "success"
	}
	return err.Error()
}

func hashDesktopVisualBytes(value []byte) string {
	sum := sha256.Sum256(value)
	return hex.EncodeToString(sum[:])
}
