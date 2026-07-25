# N38 Progress

本文件 append-only，记录 RED/GREEN 证据、安全失败路径和共享集成边界。

## Round 1

- 已确认 worktree `phase2-n38-android-lan` 干净且基于 N36 提交 `9d5017e`。
- 已读取 N36 Schema、协议文档、1 个合法 fixture、18 个威胁 fixture 和密码向量。
- 当前先落盘 Kotlin RED 测试；生产领域实现、公共导航、Manifest、Gradle 和具体相机
  provider 尚未修改。

## Round 2

- RED 测试先行证据：N36 invitation 契约、密码向量和扫码权限状态机测试落盘后，
  `:app:compileDebugUnitTestKotlin` 因 `LanInvitationParser`、`LanCrypto`、
  `PlatformX25519`、`LanPairingStateMachine` 等生产领域 API 尚不存在而失败。
- 首次命令未进入编译，因为 shell 未设置 JDK；改为显式使用
  `/Volumes/aigo S7 Media/Projects/SDK/jdk-temurin-21.0.7+6/Contents/Home`、
  Android SDK 36 和独立 `gradle-cache-n38` 后取得有效 RED 结果。

## Round 3

- 首批实现后 14 项定向测试有 3 项失败：canonical JSON 的 Kotlin
  `joinToString` 默认分隔符带空格、非法 X25519 编码被错误映射为语义错误。修正为无
  空格 canonical JSON，并在 Schema 层先校验 base64url 形状后，14/14 通过。
- 第二批出站会话 RED 测试落盘后，编译因 `LanOutboundCoordinator`、
  `LanSocketConnector`、`LanReplayStore`、`LanNetworkIdentity` 和会话类型尚不存在
  而失败；此前已通过的 parser、密码和 UI 领域实现未被回退。

## Round 4

- 实现严格 invitation parser/preflight、显式候选/本地网卡/短指纹确认门、平台
  X25519、HKDF-SHA-256、角色分域 confirmation 和 AES-256-GCM framed transport。
- Kotlin 直接消费 N36 的 1 个合法 invitation、全部 18 个威胁 fixture 和完整密码
  向量；Schema、语义错误码、fingerprint、transcript、四个派生 key、双方 tag 和
  非零低阶点结果一致。
- 出站协调器只依赖注入式 socket、网卡、时钟、密钥协商和重放端口。preflight 或用户
  确认失败时 socket 尝试为零；地址不可达、transcript/tag 错误、切网、过期、进程
  恢复和写失败均关闭会话、禁止后续动作且没有明文降级。
- 重放存储使用同路径进程锁、OS 文件锁和原子替换，一次性原子 claim invitation ID
  与 nonce；磁盘只保存 SHA-256 摘要和过期时间，进程恢复仍拒绝，过期记录有界清理。
- 扫码和手工输入通过同一 provider-neutral 端口；相机拒绝或无相机状态保留手工路径。
  UI 状态只含网卡、短指纹、过期时间和错误码，失败、停止或新输入清理解析摘要与确认。

## Round 5

- 提交前安全审查修正 UUID common Schema 兼容范围为版本 `1` 至 `8`，增加 UUID v7
  合法测试；移除地址预检中的潜在 DNS 路径，并在 socket 前拒绝完整低阶点 blacklist。
- `LanInvitation` 增加明确、幂等的 `close` 生命周期：parse 后续失败清零已分配 nonce
  与公钥；握手失败和 session stop/过期/切网/写失败均清零并丢弃原始 JSON。测试验证
  失败与成功路径字节归零。
- `LanOutboundSession.send` 同步 active 检查、序号分配、加密和 socket 写入；并发测试
  验证完整 frame 写不会交错。严格输入端口在 UI state 外持有一次性 invitation，并在
  明确确认后只移交一次。
- 最终 N38 定向测试 33/33 通过：contract 7、crypto 4、session 9、replay store 3、
  state 8、strict input 2，均为 0 failure/error/skip。
- 最终 App 全量单元测试 232/232 通过；`make comments` 覆盖 219 个手写文件并通过
  14 个检查器自测；`git diff --check` 通过。
- 未修改 Manifest、Gradle、version catalog、公共导航、Accessibility、loopback
  Bridge、协议或路线图。真实 N37 同 LAN、相机权限声明、具体扫码 provider、公共导航
  和设备验收必须由主线程串行集成，当前不得描述为已验收。
