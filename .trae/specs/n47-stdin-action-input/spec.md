# N47 stdin Action 与 Input Adapter 规格

## 目标

为 `aactl bridge action` 增加固定、有界、严格的 stdin JSON 模式，使 N47 production
adapter 可以执行非敏感 input，而不把文本或完整 action JSON 暴露到进程 argv。

## CLI 契约

- 现有 `--action JSON` 保持兼容。
- 新增 `aactl bridge action --device SERIAL --stdin --json`。
- `--action` 与 `--stdin` 必须且只能选择一个；未知选项、重复选项和位置参数拒绝。
- stdin 最大 `bridge.MaxMessageBytes - 1` bytes，只接受 UTF-8 单个 JSON object。
- 递归拒绝重复 JSON key、尾随 JSON/文本、空输入、数组、标量和 NUL。
- action bytes 只在同步 RPC 调用栈内存在；读取/验证/RPC 返回后清零可控 byte slice。
- 错误只公开稳定 code，不回显输入内容、JSON path、文本或底层解析消息。

## Adapter 契约

- input route 与其他 semantic route 一样要求唯一、可见、启用、非敏感且具备
  `setText` action 的节点。
- executor 构造固定 protocol v1 `ui.setText` JSON，但 argv 只包含
  `bridge action --device SERIAL --stdin --json`。
- 使用 `execFile` 返回的 child stdin 写入 action JSON 和单个换行；检查 backpressure、
  stream error、关闭和提前退出，任一不确定性记为提交状态未知。
- input value 只在 executor 调用栈和 child stdin buffer 中存在，完成后清零可控
  Buffer；不进入 token、observation、report、异常或测试日志。
- CLI 明确拒绝返回 `committed:false`；严格成功结果返回 `true`；进程/stream/结果
  异常抛稳定错误，由 Runner 记录 `ACTION_COMMIT_UNKNOWN`。

## 安全边界

- 不增加任意 stdin 命令、shell、raw ADB、文件输入、环境变量 action 或剪贴板路径。
- stdin 模式只属于既有 Bridge `action.execute`，仍要求已建立短期 session、显式
  serial、Android action parser、目标包 allowlist 和 Accessibility 安全门。
- 不输入密码、token、验证码、账号、支付或其他 secret；N47 `sideEffectPolicy=none`
  与用户/模拟器前置授权仍适用。
- 本任务不启用 visual/hybrid，不启动设备、不打开 Bridge、不修改 Android。

## 允许修改

- `.trae/specs/n47-stdin-action-input/`
- `internal/cli/bridge.go`、必要的同包 strict JSON helper 和对应 Go 测试
- `test-lab/runner/adapters/aactl.mjs` 与对应 adapter 测试/README

## 禁止修改

- Android、协议 Schema、Runner core、N34/N45、设备/模拟器实现
- 任意 shell/raw ADB、第三方 APK、账号、凭据或运行产物
- 公共路线图；由 main 集成后统一更新

## 验收

- RED 测试先证明 CLI `--stdin` 和 adapter input 尚未实现。
- Go 测试覆盖成功、互斥参数、预算、UTF-8、重复 key、尾随值、非 object、读取错误、
  RPC 前零调用和输入不回显。
- Node 测试覆盖 argv 零明文、stdin 固定 bytes、backpressure/error/提前退出、明确
  拒绝/成功/unknown、Buffer 清理和 token 单次消费。
- Go race、N47 全包、N34、comments 和 diff-check 通过。
- 真实 emulator input 设备验收保持未完成。
