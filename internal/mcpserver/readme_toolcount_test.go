package mcpserver

// 测试用途：验证 README 声明的 MCP 工具数量与实际注册的 ToolNames 一致，
// 防止新增或移除工具后文档继续停留在旧数字。
//
// 为什么需要这条测试：README 曾长期写「六个类型化工具」，而实际注册数已增长到
// 11 个。文档与实现的偏差没有任何自动检查会发现它，只能靠人读代码时偶然察觉。

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestReadmeToolCountMatchesRegisteredTools(t *testing.T) {
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller() did not return the current file")
	}
	readmePath := filepath.Join(filepath.Dir(currentFile), "..", "..", "README.md")
	content, err := os.ReadFile(readmePath)
	if err != nil {
		t.Fatalf("read README: %v", err)
	}

	// 只认阿拉伯数字写法。中文数字（「六个」）无法随实现增长而稳定校验，
	// 这正是旧文档长期失准的原因，因此这里要求文档使用可机器核对的写法。
	expected := fmt.Sprintf("通过 stdio 暴露 %d 个类型化工具", len(ToolNames))
	if !strings.Contains(string(content), expected) {
		t.Fatalf("README does not state the actual tool count.\nwant substring: %q\nregistered tools (%d): %v",
			expected, len(ToolNames), ToolNames)
	}
}
