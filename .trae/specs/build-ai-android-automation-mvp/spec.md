# AI 安卓自动化工具 MVP 规格

## Why

当前需要从空仓库建立一套可运行、可扩展且跨 macOS/Windows 的安卓自动化基础设施，同时覆盖两种使用方式：Android App 独立使用 AI 与无障碍执行任务，以及电脑端 Agent 通过 Skills/MCP 探查和控制连接的 Android 设备。

首版必须形成真实闭环，而不是只提供架构空壳；同时必须正视 Android 权限、Google Play 政策、录制能力和设备连接方式的现实边界。

## What Changes

- 初始化 Git 单仓库，并按协议、桌面 CLI、Android App、Agent Skills、测试和文档分区。
- 提供 Go 编写的跨平台单文件 CLI `aactl`，支持 ADB 诊断、USB/无线设备发现、设备信息、截图、UI 层级和基础操作。
- 在 `aactl` 内提供 MCP stdio 服务，使支持 MCP 的 AI 客户端可发现并调用同一套类型化能力。
- 提供三组符合 Agent Skills 规范的 Skills：设备发现、设备观察、设备操作与自动化工作流。
- 提供 Kotlin + Jetpack Compose Android App，可配置 OpenAI 兼容 Provider、管理权限、运行 AI 自动化会话、录制语义操作并确定性回放。
- Android App 使用 `AccessibilityService` 读取 UI 语义树并执行节点动作、手势、文本输入和全局导航；API 30+ 可按需截图。
- 电脑端在不安装 App 时优先通过 ADB 直接执行；安装 App 后可通过 ADB 端口转发调用 App 的 loopback 本地桥，获得语义选择器、录制和回放能力。
- 以版本化 JSON Schema 和 JSON-RPC 2.0/NDJSON 定义桌面端与 App 的稳定协议、能力协商、错误码和契约夹具。
- 提供风险分级、明确目标设备、超时/取消、敏感内容脱敏、人工确认和紧急停止机制。
- 提供深度研究结论、架构说明、录制体系、协议、安全与发布策略、备选技术路线文档。
- 提供 Go、Android、协议契约和跨平台 CI 验证；在可用 Android SDK/模拟器环境中增加端到端冒烟。
- **BREAKING**：Google Play 普通分发版本不得包含基于 Accessibility API 的自主 AI 闭环；完整能力定位为开源侧载、内部测试或企业受控分发。

## Impact

- Affected specs: 设备发现、设备观察、设备执行、App 本地桥、AI 会话、录制回放、Agent Skills、MCP、安全策略、发布策略。
- Affected code: `protocol/`、`cmd/aactl/`、`internal/`、`android/`、`skills/`、`docs/`、`.github/workflows/`。
- External dependencies: Go 1.26.5、官方 MCP Go SDK 稳定版、Android SDK 36、JDK 21、Gradle/AGP、Android Platform-Tools。

## Architecture

```text
AI Agent / 人工调用
        |
        +-- Agent Skills --------+
        +-- MCP stdio -----------+--> aactl 核心
        +-- CLI JSON ------------+       |
                                        +-- ADB 直接后端
                                        |    设备/截图/UI dump/输入/应用控制
                                        |
                                        +-- App Bridge 后端
                                             adb forward -> 127.0.0.1
                                                        |
Android App                                             |
  Compose UI -> Provider 配置 -> AI 会话状态机          |
       |                              |                 |
       +-> 录制/回放                   +-> 风险策略       |
                  \                   /                  |
                   Accessibility 执行器 <---------------+
```

### 关键架构决定

