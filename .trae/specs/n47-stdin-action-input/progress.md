# N47 stdin Input 进度

本文件 append-only，记录 RED/GREEN、输入生命周期、提交状态和未完成设备边界。

## Round 1

- worktree `/private/tmp/ai-auto-n47-stdin` 位于 `phase2-n47-stdin-input`，基线
  `8cb2c72`，开工时干净。
- 审计确认现有 CLI 只支持 `bridge action --action JSON`，Node adapter 若直接使用
  会把 input 明文暴露到进程 argv，因此此前正确保持 `INPUT_ADAPTER_UNAVAILABLE`。
- 本任务新增固定 `--stdin` 模式，不增加通用命令 stdin、文件输入、shell、raw ADB
  或剪贴板；action 仍经过既有 Bridge、protocol parser、包 allowlist 和风险门。
- 先增加 Go/Node RED 测试，生产实现尚未修改。

## Round 2

- Go RED 成功用例最初因 `--stdin` 未注册返回退出码 2；Node 四个 input 测试均因
  `INPUT_ADAPTER_UNAVAILABLE` 失败。两侧失败都来自缺失生产能力，无语法或环境噪声。
- CLI 新增 `bridge action --stdin`，与 `--action` 恰好选择一个；现有 inline 模式
  保持兼容。stdin reader 使用 `LimitReader(max+1)`，拒绝空输入、超限、非法 UTF-8、
  NUL、非 object、递归重复 key 和尾随值。
- stdin action 的原始完整 buffer 在返回前清零，传给 Bridge 的独立 action byte
  slice 在 RPC 返回后清零。错误只使用稳定 `INVALID_ARGUMENT`，不回显输入或解析
  位置。
- Go 定向覆盖成功、互斥/重复选项、inline 兼容、预算、UTF-8、重复 key、尾随值、
  非 object 和 reader failure；所有失败路径 Bridge RPC 调用数为零。

## Round 3

- aactl adapter 恢复 input semantic route：唯一、可见、启用、非敏感且具备 setText
  的节点才能生成一次性 token。executor argv 只含固定
  `bridge action --device SERIAL --stdin --json`，不含 value 或 action JSON。
- adapter 构造固定 protocol v1 `ui.setText` Buffer 并写入 child stdin，监听
  `error`/`finish`，同时等待 stream 与进程 callback；缺失 stdin、写入失败、进程
  异常或成功 data 畸形均为提交状态未知。合法非零拒绝返回 false，严格成功返回 true。
- 步骤 deadline 在进程调用前过期时返回 false 且调用数为零；route token 在任何
  执行尝试后消费，不允许以同一 input 重试。
- adapter 在 executor 完成后清零自身持有的 stdin Buffer。OS pipe、子进程和内核
  传输副本不在 JavaScript 可控清零范围内，因此文档不夸大为“系统无副本”。
- Runner input 端到端 fake 验证 values -> semantic route -> stdin action ->
  post snapshot -> condition -> 四步 cleanup，action commits 为 1。

## Round 4

- 最终 N47 全包 59/59、N34 artifacts 49/49 通过；Go
  `go test -race ./internal/cli ./internal/bridge ./internal/process`、CLI 全量测试、
  vet、Node 语法、`make comments` 和 `git diff --check` 通过。
- 范围只包含本 change spec、CLI Bridge stdin parser/tests 和 adapter input/tests，
  未修改 Android、协议 Schema、Runner core、N34/N45，也未生成 APK、截图或日志。
- 尚未在真实 emulator/Bridge 上执行 input；该项保持未验收，不能以 fake child
  process 和 fake Bridge 证据冒充设备成功。

## Round 5

- 实现提交 `a087394`（`feat(cli): accept bridge actions from stdin`）包含 stdin
  change spec、strict Go reader/CLI、adapter child stdin 和全部测试。
- 提交 author/committer 均为 `lejunyang <lejunyang@qq.com>`，消息含且仅含一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`；实现提交后 worktree 干净。
