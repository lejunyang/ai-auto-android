# N32 Progress

本文件 append-only，记录实现、测试、设备证据、失败与清理结果。

## Round 1

- 已核对分支 `phase2-n32-test-control` 位于共享 Android 集成基线 `e7a5716`，
  worktree 开工时干净；N33/N34 在独立互斥 worktree 并行。
- 已阅读 MVP 规格、备选方案、N32 路线图、N31 runner 和现有 Bridge、录制及
  debug accessibility 代码。
- 安全设计确定为无 Android 依赖的 token 内核加 debug/androidTest 适配层：
  每次授权尝试先消费 token，绑定 scope、显式 emulator serial、N31 fingerprint、
  debug App 与 instrumentation 双签名、固定 marker 和 debug variant。
- 当前仅创建规格与 RED 测试；共享 Gradle 注册和 App 依赖由主线程串行集成，
  不作为 core 单元测试的阻塞条件。

## Round 2

- core 测试先按预期在 `compileTestJava` 因安全类型尚不存在而 RED；实现后覆盖 token
  过期、重放、scope 越权、N31 profile、真机 serial、双签名、marker、release、
  随机摘要碰撞、有界清理、一次性秘密和 release APK 扫描。
- 使用未提交的临时 Gradle init script 注入 `:test-control-core` 与 App debug/test
  依赖，`compileDebugKotlin`、`compileDebugAndroidTestKotlin`、debug、androidTest
  和 release APK 构建通过；没有修改共享 Gradle 文件。
- release APK 自包含扫描为 0 findings；`aapt2` 只报告 `INTERNET` 与 AndroidX
  动态 receiver 自权限，Manifest 中无 N32 adapter、debug 测试服务、test marker
  或 `WRITE_SECURE_SETTINGS`。
- API 30 首次设备验收使用 N31 runner 启动明确 serial `emulator-5574`，runner
  聚合 fingerprint、API 和 WebView 校验通过，`aactl` 确认仅该 emulator 在线。
- API 30 instrumentation 明确失败：1 test / 1 failure；本地 attestation 与两项
  `Settings.Secure` 写入读回未抛错，但
  `ScreenshotTestAccessibilityService.awaitConnected` 在 15 秒后超时，Bridge 和
  snapshot 尚未执行。失败不作为验收证据，API 33/34 暂停。
- 失败后已通过 N31 runner 停止 `emulator-5574`；`aactl devices list` 为 0，
  `runtime/` 0 文件，AVD/port lease 0 项，未执行 `adb kill-server` 或手工删锁。
- API 30 第二次设备验收修复了首轮服务连接问题：instrumentation 使用
  `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，按 `0 -> service list -> 1`
  更新并轮询 Secure 值、AccessibilityManager 列表和服务 callback，测试已越过
  无障碍连接、脚本预置和 Bridge 启动。
- 第二次仍为 1 test / 1 failure：客户端的 `InetAddress.getLoopbackAddress()`
  在 API 30 解析为 IPv6 `::1`，而 Bridge 按安全设计绑定 IPv4 `127.0.0.1`，
  因此 snapshot 前连接被拒绝。失败后 runner 停止 `emulator-5578`，再次确认
  0 devices、0 runtime、0 lease；API 33/34 继续暂停。
- API 30 第三次使用协议固定 IPv4 loopback 后通过，XML 为 1 test / 0 failure；
  覆盖本地 profile attestation、一次性 test token、scope、设置三层确认、测试服务
  连接、无敏感脚本预置、只读 Bridge capability、一次性配对、`ui.snapshot`、
  session close、自检和 finally 恢复。
- API 30 成功后 runner 停止 `emulator-5580`，`aactl` 再次返回 0 devices，
  `runtime/` 0 文件且 lease 0 项；API 33/34 在此清理证据后解除暂停并继续串行。

## Round 3

- API 33 在明确 serial `emulator-5582` 上通过 1/1 instrumentation，N31
  fingerprint、Android 13 profile、本地设备 attestation、测试服务、只读 Bridge
  snapshot 和设置恢复均通过；测试耗时 2.886 秒。
- API 34 在明确 serial `emulator-5584` 上通过 1/1 instrumentation，使用对应 N31
  fingerprint 和 Android 14 profile；完整只读闭环耗时 1.244 秒。
- 三个 API 的成功轮均由 N31 runner 从 `clean` snapshot 启动，使用显式 serial，
  测试后关闭 Bridge、销毁一次性配对码和 token、删除预置脚本并恢复原 Secure
  settings。每台 stop 后 `aactl devices list` 为 0，runtime 与 AVD/port lease
  均为 0；未执行共享 `adb kill-server`。
- core 最终 32/32 通过：profile attestation 4、一次性 secret 2、policy 5、
  release inspector 4、token authority 17。release APK 自包含扫描输出
  `Release APK test-control surface: 0 findings`。
- `make comments` 覆盖 230 个手写文件并通过 14 个检查器自测，`git diff --check`
  通过。共享 Gradle 注册仍由主线程在本提交集成后串行完成。