1. `aactl` 是电脑端唯一执行内核；MCP 和 Skills 只做适配与编排，避免业务逻辑散落在脚本中。
2. ADB 是 MVP 的统一设备传输层。USB 为默认，Android 11+ 无线调试与 mDNS 为标准无线方案；传统 `adb tcpip 5555` 仅作为明确标记未加密风险的兼容模式。
3. 蓝牙没有通用 ADB transport，不列为已支持能力。后续通过 `DeviceProvider` 接口接入厂商互联、专用蓝牙桥、远程设备农场或云真机。
4. 电脑端基础操作无需 Android App。语义选择器、稳定回放、App 内 AI 和录制依赖 Android App 的无障碍能力。
5. Android App 本地桥仅绑定设备 loopback，并仅在用户开启桌面会话后工作；电脑通过指定设备的 ADB forward 访问。
6. MVP 桥协议使用受大小限制的 NDJSON framed JSON-RPC 2.0，避免首版同时维护 HTTP、WebSocket 和二进制流。截图等大制品走 ADB 直接通道或文件传输。
7. UI 树和节点动作优先，坐标和视觉作为回退。每次动作后重新观察，禁止长期持有失效的 `AccessibilityNodeInfo`。
8. 录制是“语义事件推断用户意图”，不是透明原始触摸录制。自由手势、游戏、自绘 Canvas 和受保护页面必须明确降级或失败。

## Product Scope

### MVP 范围

- Android 11 / API 30 及以上。
- macOS arm64/amd64 与 Windows amd64 的 `aactl` 构建。
- USB ADB、Android 11+ 无线 ADB 配对/连接、模拟器、多设备显式选择。
- ADB 直接观察和基础操作。
- Android App 单一 OpenAI 兼容 Provider 类型，支持自定义 Base URL、模型与 API Key。
- AI “观察 -> 规划一个类型化动作 -> 风险检查 -> 执行 -> 验证”循环。
- 无障碍语义录制、脚本列表、脚本回放、等待与基础断言。
- MCP stdio 和三组 Agent Skills。
- 本地优先数据、脱敏审计、会话停止。

### 非 MVP 范围

- Google Play 上架完整自主 AI 版本。
- Root、Shizuku、Device Owner 或系统签名作为默认依赖。
- 蓝牙控制、厂商跨设备 SDK、互联网远程控制。
- 原始触摸全局旁路录制、ADB `getevent/sendevent` 高保真录制。
- OCR、模板匹配、视觉选择器自愈、复杂分支/循环/子流程。
- 云端设备农场、多租户调度、浏览器串流。
- 自动执行支付、转账、安装授权、无障碍授权或其他系统安全确认。

## Data and Protocol

### CLI 响应

CLI 的 `--json` 输出必须使用稳定响应信封，日志只写 stderr：

```json
{
  "schemaVersion": "1.0",
  "requestId": "uuid",
  "ok": true,
  "data": {},
  "error": null,
  "meta": {
    "durationMs": 42
  }
}
```

错误至少包含稳定 `code`、面向用户的 `message`、`retryable` 和可选 `details`。退出码分为成功、参数错误、授权失败、设备不可达、超时、不支持和内部错误。

### App Bridge 协议

- 首次消息为 `rpc.hello`，双方交换程序版本、协议版本和 capabilities。
- `session.open` 需要 App UI 显示的一次性配对码，成功后签发仅用于当前桥会话的随机 token。
- 后续请求必须带 token、`requestId`、deadline 和明确设备/会话上下文。
- 核心方法为 `device.info`、`ui.snapshot`、`action.execute`、`recording.list`、`recording.replay`、`session.close`。
- major 版本不兼容时立即失败；新增可选字段向后兼容；未知字段可忽略，未知动作必须拒绝。
- 消息必须有长度上限、超时、取消和并发上限；token、API Key 和 secret 不得进入日志。

### 动作模型

首版类型化动作：

- `app.launch`、`app.stop`
- `ui.click`、`ui.longClick`
- `ui.tap`、`ui.swipe`
- `ui.setText`
- `ui.scroll`
- `ui.back`、`ui.home`、`ui.recents`
- `ui.wait`
- `ui.assert`
- `task.finish`

任意 shell 不作为 Agent 或 MCP 默认能力。CLI 内部只通过参数数组调用允许的 ADB 子命令，禁止拼接 shell 字符串。

### 录制模型

