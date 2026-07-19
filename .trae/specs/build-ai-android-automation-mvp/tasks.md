# Tasks

- [x] Task 1: 初始化仓库与可重复工具链：初始化 Git，建立单仓库目录、忽略规则、版本约束、基础说明和最小 CI，使后续每个任务可以独立构建与提交。
  - [x] SubTask 1.1: 初始化 Git，加入当前规格文件并建立主分支
  - [x] SubTask 1.2: 固定 Go 1.26.5、JDK 21、Android SDK 36、Gradle/AGP 版本和本地开发命令
  - [x] SubTask 1.3: 建立 `protocol/`、`cmd/`、`internal/`、`android/`、`skills/`、`docs/` 和测试目录
  - [x] SubTask 1.4: 验证空骨架并提交 `chore: initialize repository and toolchains`

- [x] Task 2: 定义协议 v1 与契约夹具：建立 CLI 信封、设备模型、动作模型、录制模型和 App Bridge JSON-RPC Schema，作为 Go、Kotlin、MCP 和 Skills 的共同语义来源。
  - [x] SubTask 2.1: 编写 JSON Schema Draft 2020-12 文件和合法/非法 fixture
  - [x] SubTask 2.2: 定义 capability、错误码、版本协商、大小限制、超时和向后兼容规则
  - [x] SubTask 2.3: 实现协议 Schema/fixture 自动验证测试
  - [x] SubTask 2.4: 验证协议测试并提交 `feat(protocol): define automation protocol v1`

- [x] Task 3: 实现 `aactl` 设备发现与 ADB 安全适配器：提供诊断、设备列表、无线配对/连接、设备信息、多设备显式选择和统一 JSON 输出。
  - [x] SubTask 3.1: 实现不经过 shell 的进程执行器、超时、输出上限和脱敏
  - [x] SubTask 3.2: 实现 ADB 路径解析、版本/5037/mDNS 诊断和状态解析
  - [x] SubTask 3.3: 实现 USB/无线/模拟器设备归一化与 `doctor`、`devices`、`device info` 命令
  - [x] SubTask 3.4: 使用 fake ADB 覆盖 unauthorized、offline、多设备、超时和恶意参数
  - [x] SubTask 3.5: 验证 Go 测试并提交 `feat(cli): add adb device discovery`

- [x] Task 4: 实现 `aactl` 直接观察与类型化动作：在无需 Android App 的情况下支持截图、UI dump、点击、滑动、文本、按键和应用启动/停止。
  - [x] SubTask 4.1: 实现截图与 UI hierarchy 观察命令及产物校验
  - [x] SubTask 4.2: 实现动作白名单、参数范围验证和显式设备选择
  - [x] SubTask 4.3: 实现 tap、swipe、text、key、launch、stop 的 ADB 参数映射
  - [x] SubTask 4.4: 添加 fake ADB 集成测试并确认未暴露任意 shell
  - [x] SubTask 4.5: 验证 Go 测试并提交 `feat(cli): add direct device observation and actions`

- [x] Task 5: 建立 Android App、Provider 配置与安全存储：创建可构建的 Compose App，实现权限状态首页、OpenAI 兼容 Provider 配置、Keystore 加密和连通性测试。
  - [x] SubTask 5.1: 创建 Gradle Kotlin DSL 工程、Compose Material 3 主题和基础导航
  - [x] SubTask 5.2: 实现首页状态卡、Provider 表单、任务入口和录制入口
  - [x] SubTask 5.3: 实现 Provider 接口、OpenAI 兼容请求/响应模型和严格 JSON 解析
  - [x] SubTask 5.4: 实现 Android Keystore AES/GCM API Key 存储与日志脱敏
  - [x] SubTask 5.5: 添加领域单测、构建 debug APK 并提交 `feat(android): add app shell and provider configuration`

- [x] Task 6: 实现无障碍观察与动作执行器：提供最小权限配置、UI 树快照、语义选择器匹配、节点动作、手势、文本和全局导航。
  - [x] SubTask 6.1: 声明 AccessibilityService、醒目披露和目标包范围配置
  - [x] SubTask 6.2: 实现不可长期缓存节点的规范化树快照与敏感字段过滤
  - [x] SubTask 6.3: 实现选择器评分、唯一性阈值和节点/坐标回退策略
  - [x] SubTask 6.4: 实现 click、longClick、setText、scroll、tap、swipe、back、home、recents
  - [x] SubTask 6.5: 添加选择器、坐标变换和动作路由单测并提交 `feat(android): add accessibility observation and execution`

