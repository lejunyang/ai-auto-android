package mcpserver

// 功能用途：本文件实现文件传输的 MCP 出口，并为其设立独立于 risk.go 的风险门。
//
// 为什么不复用 requireModelActionAllowed：那道门的准入标准写在 risk.go 的注释里，
// 是「只产生指针事件，不改变应用生命周期也不写入任何数据」。现有 7 项全部符合。
// 文件传输与该标准正面冲突——push 在设备上落盘，pull 在**运行 MCP 服务的机器**上
// 落盘，都是可持久化副作用。把它塞进那份名单等于无声地废掉该标准：日后审计再也
// 无法凭注释判断某项为何在列。
//
// bridge_action.go 的注释早已写明「clipboard.* / file.*：涉及隐私数据与文件系统，
// 需要独立的安全决策」。本文件兑现那句话：文件传输走自己的门，与直连白名单、
// 语义桥白名单三者并列，各守自己的动作面，任一层都不能成为绕过另一层的旁路。
//
// 本门的准入前提（缺一不可，且都不由本文件实现，而在 internal/adb 强制）：
//   - 不覆盖：目标已存在即失败
//   - 不删除：没有任何删除能力
//   - 路径校验：绝对路径、字符集白名单、父目录穿越检查
//
// 保护放在服务层而非此处，正是为了让 MCP 这条新出口自动继承，无需重复实现。

import (
	"context"

	"github.com/lejunyang/ai-auto-android/internal/adb"
	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/service"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ToolFilePush 把本机文件推送到设备，只新增不覆盖。
const ToolFilePush = "android_file_push"

// ToolFilePull 把设备文件拉取到本机，只新增不覆盖。
const ToolFilePull = "android_file_pull"

// fileTransferKind 区分两种传输方向，用于风险门与错误信息。
type fileTransferKind string

const (
	fileTransferPush fileTransferKind = "file.push"
	fileTransferPull fileTransferKind = "file.pull"
)

// requireFileTransferAllowed 是文件传输的独立风险门。
//
// 与 requireModelActionAllowed / requireSemanticBridgeActionAllowed 并列而非合并：
// 三者的准入标准互不相同，合并会让任一侧的放宽意外波及其它两侧。
//
// 这道门刻意只认两个方向，不接受任意动作字符串：文件传输不存在「将来可能加进来的
// 第三种操作」——删除与覆盖是被明确排除的，留出扩展位只会诱导日后往里加。
func requireFileTransferAllowed(kind fileTransferKind) error {
	switch kind {
	case fileTransferPush, fileTransferPull:
		return nil
	default:
		return modelToolError{
			Code: apperr.CodeActionNotAllowed,
			Message: "Only create-only file push and pull are allowed. " +
				"Overwrite and delete are not available through any tool.",
			Retryable: false,
		}
	}
}

// filePushInput 是推送入参。
//
// 刻意没有 force / overwrite 之类的字段：覆盖不是一个可以用参数打开的选项。
// additionalProperties:false 让等价字段在 schema 层就被拒。
type filePushInput struct {
	Device     string `json:"device"`
	LocalPath  string `json:"localPath"`
	RemotePath string `json:"remotePath"`
}

// filePullInput 是拉取入参，字段含义与 filePushInput 相同。
type filePullInput struct {
	Device     string `json:"device"`
	RemotePath string `json:"remotePath"`
	LocalPath  string `json:"localPath"`
}

// fileTransferOutput 回传实际生效的两端路径。
//
// 不回文件内容也不回字节数：内容可能很大且含敏感数据，而是否成功由错误与否表达。
type fileTransferOutput struct {
	Device     string `json:"device"`
	Action     string `json:"action"`
	LocalPath  string `json:"localPath"`
	RemotePath string `json:"remotePath"`
}

// registerFileTransferTools 注册推送与拉取两个工具。
func registerFileTransferTools(server *mcp.Server, automation service.Automation) {
	mcp.AddTool(server, &mcp.Tool{
		Name: ToolFilePush,
		Description: "Copy one local file to an explicitly selected Android device. " +
			"Create-only: the transfer fails if the device path already exists. " +
			"This tool never overwrites, appends to, or deletes any file, and there is no " +
			"option to force an overwrite.",
		InputSchema:  filePushSchema(),
		OutputSchema: fileTransferOutputSchema(),
		Annotations:  actionAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input filePushInput,
	) (*mcp.CallToolResult, fileTransferOutput, error) {
		if err := requireFileTransferAllowed(fileTransferPush); err != nil {
			return nil, fileTransferOutput{}, err
		}
		transfer, err := automation.PushFile(ctx, service.FileTransferRequest{
			Device:     input.Device,
			LocalPath:  input.LocalPath,
			RemotePath: input.RemotePath,
		})
		if err != nil {
			return nil, fileTransferOutput{}, err
		}
		return nil, toFileTransferOutput(transfer), nil
	})

	mcp.AddTool(server, &mcp.Tool{
		Name: ToolFilePull,
		Description: "Copy one file from an explicitly selected Android device to the machine " +
			"running this MCP server. The file is written to that server's local disk, not to " +
			"the caller's environment. Create-only: the transfer fails if the local path " +
			"already exists. This tool never overwrites or deletes any file.",
		InputSchema:  filePullSchema(),
		OutputSchema: fileTransferOutputSchema(),
		Annotations:  actionAnnotations(),
	}, func(
		ctx context.Context,
		_ *mcp.CallToolRequest,
		input filePullInput,
	) (*mcp.CallToolResult, fileTransferOutput, error) {
		if err := requireFileTransferAllowed(fileTransferPull); err != nil {
			return nil, fileTransferOutput{}, err
		}
		transfer, err := automation.PullFile(ctx, service.FileTransferRequest{
			Device:     input.Device,
			RemotePath: input.RemotePath,
			LocalPath:  input.LocalPath,
		})
		if err != nil {
			return nil, fileTransferOutput{}, err
		}
		return nil, toFileTransferOutput(transfer), nil
	})
}

// toFileTransferOutput 把服务层结果收敛为工具输出。
func toFileTransferOutput(transfer adb.FileTransfer) fileTransferOutput {
	return fileTransferOutput{
		Device:     transfer.Device,
		Action:     transfer.Action,
		LocalPath:  transfer.LocalPath,
		RemotePath: transfer.RemotePath,
	}
}
