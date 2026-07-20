---
name: android-device-observation
description: 收集 Android 设备信息、截图、UI 层级和 Bridge 快照。检查已选设备或采集自动化产物时使用。
---

# Android 设备观察

使用足以完成任务且敏感程度最低的数据源，观察一台已明确选择的 Android 设备。

## 必需流程

1. 要求提供由设备发现流程选出的 serial。如果该选择可能已过期，重新列出设备。
2. 获取设备信息，并验证所需观察 capability。
3. 选择一种数据源：
   - 设备元数据：用于硬件、Android 版本、状态和 capabilities。
   - ADB UI hierarchy：在不使用 App Bridge 时获取基础可见 UI 结构。
   - App Bridge 语义快照：用于无障碍选择器和节点状态。
   - 仅在必须查看视觉像素时使用截图。
4. 采集前要求用户离开包含密码、一次性验证码、支付、私人消息和其他敏感内容的页面。
5. 只采集一次，并记录格式、大小或深度、可用时的 SHA-256，以及精确的所选 serial。
6. 只提取完成任务所需的最少事实。简短脱敏摘要足够时，不得粘贴或持久化完整层级或快照。
7. 将产物交给其他工作流之前，应用保留与清理规则。

有关准确的 CLI 和 MCP 形式，请阅读[命令参考](references/commands.md)。采集截图或 UI 内容前，请阅读[隐私与产物参考](references/privacy-and-artifacts.md)。

## 数据源规则

- ADB 截图和 UIAutomator hierarchy 属于原始设备观察，可能包含敏感内容。
- App Bridge 语义快照会脱敏标记为密码或敏感内容的节点，但周边标签、包名、边界和 UI 状态仍可能属于隐私信息。
- `FLAG_SECURE` 或受保护页面可能无法采集或显示为空白。不得尝试绕过平台保护。
- 不得推断隐藏文本、索取 secret，或将原始观察放入日志。
- 不得将任意 shell、原始 ADB 命令、OCR 或未列出的采集工具作为回退方案。

## Bridge 边界

语义快照要求用户在 Android App 中启用桌面桥，并建立短期会话。打开会话需要新的单次验证码和明确的设备选择。不得暴露验证码或本地 session token。

## 完成条件

返回脱敏摘要、产物元数据和保留状态。将数据源明确标识为 `device-info`、`screenshot`、`uiautomator-hierarchy` 或 `bridge-semantic-snapshot`，不得混淆各数据源的隐私保证。
