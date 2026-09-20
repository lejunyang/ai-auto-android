package mcpserver

// 测试用途：验证 ui.pressKey 的键枚举在协议、直连 ADB 与 MCP 三处保持一致，
// 防止任一侧单独漂移后调用方拿到互相矛盾的可用键集合。
//
// 为什么这条测试必须存在：这三处枚举曾经互不相同（协议 21 键、直连 17 键、
// MCP 17 键），schema 允许的键在直连通道上会被拒，而错误信息只说「不在白名单」，
// 调用方无法判断到底该信哪一份。逐份比对能在 CI 阶段就暴露漂移。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
)

// protocolPressKeyEnum 读取协议 schema 里 uiPressKey 的键枚举。
//
// 直接解析 schema 文件而不是在测试里硬编码一份期望列表：硬编码等于把同一个事实
// 抄到第四个地方，漂移时这条测试自己也会过期。
func protocolPressKeyEnum(t *testing.T) []string {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller() did not return the current file")
	}
	schemaPath := filepath.Join(
		filepath.Dir(currentFile), "..", "..",
		"protocol", "schema", "v1", "action.schema.json",
	)
	content, err := os.ReadFile(schemaPath)
	if err != nil {
		t.Fatalf("read action schema: %v", err)
	}
	var schema struct {
		Defs struct {
			UIPressKey struct {
				Properties struct {
					Params struct {
						Properties struct {
							Key struct {
								Enum []string `json:"enum"`
							} `json:"key"`
						} `json:"properties"`
					} `json:"params"`
				} `json:"properties"`
			} `json:"uiPressKey"`
		} `json:"$defs"`
	}
	if err := json.Unmarshal(content, &schema); err != nil {
		t.Fatalf("parse action schema: %v", err)
	}
	keys := schema.Defs.UIPressKey.Properties.Params.Properties.Key.Enum
	if len(keys) == 0 {
		t.Fatal("action schema declares no ui.pressKey enum")
	}
	return keys
}

// mcpPressKeyEnum 从 MCP 动作 schema 中取出 ui.pressKey 分支的键枚举。
func mcpPressKeyEnum(t *testing.T) []string {
	t.Helper()
	var document struct {
		OneOf []struct {
			Properties struct {
				Action struct {
					Const string `json:"const"`
				} `json:"action"`
				Key struct {
					Enum []string `json:"enum"`
				} `json:"key"`
			} `json:"properties"`
		} `json:"oneOf"`
	}
	if err := json.Unmarshal(actionSchema(), &document); err != nil {
		t.Fatalf("parse MCP action schema: %v", err)
	}
	for _, branch := range document.OneOf {
		if branch.Properties.Action.Const == "ui.pressKey" {
			return branch.Properties.Key.Enum
		}
	}
	t.Fatal("MCP action schema has no ui.pressKey branch")
	return nil
}

func sortedCopy(values []string) []string {
	out := append([]string(nil), values...)
	sort.Strings(out)
	return out
}

func TestPressKeyEnumIsIdenticalAcrossProtocolDirectAndMCP(t *testing.T) {
	protocolKeys := sortedCopy(protocolPressKeyEnum(t))
	mcpKeys := sortedCopy(mcpPressKeyEnum(t))
	directKeys := sortedCopy(adb.SupportedKeys())

	if len(protocolKeys) != len(mcpKeys) {
		t.Fatalf("MCP exposes %d keys, protocol declares %d\nMCP: %v\nprotocol: %v",
			len(mcpKeys), len(protocolKeys), mcpKeys, protocolKeys)
	}
	if len(protocolKeys) != len(directKeys) {
		t.Fatalf("direct ADB accepts %d keys, protocol declares %d\ndirect: %v\nprotocol: %v",
			len(directKeys), len(protocolKeys), directKeys, protocolKeys)
	}
	for index, expected := range protocolKeys {
		if mcpKeys[index] != expected {
			t.Fatalf("MCP key %d = %q, protocol = %q", index, mcpKeys[index], expected)
		}
		if directKeys[index] != expected {
			t.Fatalf("direct ADB key %d = %q, protocol = %q", index, directKeys[index], expected)
		}
	}
}

// TestPressKeyEnumStaysWithinTheReviewedAllowlist 防止本次对齐被误读成
// 「枚举可以随意增长」。任意 keycode 与组合键属于需要单独安全决策的能力，
// 不能靠补白名单悄悄引入，因此这里钉死当前评审过的集合。
func TestPressKeyEnumStaysWithinTheReviewedAllowlist(t *testing.T) {
	reviewed := map[string]bool{
		"BACK": true, "HOME": true, "RECENTS": true, "ENTER": true,
		"TAB": true, "ESCAPE": true, "SPACE": true, "DELETE": true,
		"FORWARD_DEL": true, "DPAD_UP": true, "DPAD_DOWN": true,
		"DPAD_LEFT": true, "DPAD_RIGHT": true, "DPAD_CENTER": true,
		"MOVE_HOME": true, "MOVE_END": true, "PAGE_UP": true,
		"PAGE_DOWN": true, "VOLUME_UP": true, "VOLUME_DOWN": true,
		"VOLUME_MUTE": true,
	}
	for _, key := range protocolPressKeyEnum(t) {
		if !reviewed[key] {
			t.Fatalf("protocol declares unreviewed key %q; "+
				"adding power, call or security keys needs a separate safety decision", key)
		}
	}
}
