# 设备发现故障排查

## `ADB_NOT_FOUND`

安装官方 Android SDK Platform-Tools，或将 `AACTL_ADB_PATH` 设置为 ADB 可执行文件。重新运行诊断。不得下载未经验证的 ADB 二进制文件。

## 诊断状态异常

按名称报告未通过的检查项：

- `adb.version`：可执行文件输出缺失或不受支持。
- `adb.server`：无法查询 server 状态。
- `adb.port.5037`：loopback 上没有 ADB server 监听。
- `adb.mdns`：无线调试发现不可用。

不得自动运行 `adb kill-server`。要求用户在技能之外修复 Platform-Tools 安装、驱动、数据线、网络或 ADB server，然后重新运行诊断。

## `ADB_UNAUTHORIZED`

1. 保持设备解锁。
2. 要求用户在设备上比对并接受工作站 RSA 指纹。
3. 如果未显示提示，重新连接 USB 数据线。
4. 再次列出设备。

不得绕过提示或修改设备信任存储。

## `DEVICE_OFFLINE`

1. 停止所有自动化尝试。
2. 要求用户重新连接数据线或重新开关无线调试。
3. 对于 Wi-Fi，使用当前连接端点重新连接。
4. 重新运行诊断并列出设备。

state 为 `offline` 时不得重试动作。

## `DEVICE_NOT_FOUND`

确认数据线、USB 模式、无线调试网络、模拟器状态和精确 serial。重新启动无线调试后，Wi-Fi 端点可能发生变化。

## `MULTIPLE_DEVICES`

展示每个 serial 及其型号、transport 和 state。要求用户或调用工作流选择一个精确 serial。不得选择列表中的第一项。

## Wi-Fi 配对失败

- 确认设备为 Android 11+，且无线调试保持开启。
- 区分配对端点和连接端点。
- 在验证码过期前使用新的六位验证码。
- 保持工作站与设备位于允许本地对等流量的网络中。
- 不得保存、回显或重复使用配对码。

## mDNS 观察或可信重连失败

- `CAPABILITY_UNAVAILABLE`：确认 Platform-Tools 支持 `adb mdns services`，且系统
  mDNS、局域网对等流量和防火墙没有阻止发现；不得自动重启共享 ADB server。
- `MULTIPLE_DEVICES`：同一 identity 同时出现在多个端点或网卡。停止恢复，关闭 VPN、
  热点或多余网络后重新观察，不得按响应顺序选择。
- `DEVICE_UNREACHABLE`：连接后的 serial、identity 或型号与可信档案不一致。立即停止，
  重新通过设备端无线调试页和 `devices list` 核对身份。
- 公网地址、未知服务类型和恶意 mDNS identity 会在连接前拒绝；不得改用未认证
  `adb tcpip 5555` 规避检查。
- 可信档案不包含 Android ADB 密钥或配对码。`devices forget` 只删除本地非秘密档案，
  不撤销设备系统中的 ADB 信任。

## 重试规则

仅在状态或环境发生变化后重试。将 serial 交给观察或自动化流程前，重新运行设备发现。
