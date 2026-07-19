# 文档导航

| 文档 | 内容 |
| --- | --- |
| [架构](architecture.md) | 当前组件、双后端、数据流、信任边界和实现边界 |
| [协议 v1](protocol.md) | CLI 信封、Bridge JSON-RPC、版本协商、动作和稳定错误码 |
| [录制与回放](recording.md) | 语义录制、当前事件范围、选择器、secret 和回放报告 |
| [真机 USB 验收](device-usb-acceptance.md) | USB 模式、开发者权限、安装、Bridge 和录制回放 smoke |
| [安全与分发](security-distribution.md) | 风险模型、Google Play 边界、侧载、签名与校验要求 |
| [故障排查](troubleshooting.md) | ADB、USB/Wi-Fi、Bridge、选择器和录制错误处理 |
| [备选方案](alternatives.md) | Shizuku、DPC、设备农场、视觉、getevent、远程 Provider 和 scrcpy |
| [验证记录](validation.md) | Task 13 实际命令、测试数量、发布制品及设备条件性跳过 |
| [Round 10 评审](review-round-10.md) | 截图加固、事件归因、API 34 真机证据和剩余 API 30-33 边界 |
| [Round 11 评审](review-round-11.md) | API 33 真实会话停止证据、跨 API 验收结论与残余语义边界 |

初次使用从根目录 [README](../README.md) 的安装、快速开始、MCP 配置、Skills
路径和 Android 权限开始。实现约束以
[`spec.md`](../.trae/specs/build-ai-android-automation-mvp/spec.md) 为准，
任务完成状态见
[`tasks.md`](../.trae/specs/build-ai-android-automation-mvp/tasks.md)。

涉及设备状态或权限问题时先阅读[故障排查](troubleshooting.md)；涉及 APK
分享、企业部署或 Google Play 时必须同时阅读[安全与分发](security-distribution.md)。
