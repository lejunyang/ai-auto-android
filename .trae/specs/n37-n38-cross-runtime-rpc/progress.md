# N37/N38 真实跨运行时 LAN RPC 进度

本文件 append-only，记录设计、RED/GREEN、设备矩阵、清理和集成证据。

## Round 1

- 主分支已包含 N37 `ad59f7f`、N38 `fccd6b9` 和文档收口 `98adfcd`，但现有证据
  仅覆盖 Go `net.Pipe`、Kotlin fake socket/JVM 与两端各自真实 localhost，不满足
  change spec 中的跨 Go/Kotlin 真实 socket 要求。
- 当前 `aactl doctor` 健康且设备列表为空；主机唯一合格非 tunnel 接口为
  `if-18-en1 / 192.168.31.16`。本轮不把该实时接口值写死到仓库，runner 必须由
  操作者显式传入并由生产 CLI 再次验证。
- 设计固定为两条 session：生产 CLI 连续三次 `device.info` 后 `session.close`；
  test-only Go host 发送格式合法但错误的 LAN token，要求 Android 加密返回
  `AUTH_INVALID` 并关闭。Android 只新增 androidTest，不修改 release/UI/权限。

## Round 2

- API 30 首轮在 socket 前失败：Android 11 的 provider 不提供
  `KeyPairGenerator("X25519")`。现有 JVM/API 33+ 测试未覆盖该真实平台差异，失败轮
  已恢复 clean snapshot 并确认 0 devices/runtime/lease/temp。
- 修复保持 N36 `X25519-HKDF-SHA256-AES-256-GCM` wire suite 不变：优先使用平台
  X25519；若 generator、factory 或 agreement 缺失，则使用 Tink 1.18 public
  `X25519` 字节 API。两条路径继续执行七个低阶点和全零 secret 拒绝，私钥均可销毁。
- 新增 JCA 与 portable 双边各计算一次 shared secret 的一致性测试。LAN crypto、
  outbound session、persistent RPC 定向测试和 androidTest 编译通过；release APK
  只新增生产 crypto fallback，互操作 test class、参数名和 test-only marker 为零，
  权限集合未增加。

## Round 3

- API 30 第二轮在 socket 前返回 `LAN_INVITATION_NOT_YET_VALID`。N31 `clean`
  snapshot 的 guest wall clock 固定在 2026-07-25，而主机 invitation 生成于
  2026-08-01；N36 明确要求早于 `issuedAt` 必须拒绝，因此生产行为正确，失败轮同样
  0 残留。
- instrumentation 只通过已有可注入 `LanClock` 使用 invitation `issuedAt + 1s`，
  隔离 frozen snapshot clock；不放宽生产 TTL，也不替换真实 socket、握手、密钥、
  frame 或 Bridge dispatcher。后续生产/真机验收仍必须使用可信且同步的系统时钟。
- API 30 第三轮协议本体已 1/1 通过，但 runner 在 `emulator.stop` 后立即观察到 ADB
  短暂残留 serial，返回 `DEVICE_SELECTION_MISMATCH`；数秒后复查为 0。最终门改为
  stop 后最多等待 20 秒直至 `aactl devices list` 为零，仍不接受实际残留。

## Round 4

- API 30 `emulator-5570`、API 33 `emulator-5572`、API 34 `emulator-5574` 均从
  N31 `clean` snapshot 串行运行，profile fingerprint 分别为
  `51ec5c4a...2707`、`082695b5...f86f`、`1ac57e68...ea8f`。
- 每个 API 的第一条真实 TCP session 使用生产 `aactl bridge lan listen`、生产 Go
  listener/handshake/framer/`Session.Call` 和生产 Kotlin coordinator/session，
  在同一 socket 上连续完成三次 `device.info`，结果 API 与 profile 一致，再由
  `session.close` 双方确认关闭。
- 每个 API 的第二条独立真实 TCP session 由固定 test-only Go host 在生产握手后发送
  格式合法但错误的 LAN token；Android 加密返回 `AUTH_INVALID` 后关闭 peer，错误
  token、key、tag、plaintext 和 ciphertext 均未进入输出或报告。
- 三份外置报告位于 `AACTL_EMULATOR_STATE/reports/n37-n38-interop-api-*.json`，
  权限均为 `0600`。最终 `aactl` 为 0 devices，N31 runtime、AVD/port lease 和
  `lan-interop-*` 临时目录均为零。

## Round 5

- runner 参数与输出解析 Node 测试 5/5、test-only Go host 构建/测试通过；
  `make test`、`make verify`、`make build` 全部通过，覆盖协议 52 项、Skills 与
  Go 全包/race-independent 门禁。
- Android App JVM 全量、`lintDebug`、`assembleDebug`、
  `assembleDebugAndroidTest` 和 `assembleRelease` 共 134 个 Gradle tasks 通过。
  LAN crypto 定向覆盖 JCA/portable shared secret 一致、两条路径七个低阶点拒绝、
  outbound session 与 persistent RPC 回归。
- release 全部 DEX 静态扫描确认生产 Tink X25519 fallback 存在，而
  `LanCrossRuntimeDeviceTest`、互操作参数、test-only marker 和 test host 均为零；
  权限仍只有 `INTERNET`、`ACCESS_NETWORK_STATE` 与 AndroidX 动态 receiver
  自权限。仓库和全部 Git 历史无 APK/APKS/AAB/XAPK 路径。
- `make comments` 覆盖 388 个手写文件并通过 14 个检查器自测；`git diff --check`
  通过。最终 doctor 健康，设备、runtime、lease 和临时目录再次确认为零。
