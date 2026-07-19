# 验收清单

## 仓库与交付

- [x] Git 仓库已初始化，主分支和忽略规则正确，规格文件被保留
- [x] Go、JDK、Android SDK、Gradle/AGP 版本已固定且开发命令可重复
- [x] 每个实现任务都有独立 Conventional Commit，历史中没有密钥或构建缓存
- [x] 文档明确说明 MVP 范围、已知限制、安装方法和故障排查

## 协议

- [x] CLI 信封、设备、动作、错误和录制模型具有版本化 JSON Schema
- [x] 合法 fixture 通过、非法 fixture 被拒绝，协议兼容规则有自动测试
- [x] App Bridge 能协商协议版本和 capabilities，并拒绝不兼容 major 版本
- [x] Bridge 对消息大小、超时、并发、错误 token 和重放请求实施限制

## 桌面设备能力

- [x] `aactl doctor --json` 能诊断 ADB 缺失、版本、5037、mDNS 和常见驱动问题
- [x] USB、Android 11+ 无线调试、模拟器和多设备列表被规范化展示
- [x] unauthorized、offline、无设备和多设备未选择均返回稳定且可操作的错误
- [x] 所有设备操作显式携带 serial，CLI 不自动杀死共享 ADB server
- [x] `aactl` 可直接获取设备信息、有效 PNG 截图和 UI hierarchy
- [x] `aactl` 可直接执行 tap、swipe、text、key、launch 和 stop
- [x] 子进程使用 argv 数组且有超时/输出上限，未提供任意 shell 给 Agent

## Android App

- [x] Android debug APK 可构建，支持 API 30+，Compose 首页状态和导航完整
- [x] Provider 可配置 Base URL、API Key、模型和超时，并能测试连接
- [x] API Key 使用 Android Keystore 支持的 AES/GCM 加密，日志中无明文
- [x] AccessibilityService 需要用户手动启用并展示真实醒目披露
- [x] UI 树快照过滤密码、验证码和敏感字段，不长期缓存节点对象
- [x] 选择器按语义优先级匹配，低置信度或歧义时不猜测点击
- [x] click、longClick、setText、scroll、tap、swipe、back、home、recents 可路由执行
- [x] AI 会话按观察、单步规划、校验、执行和验证循环运行
- [x] 非法 Provider 响应、未知动作、越权目标和超过限制均不会执行
- [x] 高风险动作进入人工确认，支付/授权等禁止动作不会自动执行
- [x] 用户点击立即停止后 500ms 内不再提交新动作
- [x] AccessibilityService 在 API 30+ 可按需截图，并对敏感内容和会话授权实施限制
- [x] 用户在目标 App 中触摸时会中止活动 AI 会话，且停止后不再提交动作

## 录制与回放

- [x] 点击、长按、文本、滚动、窗口变化和显式全局动作可生成去重步骤
- [x] 脚本保存选择器候选、节点指纹、坐标回退、等待条件和环境摘要
- [x] 密码和敏感文本只保存 secret 引用，不进入脚本、日志或截图
- [x] 回放优先节点动作，使用条件等待和有限重试，并报告回退级别
- [x] 选择器冲突、目标 App 不匹配和等待超时会明确失败或请求接管
- [ ] 故障排查中的录制列表与事件订阅说明和当前 CLI、AccessibilityService 行为一致

## App Bridge、MCP 与 Skills

- [x] App Bridge 仅监听设备 loopback，未开启桌面会话时不可访问 UI 数据
- [x] ADB forward 生命周期正确清理，一次性码和短期 token 不进入日志
- [x] CLI 与 App 能完成 hello、session、snapshot、action 和 close 闭环
- [x] MCP stdio 暴露五个类型化工具，CLI 与 MCP 等价调用结果语义一致
- [x] MCP 不暴露配对、撤销信任、任意 shell 或默认高风险操作
- [x] 三个 Agent Skills 通过规范校验，frontmatter、引用和命令均可用
- [x] Skills 始终先确认目标设备和 capabilities，并遵守风险确认规则

## 测试、兼容与发布

- [x] Go 单测和 fake ADB 集成测试覆盖成功、超时、恶意参数和多设备
- [x] Android 单测覆盖选择器、坐标、状态机、风险策略、录制和回放
- [x] 协议、MCP 和 App Bridge 具有契约测试
- [x] CI 在 macOS、Windows 和 Linux 验证 Go 构建与测试
- [x] CI 验证 Android lint、单测和 debug APK 构建
- [x] 录制与回放具有真实 Compose/instrumentation UI 交互测试
- [ ] 真机测试指南明确 USB 模式选择，并覆盖开发者选项、USB 调试、RSA、无障碍、Bridge 和录制回放 smoke
- [x] 可用环境下完成 Emulator 或真机的截图、动作、桥接和录制回放冒烟（当前环境无设备、emulator、system image 或 AVD，按条件验收；未执行且不声明端到端通过）
- [x] 发布产出 macOS arm64/amd64、Windows amd64 CLI、debug APK 和 SHA-256
- [x] 文档明确 Google Play 自主 Accessibility AI 的政策禁区及合规变体
- [x] 备选方案文档覆盖 Shizuku、Device Owner、UI Automator、视觉层、getevent、蓝牙/厂商互联、设备农场和 scrcpy
