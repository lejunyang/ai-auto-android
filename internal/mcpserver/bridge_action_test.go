package mcpserver

// 测试用途：验证语义动作工具的白名单边界、服务层路由与桥会话缺失时的失败语义。
//
// 这个工具是本轮唯一新增的模型可发起能力，安全断言优先于功能断言：
// 白名单必须独立于 requireModelActionAllowed，且不能成为绕过它的旁路。

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// bridgeCapturingAutomation 记录落到 ExecuteBridgeAction 的原始动作对象。
type bridgeCapturingAutomation struct {
	fakeAutomation
	bridgeActions []string
	bridgeErr     error
}

func (f *bridgeCapturingAutomation) ExecuteBridgeAction(
	_ context.Context,
	_ string,
	action json.RawMessage,
) (json.RawMessage, error) {
	f.bridgeActions = append(f.bridgeActions, string(action))
	if f.bridgeErr != nil {
		return nil, f.bridgeErr
	}
	return json.RawMessage(`{"matched":true}`), nil
}

func TestBridgeActionToolIsRegistered(t *testing.T) {
	found := false
	for _, name := range ToolNames {
		if name == ToolBridgeAction {
			found = true
		}
	}
	if !found {
		t.Fatalf("ToolNames does not contain %q: %v", ToolBridgeAction, ToolNames)
	}
}

func TestBridgeActionRoutesAllowlistedSelectorActionsToService(t *testing.T) {
	automation := &bridgeCapturingAutomation{}
	session := connectClient(t, automation)

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolBridgeAction,
		Arguments: map[string]any{
			"device":     "SERIAL",
			"action":     "ui.click",
			"package":    "com.example.app",
			"resourceId": "com.example.app:id/submit",
		},
	})
	if err != nil {
		t.Fatalf("CallTool() error = %v", err)
	}
	if result.IsError {
		t.Fatalf("tool error = %q", toolErrorText(t, result))
	}
	if len(automation.bridgeActions) != 1 {
		t.Fatalf("bridge action calls = %#v", automation.bridgeActions)
	}

	// 断言真正落到桥上的协议动作对象，而不只看调用发生过：
	// 选择器拼错会让文本写进另一个元素，只有比对 payload 才能发现。
	var sent struct {
		Type   string `json:"type"`
		Params struct {
			Target struct {
				PackageName        string `json:"packageName"`
				SelectorCandidates []struct {
					Strategy string  `json:"strategy"`
					Value    string  `json:"value"`
					Weight   float64 `json:"weight"`
					Required bool    `json:"required"`
				} `json:"selectorCandidates"`
			} `json:"target"`
		} `json:"params"`
	}
	if err := json.Unmarshal([]byte(automation.bridgeActions[0]), &sent); err != nil {
		t.Fatalf("parse bridge action: %v\npayload = %s", err, automation.bridgeActions[0])
	}
	if sent.Type != "ui.click" {
		t.Fatalf("bridge action type = %q", sent.Type)
	}
	if sent.Params.Target.PackageName != "com.example.app" {
		t.Fatalf("target package = %q", sent.Params.Target.PackageName)
	}
	if len(sent.Params.Target.SelectorCandidates) != 1 {
		t.Fatalf("selector candidates = %#v", sent.Params.Target.SelectorCandidates)
	}
	candidate := sent.Params.Target.SelectorCandidates[0]
	if candidate.Strategy != "resourceId" ||
		candidate.Value != "com.example.app:id/submit" {
		t.Fatalf("selector candidate = %#v", candidate)
	}
}

func TestBridgeActionRejectsActionsOutsideItsAllowlist(t *testing.T) {
	// 这些动作要么触及应用生命周期与系统面，要么本就不属于语义选择器路径。
	// 它们在协议里是合法动作对象，正因如此才必须在工具层显式拒绝——
	// 否则模型可以用这个工具绕过 android_action_execute 的风险门。
	for _, action := range []string{
		"app.stop",
		"app.launch",
		"ui.tap",
		"ui.swipe",
		"ui.lockScreen",
		"ui.powerDialog",
		"device.rotate",
		"file.push",
		"clipboard.get",
		"task.finish",
	} {
		t.Run(action, func(t *testing.T) {
			automation := &bridgeCapturingAutomation{}
			session := connectClient(t, automation)

			result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
				Name: ToolBridgeAction,
				Arguments: map[string]any{
					"device":     "SERIAL",
					"action":     action,
					"resourceId": "com.example.app:id/submit",
				},
			})
			if err != nil {
				t.Fatalf("CallTool() protocol error = %v", err)
			}
			if !result.IsError {
				t.Fatalf("action %q was accepted", action)
			}
			if len(automation.bridgeActions) != 0 {
				t.Fatalf("rejected action reached the bridge: %#v", automation.bridgeActions)
			}
		})
	}
}

func TestBridgeActionRequiresASelector(t *testing.T) {
	automation := &bridgeCapturingAutomation{}
	session := connectClient(t, automation)

	// 三个定位字段全空会匹配到任意节点，动作落点不可预期。
	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolBridgeAction,
		Arguments: map[string]any{
			"device": "SERIAL",
			"action": "ui.click",
		},
	})
	if err != nil {
		t.Fatalf("CallTool() protocol error = %v", err)
	}
	if !result.IsError {
		t.Fatal("selector-less action was accepted")
	}
	if len(automation.bridgeActions) != 0 {
		t.Fatalf("selector-less action reached the bridge: %#v", automation.bridgeActions)
	}
}

func TestBridgeActionSurfacesMissingBridgeSessionWithoutFallback(t *testing.T) {
	// 无桥会话时必须明确失败。静默降级为坐标动作会让模型以为自己点中了元素，
	// 实际却在拍一个可能已经移位的坐标。
	automation := &bridgeCapturingAutomation{
		bridgeErr: apperr.New(
			apperr.CodeCapabilityMissing,
			"semantic action execution is unavailable.",
			false,
			nil,
		),
	}
	session := connectClient(t, automation)

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolBridgeAction,
		Arguments: map[string]any{
			"device":     "SERIAL",
			"action":     "ui.click",
			"resourceId": "com.example.app:id/submit",
		},
	})
	if err != nil {
		t.Fatalf("CallTool() protocol error = %v", err)
	}
	if !result.IsError {
		t.Fatal("missing bridge session was reported as success")
	}
	text := toolErrorText(t, result)
	if !strings.Contains(text, "unavailable") {
		t.Fatalf("error text does not explain the missing bridge: %q", text)
	}
}

// TestBridgeActionDoesNotWidenTheDirectActionGate 确认新增工具没有顺带放宽
// 既有风险门。两者是独立的两层，各自守住自己的动作面。
func TestBridgeActionDoesNotWidenTheDirectActionGate(t *testing.T) {
	for _, action := range []string{service.ActionStop, "shell", "app.install"} {
		if err := requireModelActionAllowed(action); err == nil {
			t.Fatalf("direct action gate now allows %q", action)
		}
	}
	// 反向确认：语义白名单不接受直连坐标动作，直连白名单也不接受语义动作。
	if err := requireSemanticBridgeActionAllowed(service.ActionTap); err == nil {
		t.Fatal("semantic allowlist accepts a direct coordinate action")
	}
	if err := requireModelActionAllowed("ui.click"); err == nil {
		t.Fatal("direct action gate accepts a semantic selector action")
	}
}
