# N37/N38 配对可用性与内置扫码规格

## 目标

把 N37/N38 从“120 秒内扫码、手选唯一候选、再次抄写短指纹、再点击连接”的冗长流程，
收敛为可在高版本 Android 稳定使用的内置扫码和一次明确人工确认：

1. App 内使用 CameraX 采集预览帧，ZXing Core 仅在本地解码 QR，不依赖外部旧版扫码
   App，不上传、不保存图像。
2. 用户点击“打开内置扫码”后由 Android 系统请求 `CAMERA`；拒绝时保留手工码路径，
   不自动授权或引导降低其他系统安全设置。
3. invitation 默认有效 300 秒，协议允许范围为 15 至 600 秒；过期、重放、篡改和
   TTL/timestamp 不一致仍失败关闭。
4. 当桌面地址候选和手机非 VPN LAN 网卡都唯一时自动选择；多候选时仍要求用户明确
   选择，不猜测。
5. UI 展示桌面短指纹，用户点击一次“我已核对短指纹并连接”即完成确认和连接；
   不再重复手工输入 invitation 自带的同一短指纹。
6. 桌面 CLI 提供 `--terminal` 模式，直接在当前终端渲染 UTF-8 二维码和安全摘要；
   本地网页只作为可选辅助，不再是展示二维码的必要条件。

## 安全边界

- 短指纹仍作为独立视觉核对门；扫码后不得静默连接。
- 用户确认按钮只在 invitation、地址和手机网卡全部明确时可用。
- 切换地址或网卡会撤销此前确认；进程恢复、失败和停止不会恢复确认或 session。
- `CAMERA` 只用于 App 内扫码页面；不后台采集、不创建文件、不录音、不上传。
- 分析帧在单次调用后关闭，临时 luminance byte array 主动清零；只保留通过严格
  invitation parser 产生的安全摘要。
- 手工邀请码仍进入同一严格 parser/preflight，作为无相机或权限拒绝的降级路径。
- `--terminal` 与机器可读 `--json` 互斥，终端摘要不输出 invitation JSON、手工码、
  nonce、公钥或其他协议内部字段。

## 允许修改

- `.trae/specs/n37-n38-pairing-usability/`
- `protocol/schema/v1/lan-invitation.schema.json` 与对应 fixture/validator 测试
- `internal/bridge/lan/`、`internal/cli/` 及对应 Go 测试
- `android/gradle/libs.versions.toml`
- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/dev/aiauto/android/bridge/lan/`
- `android/app/src/main/java/dev/aiauto/android/ui/bridge/lan/`
- 对应 Android unit/androidTest
- N37/N38 与 `docs/next-phase-tasks.md` 的 append-only 进度证据

## 禁止修改

- loopback Bridge、Accessibility、动作白名单、第三方 App 场景、N47/N52
- 自动授予相机权限、绕过 OEM 提示、保存原始帧或二维码
- 在 release 中静默扫码、静默选择多候选或静默连接
- 提交 APK、二维码、invitation、短指纹、真机 serial 或 session secret

## 验收

- RED 覆盖新 TTL 上限、默认 300 秒、唯一候选自动选择、一次确认连接、确认撤销、
  相机权限拒绝和纯本地 QR 解码。
- Go LAN/CLI 定向与 race、协议全量、Android LAN/UI/decoder 定向通过。
- App unit、lint、debug、androidTest、release 和 `make comments` 通过。
- 生产 CLI 在真实单地址 listener 上直接输出有界终端二维码，并在超时后清理端口和
  invitation；不创建 HTML、图片或其他临时二维码文件。
- 真机由用户手动授予相机权限，完成内置扫码、一次确认、三次只读 RPC、
  `session.close` 和显式停止。
- 清理外部 ZXing 临时 APK 与所有 `/private/tmp` helper，不要求用户保留旧扫码 App。
