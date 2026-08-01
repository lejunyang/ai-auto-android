# N37/N38 二维码生产闭环进度

本文件 append-only，记录二维码/扫码测试先行证据、安全边界、验证和真机缺口。

## Round 1

- 独立 worktree `/private/tmp/ai-auto-n37-n38-qr` 位于分支
  `n37-n38-qr-integration`，基线 `9acb54f`，开工时干净；并行 N44 worktree 未读取
  或修改。
- 现状审计确认 N37 核心已有 payload-only `QRProvider`，但生产 `DefaultApp` 未注入
  provider，CLI 也不输出 representation 数据；N38 已有 scanner 状态与严格输入端口，
  但 Compose 权限回调为空且生产固定 `PROVIDER_UNAVAILABLE`。
- 审计额外发现 N37 输出的 `AIAUTO1-` base32 `manualCode` 与 N38 当前直接 JSON
  parser 不兼容，现有“手工邀请码”生产路径不能消费桌面输出，必须在同一严格输入端口
  解码后再进入既有 parser/preflight。
- N37 固定为纯 Go、无 CGO、无 GUI/临时文件的 `terminal-utf8-v1` QR Model 2
  representation；N38 固定为受信显式外部 scanner Activity contract，不在本 App
  声明无用途 `CAMERA`，相机授权由 scanner 自身处理，拒绝/取消后仍可手工输入。
- 本任务不操作设备、模拟器、SDK 或 APK，也不修改 `docs/next-phase-tasks.md`。

## Round 2

- Go RED 先因 `NewTerminalQRProvider`、`QRFormatTerminalUTF8`、`qrText` 和固定边界
  常量缺失而编译失败；Android RED 先因 scanner contract、可信组件选择、
  `updateScannerCapability` 与 `onQrScanResult` 缺失而编译失败。外置 cache 的初次
  sandbox 写入失败只作为环境问题处理，未冒充 RED。
- Android RED 同时锁定 `AIAUTO1-` 手工码与扫码 JSON 必须进入同一严格端口、原始
  payload 不进入状态、扫码成功后连接尝试数仍为零、取消和无相机仍保留手工路径。
- Go RED 锁定二维码确定性、finder/function/format/version/data 布局、最大 payload、
  context 取消和 CLI 默认生产注入。

## Round 3

- N37 新增纯 Go、无 CGO 的固定 QR Model 2 Version 28-L encoder，包含 byte segment、
  13 个 Reed-Solomon block、BCH format/version、alignment/timing/finder pattern、
  8 种 mask 评分、4-module quiet zone 和 UTF-8 半块 renderer。
- `aactl bridge lan listen` 默认注入 provider，并在原 invitation 事件输出
  `qrGenerated=true`、`qrFormat=terminal-utf8-v1` 和有界 `qrText`；不调用外部命令、
  不打开 GUI、不创建文件。
- 仓库内独立数据区 decoder 可精确还原 payload。另在 `/private/tmp` 创建不入库的
  `gozxing` 验证模块，实际读取生产 representation，得到 Version 28、EC L、mask 2、
  1921 codewords，并字节一致解码 1157-byte N36 invitation。
- 独立 decoder 首轮 `ChecksumException` 暴露 alignment function map 顺序缺陷；
  修复按标准保留全部非 finder 冲突的 alignment pattern 后才取得 GREEN，未以自写
  decoder 结果代替跨实现证据。
- QR 超出固定容量时返回 `LAN_QR_CAPACITY_EXCEEDED`，invitation 只降级为
  `payload-only`，`manualCode` 和 listener 继续可用；context 取消或非容量 provider
  错误仍失败关闭并清理已绑定端口、临时私钥和缓冲。

## Round 4

- N38 只发现精确受信组件
  `com.google.zxing.client.android/.CaptureActivity`，并在启动前固定为显式 Intent、
  `QR_CODE_MODE` 和静态提示；未安装、禁用、未导出、非受信或歧义 handler 均不启动。
- App 明示相机硬件和外部 provider 状态。相机权限由外部 scanner 自身申请，本 App
  不声明 `CAMERA`、不自动授权；拒绝、取消、无相机、无 provider 或启动竞态失败时
  手工输入始终可用。
- scanner 只接受 `QR_CODE`/`QR_CODE_MODE`、非空且 UTF-8 不超过 64 KiB 的结果；
  回调后删除返回 Intent 的 result/format/error extras。扫码 JSON 与桌面
  `AIAUTO1-` base32 手工码进入同一严格 parser/preflight，解析后状态只保留安全摘要。
