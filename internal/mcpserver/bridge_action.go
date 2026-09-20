package mcpserver

// 功能用途：本文件实现受独立白名单约束的语义动作工具，让模型能按选择器操作元素，
// 而不是只能按坐标点击。
//
// 为什么需要这个工具：此前 MCP 只暴露 7 个坐标类直连动作（见 risk.go），
// 模型无法表达「点击 resourceId 为 submit 的按钮」这一 UIAutomator 最核心的用法。
// CLI 侧一直可以通过 `aactl bridge action` 透传任意协议动作对象，MCP 侧却没有
// 对等入口，于是模型只能先读层级、自己算坐标、再拍一个可能已经移位的点。
//
// 为什么不直接把 bridge action 整个透传给模型：那等于把 29 个语义动作一次性开放，
// 其中包含锁屏、电源对话框、剪贴板读取等触及系统面与隐私的动作。这里改为维护
// 一份独立的、只含元素级安全动作的白名单，并且与 risk.go 的直连白名单互不覆盖——
// 两层各守自己的动作面，任一层都不能成为绕过另一层的旁路。

import (
	"context"
	"encoding/json"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ToolBridgeAction 是按选择器操作元素的语义动作工具。
const ToolBridgeAction = "android_bridge_action"

// semanticBridgeActions 是模型可经桥发起的语义动作白名单。
//
// 只收元素级、可由选择器唯一定位、且不改变应用生命周期或系统状态的动作：
//
//   - ui.click / ui.longClick：元素级点击，对应 UiObject2.click / longClick。
//   - ui.scrollIntoView：把目标滚动到可见区域，对应 UiScrollable.scrollIntoView。
//   - ui.nodeAction：focus / expand / collapse 等节点自身状态变更。
//
// 刻意不含的动作及原因：
//   - app.launch / app.stop：改变应用生命周期，属直连通道的既有职责。
//   - ui.tap / ui.swipe / ui.drag 等坐标动作：本工具的存在意义就是摆脱坐标。
//   - ui.lockScreen / ui.powerDialog / ui.notifications：触及系统面。
//   - clipboard.* / file.*：涉及隐私数据与文件系统，需要独立的安全决策。
//   - ui.setText：写入文本已有专门的 android_bridge_set_text，
//     那里有自己的定位字段约束与结果证据，不在这里重复开口。
var semanticBridgeActions = map[string]bool{
	"ui.click":          true,
	"ui.longClick":      true,
	"ui.scrollIntoView": true,
	"ui.nodeAction":     true,
}

// requireSemanticBridgeActionAllowed 校验动作是否在语义白名单内。
//
// 与 requireModelActionAllowed 分开而不是共用一份名单：两者的动作面本就不相交，
// 合并会让任一侧的放宽意外波及另一侧。
func requireSemanticBridgeActionAllowed(action string) error {
	if semanticBridgeActions[action] {
		return nil
	}
	return modelToolError{
		Code: apperr.CodeActionNotAllowed,
		Message: "The requested action is not in the semantic bridge allowlist. " +
			"This tool only performs element-scoped actions located by selector.",
		Retryable: false,
	}
}

// bridgeActionInput 是语义动作的入参。
//
// 三个定位字段用 anyOf 约束「至少填一个」，与 bridgeSetTextSchema 同理：
// 全空会匹配到任意节点，动作落点将不可预期。
type bridgeActionInput struct {
	Device      string `json:"device"`
	Action      string `json:"action"`
	Package     string `json:"package,omitempty"`
	ResourceID  string `json:"resourceId,omitempty"`
	Description string `json:"description,omitempty"`
	MatchText   string `json:"matchText,omitempty"`
	// NodeAction 仅 ui.nodeAction 使用。
	NodeAction string `json:"nodeAction,omitempty"`
	// DurationMS 仅 ui.longClick 使用。
	DurationMS int `json:"durationMs,omitempty"`
}

// selectorCandidate 是协议 target.selectorCandidates 的单个条目。
type selectorCandidate struct {
	Strategy string  `json:"strategy"`
	Value    string  `json:"value"`
	Weight   float64 `json:"weight"`
	Required bool    `json:"required"`
}

// toProtocolAction 把入参转换为协议动作对象。
//
// 权重按定位方式的可靠性递减：resourceId 是结构性标识，最稳；
// contentDescription 次之；可见文本最易随文案改动而失效。
func (input bridgeActionInput) toProtocolAction() (json.RawMessage, error) {
	candidates := make([]selectorCandidate, 0, 3)
	if input.ResourceID != "" {
		candidates = append(candidates, selectorCandidate{
			Strategy: "resourceId", Value: input.ResourceID, Weight: 1, Required: true,
		})
	}
	if input.Description != "" {
		candidates = append(candidates, selectorCandidate{
			Strategy: "contentDescription", Value: input.Description, Weight: 0.8,
		})
	}
	if input.MatchText != "" {
		candidates = append(candidates, selectorCandidate{
			Strategy: "text", Value: input.MatchText, Weight: 0.6,
		})
	}
	if len(candidates) == 0 {
		return nil, modelToolError{
			Code: apperr.CodeInvalidArgument,
			Message: "At least one of resourceId, description or matchText is required; " +
				"an empty selector would match an arbitrary node.",
			Retryable: false,
		}
	}

	target := map[string]any{"selectorCandidates": candidates}
	if input.Package != "" {
		target["packageName"] = input.Package
	}
	params := map[string]any{"target": target}

	switch input.Action {
	case "ui.longClick":
		if input.DurationMS != 0 {
			params["durationMs"] = input.DurationMS
		}
	case "ui.nodeAction":
		if input.NodeAction == "" {
			return nil, modelToolError{
				Code:      apperr.CodeInvalidArgument,
				Message:   "ui.nodeAction requires the nodeAction field.",
				Retryable: false,
			}
		}
		params["action"] = input.NodeAction
	}

	return json.Marshal(map[string]any{
		"type":   input.Action,
		"params": params,
	})
}

// bridgeActionSchema 描述语义动作的入参。
//
// action 枚举与 semanticBridgeActions 一致：schema 声明面与运行时白名单保持同一
// 集合，避免重演 app.stop 那种「schema 允许、运行时必拒」的矛盾。
func bridgeActionSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"required":["device","action"],
		"properties":{
			"device":` + deviceSchema() + `,
			"action":{"enum":["ui.click","ui.longClick","ui.scrollIntoView","ui.nodeAction"]},
			"package":` + packageSchema() + `,
			"resourceId":{"type":"string","minLength":1,"maxLength":512},
			"description":{"type":"string","minLength":1,"maxLength":1024},
			"matchText":{"type":"string","minLength":1,"maxLength":1024},
			"nodeAction":{"enum":["focus","clearFocus","select","clearSelection","expand","collapse","dismiss"]},
			"durationMs":{"type":"integer","minimum":300,"maximum":10000}
		},
		"anyOf":[
			{"required":["resourceId"]},
			{"required":["description"]},
			{"required":["matchText"]}
		],
		"additionalProperties":false
	}`)
}

// bridgeActionOutputSchema 描述语义动作的返回值。
//
// 字段全可选：App 侧按执行路径决定回哪些证据。additionalProperties 为 false
// 与其它工具保持一致，未知字段一律拒绝。
func bridgeActionOutputSchema() json.RawMessage {
	return json.RawMessage(`{
		"type":"object",
		"properties":{
			"route":{"type":"string","maxLength":128},
			"matched":{"type":"boolean"},
			"matchedPath":{"type":"array","maxItems":128,"items":{"type":"integer"}},
			"matchScore":{"type":"number","minimum":0,"maximum":1}
		},
		"additionalProperties":false
	}`)
}

// bridgeActionOutput 是语义动作的结果。
type bridgeActionOutput struct {
	Route       string  `json:"route,omitempty"`
	Matched     bool    `json:"matched,omitempty"`
	MatchedPath []int   `json:"matchedPath,omitempty"`
	MatchScore  float64 `json:"matchScore,omitempty"`
}

// addBridgeActionTool 注册语义动作工具。
func addBridgeActionTool(server *mcp.Server, automation service.Automation) {
	mcp.AddTool(server, &mcp.Tool{
		Name: ToolBridgeAction,
		Description: "Act on one interface element located by selector through the App Bridge " +
			"accessibility service, instead of computing coordinates. Locate the element by " +
			"resourceId (most reliable), description, or matchText. Requires an established " +
			"bridge session; without one the call fails rather than falling back to coordinates. " +
			"Only element-scoped actions are available here: app lifecycle, system surfaces, " +
			"clipboard and coordinate gestures are deliberately excluded.",
		InputSchema:  bridgeActionSchema(),
		OutputSchema: bridgeActionOutputSchema(),
		Annotations:  actionAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input bridgeActionInput,
	) (*mcp.CallToolResult, bridgeActionOutput, error) {
		if err := requireSemanticBridgeActionAllowed(input.Action); err != nil {
			return nil, bridgeActionOutput{}, err
		}
		action, err := input.toProtocolAction()
		if err != nil {
			return nil, bridgeActionOutput{}, err
		}
		raw, err := automation.ExecuteBridgeAction(ctx, input.Device, action)
		if err != nil {
			return nil, bridgeActionOutput{}, err
		}
		var output bridgeActionOutput
		// App 侧应答字段可能多于这里声明的四个；解析失败不应让已经成功的动作
		// 变成错误，只丢证据。
		_ = json.Unmarshal(raw, &output)
		return nil, output, nil
	})
}
