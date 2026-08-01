# N47 Test-only 授权 Provider 进度

本文件 append-only，记录 RED/GREEN、验证和主线程设备证据缺口。

## Round 1

- 基线为 `main` 的 `ec0c596`，独立分支
  `feat/n47-test-authorization-provider`，worktree
  `/Volumes/aigo S7 Media/Projects/ai-auto-android-n47-test-authorization-provider`。
- 主工作区干净且只有 `main` worktree；本 change 创建后未修改主工作区。
- 已确认 N47 fixed production fixture runner 为 73/73，默认 provider 因
  `PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE` 在 N31 start 前失败关闭。
- 既有 `test-control-core` 已固定 N31 aggregate/build fingerprint、marker、debug
  双签名和一次性 token；`SecureAccessibilitySettings` 已具备 finally 恢复原 secure
  settings，但现有 Bridge 只读且生命周期封装在单次 device test 内。
- 本 change 将先以失败测试固定 signer/token、host 固定 argv、专用 instrumentation
  endpoint 与 teardown，再实现 debug/androidTest-only 授权链；本 worker 不操作设备。

## Round 2

- 新增固定授权内核、不可扩展场景 grant 和严格单行控制请求解析；固定 token、固定
  debug signer、N31 aggregate/build fingerprint、marker、显式 emulator serial 和
  场景目标包任一不符均失败关闭，错误与 `toString()` 不回显秘密。
- 新增唯一 instrumentation 端点
  `ProductionFixtureAuthorizationDeviceTest`。端点只绑定设备 loopback `38484`，
  保存 App 目标包设置与系统无障碍设置，启用真实
  `AiAutomationAccessibilityService`，启动完整 `DesktopBridgeController`，并在固定
  stop 请求后先关闭 Bridge、恢复 App 设置，再由 `SecureAccessibilitySettings`
  恢复系统设置。
- Host provider 固定构建并校验 debug App/androidTest APK、双 signer、安装 argv、
  instrumentation class/参数、control forward 和 `aactl bridge open/close`；pairing
  code 只经 stdin 传入。setup readiness 仅在连接前允许有界重连，写出请求后的未知
  结果不重放。
- Production fixture port 已在 emulator 启动前构建 androidTest APK，并把 N31 owned
  runtime 中的真实 build fingerprint、当前 run ID 与仓库外临时 `aactl` 路径传入
  provider。CLI 默认接入 concrete provider，不再使用 unavailable stub。
- Teardown 会关闭可能已提交的 aactl session、发送固定 stop、等待 instrumentation
  正常完成、移除 control forward，并重新读取当前 serial 的 forward 列表确认
  `38383` 与 `38484` 均无残留。
- 定向 Node provider/ports/runner 为 15/15；N47 runner 为 73/73。Core JVM 固定授权
  与严格协议测试、`compileDebugAndroidTestKotlin`、`testDebugUnitTest`、
  `lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 均通过。
- Release 构建通过，`verifyReleaseApk` 输出
  `Release APK test-control surface: 0 findings`。`make comments` 覆盖 473 个手写
  文件及 14 个检查器自测；`make test`、`make verify`、`make build` 和
  `git diff --check` 通过。
- 本轮未操作任何设备。API 30/33/34 production fixture 真实矩阵仍由主线程在集成后
  串行运行，因此 `docs/next-phase-tasks.md` 未修改，N47 仍不得标记完成。