- Compose 手工输入上限按 64 KiB JSON 的完整 base32 长度计算。扫码后仍停在 REVIEW，
  必须由用户选择桌面地址、手机网卡并再次输入短指纹，release 不静默连接。

## Round 5

- Go LAN 62 项、CLI 82 项全部通过；`go test -race` 覆盖 LAN、CLI 与 `cmd/aactl`
  且无 race。
- Android LAN 42 项、LAN UI/scanner 25 项、App JVM 总计 369 项通过，0 skip、
  0 failure、0 error；`lintDebug`、debug、androidTest 和 release 共 134 个 Gradle
  tasks 全部执行并通过。
- release merged Manifest 只有 `INTERNET`、`ACCESS_NETWORK_STATE` 和 AndroidX
  自权限，并包含受信 scanner query，零 `CAMERA`；release DEX 不含 scanner 测试类、
  N36 fixture 或测试 secret。
- `make test`、`make verify`、`make build`、`make comments` 和
  `git diff --check` 全部通过；协议 52 项、3 个 Skills 与 repository metadata
  门禁通过。改动只位于允许范围，未加入 APK、二维码、邀请、密钥、缓存或构建产物。
- 自动化证据不替代真机：仍需用户安装受信 ZXing provider、人工处理其相机权限，并在
  真实屏幕/终端上验证扫码、拒绝后手工码、短指纹核对和真实 Wi-Fi 会话。

## Round 6

- 功能提交 `db2234f0b070d4349937300f507dd08026e8a238` 使用
  `feat(bridge): complete lan qr pairing flow`，包含 change spec、N37 终端二维码、
  N38 scanner/手工码闭环、测试和既有 N37/N38 append-only 证据。
- Author 与 Committer 均为 `lejunyang <lejunyang@qq.com>`；提交消息末尾恰好一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`。提交路径审计未发现越界文件，
  `git show --check` 通过，功能提交后 worktree 干净。

## Round 7

- 集成审查指出仅校验 package/class/exported/enabled 无法阻止恶意重签同包冒充；
  signer RED 先因 `LanQrScannerSigningIdentity` 与 candidate signer 字段缺失而按预期
  编译失败，随后才增加生产签名门。
- 本回合从 F-Droid 官方仓库下载
  `https://f-droid.org/repo/com.google.zxing.client.android_108.apk` 到
  `/private/tmp`。`aapt` 确认 package `com.google.zxing.client.android`、版本
  `4.7.8(108)`，以及 `.CaptureActivity` 暴露
  `com.google.zxing.client.android.SCAN`；APK SHA-256 为
  `2ed4c2661ed0e2e56b2980d59291dacd58040d219cb7e83b3f6db1102d2ed483`。
- Android SDK 36 `apksigner verify --verbose --print-certs` 确认 APK `Verifies`、
  signer 数量为 1、DN 为
  `CN=FDroid, OU=FDroid, O=fdroid.org, L=ORG, ST=ORG, C=UK`，certificate
  SHA-256 为
  `1f97ed3c5800111d4627d53512bb38102fda0385c45f763b4b92d6341d29f1ad`，
  public key SHA-256 为
  `86c4cf4cd5b664612c3eb07f58a80966e0efca0d085f2437680794855d82170a`。
- 生产 allowlist 只接受上述 F-Droid certificate SHA-256；未知 Play/GitHub signer
  不接受。discovery 要求唯一 current signer 且 current/history 均恰好为该证书；
  无 signer、错误 signer、多 current signer、仅历史命中、轮换 history、签名或
  component query API 异常均失败关闭为 provider unavailable，手工码继续可用。
- 使用从该 APK 提取的公开 DER certificate 驱动成功测试；同包错误签名、缺失签名、
  多 current、历史 signer、证书 byte 读取异常及 PackageManager 异常均有负向测试。
  signer/LAN UI 定向通过；最终 App JVM 375/375、`lintDebug`、debug、androidTest、
  release 共 134 tasks 通过，`make comments` 与 `git diff --check` 通过。
- APK、DER、PEM、PKCS#7 与 release API 临时文件只存在于 `/private/tmp`，提交前删除；
  仓库不保存或重新分发 APK，`docs/next-phase-tasks.md` 未修改。
