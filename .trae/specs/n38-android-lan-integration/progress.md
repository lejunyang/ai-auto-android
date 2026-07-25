# N38 Android LAN Integration Progress

本文件 append-only，记录 RED/GREEN 证据、真实生产接线和仍需设备验收的边界。

## Round 1

- `phase2-n38-android-integration` worktree 基于 `3e79bf9` 且开工时干净。
- 审计确认 N38 已有严格 parser、preflight、X25519/HKDF/AES-GCM、重放存储、状态机
  和 Compose 页面，但 `LanSocketConnector` 仍无真实实现，App 导航也未接入。
- 审计 N37 wire 后确认握手与加密传输均为有界 NDJSON；现有 Android
  `LanEncryptedFrame` 缺少版本、方向、序号和类型，无法编码合法 N37 frame。
- 本轮先增加 wire、网络和 ViewModel RED 测试，再修改生产实现。真实相机扫码、同
  LAN N37 互操作、切网、防火墙和 API 设备矩阵仍明确待验收。

## Round 2

- RED 编译在新增测试后按预期因 `NdjsonLanSocketPort`、完整 frame 元数据、
  `LanPairingViewModel` 和 session launcher 缺失而失败；环境写缓存权限问题先单独
  修正，未把环境错误记为 RED 证据。
- 审计 N37 wire 后修正 Kotlin frame 的真实互操作缺陷：nonce 前缀从整数 `1/2`
  改为 `C2D\x01/D2C\x01`，AAD 增加固定 domain、transcript、方向、序号和类型的
  NUL 分隔；独立固定 ciphertext 断言锁定字节结果。
- 新增 64 KiB 严格握手和 1 MiB + 64 KiB 加密 frame NDJSON port，覆盖分片读取、
  重复 JSON key、UTF-8、超限、完整 wire 字段、同步写入和失败关闭。
- 新增 Android 网络目录和 connector：只列出带私网/链路本地/IPv6 ULA 地址的非
  VPN Wi-Fi/以太网，socket 先经用户选择的 `Network.bindSocket` 再连接 numeric
  IP literal，连接前后及每次动作前复核同一网络身份。

## Round 3

- 新增生命周期 ViewModel、App 导航和桌面 Bridge 页面入口。手工 invitation 是当前
  真实入口；未接入受测扫码 provider，因此 Manifest 不声明 `CAMERA`，页面明确提示
  provider 不可用而不触发空权限请求。
- ViewModel 在候选、本地网卡和短指纹确认前不启动 executor/socket；CONNECTING 后
  冻结选择，停止或销毁会主动关闭 in-flight connector、session、pending invitation
  和 executor。并发测试验证迟到 session 被关闭且不会重新发布为 connected。
- Manifest 仅新增普通 `ACCESS_NETWORK_STATE` 以支持网络枚举。首次 lint 复用了
  修正前报告；merged manifest 已确认权限后以 `--rerun-tasks` 强制重算并通过。
- 最终 N38 定向测试 43/43 通过，包含 N36 fixture/vector、固定跨端 frame 密文、
  localhost NDJSON、网络过滤/绑定、重放、出站会话、状态机和 ViewModel。
- App 全量 JVM 单测、debug/release assemble、`lintDebug`、`make comments` 和
  `git diff --check` 通过。SDK XML 版本、`getAllNetworks` 弃用和既有 lint warning
  不影响本轮门禁。
- 真实扫码、N37 同 LAN wire 互操作、持续双向 RPC、切网/防火墙/OEM 和 API 设备
  矩阵仍未执行，主路线图 N38 必须保持未完成。

## Round 4

- 实现提交 `fa3d6da`（`feat(android): integrate lan pairing flow`）包含 N38 生产
  socket、跨端 frame 修正、网络绑定、ViewModel、导航、测试和本 change spec。
- 提交 author/committer 均为 `lejunyang <lejunyang@qq.com>`，消息含且仅含一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`；提交后 worktree 干净。
- 提交前最终 App JVM 319/319、N38 定向 43/43、debug/release assemble、
  `lintDebug`、`make comments` 和 `git diff --check` 均通过。
