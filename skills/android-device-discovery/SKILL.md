---
name: android-device-discovery
description: 安全诊断 ADB 并发现通过 USB 或 Wi-Fi 连接的 Android 设备。连接、配对、排查或选择 Android 设备时使用。
---

# Android 设备发现

仅使用 `aactl` 作为设备传输接口。调用观察或自动化技能之前，必须先发现并选择设备。

## 必需流程

1. 运行版本与诊断检查。报告未通过的诊断项，不得自动终止或重启共享 ADB server。
2. 列出设备，并检查每台设备返回的 `serial`、`state`、`transport` 和 capability。
3. 使用 USB 时，要求用户连接数据线、启用 USB 调试，并在设备上接受工作站的 RSA 指纹。不得代替用户接受。
4. 使用 Android 11+ Wi-Fi 调试时，要求用户启用无线调试并提供配对端点。从交互式提示中读取六位配对码，再连接到另行显示的连接端点。
5. 配对或连接后重新列出设备。
6. 如果存在多台设备，展示所有 serial 并要求明确选择。后续每个 `--device` 参数或 MCP `device` 字段都必须使用该精确 serial。
7. 仅当所选设备的 state 为 `device`，且所需 capability 报告 `available: true` 时继续。

配对或连接前阅读[命令参考](references/commands.md)。诊断或设备状态异常时阅读[故障排查参考](references/troubleshooting.md)。

## 安全规则

- 不得运行原始 `adb`、任意 shell、`adb kill-server`、`root`、`remount`、`disable-verity`、恢复出厂设置或撤销信任命令。
- 未经用户直接参与，不得绕过 `unauthorized`、批准 RSA 提示、启用无线调试或输入配对码。
- 不得在参数、日志、聊天或保存的产物中暴露配对码。
- 列出多台设备时，不得依据顺序、型号、传输方式或之前的会话推断目标设备。

## MCP 边界

MCP 可用时使用 `android_devices_list` 和 `android_device_get`。诊断、Wi-Fi 配对和 Wi-Fi 连接仅由 CLI 提供。MCP 有意不提供设备配对或信任变更能力。

## 完成条件

返回所选 serial、transport、规范化 state、相关 capabilities，以及仍需用户完成的操作。当设备为 `unauthorized`、`offline`、不存在或选择不明确时，不得声称设备已就绪。
