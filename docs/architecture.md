# 架构

本文描述仓库当前实现，不代表路线图中的待实现能力。系统支持 Android 11
（API 30）及以上设备；电脑端以 `aactl` 为唯一执行内核，对上提供 CLI 和 MCP，
对下提供直接 ADB 与 App Bridge 两个后端。

## 总览

```text
人工 / Agent
  |
  +-- CLI JSON -------------------------+
  +-- MCP stdio ------------------------+--> Go service.Automation
  +-- Agent Skills（调用 CLI/MCP）------+          |
                                                   +-- DirectBackend
                                                   |     |
                                                   |     +-- argv 形式调用官方 adb
                                                   |         设备、截图、UIAutomator、输入、应用控制
                                                   |
                                                   +-- BridgeBackend
                                                         |
                                                         +-- adb forward tcp:0 -> tcp:38383
                                                               |
Android App                                                   v
  Compose UI --> Provider 配置 --> AI 单步会话       127.0.0.1:38383
      |                |              |                    |
      |                |              +--> 风险策略         |
      |                +--> Android Keystore                |
      +--> 录制/回放 --------------------------------------+
                              |
                    AccessibilityService
                    语义快照、节点动作、手势、全局导航
```

### 组件职责

| 组件 | 当前职责 |
| --- | --- |
| `cmd/aactl`、`internal/cli` | 参数解析、稳定 JSON 信封、交互读取配对码 |
| `internal/service` | CLI 与 MCP 共用的设备、观察、动作和回放语义 |
| `internal/adb` | ADB 定位、诊断、设备归一化、类型化命令和端口转发 |
| `internal/bridge` | Bridge 握手、短期会话、NDJSON 客户端和 forward 生命周期 |
| `internal/mcpserver` | 五个 MCP tools；不暴露配对、Bridge 管理或任意 shell |
| `protocol/` | JSON Schema Draft 2020-12、版本、限制和契约夹具 |
| Android `accessibility` | 目标包约束、脱敏快照、选择器匹配和动作路由 |
| Android `automation/session` | 观察、单步规划、风险检查、执行、验证和停止 |
| Android `automation/recording` | 语义事件映射、本地脚本、条件等待和回放报告 |
| Android `bridge` | 仅 loopback 的有界 JSON-RPC 服务、配对和 token 校验 |
| `skills/` | 设备发现、观察和受控自动化工作流，不复制执行逻辑 |

## 双后端

### 直接 ADB 后端

直接后端只要求官方 Android SDK Platform-Tools、设备已启用调试且主机已获
ADB 授权，不要求安装 Android App。

当前能力：

- 发现 USB、Wi-Fi 和模拟器设备，读取设备属性；
- `exec-out screencap -p` 截图并校验 PNG、计算 SHA-256；
- `uiautomator dump` 获取 XML 层级；
- 类型化 `tap`、`swipe`、受限 ASCII 文本、白名单按键；
- 启动和强制停止指定包。

所有设备操作都要求 `--device SERIAL`。子进程使用可执行文件加参数数组，
没有用户可控的 shell 字符串，也没有任意 shell 接口。直接后端得到的是像素、
UIAutomator XML 或坐标，不提供 Accessibility 选择器与录制。

### App Bridge 后端

Bridge 后端要求设备安装并运行 Android App，用户已接受 App 内醒目披露、配置
目标包、手动启用无障碍服务，并在 App 内开启桌面桥。

App 服务只绑定设备的 `127.0.0.1:38383`。`aactl` 为指定 serial 创建
`adb forward tcp:0 tcp:38383`，再从电脑的 `127.0.0.1:<临时端口>` 访问，
因此服务不直接暴露到 LAN。Bridge 增加：

- 脱敏 Accessibility 语义树；
- 资源 ID、内容描述、文本、角色、祖先和指纹选择器；
- 节点优先的点击、长按、文本、滚动以及受控手势和全局导航；
- App 本地录制脚本回放。

一次性码有效 120 秒；成功配对后签发最长 900 秒的随机 token。关闭或认证失败
会清理本地会话，`bridge close` 还会移除 ADB forward。详细限制见
[协议](protocol.md)。

## 数据流

### 1. 直接观察或动作

```text
CLI/MCP -> service.Automation -> adb.Client
        -> adb -s SERIAL <固定子命令>
        -> 校验/归一化 -> CLI 信封或 MCP structuredContent
```

截图由 CLI 写到用户指定文件，权限为当前用户读写；MCP 截图以 image content
返回给 MCP 客户端。UIAutomator XML 没有 App 端敏感节点脱敏保证。

### 2. 桌面语义操作

```text
用户在 App 开桥 -> App 显示一次性码
CLI bridge open -> ADB forward -> rpc.hello -> session.open
CLI/MCP -> 本地短期 token -> ui.snapshot / action.execute / recording.replay
Android Bridge -> AccessibilityRuntime -> 重新获取当前窗口 -> 执行 -> 结构化结果
```

每次语义动作都重新打开节点会话并复制当前树，不跨动作长期持有
`AccessibilityNodeInfo`。选择器歧义会失败，不选择第一个候选。

### 3. App 内 AI 会话

```text
用户任务 -> 目标包校验 -> 脱敏语义摘要 -> OpenAI 兼容 Provider
         <- 一个严格类型化动作 <-
风险策略 -> 必要时人工确认 -> 本地执行 -> 重新观察验证
```

默认限制为最多 30 步、单阶段 20 秒、总时长 5 分钟、同一动作连续 3 次即失败。
模型每轮只能返回一个动作。支付、安装、授权和系统设置目标被阻断；发送、提交、
删除等语义动作要求当次明确确认。截图不会进入这条当前实现的数据流。

### 4. 录制与回放

录制器将无障碍事件推断为版本化语义步骤，脚本写入 App 私有
`filesDir/recordings`。回放逐步重新快照、匹配、执行、条件等待并在首个失败处
停止。它不是原始触摸录制；当前有效事件订阅和 secret 边界见
[录制与回放](recording.md)。

## 信任边界

1. **电脑到设备**：ADB RSA 授权，用户必须在已解锁设备上确认主机指纹。
2. **电脑到 App**：指定 serial、loopback forward、一次性码、短期 token、
   requestId 防重放和有界消息共同约束会话。
3. **App 到目标应用**：醒目披露、目标包白名单、当前前台包复核和本地风险策略。
4. **App 到 Provider**：仅发送脱敏且有长度上限的语义摘要；API Key 由
   Android Keystore AES/GCM 密钥加密，但 Provider 仍是外部数据处理方。
5. **Agent 边界**：MCP 和 Skills 只获得类型化能力，不获得 ADB 配对、
   Accessibility 授权、Bridge token 或任意 shell。

## 当前边界

- CLI 没有 `devices watch`、`recording list` 或录制编辑命令。
- CLI 的语义观察命令是 `bridge snapshot`；MCP 将三种观察统一为
  `android_observe`。
- Bridge 不传输截图；像素截图始终走直接 ADB 后端。
- 协议动作集合大于当前 Bridge 执行集合；能力必须以协商结果和实际错误为准。
- 蓝牙、Root、Shizuku、Device Owner、OCR、视觉自愈、设备农场和互联网远控
  均未实现。
- 仓库当前没有面向 Google Play 的裁剪变体；完整自主 AI 版本不应按 Play
  合规版本分发，见[安全与分发](security-distribution.md)。

## 参考

- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [MCP 规范](https://modelcontextprotocol.io/specification/2025-11-25)
- [Agent Skills 规范](https://agentskills.io/specification)