```text
AutomationScript
  id, schemaVersion, name, targetPackages, createdAt, requirements
  steps[]

AutomationStep
  id, type, target, input, waitAfter, retry, failurePolicy

Target
  selectorCandidates[], fingerprint, recordedBounds,
  relativePoint, normalizedScreenPoint
```

选择器优先级为资源 ID、内容描述、稳定文本与角色、祖先上下文、节点指纹、节点内相对坐标、屏幕归一化坐标。回放低置信度或多候选冲突时必须失败，不得静默点击猜测对象。

## Android App

### UI 与视觉质量

- 使用 Material 3、动态颜色、浅色/深色模式和清晰的状态层级。
- 首页展示无障碍、Provider、桌面桥三个状态卡，以及任务输入和最近会话。
- Provider 配置表单明确区分 Base URL、API Key、模型、超时，并提供连通性测试。
- 自动化会话页持续展示当前步骤、观察摘要、风险提示、暂停和醒目的立即停止按钮。
- 录制页提供开始、暂停、结束、命名、步骤预览和回放入口。
- loading、空状态和错误状态必须有明确反馈；不得依赖占位图片。

### Provider 配置

- Provider 抽象支持后续扩展，MVP 实现 OpenAI 兼容 Chat Completions。
- API Key 由 Android Keystore 生成的不可导出 AES/GCM 密钥加密后保存。
- 网络请求只发送当前用户授权目标 App 的最小 UI 信息；截图默认关闭，可由用户按会话启用。
- Provider 原始响应先做严格 JSON 解析和动作白名单校验，再进入风险策略。

### AI 会话

- 状态机包含 Idle、Observing、Planning、AwaitingConfirmation、Executing、Verifying、Paused、Completed、Failed、Stopped。
- 每次只允许规划并执行一个动作，执行后重新抓取状态并验证。
- 设置总步骤数、单步超时、总时长、重复动作检测和用户触摸中止。
- 风险动作和敏感目标必须进入 `AwaitingConfirmation`；禁止模型自行批准。
- 停止后不得继续提交动作，并清除待执行动作和临时截图。

### Accessibility 执行

- 仅在用户手动启用服务后工作，使用醒目披露说明读取与操作范围。
- 只处理用户明确选择的目标包，最小化事件类型。
- 节点 `performAction` 优先，失败后才允许节点中心手势，最后才使用归一化坐标。
- 支持节点树快照、点击、长按、文本、滚动、返回/主页/最近任务和 API 30+ 截图。
- 密码、验证码、支付和 `FLAG_SECURE` 内容不得记录或上传。

### 录制与回放

- 录制点击、长按、文本变化、滚动、窗口变化和显式全局动作。
- 事件按时间窗口去重并关联源节点、祖先链、包名和窗口摘要。
- 文本字段若为密码或敏感输入，只保存 secret 引用，不保存明文。
- 回放采用条件等待而非固定 sleep，并输出匹配分数、使用的回退级别和失败产物摘要。

## Desktop CLI and Skills

### CLI 命令

```text
aactl version --json
aactl doctor --json
aactl devices list --json
aactl devices watch --json
aactl devices pair HOST:PORT
aactl devices connect HOST:PORT
aactl device info --device SERIAL --json
aactl observe screenshot --device SERIAL --output FILE
aactl observe hierarchy --device SERIAL --json
aactl action tap|swipe|text|key|launch|stop ...
aactl bridge open --device SERIAL --code CODE
aactl recording list|replay ...
aactl mcp serve
```

- 多设备时必须明确 `--device`，不得隐式选择。
- 配对码优先从交互式 stdin 获取，不出现在日志。
- CLI 不自动执行 `adb kill-server`，诊断修复必须显式请求。
- `root`、`remount`、`disable-verity`、恢复出厂、任意 shell 默认不提供。

### MCP

MCP stdio 至少暴露：

- `android_devices_list`
- `android_device_get`
- `android_observe`
- `android_action_execute`
- `android_recording_replay`

