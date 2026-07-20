# 安全与恢复

## 决策顺序

按以下顺序评估每个动作：

1. serial 是否明确且当前在线？
2. 包是否已明确授权且仍在前台？
3. 动作是否可通过类型化 CLI、MCP 或 Bridge 接口执行？
4. 动作是否触及禁止目标或 secret？
5. 动作是否会发送、提交、发布、删除、移除、停止、离开当前页面或更改外部数据？
6. 是否具有最新观察和客观后置条件？

第 3 或第 4 步不满足时拒绝执行。第 5 步涉及的动作需要明确确认。只有定义第 6 步后才能执行。

## 确认记录

执行需要确认的动作前，展示：

- 设备 serial 和 transport。
- 目标包和可见目标标签。
- 准确的类型化动作及参数。
- 外部或破坏性效果。
- 验证条件和恢复限制。

仅接受针对该具体动作的明确回复。不得重复使用配对、Bridge 设置、创建任务或之前步骤中的同意。

## 重试策略

- 确认设备状态后，只读观察可以重试一次。
- 结果已确认失败的动作，只有在最新观察仍显示原始前置条件时才能重试一次。
- 结果未知的动作不得重试。先观察并核对结果，避免重复发送、提交、删除或回放。
- 不得用猜测坐标代替有歧义的语义选择器。
- 不得为了推进任务而扩大目标包范围或减少安全检查。

## 错误恢复

### `ADB_UNAUTHORIZED`

停止。要求用户在设备上批准工作站 RSA 指纹，然后重新发现设备。不得自动批准。

### `DEVICE_OFFLINE`, `DEVICE_NOT_FOUND`, `DEVICE_UNREACHABLE`

停止动作。返回设备发现流程，修复传输连接，并在恢复前取得最新观察。

### `MULTIPLE_DEVICES`

要求提供精确 serial。不得按列表顺序选择。

### `AUTH_REQUIRED`, `AUTH_INVALID`, `AUTH_EXPIRED`

停止 Bridge 工作。要求用户在 App 中生成新的单次验证码并打开新的 Bridge session。不得重复使用或暴露旧凭据。

### `CAPABILITY_UNAVAILABLE`

不得使用 shell 模拟缺失的 capability。选择有文档说明的类型化后端，或要求用户手动完成该步骤。

### `ACTION_NOT_ALLOWED`, `PERMISSION_DENIED`

不得重试。报告阻止操作的策略或权限。任何合理的 Android 权限都必须由用户在自动化之外手动授予。

### `SELECTOR_NOT_FOUND`

采集限定到指定包的最新语义快照。仅当新数据仍能识别同一唯一目标时重试，否则请求人工介入。

### `SELECTOR_AMBIGUOUS`

停止并请求人工介入。不得选择第一个候选项或点击中心坐标。

### `ACTION_FAILED`, `DEADLINE_EXCEEDED`

重试前先观察。超时可能导致结果未知。如果动作可能更改外部数据，在核对结果之前不得重试。

### `SCRIPT_NOT_FOUND`

使用 `aactl recording list --device SERIAL --json` 列出脱敏摘要，然后要求用户在 Android App 中选择并审核已有录制。该列表不暴露步骤、变量或 secret。

### 回放失败

当 `succeeded` 为 false 或 `requiresIntervention` 为 true 时停止。报告第一个失败步骤及其 route、score、attempts、code 和 message。不得跳过失败步骤，也不得启动未经审核的替代脚本。

## 紧急停止

收到停止请求后，不再提交新动作、回放或重试。在安全时关闭 Bridge，丢弃待处理动作数据和临时截图，并报告最后一次已验证状态。