- [x] Task 7: 实现电脑到 App 的本地桥：通过 ADB forward、loopback NDJSON JSON-RPC、一次性码和会话 token 提供语义观察与动作执行。
  - [x] SubTask 7.1: 在 Android 端实现仅绑定 `127.0.0.1` 的有界消息服务和桥 UI 状态
  - [x] SubTask 7.2: 实现 hello、session open/close、device info、ui snapshot、action execute
  - [x] SubTask 7.3: 在 `aactl` 实现端口分配、ADB forward 生命周期、配对和桥客户端
  - [x] SubTask 7.4: 覆盖错误码、错误/过期 token、重放、超时、断连和消息过大测试
  - [x] SubTask 7.5: 验证双方契约测试并提交 `feat(bridge): connect desktop to android app`

- [x] Task 8: 实现 App 内 AI 自动化会话与风险控制：完成观察、单步规划、确认、执行、验证、暂停、失败和紧急停止状态机。
  - [x] SubTask 8.1: 实现会话状态机、步骤/时长限制、重复动作检测和取消传播
  - [x] SubTask 8.2: 构造最小化 UI 上下文和严格动作提示/响应协议
  - [x] SubTask 8.3: 实现风险分类、目标包约束、人工确认和禁止动作
  - [x] SubTask 8.4: 实现会话页的当前步骤、确认、暂停、恢复、停止和脱敏审计
  - [x] SubTask 8.5: 使用 fake Provider/Executor 覆盖成功、非法动作、确认、超时和停止并提交 `feat(android): add guarded ai automation sessions`

- [x] Task 9: 实现语义录制与确定性回放：从无障碍事件生成版本化脚本，支持编辑名称、步骤预览、条件等待、断言、secret 引用和回放报告。
  - [x] SubTask 9.1: 实现录制状态机、事件归因/去重和节点指纹生成
  - [x] SubTask 9.2: 实现本地脚本存储、列表、详情、删除和 schema 迁移入口
  - [x] SubTask 9.3: 实现选择器优先回放、条件等待、有限重试和低置信度失败
  - [x] SubTask 9.4: 实现密码/验证码过滤、secret 引用和回放审计
  - [x] SubTask 9.5: 添加录制/回放单测与 UI 测试并提交 `feat(android): add semantic recording and replay`

- [x] Task 10: 实现 MCP stdio 适配器：使用官方稳定 MCP Go SDK 暴露设备列表、设备信息、观察、动作和录制回放工具。
  - [x] SubTask 10.1: 将公共 `aactl` 服务层与 CLI 参数层解耦
  - [x] SubTask 10.2: 实现五个 MCP tools 及结构化输入输出
  - [x] SubTask 10.3: 阻止配对、撤销信任和任意 shell 暴露给模型
  - [x] SubTask 10.4: 添加 MCP stdio 协议测试和 CLI/MCP 语义一致性测试
  - [x] SubTask 10.5: 验证 MCP 测试并提交 `feat(mcp): expose android automation tools`

- [x] Task 11: 创建跨 Agent 的 Skills 组：提供设备发现、设备观察和设备自动化三组符合 Agent Skills 规范的技能。
  - [x] SubTask 11.1: 创建 `android-device-discovery` Skill 与故障诊断参考
  - [x] SubTask 11.2: 创建 `android-device-observation` Skill 与隐私/产物参考
  - [x] SubTask 11.3: 创建 `android-device-automation` Skill 与风险/恢复参考
  - [x] SubTask 11.4: 验证 frontmatter、引用深度、命令示例和安全约束
  - [x] SubTask 11.5: 运行 Skill 校验并提交 `feat(skills): add portable android automation skills`

