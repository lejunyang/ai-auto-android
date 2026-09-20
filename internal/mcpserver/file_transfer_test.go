package mcpserver

// 测试用途：验证文件传输 MCP 出口的独立风险门、schema 约束，
// 以及不覆盖保护在 MCP 路径上确实生效。
//
// 关键点：不覆盖保护实现在 internal/adb，MCP 经 service.Automation 抵达同一处。
// 「理论上自动受保护」不算验证，因此这里用真实的 service.New 驱动 fake ADB，
// 让远端探测返回「已存在」，确认 MCP 调用被拒且未发出 push。
// 用 fakeAutomation 无法证明这一点——它绕过了真实服务层。

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/process"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// scriptedADB 按调用顺序回放预设结果，并记录实际发出的 adb 参数。
type scriptedADB struct {
	results []process.Result
	errors  []error
	calls   [][]string
}

func (s *scriptedADB) Run(
	_ context.Context,
	_ string,
	args []string,
	_ process.Options,
) (process.Result, error) {
	s.calls = append(s.calls, append([]string(nil), args...))
	index := len(s.calls) - 1
	if index >= len(s.results) {
		return process.Result{}, nil
	}
	return s.results[index], s.errors[index]
}

// pushed 报告是否真的发出过 adb push。
func (s *scriptedADB) pushed() bool {
	for _, call := range s.calls {
		for _, arg := range call {
			if arg == "push" {
				return true
			}
		}
	}
	return false
}

// realServiceOverFakeADB 构造经过完整服务层的 Automation。
func realServiceOverFakeADB(fake *scriptedADB) service.Automation {
	return service.New(adb.NewClient("adb", fake), nil)
}

func onlineDevices() process.Result {
	return process.Result{
		Stdout: []byte("List of devices attached\nSERIAL\tdevice usb:1-1 transport_id:1\n"),
	}
}

// 这条是本文件的核心断言：MCP 路径必须继承 adb 层的不覆盖保护。
func TestMCPFilePushIsBlockedByTheServiceLayerOverwriteGuard(t *testing.T) {
	fake := &scriptedADB{
		results: []process.Result{
			onlineDevices(),
			// 远端存在性探测命中：文件已存在。
			{Stdout: []byte("AACTL_REMOTE_EXISTS\n")},
		},
		errors: []error{nil, nil},
	}
	session := connectClient(t, realServiceOverFakeADB(fake))
	local := filepath.Join(t.TempDir(), "src.txt")
	if err := os.WriteFile(local, []byte("x"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolFilePush,
		Arguments: map[string]any{
			"device":     "SERIAL",
			"localPath":  local,
			"remotePath": "/data/local/tmp/taken.txt",
		},
	})
	if err != nil {
		t.Fatalf("CallTool() error = %v", err)
	}

	if !result.IsError {
		t.Fatal("MCP push to an existing remote path succeeded; the guard did not apply")
	}
	// 最重要的一条：绝不能真的发出 push。
	if fake.pushed() {
		t.Fatalf("adb push was executed through the MCP path: %#v", fake.calls)
	}
}

// pull 侧同理：模型给出的本地路径会写在运行 MCP 服务的机器上，
// 已存在时必须拒绝，且本地内容不得被扰动。
func TestMCPFilePullIsBlockedByTheLocalOverwriteGuard(t *testing.T) {
	fake := &scriptedADB{
		results: []process.Result{onlineDevices()},
		errors:  []error{nil},
	}
	session := connectClient(t, realServiceOverFakeADB(fake))
	local := filepath.Join(t.TempDir(), "precious.txt")
	if err := os.WriteFile(local, []byte("precious"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolFilePull,
		Arguments: map[string]any{
			"device":     "SERIAL",
			"remotePath": "/data/local/tmp/a.txt",
			"localPath":  local,
		},
	})
	if err != nil {
		t.Fatalf("CallTool() error = %v", err)
	}

	if !result.IsError {
		t.Fatal("MCP pull onto an existing local path succeeded; the guard did not apply")
	}
	content, readErr := os.ReadFile(local)
	if readErr != nil || string(content) != "precious" {
		t.Fatalf("local file was disturbed: %q err=%v", string(content), readErr)
	}
}

