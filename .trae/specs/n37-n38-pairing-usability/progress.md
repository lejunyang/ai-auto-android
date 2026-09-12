# N37/N38 配对可用性与内置扫码进度

本文件 append-only，记录 RED/GREEN、协议兼容和真机证据。

## Round 1

- API 34 OPPO 真机已证明旧流程的主要可用性问题：120 秒会在扫码、切页、地址/网卡
  和抄写短指纹期间过期；自动轮换 invitation 又会让手机持有旧 fingerprint。
- 手工码与受信 F-Droid ZXing 两条路径都已完成真实认证、同一 socket 三次
  `device.info` 与 `session.close`，说明密码协议和网络实现可用，问题集中在交互流程。
- 外部 ZXing `4.7.8(108)` targetSdk 22，只能在 Android 14 兼容模式运行；当前信任
  定义为固定 package/class/exported/enabled 加唯一当前/历史 F-Droid signer。该边界
  能防止重签冒充，但不应继续作为现代 Android 主路径。
- 本 change 选择 App 内置 CameraX + ZXing Core：系统相机权限仍由用户人工确认，
  图像只在内存中本地解码。协议默认 TTL 提升到 300 秒、上限 600 秒；唯一地址和
  手机网卡自动选择，用户只进行一次短指纹视觉核对和连接确认。
- 用户明确停止 N35 无线 ADB 验收，原因是当前网络代理环境阻断 mDNS/连接；本 change
  不继续重试无线调试。

## Round 2

- RED 证明旧 TTL 上限仍为 120 秒、CLI 默认仅 90 秒；协议 Schema、Node preflight、
  Go invitation/CLI 和 Kotlin parser 已统一为 15 至 600 秒，生产 CLI 默认
  invitation/accept timeout 均为 300 秒。
- 状态机与 ViewModel 已让唯一桌面候选和唯一手机 LAN 网卡自动选择；多候选仍不猜测。
  UI 删除短指纹重复输入和第二个连接按钮，只保留一次“我已核对短指纹并连接”，切换
  候选、失败、停止和恢复仍撤销确认。
- 生产 App 已改为 CameraX 1.5.3 + ZXing Core 3.5.4 内置扫码，不再查询外部 ZXing
  Activity 或 signer。相机只在用户点击扫码后请求；帧仅在内存解码，正常、反色和
  0/90/180/270 度 QR 有单测，灰度副本在 analyzer 与 decoder 两层清零，
  `ImageProxy` 始终关闭。
- `aactl bridge lan listen ... --terminal` 直接在当前终端渲染纯 Go、无 CGO 的
  UTF-8 二维码；与 `--json` 互斥，终端摘要不输出 invitation JSON、manual code、
  nonce 或公钥。本机明确网卡的 1 秒 smoke 输出 76 行、22571 字节二维码，随后以
  `LAN_ACCEPT_TIMEOUT` 关闭且不创建网页或二维码文件。
- 自动验证通过：Node invitation 10/10、协议 52 项、LAN/CLI 普通与 race、Go 全量、
  `make test`、`make verify`、`make build`、中文注释门禁、Android 全量 unit、
  androidTest Kotlin 编译、lint、debug 和 release 构建。API 34 真机内置扫码与完整
  RPC/停止/清理复验尚未执行，因此不提前关闭真机验收项。

## Round 3

- API 34 OPPO 真机首次暴露三个 CameraX/Compose 集成问题：默认预览实现显示白屏、
  `AndroidView` 缺少横向尺寸约束，以及相机暂态异常帧会提前结束 analyzer。生产实现
  已分别强制 `PreviewView.ImplementationMode.COMPATIBLE`、补齐全宽约束，并把单帧
  异常收敛为丢弃当前帧后继续扫描；对应 LAN 单测、AndroidTest Kotlin 编译和 debug
  构建通过。
- 真机进一步证明相机打开时的配置变化会让普通 `remember` 丢失 `scanning` 状态，
  表现为扫码页无错误返回空白配对表单。状态改为 `rememberSaveable` 后，配置变化后
  扫码预览持续存在。
- 用户人工授予 App 相机权限并使用内置 CameraX + ZXing Core 扫描同一 N36
  invitation；扫码后只需点击一次“我已核对短指纹并连接”，未输入短指纹或长确认码。
  生产 Go listener 在同一认证加密 socket 上返回三次 `device.info` 成功和
  `session.close` 成功，脱敏摘要为 `rpc=3`、`closed=1`。
- 二维码展示适配了远程 TRAE 客户端限制：CLI 的 UTF-8 字符二维码会被界面折叠，
  因此验收时将同一 invitation 临时渲染为固定 1200×1200、1-bit PNG；该 PNG 先经
  ZXing Core 3.5.4 离线完整还原 804 字节 invitation，再由真机内置扫描器识别。
  二维码、临时 CLI、解码工具和摘要均在连接完成后删除，未提交或保留协议内容。
