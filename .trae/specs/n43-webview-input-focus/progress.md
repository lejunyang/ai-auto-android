# N43 WebView 聚焦输入进度

本文件 append-only，记录 API 30 输入动作能力、实现、设备执行和清理证据。

## Round 1

- worktree `/private/tmp/ai-auto-n43-focus` 位于 `phase2-n43-webview-input-focus`，
  基线 `dafbfc0`，创建时主 worktree 和本 worktree 均干净。
- 既有设备证据显示 API 30 `emulator-5568` 在输入步骤失败：WebView 虚拟输入节点
  初始未暴露 `ACTION_SET_TEXT`，测试没有使用坐标、剪贴板、DOM/JavaScript 或 shell
  降级，设备、runtime 和 lease 已清理。
- 共享 `ui.setText` 只能表示一次动作提交；在生产 backend 内隐式执行 focus 再输入会
  造成部分提交后状态不明，因此本 change spec 不修改共享 action/router/backend。
- 候选方案固定为显式两步：先在唯一输入节点执行 `ACTION_CLICK`，重新观察后仅当
  `ACTION_SET_TEXT` 明确出现才执行输入。任一步失败都停止，不重放第一步。

## Round 2

- Web fixture JVM 测试和 `assembleDebugAndroidTest` 通过，53 个 Gradle task 成功；
  该结果只证明实验代码可编译，不是设备通过证据。
- N31 runner 从 API 30 clean snapshot 启动明确 serial `emulator-5570`，fingerprint
  为 `51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707`。
  `aactl` 确认唯一设备 state=`device`、transport=`emulator`、API 30、
  `adb.direct` 可用；runner 确认 WebView `com.google.android.webview`
  版本 `91.0.4472.114`。
- 单轮 instrumentation 在输入步骤的第一个显式 `ACTION_CLICK` 失败，错误为
  `semantic action 16 unavailable for node: Fixture text input`。因此该虚拟节点
  初始既未暴露 click，也未暴露 setText；动作提交数仍为零，后续步骤未执行。
- 测试没有尝试坐标、剪贴板、IME shell、DOM/JavaScript、直接 ADB 文本或其他动作
  降级。由于真实设备证据否定了候选，实验代码已完整撤销，不提交已知失败实现。
- N31 runner 返回 `stopped`；随后 `aactl devices list` 为 0，state root 的
  runtime、AVD/port lock 和 lease 文件扫描均为空。未运行 API 33/34 或二十轮矩阵，
  N43 设备 checklist 保持未勾选。