// 空闲路径必须能正常推送，否则上面的否定断言可能只是「功能坏了」。
func TestMCPFilePushSucceedsWhenTheRemotePathIsFree(t *testing.T) {
	fake := &scriptedADB{
		results: []process.Result{
			onlineDevices(),
			// 探测未命中：非零退出码且无标记。
			{ExitCode: 1},
			{},
		},
		errors: []error{nil, nil, nil},
	}
	session := connectClient(t, realServiceOverFakeADB(fake))
	local := filepath.Join(t.TempDir(), "src.txt")
	if err := os.WriteFile(local, []byte("x"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{
		Name: ToolFilePush,
		Arguments: map[string]any{
			"device":     "SERIAL",
			"localPath":  local,
			"remotePath": "/data/local/tmp/free.txt",
		},
	})
	if err != nil {
		t.Fatalf("CallTool() error = %v", err)
	}
	if result.IsError {
		t.Fatalf("push to a free path failed: %#v", result)
	}
	if !fake.pushed() {
		t.Fatalf("no adb push was issued: %#v", fake.calls)
	}
}

// schema 层不得存在 force / overwrite 的等价字段，且必须禁止额外属性。
func TestFileTransferSchemasRejectOverwriteFields(t *testing.T) {
	for name, raw := range map[string]json.RawMessage{
		"push": filePushSchema(),
		"pull": filePullSchema(),
	} {
		t.Run(name, func(t *testing.T) {
			var schema struct {
				Properties           map[string]json.RawMessage `json:"properties"`
				AdditionalProperties *bool                      `json:"additionalProperties"`
			}
			if err := json.Unmarshal(raw, &schema); err != nil {
				t.Fatalf("Unmarshal() error = %v", err)
			}
			if schema.AdditionalProperties == nil || *schema.AdditionalProperties {
				t.Fatal("additionalProperties must be false so equivalent fields are rejected")
			}
			for field := range schema.Properties {
				lowered := strings.ToLower(field)
				for _, banned := range []string{"force", "overwrite", "replace", "delete"} {
					if strings.Contains(lowered, banned) {
						t.Fatalf("schema exposes an overwrite-style field %q", field)
					}
				}
			}
		})
	}
}

// 没有任何删除类 MCP 工具。
func TestMCPExposesNoFileDeletionTool(t *testing.T) {
	for _, name := range ToolNames {
		lowered := strings.ToLower(name)
		for _, banned := range []string{"delete", "remove", "unlink", "rmdir"} {
			if strings.Contains(lowered, banned) {
				t.Fatalf("MCP exposes a deletion tool %q", name)
			}
		}
	}
}

// 风险门只认两个方向，不接受任意动作字符串。
func TestFileTransferGateAcceptsOnlyPushAndPull(t *testing.T) {
	for _, kind := range []fileTransferKind{fileTransferPush, fileTransferPull} {
		if err := requireFileTransferAllowed(kind); err != nil {
			t.Fatalf("requireFileTransferAllowed(%q) = %v, want nil", kind, err)
		}
	}
	for _, kind := range []fileTransferKind{"file.delete", "file.move", ""} {
		if err := requireFileTransferAllowed(kind); err == nil {
			t.Fatalf("requireFileTransferAllowed(%q) = nil, want error", kind)
		}
	}
}

// 文件传输不得混进直连白名单：那份名单的准入标准是「不写入任何数据」。
func TestFileTransferIsNotInTheDirectActionAllowlist(t *testing.T) {
	for _, action := range []string{"file.push", "file.pull"} {
		if err := requireModelActionAllowed(action); err == nil {
			t.Fatalf("%q entered the pointer-event allowlist; it writes data", action)
		}
	}
}
