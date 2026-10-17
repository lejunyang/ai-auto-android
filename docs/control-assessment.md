# Android 核心控制与远程能力评估

评估基线：2026-10-17，main `7cbbf049`；仅云端隔离 fixture、loopback socket 与构建验证。
未操作真实设备、未创建凭据、未启动公网服务；未修改 one-sdk 检出。

## 能力与证据

| 面向用户的能力 | 当前实现 | 边界/缺口 |
| --- | --- | --- |
| USB / Wi-Fi ADB | `internal/adb/client.go`、`mdns.go`、`trusted.go`；显式 serial，配对码 stdin，可信重连与身份/型号复核 | RSA、无线调试与配对由设备用户完成；不是互联网设备代理 |
| 目标选择 | `internal/adb/parser.go`、`direct.go`；检查在线状态再以 `-s` 调用 | serial 不是统一跨主机 target ID；transport_id 已解析但动作尚未绑定该代连接，检查与执行间仍有竞态 |
| 截图 / UI 检查 | `internal/adb/direct.go` 校验 PNG/XML、预算与摘要；`internal/bridge/service.go` 语义快照/窗口/前台/瞬时消息 | 原始 ADB 产物可能敏感；WebView/Canvas 没有通用 DOM/CDP；不能等同 desktop browser use |
| 输入 / 应用控制 | `internal/service/service.go`、`internal/adb/device_state.go`、`composite_gesture.go` | ADB text 受字符限制；Unicode、语义动作、多指手势需要 App/无障碍；输入时序需真实平台复验 |
| Bridge 会话 | `internal/bridge/{service,store,client}.go`；设备 loopback、一次性码、短期 token、原子持久化、关闭/到期回收 | 同一 OS 账户下按 serial 存一份会话；不是多租户授权模型。Windows 依赖用户目录 ACL，Unix mode 不能证明 Windows ACL 安全 |
| LAN | `internal/bridge/lan`、App `bridge/lan`、`LanBridgeRpcDispatcher.kt`；X25519/HKDF/AES-GCM、transcript、方向/序列、防重放、私网明确绑定、到期清理、独立 token | App 可复用 Bridge dispatcher；`internal/cli/lan.go` 只开放 device.info/recording.list 探针，然后关闭。尚未接入 service/MCP、截图与长驻路由 |
| MCP | `internal/mcpserver/{server,schemas,risk}.go`；类型化工具、严格 schema、共享服务、确认限制 | 配对/权限与会话建立留给人工 CLI；回放工具保持确认错误，不应为接入统一工具而放开 |
| 录制/观察 | App recording 与 replay、`internal/adb/watch.go` | 本轮保持低优先级；语义事件不代表原始触摸记录，未重新验证历史设备矩阵 |

README 的 MVP 描述落后于 LAN/视觉等实现，不能据此声称完全没有远程代码；同样不能把
已存在的加密 transport 视为已经交付完整远程产品。

## 已确认并修复

