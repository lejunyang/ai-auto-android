# 录制与回放

当前录制是“从 AccessibilityEvent 推断语义动作”，不是触摸屏事件的透明复制。
脚本适合普通原生表单和稳定控件，不适合游戏、自绘 Canvas、高度动态列表或需要
原始轨迹的手势。

## 使用前提

- Android 11 / API 30 及以上；
- 用户已在 App 内接受无障碍醒目披露；
- 已配置一个或多个目标包，并在系统设置中手动启用无障碍服务；
- 录制开始时再次填写目标包；其他包的事件会被忽略；
- 回放时目标应用必须位于允许列表且保持前台。

脚本只能在 Android App 中创建、编辑名称、删除和选择。电脑端可以列出脱敏摘要，
再由人工按 UUID 回放，但不能读取步骤、变量或 secret：

```bash
aactl bridge open --device SERIAL --json
aactl recording list --device SERIAL --json
aactl recording replay --device SERIAL \
  --script 123e4567-e89b-42d3-a456-426614174000 --json
aactl bridge close --device SERIAL --json
```

## 当前有效录制范围

映射器包含以下事件到动作的转换：

| Accessibility 事件 | 脚本动作 |
| --- | --- |
| `TYPE_VIEW_CLICKED` | `ui.click` |
| `TYPE_VIEW_LONG_CLICKED` | `ui.longClick` |
| `TYPE_VIEW_TEXT_CHANGED` | `ui.setText` |
| `TYPE_VIEW_SCROLLED` | `ui.scroll` |
| `TYPE_WINDOW_STATE_CHANGED`、`TYPE_WINDOWS_CHANGED` | `ui.wait`，等待包名 |

发布配置订阅上述六类事件。服务只处理录制运行时已挂载且目标包匹配的事件，并在
接收时转换到与录制开始时间一致的墙钟时间轴。

Back、Home、Recents 由 App 的 Accessibility 执行器成功提交后，会经
`RecordingRuntime` 进入同一去重状态机。用户通过物理按键或系统导航手势触发的
全局动作没有可靠 Accessibility 事件，因此不会伪装成已录制步骤。

## 脚本模型

脚本使用 schema `1.0`，核心结构为：

```text
AutomationScript
  id, schemaVersion, name, targetPackages, createdAt
  requirements, environment?, variables[], steps[]

RecordedStep
  id, recordedAtMs, action, waitAfter?, retry, failurePolicy
```

默认要求 `minApiLevel: 30`、`accessibility.snapshot` 和
`accessibility.action`。每个脚本最多 32 个目标包、10,000 个步骤；名称最多
128 字符。

脚本以 JSON 存在 App 私有目录 `filesDir/recordings`，使用临时文件加替换写入。
App 禁用 Android 备份和设备迁移数据提取。读取时支持从内部旧格式 `0.9` 或无
版本格式迁移到 `1.0`；其他未知版本明确失败。

## 事件归并

- 同一动作和参数在 350 ms 内重复出现时去重；
- 同一目标的连续文本变化在 1,000 ms 内合并，只保留最后值；
- 非 `ui.wait` 步骤默认追加“UI 在 300 ms 内保持稳定”的 `waitAfter`；
- 默认条件超时 5,000 ms；
- 默认最多执行 2 次，重试间隔 250 ms；
- 失败策略为 `stop`，可表达 `requestIntervention`。

这些等待通过每 100 ms 重新抓取语义树实现，不按录制时间固定 sleep。

## 选择器

录制器为有源节点构造以下信息：

1. 目标包名；
2. 资源 ID，权重 `0.45`，生成时标为必需；
3. 内容描述，权重 `0.25`；
4. 非敏感文本，权重 `0.20`；
5. 从 class 推导的角色，权重 `0.10`；
6. SHA-256 节点指纹，权重 `0.15`；
7. 节点 `recordedBounds` 和中心相对点 `(0.5, 0.5)`。

匹配分数是命中权重占总权重的比例。默认最低分数为 `0.70`；第一、第二候选的
差值小于 `0.15` 时视为歧义，除非指纹分数至少拉开 `0.25`。必需选择器不匹配
时，该节点直接排除。

匹配器支持 `ancestor` 和 `normalizedScreenPoint`，但当前录制器不生成这两项。
资源 ID 被记录为必需可以降低误点，但应用升级后 ID 改名会让旧脚本明确失败。

