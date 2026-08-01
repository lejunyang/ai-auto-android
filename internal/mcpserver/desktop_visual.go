package mcpserver

// 功能用途：注册共享桌面视觉候选工具，并在 MCP 响应写出后确定清零图片字节。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"

	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/jsonrpc"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type desktopVisualInput struct {
	Device          string               `json:"device"`
	ExpectedPackage string               `json:"expectedPackage"`
	Target          visual.DesktopTarget `json:"target"`
}

type desktopVisualRuntime struct {
	service *visual.DesktopProposalService
	mu      sync.Mutex
	pending map[string]func()
	closed  bool
}

func newDesktopVisualRuntime(
	service *visual.DesktopProposalService,
) *desktopVisualRuntime {
	return &desktopVisualRuntime{
		service: service,
		pending: make(map[string]func()),
	}
}

func (runtime *desktopVisualRuntime) register(id string, cleanup func()) bool {
	if runtime == nil || id == "" || cleanup == nil {
		return false
	}
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	if runtime.closed {
		return false
	}
	if existing := runtime.pending[id]; existing != nil {
		existing()
	}
	runtime.pending[id] = cleanup
	return true
}

func (runtime *desktopVisualRuntime) cleanup(id string) {
	if runtime == nil || id == "" {
		return
	}
	runtime.mu.Lock()
	cleanup := runtime.pending[id]
	delete(runtime.pending, id)
	runtime.mu.Unlock()
	if cleanup != nil {
		cleanup()
	}
}

func (runtime *desktopVisualRuntime) close() {
	if runtime == nil {
		return
	}
	runtime.mu.Lock()
	if runtime.closed {
		runtime.mu.Unlock()
		return
	}
	runtime.closed = true
	cleanups := make([]func(), 0, len(runtime.pending))
	for id, cleanup := range runtime.pending {
		cleanups = append(cleanups, cleanup)
		delete(runtime.pending, id)
	}
	runtime.mu.Unlock()
	for _, cleanup := range cleanups {
		cleanup()
	}
}

func (runtime *desktopVisualRuntime) pendingCount() int {
	if runtime == nil {
		return 0
	}
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	return len(runtime.pending)
}

func (runtime *desktopVisualRuntime) wrapTransport(
	transport mcp.Transport,
) mcp.Transport {
	if runtime == nil {
		return transport
	}
	return &visualCleanupTransport{Transport: transport, runtime: runtime}
}

type visualCleanupTransport struct {
	mcp.Transport
	runtime *desktopVisualRuntime
}

func (transport *visualCleanupTransport) Connect(
	ctx context.Context,
) (mcp.Connection, error) {
	connection, err := transport.Transport.Connect(ctx)
	if err != nil {
		transport.runtime.close()
		return nil, err
	}
	return &visualCleanupConnection{
		Connection: connection,
		runtime:    transport.runtime,
	}, nil
}

type visualCleanupConnection struct {
	mcp.Connection
	runtime *desktopVisualRuntime
}

func (connection *visualCleanupConnection) Write(
	ctx context.Context,
	message jsonrpc.Message,
) error {
	observationID := visualResponseObservationID(message)
	err := connection.Connection.Write(ctx, message)
	connection.runtime.cleanup(observationID)
	return err
}

func (connection *visualCleanupConnection) Close() error {
	connection.runtime.close()
	return connection.Connection.Close()
}

func visualResponseObservationID(message jsonrpc.Message) string {
	response, ok := message.(*jsonrpc.Response)
	if !ok || response == nil || len(response.Result) == 0 {
		return ""
	}
	var payload struct {
		StructuredContent struct {
			Observation struct {
				SchemaVersion string `json:"schemaVersion"`
				ID            string `json:"id"`
			} `json:"observation"`
		} `json:"structuredContent"`
	}
	if err := json.Unmarshal(response.Result, &payload); err != nil ||
		payload.StructuredContent.Observation.SchemaVersion !=
			"visual-observation/1.0" {
		return ""
	}
	return payload.StructuredContent.Observation.ID
}