| 优先级 | 问题 | 修复与回归证据 |
| --- | --- | --- |
| P1 | Go LAN 握手及加密帧 `ReadBytes` 在换行之后才检查大小，未认证的握手对端也能持续扩大缓冲 | 分片读取时执行预算，超限立即失败；`io_limits_test.go` 不发送换行、不主动断开来验证及时拒绝 |
| P1 | LAN context 取消不能打断阻塞的读/写；方向共享 deadline 可相互覆盖 | 取消关闭 socket 并清零会话、读写各自 deadline；取消前/中四个场景与 race 验证 |
| P1 | 握手使用 bufio 预读后丢弃 reader，首个加密 frame 可能随交接丢失 | 交接同一 reader；`handshake_buffer_test.go` 真实 transcript/密钥派生，单次 Write 合并确认与首帧 |
| P1 | UI hierarchy 所有调用共用 `/data/local/tmp/aactl-window.xml`；dump 失败不清理，可能留下敏感 XML | 每次观察 UUID 路径，dump 前注册有界清理；路径隔离与失败清理回归 |
| P1 | Actions Android 安装缺少显式许可证参数 | 用发布版 osdk 0.0.6 原样复现错误；CI/release 限定安装构建工具并显式指定许可证，不移除测试 |
| P2 | 历史 artifact/matrix Windows job 的 19 个安全用例因相对路径分隔符断言失败；开发 Node 20 不满足 test-lab engines >=24 | 断言使用 path.join，仍严格检查只留下 summary；Node pin 对齐 24.19.0 |
| P2 | N52 Windows 把 Node stat.mode 当作 POSIX 权限，0666 与 0600 比较必然失败 | 将 POSIX mode 检查单列为 Linux/macOS 测试；三平台均保留原子替换、旧报告保护与失败清理断言。Windows DACL 未验证 |
| P2 | Android CI 的 SessionCoordinatorTest 把活跃 engine mock 为立即返回，后台终态清理与断言竞态 | 改为长期挂起 mock 与虚拟协程调度；保留无终态返回测试，停止超时推进精确 5 秒验证 |
| P2 | Windows POSIX 包没有平台限定；普通安装下载仅设备测试需要的镜像 | released osdk 的 `when` / `lazy`；固定 CI 的 osdk installer+binary 为 v0.0.6，避免漂移 |

原失败日志：[Android job 117460500882](https://github.com/lejunyang/ai-auto-android/actions/runs/39054809364/job/117460500882)。
`--yes` 不代表同意 Android license，失败发生在编译前；不能把原 CI 失败归因于 Kotlin 代码。

## 后续优先级及共享契约提案

1. **P1，远程持续控制**：先在父线程对齐 target/session 契约，再将已认证 LAN session 作为
   BridgeBackend transport 接入 service/MCP。需要有会话所有权、操作员撤销、忙碌冲突、
   到期与断网失效，不能把 LAN token 复用为 loopback token。截图也需要独立能力和预算。
2. **P1，目标与观察绑定**：统一逻辑 target 应包含 host/agent、平台、设备标识和连接代次；
   动作带 observation ID、采集时间、坐标空间/尺寸/旋转、前台包与连接代次。对 stale、
   断线或结果不明返回稳定错误和 `committed/unknown/notCommitted`，不自动重复有副作用动作。
3. **P2，desktop/browser/mobile 互通**：共享 discover → capabilities → observe → typed action →
   close 语义；保留 Android package/selector、桌面 display/window 和 browser DOM/tab 的
   平台扩展。能力不足明确返回 unsupported，不将 Android Accessibility 节点伪装成 DOM。
4. **P2，会话并发**：Bridge store 当前按设备覆盖；多个独立控制器 open/close 需要持有者/锁
   或隔离 session ID。清理失败后的 forward 重试、连接代次验证与跨进程竞争需单独 change spec。
5. **P3，录制回放与事件观察**：统一上述基础控制后再收敛事件模型和 replay，保持确认/敏感动作边界。

这些是提案，没有实施跨仓库破坏性协议修改，也未假定 one-sdk draft PR 4 的功能存在。

## 远程配置与验证限制

短期可使用 README 的 SSH stdio 配置：在已授权的远端主机启动 `aactl mcp serve`，让
ADB server、forward、token store 与设备在同一主机/账户内。SSH 的身份验证、加密和
host key 校验由既有 SSH 配置负责，不新建凭据。该方式不需要公开控制端口，但不是
内置设备农场、多租户网关或已测端到端产品。

不应使用远端 `ADB_SERVER_SOCKET` 代替 transport 设计：ADB forward 端口在 ADB server
所在主机，当前 RPC client 固定拨本机 loopback；doctor 也固定探测本机 5037。裸 ADB server
本身不应作为公网认证边界。

本轮测试及 CI 结果见 PR；USB/Wi-Fi RSA、真机输入/截图、Android 权限交互、两机 LAN、
真实 SSH、Windows ACL 与 macOS/Windows 硬件路径均未在此云环境实际验证。历史验收文档
保留原结论，不将本轮纯云端结果扩展为硬件兼容结论。