### 动作路由

普通 Bridge 动作路由的优先级为：

```text
节点 performAction
  -> 已匹配节点中心手势
  -> recordedBounds 内相对坐标
  -> 屏幕归一化坐标
```

歧义选择器不会进入坐标回退。更严格的是，当前回放引擎会在执行语义动作前先做
一次选择器预检；低于阈值或歧义会立即以 `SELECTOR_LOW_CONFIDENCE` 或
`SELECTOR_AMBIGUOUS` 失败。因此录制脚本当前不会在语义预检失败后自动使用保存
的坐标。报告中的 `route` 是实际成功执行的路由，不应预期每种回退都可达。

## Secret

文本事件满足任一条件时视为敏感：

- `AccessibilityEvent.isPassword`；
- 节点状态为 password 或 sensitive；
- 快照脱敏器从资源 ID、文本或内容描述识别出密码、验证码、PIN、支付、银行卡、
  CVV 等中英文敏感关键词。

敏感文本不会写入脚本。录制器保存：

```json
{
  "type": "ui.setText",
  "params": {
    "target": {},
    "secretRef": "input-<fingerprint-prefix>"
  }
}
```

并在 `variables` 中声明对应的 `type: "secret"`。敏感目标不会生成文本选择器。

App 回放详情页要求用户为每个引用临时输入值；值只存在当前页面内存和本次回放
调用中，不写回脚本。离开详情页后 UI 状态丢弃。

当前桌面限制：

- CLI/MCP 回放请求不接受 secret 值；
- Bridge Android 实现拒绝 `recording.replay` 的 `variables` 参数；
- Bridge 回放使用的 `SecretResolver` 固定返回 `null`。

所以含 `secretRef` 的脚本只能从 App 详情页回放；通过 CLI/MCP 回放会以单步
`SECRET_REQUIRED` 失败。不要把密码、验证码或 token 改成字面量写入脚本，也
不要通过直接 ADB 文本命令绕过。

## 回放报告

回放在首个失败步骤处停止，返回：

```text
scriptId, startedAtMs, finishedAtMs
succeeded, requiresIntervention
steps[]:
  stepId, status, attempts, route?, matchScore?, errorCode?, message?
```

需要重点处理：

- `SELECTOR_LOW_CONFIDENCE`：没有候选达到 `0.70`；
- `SELECTOR_AMBIGUOUS`：多个候选无法唯一确定；
- `SECRET_REQUIRED`：本次回放未提供 secret；
- `CONDITION_TIMEOUT`：等待或断言条件超时；
- Accessibility 错误：目标包改变、窗口不可用、动作或手势失败。

`requiresIntervention` 只在失败步骤的 `failurePolicy` 为
`requestIntervention` 时为 true；即使为 false，只要 `succeeded` 为 false
也必须停止，不能跳过失败步骤。

## 已知限制

- 显式全局动作只覆盖由 App Accessibility 执行器发起的 Back、Home、Recents；
  物理系统导航不会被录制。
- 没有原始触摸、多点触控、自由手势、按键序列、分支、循环或子流程。
- WebView、自绘控件和游戏可能没有可用 Accessibility 节点或稳定事件。
- UI 树最多复制 64 层、4,000 个节点；Bridge 输出还会按请求深度 1..100
  截断展示，底层快照的 64 层上限不会因此提高。
- `FLAG_SECURE`、系统保护页面和不可访问窗口可能为空或缺少内容；不得绕过。
- 脚本只检查 schema 版本和局部约束，没有导入任意外部脚本的 UI。
- 电脑端 `recording list` 只返回脱敏摘要，不能读取或编辑脚本步骤、变量和 secret。

## 隐私操作

- 录制前关闭通知浮层并离开支付、登录、恢复账号和私密消息页面。
- 仅选择完成任务所需的目标包。
- 回放前检查脚本目标包、步骤和所有外部副作用。
- 选择器不唯一、目标包变化或结果未知时停止，不以猜测坐标继续。
- 原始截图、UIAutomator XML 和语义树都可能包含敏感数据，不提交到 Git。

参考：

- [AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)
- [Google Play AccessibilityService API 政策](https://support.google.com/googleplay/android-developer/answer/10964491)
