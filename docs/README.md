# 文档导航

- [C 端 AI 助理交互原型](ui-prototype/index.html)：用浏览器直接打开，体验任务、技能编排、录制与悬浮概念；示例状态不代表 Android 已实现。

| 文档 | 内容 |
| --- | --- |
| [架构](architecture.md) | 当前组件、双后端、数据流、信任边界和实现边界 |
| [协议 v1](protocol.md) | CLI 信封、Bridge JSON-RPC、版本协商、动作和稳定错误码 |
| [虚拟显示与主屏息屏](virtual-display.md) | helper 构建、CLI、窗口列表、焦点歧义、休眠重建与模拟器验收 |
| [录制与回放](recording.md) | 语义录制、当前事件范围、选择器、secret 和回放报告 |
| [真机 USB 验收](device-usb-acceptance.md) | USB 模式、开发者权限、安装、Bridge 和录制回放 smoke |
| [安全与分发](security-distribution.md) | 风险模型、Google Play 边界、侧载、签名与校验要求 |
| [故障排查](troubleshooting.md) | ADB、USB/Wi-Fi、Bridge、选择器和录制错误处理 |
| [备选方案](alternatives.md) | Shizuku、DPC、设备农场、视觉、getevent、远程 Provider 和 scrcpy |
| [下一阶段任务](next-phase-tasks.md) | N31-N60 的任务级状态、依赖、并行波次和验收证据；早期记录按时序保留 |
| [动作面扩展追踪](action-surface-tasks.md) | A1-A17 当前能力/出口状态与分 API 设备证据；原始实测段落为历史记录 |
| [C 端 Agent UI 追踪](consumer-agent-ui-tasks.md) | N56 分项状态及带日期的 UI 迭代记录；历史截图不是最新 APK 证明 |
| [摄像头当前路线](camera-virtual-stacks.md) | Windows 两套 API 的区别、已否定路线及 Linux/MF 待验判据；宿主安装表为历史环境 |
| [虚拟摄像头选型](virtual-camera-selection.md) | 当前候选、已否定的 Windows OBS/Unity 路线与验证顺序；第三方/macOS 研究未重新核实 |

下列为历史评审、评估、选型或实测记录。其测试数量、环境和未实现描述以当时基线为准；
文首“现状更正”说明已被后续实现或实验改变的结论，不能把旧缺口直接抄入路线图。

| 历史文档 | 内容与后续指向 |
| --- | --- |
| [验证记录](validation.md) | 2026-07-19 Task 13/14/20-23 与 Round 11 命令、制品和设备证据 |
| [Round 10 评审](review-round-10.md) | 截图加固、事件归因与 API34 证据；待验项后来由 Round 11 收口 |
| [Round 11 评审](review-round-11.md) | API33 真实会话停止证据与残余语义边界 |
| [控制能力评估](control-assessment.md) | 2026-10-17 基线的控制/LAN/会话隔离评估与提案，后续虚拟屏见操作指南 |
| [模拟器 UI 自动化盘点](emulator-ui-automation.md) | 早期模拟器连接与缺口盘点；多项观察/动作/预置能力后来已实现 |
| [观察能力扩展](observe-capability-extension.md) | foreground/apps/recording、扫码与证书调查；后续输入/判据见文首 |
| [界面判据与桥侧对齐](interface-predicates-and-bridge.md) | wait/assert、watch flaky 与 foreground 实测；默认匹配后来改为 exact |
| [手势、lint 与摄像头](gestures-lint-and-camera.md) | 长按/滚动、预置与预录视频序列实验；不能用于运行时新 payload 热换码 |
| [扫码通道与文本输入盘点](camera-channels-and-text-input.md) | videofile 覆盖实验和早期中文输入建议；后续已补 Bridge/MCP 输入 |
| [中文输入与虚拟摄像头](text-input-and-virtual-camera.md) | CLI Unicode 与普通 App 相机权限门实测；后续已补 MCP 输入 |
| [MCP 文本输入与 webcam](mcp-text-input-and-webcam.md) | MCP 中文回读与实体 webcam；后续 DirectShow 结论已否定旧推荐 |
| [Unity Capture verdict](unity-capture-verdict.md) | Unity 的失败观测；像素格式根因判断已被 OBS 对照实验推翻 |
| [宿主虚拟摄像头 verdict](host-virtual-camera-verdict.md) | OBS/Unity DirectShow 枚举失败与 PnP 对照；不能外推 Linux/MF |

初次使用从根目录 [README](../README.md) 的安装、快速开始、MCP 配置、Skills
路径和 Android 权限开始。实现约束以
[`spec.md`](../.trae/specs/build-ai-android-automation-mvp/spec.md) 为准，
任务完成状态见
[`tasks.md`](../.trae/specs/build-ai-android-automation-mvp/tasks.md)。MVP 后变更以各自独立
规格及上方状态入口为准；既有规格保留历史，不把后续能力回写 MVP。

涉及设备状态或权限问题时先阅读[故障排查](troubleshooting.md)；涉及 APK
分享、企业部署或 Google Play 时必须同时阅读[安全与分发](security-distribution.md)。
