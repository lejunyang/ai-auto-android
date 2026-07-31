# N37/N38 真实跨运行时 LAN RPC 规格

## 目标

在 N31 管理的 disposable emulator 上，让生产 Go listener/session 与生产 Kotlin
outbound session 通过真实 TCP socket 完成加密握手、多轮只读 Bridge RPC、致命认证
关闭和 `session.close`，补齐 N37/N38 仅有各端自动化测试而缺少跨实现证据的问题。

## 范围

- 成功路径必须使用生产 `aactl bridge lan listen`、生产 Go `Session.Call` 和生产
  Android LAN coordinator/session。
- 致命认证路径使用仓库内 test-only Go host，在完成真实生产握手后发送一个格式合法
  但不匹配当前 session 的 LAN token，并验证 Android 加密返回 `AUTH_INVALID` 后关闭。
- Android 入口只存在于 `androidTest`，由 N31 profile、本地设备事实和 debug 双签名
  证明约束，不增加 release 组件、权限、Manifest 或生产命令。
- 主机只向 instrumentation 传递公开 invitation 的 canonical base64url；token、
  临时私钥、derived key、tag、plaintext 和 ciphertext 不进入参数、报告或日志。
- API 30、33、34 均从 `clean` snapshot 运行；每个场景后恢复快照，最终停止 owned
  emulator 并确认设备、runtime、lease 和临时文件为零。

## 非目标

- 不自动授权相机、无障碍、MediaProjection、ADB RSA 或未知来源。
- 不开放任意 RPC 方法、错误 token 参数、任意 host、任意 Gradle task 或任意 ADB
  shell 给用户命令面。
- 不把 emulator NAT 路径描述为 macOS/Windows 真实 Wi-Fi、Windows 防火墙或 OEM
  真机兼容证据。
- 不修改 N36 wire schema、密码套件、token 公式、frame 格式或 release 行为。

## 允许修改

- `.trae/specs/n37-n38-cross-runtime-rpc/`
- `.trae/specs/n37-persistent-lan-rpc/`
- `.trae/specs/n38-persistent-lan-rpc/`
- `docs/next-phase-tasks.md`
- `android/gradle/libs.versions.toml` 与 `android/app/build.gradle.kts`
- `android/app/src/main/java/dev/aiauto/android/bridge/lan/LanCrypto.kt`
- `android/app/src/test/java/dev/aiauto/android/bridge/lan/LanCryptoVectorTest.kt`
- `android/app/src/androidTest/java/dev/aiauto/android/bridge/lan/`
- `scripts/lan-interop/`

## 验收

- 每个 API 在同一真实 socket 上连续完成至少三次 `device.info`，结果 API 与当前
  N31 profile 一致，随后 `session.close` 双方确认并清理。
- 每个 API 另建新 invitation/session，test-only host 收到加密 `AUTH_INVALID`
  response，并观察 Android 随后关闭连接；错误 token 不输出。
- orchestration 只接受 `api-30|33|34`、显式非 tunnel 接口 ID 和该接口的私网 IP。
- 定向 Node/Go/Android 测试、`make comments`、`git diff --check` 通过；最终再运行
  仓库和 Android 全量验证。

## 失败与清理

- 任一握手、frame、响应、设备身份或网络状态不匹配立即停止当前场景，不盲目重试。
- 失败时终止本任务创建的 host，恢复当前 N31 clean snapshot，再停止 owned emulator。
- 不执行 `adb kill-server`、强制删除 worktree、手工删锁或清理其他任务的设备。