输入输出由公共类型和 Schema 生成或验证。配对、撤销信任和任意 shell 不暴露给模型。

### Agent Skills

1. `android-device-discovery`：诊断 ADB、发现 USB/无线设备、处理 unauthorized/offline、多设备选择。
2. `android-device-observation`：获取设备信息、截图、UI 层级和 App Bridge 语义快照，遵守隐私与脱敏要求。
3. `android-device-automation`：执行类型化动作、运行录制脚本、处理确认/失败/恢复，禁止高风险静默操作。

Skills 必须符合 Agent Skills 目录和 frontmatter 规范，详细 CLI 与安全参考放入一层 `references/`，核心逻辑不放入 Skill 脚本。

## Security and Distribution

- 完整版以 GitHub Release/内部制品/企业托管渠道发布，APK 侧载必须提供签名与 SHA-256 校验。
- Google Play 政策明确禁止使用 Accessibility API 自主发起、规划和执行操作或决策，因此 Play 变体必须移除自主执行，只保留 AI 建议与逐步用户确认。
- App 不得将自身声明为 `isAccessibilityTool=true`，除非未来产品核心目的真实变为服务残障人士。
- 所有敏感权限使用前提供独立醒目披露和明确同意，拒绝后仍可使用非自动化功能。
- 桥只监听 loopback；ADB RSA 授权作为外层信任，一次性码与短期 token 作为应用层会话保护。
- 目标包、设备、动作、时间、结果进入脱敏审计；原始截图、密码、验证码、API Key 不进入日志。
- 支付、转账、发送、删除、安装、授权和系统设置变更默认阻断或强制人工确认。

## Alternatives and Evolution

实现阶段必须提供备选方案落地文档，覆盖：

- TypeScript/Node CLI 与 Go CLI 的取舍和迁移成本。
- Shizuku 高级用户后端。
- Device Owner/DPC 企业专用设备后端。
- ADB/UI Automator 测试与设备农场后端。
- MediaProjection + OCR/模板匹配视觉层。
- ADB `getevent` 高保真录制插件的 Root/SELinux 限制。
- 厂商互联、专用蓝牙桥和互联网远程设备提供者。
- scrcpy 可视化和 OTG 恢复控制。

所有后续后端通过稳定的 `DeviceProvider`、`Observer` 和 `ActionExecutor` 边界接入，不修改 Agent Skills 的上层动作语义。

## ADDED Requirements

### Requirement: 跨平台设备发现

系统 SHALL 在 macOS 和 Windows 上通过官方 ADB 发现 USB、无线调试和模拟器设备，并返回规范化状态。

#### Scenario: 多设备发现成功

- **WHEN** 用户执行 `aactl devices list --json` 且存在多个连接
- **THEN** 系统返回每个设备的 serial、状态、型号、传输类型和 capabilities，后续操作要求显式选择 serial

#### Scenario: 未授权设备

- **WHEN** ADB 返回 `unauthorized`
- **THEN** 系统返回稳定错误与设备端确认 RSA 指纹的操作指引，不尝试绕过授权

### Requirement: 无 App 的直接设备操作

系统 SHALL 允许电脑端在仅有 ADB 授权时获取设备信息、截图、UI 层级并执行类型化基础输入。

#### Scenario: 直接截图

- **WHEN** 用户指定在线设备和输出路径执行截图命令
- **THEN** 系统通过 `adb exec-out screencap -p` 写入有效 PNG，并返回文件元数据

#### Scenario: 禁止任意 shell

- **WHEN** Agent 尝试执行未列入动作白名单的 shell
- **THEN** 系统拒绝请求且不启动子进程

### Requirement: Android App 独立 AI 自动化

系统 SHALL 允许用户配置 OpenAI 兼容 Provider，并在明确授权的目标 App 上运行受控单步 AI 自动化循环。

#### Scenario: AI 会话成功

- **WHEN** Provider、无障碍服务和目标 App 均就绪，用户输入任务并启动
- **THEN** App 逐步观察、规划、校验、执行和验证，直至模型返回完成或达到限制

