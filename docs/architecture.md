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
             |                                     |
             +--> DesktopVisual capture -----------+--> visual.Registry / Proposal
                                                   |      |
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
| `internal/vdisplay`、`android/displayd` | shell 辅助进程身份核验、管理/有界取帧；仅 CLI 部署/启停，CLI/MCP 可管理虚拟屏 |
| `internal/bridge` | Bridge 握手、短期会话、NDJSON 客户端和 forward 生命周期 |
| `internal/mcpserver` | 20 个 MCP tools；视觉候选只读，不暴露配对、Bridge 管理或任意 shell |
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

- 发现USB/Wi-Fi/模拟器设备，读取属性、应用列表与前台包；有界 devices watch、已信任设备重连；
- PNG截图与摘要校验、UIAutomator层级、屏幕状态/输入法列表、有界MP4录屏；
- 类型化点按、滑动、长按、滚动、拖拽/有限手势、受限ASCII文本与21键白名单；
- 应用启动/停止、deeplink与独立的设备/IME控制；各入口开放范围不同，MCP不开放所有CLI动作；
- check wait/assert/scroll-until及判据求值；只新增的文件push/pull（有传输前存在性检查及TOCTOU限制）；
- API33+ helper虚拟屏，焦点策略及显示0见下节；等待/视觉/Bridge等尚无多显示支持。

所有设备操作都要求 `--device SERIAL`。子进程使用可执行文件加参数数组，
没有用户可控的 shell 字符串，也没有任意 shell 接口。直接后端得到的是像素、
UIAutomator XML 或坐标，不提供Accessibility选择器或语义录制脚本；MP4录屏是独立能力。

### 虚拟显示辅助进程

CLI 的 `display` 与可选 `--display` 在直接 ADB 后端上增加 shell 身份辅助进程。
APK 只作 `app_process` 的 CLASSPATH，不安装、不申请权限。helper部署/启停只有CLI，
MCP提供独立风险门下的四种管理，以及带displayId的两种观察/六类既有动作；App/Bridge不接多显示。
虚拟屏需要 API33+；只有 API35 模拟器已做设备验证，API33/34 与真机/OEM 尚待验收。

```text
CLI display / observe --display / action --display，或MCP管理/显式目标观察与动作
  -> 共享service装配（helper启停只接受操作员CLI）
     -> internal/vdisplay -> 检查 shell 进程及 socket inode/fd 归属
        -> 每次独立 adb forward tcp:0 localabstract:aactl-displayd-v1
        -> displayd -> VirtualDisplay + ImageReader -> 有界 PNG
     -> input -d / am start --display
     -> uiautomator dump --windows -> 严格解析 -> 仅返回目标 display 的窗口列表
```

helper 在连接解析前校验对端 uid，仅接受0/2000；电脑侧在转发前后核对进程和监听
套接字归属，不信任同名未知套接字。部署比对本地/远端 SHA-256；每次调用结束或失败
都会尝试移除自己的 forward，stop 等待进程退出并清固定临时文件。

截图来自 helper 的 ImageReader，目标错误时不回退主屏。层级返回
`format=uiautomator-windows-xml`，只含目标显示的窗口组；底层 `--windows` 的源码
门槛是 API30，API未知或过低时不会发 dump。不带目标的普通 dump 保持既有 argv，
但读的是活动窗口：虚拟屏持有焦点时可能返回虚拟屏内容，不能用来证明主屏不变。

主屏 `device.sleep` 使用 KEYCODE_SLEEP。helper 每2秒向虚拟屏组上报用户活动；
凭据锁定时不保活、不唤醒，截图返回 DEVICE_LOCKED。锁定解除后该组可能继续休眠，
返回 DISPLAY_ASLEEP；必须关闭重建，原屏应用内容会销毁。API33 无按屏组查询，
groupAwake 如实为 null，不能保证发现所有“组睡眠但 Display 仍 ON”的情况。
已在主屏运行的任务再向虚拟屏启动会整体移过去，不自动另开实例。

显示0明确主屏且不要求helper；无目标的旧命令不变。helper独立协议v2，升级前先用旧CLI
停止v1 helper。focus默认isolated，API34+保护主屏输入，但息屏无层级且虚拟屏无IME；
steal可息屏读层级，但会打断主屏输入法；API33 focusIsolated=false，不能承诺隔离。
空屏返回DISPLAY_EMPTY，无窗口输出返回WINDOWS_UNAVAILABLE，不回退主屏。