func addDesktopVisualTool(
	server *mcp.Server,
	runtime *desktopVisualRuntime,
) {
	server.AddTool(&mcp.Tool{
		Name:         ToolVisualTargetPropose,
		Description:  "Capture one verified desktop visual observation and propose hierarchy-derived targets without executing an action.",
		InputSchema:  desktopVisualInputSchema(),
		OutputSchema: visualOutputSchema(),
		Annotations:  readOnlyAnnotations(),
	}, func(
		ctx context.Context,
		request *mcp.CallToolRequest,
	) (*mcp.CallToolResult, error) {
		input, err := decodeDesktopVisualInput(request)
		if err != nil {
			return desktopVisualToolError(err), nil
		}
		if runtime == nil || runtime.service == nil {
			return desktopVisualToolError(
				visual.NewError(visual.CodeObservationInvalid),
			), nil
		}
		lease, err := runtime.service.Propose(ctx, visual.DesktopProposalRequest{
			Device:          input.Device,
			ExpectedPackage: input.ExpectedPackage,
			Target:          input.Target,
		})
		if err != nil {
			return desktopVisualToolError(err), nil
		}
		defer lease.Close()
		bundle := lease.Bundle()
		output := VisualProposeOutput{
			Observation:       bundle.Observation,
			Candidates:        bundle.Candidates,
			ActionCommitCount: bundle.ActionCommitCount,
		}
		outputBytes, err := json.Marshal(output)
		if err != nil {
			return desktopVisualToolError(
				visual.NewError(visual.CodeJSONInvalid),
			), nil
		}
		image, err := lease.TakeImage()
		if err != nil {
			return desktopVisualToolError(err), nil
		}
		if !runtime.register(bundle.Observation.ID, func() {
			clearVisualBytes(image)
		}) {
			clearVisualBytes(image)
			return desktopVisualToolError(
				visual.NewError(visual.CodeObservationNotFound),
			), nil
		}
		return &mcp.CallToolResult{
			Content: []mcp.Content{
				&mcp.ImageContent{Data: image, MIMEType: "image/png"},
			},
			StructuredContent: json.RawMessage(outputBytes),
		}, nil
	})
}

func decodeDesktopVisualInput(
	request *mcp.CallToolRequest,
) (desktopVisualInput, error) {
	if request == nil || request.Params == nil ||
		len(request.Params.Arguments) == 0 ||
		len(request.Params.Arguments) > 16*1024 {
		return desktopVisualInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	raw := request.Params.Arguments
	if err := rejectDesktopVisualDuplicateKeys(raw); err != nil {
		return desktopVisualInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var input desktopVisualInput
	if err := decoder.Decode(&input); err != nil {
		return desktopVisualInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return desktopVisualInput{}, visual.NewError(visual.CodeJSONInvalid)
	}
	return input, nil
}

func rejectDesktopVisualDuplicateKeys(payload []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.UseNumber()
	if err := consumeDesktopVisualJSON(decoder); err != nil {
		return err
	}
	if token, err := decoder.Token(); !errors.Is(err, io.EOF) {
		if err != nil {
			return err
		}
		return fmt.Errorf("unexpected trailing token %v", token)
	}
	return nil
}

func consumeDesktopVisualJSON(decoder *json.Decoder) error {
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delimiter, structured := token.(json.Delim)
	if !structured {
		return nil
	}
	switch delimiter {
	case '{':
		seen := make(map[string]struct{})
		for decoder.More() {
			keyToken, err := decoder.Token()
			if err != nil {
				return err
			}
			key, ok := keyToken.(string)
			if !ok {
				return errors.New("object key is not a string")
			}
			if _, duplicate := seen[key]; duplicate {
				return errors.New("duplicate object key")
			}
			seen[key] = struct{}{}
			if err := consumeDesktopVisualJSON(decoder); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil {
			return err
		}
		if end != json.Delim('}') {
			return errors.New("object is not terminated")
		}
	case '[':
		for decoder.More() {
			if err := consumeDesktopVisualJSON(decoder); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil {
			return err
		}
		if end != json.Delim(']') {
			return errors.New("array is not terminated")
		}
	default:
		return errors.New("unexpected closing delimiter")
	}
	return nil
}

func desktopVisualToolError(err error) *mcp.CallToolResult {
	result := &mcp.CallToolResult{}
	result.SetError(err)
	return result
}

func desktopVisualInputSchema() map[string]any {
	return strictVisualSchema(`{
		"type":"object",
		"required":["device","expectedPackage","target"],
		"properties":{
			"device":{
				"type":"string",
				"minLength":1,
				"maxLength":255,
				"pattern":"^[A-Za-z0-9][A-Za-z0-9._:%+\\-\\[\\]]{0,254}$"
			},
			"expectedPackage":{
				"type":"string",
				"minLength":3,
				"maxLength":255,
				"pattern":"^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$"
			},
			"target":{
				"type":"object",
				"properties":{
					"role":{"type":"string","minLength":1,"maxLength":128},
					"label":{"type":"string","minLength":1,"maxLength":256}
				},
				"additionalProperties":false,
				"anyOf":[
					{"required":["role"]},
					{"required":["label"]}
				]
			}
		},
		"additionalProperties":false
	}`)
}
