# N38 Persistent LAN RPC Progress

本文件 append-only，记录 RED/GREEN 证据、安全失败路径和并行集成边界。

## Round 1

- 已确认独立 worktree `/private/tmp/ai-auto-n38-rpc` 位于分支
  `n38-persistent-lan-rpc`，开工时干净；Go N37 位于另一独立 worktree。
- 已读取 `docs/next-phase-tasks.md` N38、`.trae/specs/n38-android-lan-outbound/`、
  Android LAN 握手/frame/session、`BridgeDispatcher` 与 `NdjsonBridgeServer`。
- 共享线契约固定为 `bridge.request` / `bridge.response`；LAN token 使用 Go N37 的
  HMAC-SHA-256 domain、transcript、invitation ID 和 UTC RFC3339Nano 过期时间公式。
- 当前只创建 change spec 并准备 RED 测试；生产实现尚未修改，协议、Go、路线图、
  Manifest、权限、相机和 Accessibility 均未修改。

## Round 2

- RED 测试先落盘后，`:app:compileDebugUnitTestKotlin` 因 `LanSessionToken`、
  `LanFrameReceiver`、`LanBridgeRpcDispatcher`、`LanOutboundSession.serve` 和
  `LanSocketPort.readEncrypted` 尚不存在而失败；生产实现随后才开始。
- 实现严格加密 NDJSON frame 读取、desktop-to-client 单调序号、方向、nonce、AAD、
  GCM tag、type、大小和 canonical base64url 校验。重复、乱序、篡改、错误方向或
  metadata 均失败关闭并清零可控 frame/plaintext 缓冲。
- LAN token 与 Go N37 使用相同 HMAC-SHA-256 domain、transcript、invitation ID 和
  UTC RFC3339Nano 过期时间输入，常量时间验证且不进入 loopback `BridgeSessionManager`。
- 同一 session 持续处理 `bridge.request` / `bridge.response`，复用 Bridge 严格
  request schema、`RequestReplayCache`、deadline 和 `BridgeMethodHandler` 风险门；
  `rpc.hello` / `session.open` 在已认证 LAN transport 上拒绝。

## Round 3

- 审查后将 `LanBridgeRpcDispatcher` 收缩为单线程 executor 和同步 `dispatch`，即使
  调用方并发提交，同一 socket 的 handler 最大并发仍为 1，请求和响应严格按 frame
  sequence 顺序完成。
- `BridgeDispatcher` 大改被收缩：public `dispatch` 主体不变，只通过最小
  `BridgeSessionAuthority` 隔离 LAN 凭据、增加预认证 route，并抽取原 protected
  method switch；`BridgeSessionManager` 方法体和 loopback 行为不变。
- `AUTH_REQUIRED`、`AUTH_INVALID`、`AUTH_EXPIRED` 均视为 LAN transport 致命错误；
  测试验证 Android 先发送一个加密错误 response，再关闭 socket 并停止后续动作。
- 强制定向 `BridgeDispatcherTest`、`NdjsonBridgeServerTest`、LAN dispatcher 和全部
  Android LAN 测试共 64/64 通过，0 failure、0 error、0 skip。

## Round 4

- 主线程完成最终强制验收：App JVM 全量、`lintDebug`、`assembleDebug`、
  `assembleDebugAndroidTest` 和 `assembleRelease` 共 134 个 Gradle tasks 通过。
- `make comments` 通过中文注释覆盖与 14 个检查器自测；`git diff --check` 通过。
- 未修改协议 Schema/fixture、Go、公共路线图、Manifest/权限、相机、Accessibility
  授权或 release 绕过；未记录 token、key、nonce、ciphertext 或 plaintext。
- 剩余未完成项是 Go N37 与 Android N38 在真实跨实现加密 socket 上进行多轮 RPC、
  致命认证关闭、deadline、`session.close` 和切网/过期互操作。当前 Kotlin/Go 各端
  自动化证据不能替代该跨实现验收。

## Round 5

- 单一职责提交 `ce50e1b` 已以等价集成提交 `fccd6b9` 落入 `main`。集成提交的
  Author 与 Committer 均为 `lejunyang <lejunyang@qq.com>`，提交末尾恰好一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`。
- 提交、身份、trailer、集成和干净 worktree 状态已闭合；真实 Go/Kotlin socket
  多轮 RPC 继续保持未勾选，不以两端各自自动化证据代替。

## Round 6

- 独立 change `n37-n38-cross-runtime-rpc` 已在 API 30/33/34 N31 clean emulator
  上完成真实 Go/Kotlin TCP socket 互操作。每个 API 先在同一 session 连续处理三次
  `device.info` 与 `session.close`，再以独立 session 验证加密 `AUTH_INVALID`
  response 和致命关闭。
- API 30 首轮设备证据暴露 Android 11 缺少平台 X25519 provider；同一 RFC 7748
  契约的 Tink fallback 修复后，API 30 portable 路径与 API 33/34 JCA 路径均和
  Go 生产实现互操作。该证据不替代相机扫码、切网、真机/OEM 或 Windows 防火墙矩阵，
  N38 路线图主任务仍保持未完成。