详细命令、窗口列表形态、恢复与验收入口见[虚拟显示操作](virtual-display.md)。

静态图片摄像头是独立的操作员启用路径：Go建立或复用精确shell伴生关联并读回编号，
仅CLI的stop可选保留关联，默认仍移除；启动失败根据Created只回收本次新建的关联，
既有/崩溃遗留保留并公开结果。Java退出不解除关联；摄像头先移除显示、用CameraRelease共享6秒预算等流关闭再注销相机/设备。
BUSY保留所有权与出帧，ShutdownCompletion仅在安全拆除成功后允许退出；Go遇BUSY不清文件/关联。
创建失败残留保留引用并公开计数，待释放屏只可诊断/重试关闭，不能作为输入/取帧目标。
AndroidBackend仍串行，只读请求可能等待局部排空预算；b6956真机关闭复验待负责人重启后执行。
helper 为每块 camera:back 屏创建一个 VirtualDevice 和后置 VirtualCamera，逐流 EGL
将有界图片等比绘入输入面。普通屏仍使用原 DisplayManager；摄像头屏返回实际显示标志，
不复用普通屏的焦点/IME/息屏验收结论。协议仍 v2，以 virtualCamera 能力位扩展；MCP
只增加显示创建的 camera 枚举，图片路径/启停/相机授权不开放给模型，见[摄像头契约](virtual-camera.md)。

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
删除等语义动作要求当次明确确认。默认只用语义摘要；用户显式允许截图且取得有效授权时，
ProviderSessionPlanner才创建有界视觉observation、向Provider发送图像/上下文，验证包、图像摘要、
候选和有效期后执行，并重新观察。临时图片副本与lease在finally清零/关闭；
不能把通用parser接受某动作等同于App AI风险策略已放行。

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
4. **App 到 Provider**：默认发送脱敏且有长度上限的语义摘要；显式截图授权可允许
   有界图像/observation上下文，使用后清零副本。API Key 由
   Android Keystore AES/GCM 密钥加密，但 Provider 仍是外部数据处理方。
5. **Agent 边界**：MCP 和 Skills 只获得类型化能力，不获得 ADB 配对、
   Accessibility 授权、Bridge token 或任意 shell。

## 当前边界

- CLI已有有界 devices watch及可信重连；N35 macOS/Windows断线恢复矩阵仍未完成。
- CLI不能编辑录制脚本；recording list只返回脱敏摘要，MCP回放仍拒绝执行。
- CLI语义观察使用bridge snapshot/foreground/windows/messages；android_observe现有8种kind：
  screenshot、hierarchy、foreground、apps、semantic、windows、transientMessages、recordings。
  前四种走原始ADB（截图也可走helper）；后四种使用已建立Bridge，语义树受脱敏/目标包边界约束。

### 3. 桌面视觉候选

```text
MCP visual propose -> 明确 serial/package/target
  -> device info -> hierarchy -> screenshot -> hierarchy -> device info
  -> 双 hierarchy 与设备身份稳定门
  -> PNG 尺寸/rotation + 脱敏 UIAutomator tree -> 短期 visual observation
  -> hierarchy-derived template candidate -> ImageContent + structuredContent
  -> transport 写出完成后撤销 observation 并清零服务端图片
```

该路径只提出候选，`actionCommitCount` 固定为 0，不执行 tap、swipe 或其他动作。
password 节点及其后代不返回 label；普通 `android_observe kind=hierarchy` 是原始
UIAutomator XML，不具有该视觉 observation 的脱敏与同轮稳定保证。
- 普通Bridge观察不回传PNG；获授权debug disposable emulator的visual.action.execute请求
  可携带有界PNG及attested证据，校验包/摘要/有效期后执行并验证，finally清零图像。
- 协议动作集合大于当前 Bridge 执行集合；能力必须以协商结果和实际错误为准。
- 已有桌面只读视觉候选、原子视觉动作以及显式授权的App视觉执行端口；完整真实App的
  通用自愈产品/兼容矩阵仍未验收。蓝牙、Root、Shizuku、Device Owner、OCR、设备农场
  和内置互联网远控尚未实现。
- 仓库当前没有面向 Google Play 的裁剪变体；完整自主 AI 版本不应按 Play
  合规版本分发，见[安全与分发](security-distribution.md)。

## 参考

- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [MCP 规范](https://modelcontextprotocol.io/specification/2025-11-25)
- [Agent Skills 规范](https://agentskills.io/specification)
