package mcpserver

// 功能用途：提供可复用的只读视觉候选 adapter，并约束独立 observation 图片清零生命周期。

import (
	"context"
	"encoding/json"
	"time"

	"github.com/lejunyang/ai-auto-android/internal/visual"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ToolVisualTargetPropose 是共享与独立 adapter 统一使用的只读候选工具名。
const ToolVisualTargetPropose = "android_visual_target_propose"

// VisualProposeInput 只绑定 observation 和预期前台包，不包含动作字段。
type VisualProposeInput struct {
	ObservationID   string `json:"observationId"`
	ExpectedPackage string `json:"expectedPackage"`
}

// VisualProposeOutput 关联同一 observation、脱敏 hierarchy 和视觉候选。
type VisualProposeOutput struct {
	Observation       visual.Observation `json:"observation"`
	Candidates        []visual.Candidate `json:"candidates"`
	ActionCommitCount int                `json:"actionCommitCount"`
}

type visualProposer interface {
	Propose(context.Context, visual.ProposalRequest) (visual.ProposalResponse, error)
}

// VisualAdapterResponse 同时持有结构化输出和短生命周期 MCP 图片副本。
type VisualAdapterResponse struct {
	Output     VisualProposeOutput
	ToolResult *mcp.CallToolResult
	image      []byte
}

// Close 清零 MCP ImageContent 与 adapter 持有的图片副本；重复调用安全。
func (response *VisualAdapterResponse) Close() {
	if response == nil {
		return
	}
	clearVisualBytes(response.image)
	if response.ToolResult != nil {
		for _, content := range response.ToolResult.Content {
			if image, ok := content.(*mcp.ImageContent); ok {
				clearVisualBytes(image.Data)
			}
		}
	}
	response.image = nil
}

// VisualAdapter 是不具备动作执行能力的独立 MCP adapter。
type VisualAdapter struct {
	proposer visualProposer
	registry *visual.Registry
	now      func() time.Time
}

// NewVisualAdapter 创建只读 adapter；调用方必须显式决定是否注册工具。
func NewVisualAdapter(
	proposer visualProposer,
	registry *visual.Registry,
	now func() time.Time,
) *VisualAdapter {
	if now == nil {
		now = time.Now
	}
	return &VisualAdapter{proposer: proposer, registry: registry, now: now}
}

// Tool 返回严格输入输出 Schema 和只读注解，不修改共享工具列表。
func (adapter *VisualAdapter) Tool() *mcp.Tool {
	return &mcp.Tool{
		Name:         ToolVisualTargetPropose,
		Description:  "Propose visual targets for one verified observation without executing an action.",
		InputSchema:  visualInputSchema(),
		OutputSchema: visualOutputSchema(),
		Annotations:  readOnlyAnnotations(),
	}
}

// Call 返回同一 observation 的结构化候选与图片；失败时不返回部分响应。
func (adapter *VisualAdapter) Call(
	ctx context.Context,
	input VisualProposeInput,
) (*VisualAdapterResponse, error) {
	if adapter == nil || adapter.proposer == nil || adapter.registry == nil {
		return nil, visual.NewError(visual.CodeObservationInvalid)
	}
	proposal, err := adapter.proposer.Propose(ctx, visual.ProposalRequest{
		ObservationID:   input.ObservationID,
		ExpectedPackage: input.ExpectedPackage,
		Now:             adapter.now().UTC(),
	})
	if err != nil {
		return nil, err
	}
	observation, ok := adapter.registry.Lookup(input.ObservationID)
	if !ok {
		return nil, visual.NewError(visual.CodeObservationNotFound)
	}

	var imageCopy []byte
	err = adapter.registry.WithImage(input.ObservationID, func(image []byte) error {
		imageCopy = append([]byte(nil), image...)
		return nil
	})
	if err != nil {
		clearVisualBytes(imageCopy)
		return nil, err
	}
	resultImage := append([]byte(nil), imageCopy...)
	response := &VisualAdapterResponse{
		Output: VisualProposeOutput{
			Observation:       *observation,
			Candidates:        proposal.Candidates,
			ActionCommitCount: proposal.ActionCommitCount,
		},
		ToolResult: &mcp.CallToolResult{
			Content: []mcp.Content{
				&mcp.ImageContent{Data: resultImage, MIMEType: "image/png"},
			},
		},
		image: imageCopy,
	}
	return response, nil
}

func visualInputSchema() map[string]any {
	return strictVisualSchema(`{
		"type":"object",
		"required":["observationId","expectedPackage"],
		"properties":{
			"observationId":{"type":"string","format":"uuid"},
			"expectedPackage":{"type":"string","minLength":3,"maxLength":255}
		},
		"additionalProperties":false
	}`)
}

func visualOutputSchema() map[string]any {
	return strictVisualSchema(`{
		"type":"object",
		"required":["observation","candidates","actionCommitCount"],
		"properties":{
			"observation":{
				"type":"object",
				"required":["schemaVersion","id","foregroundPackage","screen","crop","capturedAt","expiresAt","png","hierarchy"],
				"properties":{
					"schemaVersion":{"const":"visual-observation/1.0"},
					"id":{"type":"string","format":"uuid"},
					"foregroundPackage":{"type":"string","minLength":3,"maxLength":255},
					"screen":{
						"type":"object",
						"required":["width","height","rotation"],
						"properties":{
							"width":{"type":"integer","minimum":1},
							"height":{"type":"integer","minimum":1},
							"rotation":{"enum":[0,90,180,270]}
						},
						"additionalProperties":false
					},
					"crop":{
						"type":"object",
						"required":["left","top","right","bottom"],
						"properties":{
							"left":{"type":"integer","minimum":0},
							"top":{"type":"integer","minimum":0},
							"right":{"type":"integer","minimum":1},
							"bottom":{"type":"integer","minimum":1}
						},
						"additionalProperties":false
					},
					"capturedAt":{"type":"string","format":"date-time"},
					"expiresAt":{"type":"string","format":"date-time"},
					"png":{
						"type":"object",
						"required":["format","sizeBytes","sha256","verification"],
						"properties":{
							"format":{"const":"png"},
							"sizeBytes":{"type":"integer","minimum":1,"maximum":921600},
							"sha256":{"type":"string","pattern":"^[0-9a-f]{64}$"},
							"verification":{"const":"trusted-context"}
						},
						"additionalProperties":false
					},
					"hierarchy":{"type":"array","maxItems":256,"items":{"$ref":"#/$defs/hierarchyNode"}}
				},
				"additionalProperties":false
			},
			"candidates":{
				"type":"array",
				"maxItems":64,
				"items":{
					"type":"object",
					"required":["id","observationId","source","point","bounds","confidence"],
					"properties":{
						"id":{"type":"string","format":"uuid"},
						"observationId":{"type":"string","format":"uuid"},
						"source":{"enum":["ocr","template","model","manual"]},
						"point":{"$ref":"#/$defs/normalizedPoint"},
						"bounds":{"$ref":"#/$defs/normalizedBounds"},
						"confidence":{"type":"number","minimum":0,"maximum":1}
					},
					"additionalProperties":false
				}
			},
			"actionCommitCount":{"const":0}
		},
		"additionalProperties":false,
		"$defs":{
			"normalizedPoint":{
				"type":"object",
				"required":["x","y"],
				"properties":{
					"x":{"type":"number","minimum":0,"maximum":1},
					"y":{"type":"number","minimum":0,"maximum":1}
				},
				"additionalProperties":false
			},
			"normalizedBounds":{
				"type":"object",
				"required":["left","top","right","bottom"],
				"properties":{
					"left":{"type":"number","minimum":0,"maximum":1},
					"top":{"type":"number","minimum":0,"maximum":1},
					"right":{"type":"number","minimum":0,"maximum":1},
					"bottom":{"type":"number","minimum":0,"maximum":1}
				},
				"additionalProperties":false
			},
			"hierarchyNode":{
				"oneOf":[
					{"$ref":"#/$defs/hierarchyPublic"},
					{"$ref":"#/$defs/hierarchySensitive"}
				]
			},
			"hierarchyPublic":{
				"type":"object",
				"required":["role","bounds","sensitive","children"],
				"properties":{
					"role":{"type":"string","minLength":1,"maxLength":128},
					"label":{"type":"string","minLength":1,"maxLength":256},
					"bounds":{"$ref":"#/$defs/normalizedBounds"},
					"sensitive":{"const":false},
					"children":{"type":"array","maxItems":256,"items":{"$ref":"#/$defs/hierarchyNode"}}
				},
				"additionalProperties":false
			},
			"hierarchySensitive":{
				"type":"object",
				"required":["role","bounds","sensitive","children"],
				"properties":{
					"role":{"type":"string","minLength":1,"maxLength":128},
					"bounds":{"$ref":"#/$defs/normalizedBounds"},
					"sensitive":{"const":true},
					"children":{"type":"array","maxItems":256,"items":{"$ref":"#/$defs/hierarchyNode"}}
				},
				"additionalProperties":false
			}
		}
	}`)
}

func strictVisualSchema(source string) map[string]any {
	var schema map[string]any
	if err := json.Unmarshal([]byte(source), &schema); err != nil {
		panic(err)
	}
	return schema
}

func clearVisualBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}