- [x] Task 12: 完成研究文档、端到端验证与发布：落地架构、协议、录制、安全、分发和备选方案文档，建立 CI、跨平台构建与制品校验。
  - [x] SubTask 12.1: 编写架构、协议、录制、安全分发、故障排查文档
  - [x] SubTask 12.2: 编写 Shizuku、Device Owner、UI Automator/设备农场、视觉层、getevent、蓝牙/厂商互联和 scrcpy 备选方案
  - [x] SubTask 12.3: 建立 Go、Android、Schema、Skill 和跨平台构建 CI
  - [x] SubTask 12.4: 运行全量单测、静态检查、契约测试和可用环境下的 Emulator/真机冒烟
  - [x] SubTask 12.5: 生成 macOS/Windows CLI 与 debug APK、校验和，检查 Git 历史并提交 `docs: finalize architecture and release guidance`

- [x] Task 13: 修复系统验收发现的协议、设备诊断、录制回放与 MCP 安全缺口，并重新执行设备条件检查。
  - [x] SubTask 13.1: 增加协议最高共同版本、同 major 可选字段兼容、未知字段策略和配置一致性自动测试
  - [x] SubTask 13.2: 修复 doctor 的 Platform-Tools 版本、5037/mDNS、Windows 驱动/Linux udev/USB 提示与 fake ADB 覆盖
  - [x] SubTask 13.3: 修复 mDNS service serial、无线地址、模拟器和无 `usb:` 属性物理设备的传输类型归一化
  - [x] SubTask 13.4: 补齐无障碍录制事件订阅、显式全局动作录制入口和运行环境摘要
  - [x] SubTask 13.5: 区分快照失败与节点不存在，执行前强制校验当前包属于脚本目标包
  - [x] SubTask 13.6: 为 MCP 动作和录制回放增加统一风险门，默认拒绝高风险/破坏性调用
  - [x] SubTask 13.7: 实现 App Bridge 与 CLI 的 `recording.list`，并补齐双端契约测试
  - [x] SubTask 13.8: 运行全量测试、重新检测 ADB/Emulator/真机条件并更新验证记录

- [x] Task 14: 修复最终独立复验发现的 ADB 路径诊断缺口，并补齐录制回放等待超时的直接回归测试。
  - [x] SubTask 14.1: 将无效 `AACTL_ADB_PATH` 归一化为可操作的 `ADB_NOT_FOUND`，保留底层原因并增加单测
  - [x] SubTask 14.2: 增加 `ui.wait` 条件超时返回 `CONDITION_TIMEOUT` 且不执行动作的直接单测
  - [x] SubTask 14.3: 运行定向测试和全量验证，更新最终验收记录

- [x] Task 15: 补齐 App 端无障碍截图：实现规格要求的 API 30+ `AccessibilityService.takeScreenshot` 能力、敏感内容限制和直接测试。

- [x] Task 16: 补齐用户触摸中止安全控制：将目标 App 中的用户触摸交互接入活动 AI 会话的同步停止入口，并验证停止后不再提交动作。

- [x] Task 17: 补齐录制与回放 UI 测试：增加真实 Compose/instrumentation UI 测试及依赖，覆盖录制、步骤预览、secret 输入、回放和失败反馈交互。

- [x] Task 18: 修正过期录制故障排查：更新 `docs/troubleshooting.md` 中已失效的录制列表和事件订阅说明，使其与当前 CLI、AccessibilityService 和 Task 13 实现一致。

- [x] Task 19: 完善真机 USB 验收指南：明确“仅充电/传输文件/传输图片”的选择策略，并串联开发者选项、USB 调试、RSA、App 安装、目标包、无障碍、Bridge 与录制回放 smoke 步骤。

# Task Dependencies

- Task 2 depends on Task 1.
- Task 3 and Task 5 depend on Task 2 and can run in parallel.
- Task 4 depends on Task 3.
- Task 6 depends on Task 5.
- Task 7 depends on Task 3, Task 5 and Task 6.
- Task 8 and Task 9 depend on Task 6 and can run in parallel.
- Task 10 depends on Task 3, Task 4 and Task 7.
- Task 11 depends on Task 4 and Task 10.
- Task 12 depends on Tasks 1-11.
- Task 13 depends on Tasks 1-12.
- Task 14 depends on Task 13.

# Commit Policy

- 每个 Task 完成验证后立即创建一个单一职责 Conventional Commit。
- 并行任务只能修改彼此独立的目录；集成冲突必须在对应任务提交前解决。
- 不提交密钥、API Key、签名私钥、本机 SDK 路径、IDE 用户配置或构建缓存。
- 每个提交必须至少通过该任务的定向测试；最终提交前运行全量验证。
