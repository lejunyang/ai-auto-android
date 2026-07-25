package mcpserver

// 测试用途：验证独立视觉 adapter 的只读 schema、同 observation 图片/层级返回和清零生命周期。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestVisualAdapterDefinesStandaloneReadOnlyToolWithoutExecuteCapability(t *testing.T) {
	adapter := NewVisualAdapter(nil, nil, func() time.Time {
		return mustVisualTime("2026-07-26T01:00:05Z")
	})

	tool := adapter.Tool()

	if tool.Name != ToolVisualTargetPropose {
		t.Fatalf("tool name = %q, want %q", tool.Name, ToolVisualTargetPropose)
	}
	if tool.Annotations == nil || !tool.Annotations.ReadOnlyHint {
		t.Fatalf("tool annotations = %#v", tool.Annotations)
	}
	input := visualSchemaObject(t, tool.InputSchema)
	output := visualSchemaObject(t, tool.OutputSchema)
	assertVisualStrictObject(t, "visual input", input)
	assertVisualStrictObject(t, "visual output", output)
	if _, ok := input["properties"].(map[string]any)["execute"]; ok {
		t.Fatal("visual input schema unexpectedly exposes execute")
	}
	if _, ok := output["properties"].(map[string]any)["pngBytes"]; ok {
		t.Fatal("visual output schema unexpectedly exposes pngBytes")
	}
}

func TestVisualAdapterReturnsImageAndRedactedHierarchyForSameObservation(t *testing.T) {
	registry := visualRegistry(t)
	proposer := visualProposerFunc(func(
		context.Context,
		visual.ProposalRequest,
	) (visual.ProposalResponse, error) {
		return visual.ProposalResponse{
			Candidates: []visual.Candidate{
				{
					ID:            "123e4567-e89b-42d3-a456-426614174101",
					ObservationID: visualObservationID,
					Source:        visual.SourceOCR,
					Point:         visual.NormalizedPoint{X: 0.5, Y: 0.65},
					Bounds: visual.NormalizedBounds{
						Left: 0.3, Top: 0.6, Right: 0.7, Bottom: 0.8,
					},
					Confidence: 0.99,
				},
			},
			ActionCommitCount: 0,
		}, nil
	})
	adapter := NewVisualAdapter(proposer, registry, func() time.Time {
		return mustVisualTime("2026-07-26T01:00:05Z")
	})

	response, err := adapter.Call(context.Background(), VisualProposeInput{
		ObservationID:  visualObservationID,
		ExpectedPackage: visualTargetPackage,
	})
	if err != nil {
		t.Fatalf("Call() error = %v", err)
	}
	if response.Output.Observation.ID != visualObservationID ||
		response.Output.ActionCommitCount != 0 ||
		len(response.Output.Candidates) != 1 {
		t.Fatalf("structured output = %#v", response.Output)
	}
	node := response.Output.Observation.Hierarchy[0]
	if !node.Sensitive || node.Label != "" {
		t.Fatalf("sensitive hierarchy node = %#v", node)
	}
	if len(response.ToolResult.Content) != 1 {
		t.Fatalf("tool content = %#v", response.ToolResult.Content)
	}
	image, ok := response.ToolResult.Content[0].(*mcp.ImageContent)
	if !ok || image.MIMEType != "image/png" || len(image.Data) == 0 {
		t.Fatalf("image content = %#v", response.ToolResult.Content[0])
	}
	encoded, err := json.Marshal(response.Output)
	if err != nil {
		t.Fatalf("marshal structured output: %v", err)
	}
	for _, forbidden := range [][]byte{
		[]byte("pngBytes"),
		[]byte("screenshotBase64"),
		[]byte("filePath"),
		[]byte("secret-123"),
	} {
		if bytes.Contains(encoded, forbidden) {
			t.Fatalf("structured output contains forbidden value %q", forbidden)
		}
	}

	response.Close()

	if !allVisualZero(image.Data) {
		t.Fatal("MCP image was not cleared after response close")
	}
}

func TestVisualAdapterFailureReturnsNoImageAndZeroActionCommit(t *testing.T) {
	registry := visualRegistry(t)
	proposer := visualProposerFunc(func(
		context.Context,
		visual.ProposalRequest,
	) (visual.ProposalResponse, error) {
		return visual.ProposalResponse{ActionCommitCount: 0},
			visual.NewError(visual.CodeCandidateAmbiguous)
	})
	adapter := NewVisualAdapter(proposer, registry, time.Now)

	response, err := adapter.Call(context.Background(), VisualProposeInput{
		ObservationID:  visualObservationID,
		ExpectedPackage: visualTargetPackage,
	})

	if !errors.Is(err, visual.NewError(visual.CodeCandidateAmbiguous)) {
		t.Fatalf("Call() error = %v", err)
	}
	if response != nil {
		t.Fatalf("failed response = %#v, want nil", response)
	}
}

type visualProposerFunc func(
	context.Context,
	visual.ProposalRequest,
) (visual.ProposalResponse, error)

func (function visualProposerFunc) Propose(
	ctx context.Context,
	request visual.ProposalRequest,
) (visual.ProposalResponse, error) {
	return function(ctx, request)
}

func visualRegistry(t *testing.T) *visual.Registry {
	t.Helper()
	registry := visual.NewRegistry(visual.RegistryOptions{
		TrustedVerifier: func(image []byte, context visual.VerificationContext) (bool, error) {
			return len(image) > 0 && context.PNGSHA256 != "", nil
		},
	})
	t.Cleanup(registry.Close)
	image := []byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
	_, err := registry.Register(visual.Capture{
		ID:                visualObservationID,
		ForegroundPackage: visualTargetPackage,
		Screen:            visual.Screen{Width: 1000, Height: 2000, Rotation: 90},
		Crop:              visual.PixelBounds{Left: 200, Top: 100, Right: 1200, Bottom: 900},
		CapturedAt:        mustVisualTime("2026-07-26T01:00:00Z"),
		ExpiresAt:         mustVisualTime("2026-07-26T01:00:10Z"),
		PNGBytes:          image,
		Hierarchy: []visual.HierarchyNode{
			{
				Role:      "password",
				Label:     "secret-123",
				Bounds:    visual.NormalizedBounds{Left: 0.1, Top: 0.1, Right: 0.2, Bottom: 0.2},
				Sensitive: true,
			},
		},
	})
	if err != nil {
		t.Fatalf("Register() error = %v", err)
	}
	return registry
}

func visualSchemaObject(t *testing.T, schema any) map[string]any {
	t.Helper()
	payload, err := json.Marshal(schema)
	if err != nil {
		t.Fatalf("marshal schema: %v", err)
	}
	var object map[string]any
	if err := json.Unmarshal(payload, &object); err != nil {
		t.Fatalf("unmarshal schema: %v", err)
	}
	return object
}

func assertVisualStrictObject(t *testing.T, name string, schema map[string]any) {
	t.Helper()
	if schema["type"] != "object" || schema["additionalProperties"] != false {
		t.Fatalf("%s is not a strict object: %#v", name, schema)
	}
}

func allVisualZero(value []byte) bool {
	return bytes.Count(value, []byte{0}) == len(value)
}

func mustVisualTime(value string) time.Time {
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		panic(err)
	}
	return parsed
}

const (
	visualObservationID = "123e4567-e89b-42d3-a456-426614174044"
	visualTargetPackage = "com.example.notes"
)
