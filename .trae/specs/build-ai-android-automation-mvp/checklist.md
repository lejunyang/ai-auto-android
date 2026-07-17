# 验收清单

## 仓库与交付

- [ ] Git 仓库已初始化，主分支和忽略规则正确，规格文件被保留
- [ ] Go、JDK、Android SDK、Gradle/AGP 版本已固定且开发命令可重复
- [ ] 每个实现任务都有独立 Conventional Commit，历史中没有密钥或构建缓存
- [ ] 文档明确说明 MVP 范围、已知限制、安装方法和故障排查

## 协议

- [ ] CLI 信封、设备、动作、错误和录制模型具有版本化 JSON Schema
- [ ] 合法 fixture 通过、非法 fixture 被拒绝，协议兼容规则有自动测试
- [ ] App Bridge 能协商协议版本和 capabilities，并拒绝不兼容 major 版本
- [ ] Bridge 对消息大小、超时、并发、错误 token 和重放请求实施限制

## 桌面设备能力

- [ ] `aactl doctor --json` 能诊断 ADB 缺失、版本、5037、mDNS 和常见驱动问题
- [ ] USB、Android 11+ 无线调试、模拟器和多设备列表被规范化展示
- [ ] unauthorized、offline、无设备和多设备未选择均返回稳定且可操作的错误
- [ ] 所有设备操作显式携带 serial，CLI 不自动杀死共享 ADB server
- [ ] `aactl` 可直接获取设备信息、有效 PNG 截图和 UI hierarchy
- [ ] `aactl` 可直接执行 tap、swipe、text、key、launch 和 stop
- [ ] 子进程使用 argv 数组且有超时/输出上限，未提供任意 shell 给 Agent

## Android App

- [ ] Android debug APK 可构建，支持 API 30+，Compose 首页状态和导航完整
- [ ] Provider 可配置 Base URL、API Key、模型和超时，并能测试连接
- [ ] API Key 使用 Android Keystore 支持的 AES/GCM 加密，日志中无明文
- [ ] AccessibilityService 需要用户手动启用并展示真实醒目披露
- [ ] UI 树快照过滤密码、验证码和敏感字段，不长期缓存节点对象
- [ ] 选择器按语义优先级匹配，低置信度或歧义时不猜测点击
- [ ] click、longClick、setText、scroll、tap、swipe、back、home、recents 可路由执行
- [ ] AI 会话按观察、单步规划、校验、执行和验证循环运行
- [ ] 非法 Provider 响应、未知动作、越权目标和超过限制均不会执行
- [ ] 高风险动作进入人工确认，支付/授权等禁止动作不会自动执行
- [ ] 用户点击立即停止后 500ms 内不再提交新动作

## 录制与回放

- [ ] 点击、长按、文本、滚动、窗口变化和显式全局动作可生成去重步骤
- [ ] 脚本保存选择器候选、节点指纹、坐标回退、等待条件和环境摘要
- [ ] 密码和敏感文本只保存 secret 引用，不进入脚本、日志或截图
- [ ] 回放优先节点动作，使用条件等待和有限重试，并报告回退级别
- [ ] 选择器冲突、目标 App 不匹配和等待超时会明确失败或请求接管

## App Bridge、MCP 与 Skills

- [ ] App Bridge 仅监听设备 loopback，未开启桌面会话时不可访问 UI 数据
- [ ] ADB forward 生命周期正确清理，一次性码和短期 token 不进入日志
- [ ] CLI 与 App 能完成 hello、session、snapshot、action 和 close 闭环
- [ ] MCP stdio 暴露五个类型化工具，CLI 与 MCP 等价调用结果语义一致
- [ ] MCP 不暴露配对、撤销信任、任意 shell 或默认高风险操作
- [ ] 三个 Agent Skills 通过规范校验，frontmatter、引用和命令均可用
- [ ] Skills 始终先确认目标设备和 capabilities，并遵守风险确认规则

## 测试、兼容与发布

- [ ] Go 单测和 fake ADB 集成测试覆盖成功、超时、恶意参数和多设备
- [ ] Android 单测覆盖选择器、坐标、状态机、风险策略、录制和回放
- [ ] 协议、MCP 和 App Bridge 具有契约测试
- [ ] CI 在 macOS、Windows 和 Linux 验证 Go 构建与测试
- [ ] CI 验证 Android lint、单测和 debug APK 构建
- [ ] 可用环境下完成 Emulator 或真机的截图、动作、桥接和录制回放冒烟
- [ ] 发布产出 macOS arm64/amd64、Windows amd64 CLI、debug APK 和 SHA-256
- [ ] 文档明确 Google Play 自主 Accessibility AI 的政策禁区及合规变体
- [ ] 备选方案文档覆盖 Shizuku、Device Owner、UI Automator、视觉层、getevent、蓝牙/厂商互联、设备农场和 scrcpy