#### Scenario: Provider 输出非法动作

- **WHEN** Provider 返回未知动作、缺少字段或越过白名单
- **THEN** App 拒绝执行、记录脱敏错误并停止或重试规划

### Requirement: 安全控制

系统 SHALL 对设备、目标包、动作风险、会话时限和敏感数据实施默认安全策略。

#### Scenario: 高风险动作

- **WHEN** 动作涉及发送、删除、购买、支付、安装、授权或系统设置
- **THEN** 会话暂停并要求用户在 App 中明确确认，未经确认不执行

#### Scenario: 紧急停止

- **WHEN** 用户点击立即停止
- **THEN** App 在 500ms 内停止提交新动作，取消等待任务并清除临时敏感数据

### Requirement: 语义录制与确定性回放

系统 SHALL 从无障碍事件生成版本化脚本，并优先通过语义选择器回放。

#### Scenario: 录制普通表单流程

- **WHEN** 用户开始录制并完成点击、输入、滚动和返回
- **THEN** App 保存去重后的类型化步骤、选择器候选、等待条件和环境摘要

#### Scenario: 选择器歧义

- **WHEN** 回放时最高匹配分数不足或多个候选无法区分
- **THEN** 回放明确失败或请求接管，不执行猜测点击

### Requirement: 电脑与 App 的受控桥接

系统 SHALL 通过指定设备的 ADB forward 访问仅监听 loopback 的 App 服务。

#### Scenario: 桥接会话成功

- **WHEN** 用户在 App 开启桌面会话并向 CLI 提供一次性码
- **THEN** 双方协商协议和能力、建立短期 token，并允许调用语义观察与录制回放

#### Scenario: 无效会话

- **WHEN** 请求使用错误、过期或缺失 token
- **THEN** App 拒绝请求且不泄露设备或 UI 信息

### Requirement: Agent Skills 与 MCP

系统 SHALL 让 Agent Skills 和 MCP 调用同一 `aactl` 内核并返回一致语义。

#### Scenario: MCP 执行动作

- **WHEN** MCP 客户端调用 `android_action_execute`
- **THEN** 服务使用公共动作模型执行并返回与等价 CLI 命令一致的结构化结果

#### Scenario: Skill 安全约束

- **WHEN** Agent Skill 被触发执行安卓自动化
- **THEN** Skill 先确认目标设备与能力，遵守确认规则，并使用类型化命令而非拼接 shell

### Requirement: 可验证工程交付

系统 SHALL 提供可重复的构建、测试、CI 和分步 Git 历史。

#### Scenario: 跨平台构建

- **WHEN** 发布工作流运行
- **THEN** 产出 macOS arm64/amd64、Windows amd64 的 `aactl` 与 Android debug APK，并生成校验和

#### Scenario: 每步提交

- **WHEN** 一个规格任务及其验证完成
- **THEN** 对应文件以单一职责 Conventional Commit 提交，不混入无关改动

## MODIFIED Requirements

无现有规格需要修改。

## REMOVED Requirements

无现有规格需要移除。

## Research Basis

- Android ADB、无线调试、mDNS 与端口转发：<https://developer.android.com/tools/adb>
- Android AccessibilityService：<https://developer.android.com/reference/android/accessibilityservice/AccessibilityService>
- AccessibilityEvent：<https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent>
- MediaProjection：<https://developer.android.com/media/grow/media-projection>
- Google Play Accessibility API 政策：<https://support.google.com/googleplay/android-developer/answer/10964491>
- Android Keystore：<https://developer.android.com/privacy-and-security/keystore>
- Agent Skills 规范：<https://agentskills.io/specification>
- MCP 规范与官方 Go SDK：<https://modelcontextprotocol.io/specification/2025-11-25>、<https://github.com/modelcontextprotocol/go-sdk>
- Go 发布历史：<https://go.dev/doc/devel/release>
- JSON Schema Draft 2020-12：<https://json-schema.org/draft/2020-12>
