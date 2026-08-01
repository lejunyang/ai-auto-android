# N47 Test-only 授权 Provider 规格

## 目标

为 N47 固定 production fixture runner 提供仅存在于 debug/androidTest 的 Bridge 与
Accessibility 授权入口。Host 只能为 N31 管理的 `api-30`、`api-33`、`api-34`
disposable emulator 建立短生命周期授权，使 native、WebView、Canvas 本地 fixture
可复用既有 semantic、input 与 attested visual production 端口。

本 change 只建立可供主线程调用的固定 provider，不操作设备，不形成 API 30/33/34
设备验收证据，也不勾选 N47。

## 安全身份

- Host CLI 仍只接受固定 `--profile api-30|api-33|api-34`，不接受 serial、APK、
  package、测试类、token、命令或路径参数。
- Provider 的 `probe` 只验证仓库内固定 profile、debug App APK、debug androidTest
  APK、测试 token 与工具路径可用，不启动 emulator、不安装 APK。
- `setup` 必须绑定 N31 返回的显式 `emulator-*` serial、三套固定 aggregate/build
  fingerprint、`clean` marker 和 profile/API 对应关系。
- Android 端从本地设备事实、实际 App 与 instrumentation APK 当前 signer 重建身份；
  两个 APK 必须单 signer、摘要相同且命中本 change 固定 debug test signer。
- 固定 test token 不从 CLI、环境变量、文件或 stdin 接受替代值；Android 端使用常量时间
  摘要比较，错误与输出不得回显 token、pairing code、Bridge session token 或 signer。
- 真机、release、未知 serial、未知 fingerprint、错误 build fingerprint、错误 marker、
  未知 signer、错配 profile/API、重复或未知 endpoint 命令全部失败关闭。

## 固定生命周期

1. production port 构建固定 `app-debug.apk`、`app-debug-androidTest.apk` 和三个 fixture
   debug APK，并确认输出存在。
2. N31 `startClean` 返回可信身份后，provider 仅以固定 ADB 参数数组和显式 serial 安装
   App 与 androidTest APK。
3. Provider 启动唯一专用 instrumentation test。Instrumentation 先保存原
   `enabled_accessibility_services` 与 `accessibility_enabled`，再以最小
   `WRITE_SECURE_SETTINGS` shell identity 启用 debug test service。
4. Instrumentation 启动可执行 `ui.snapshot`、`action.execute` 与 attested visual
   action 的 loopback Bridge，输出单行严格 JSON readiness envelope；pairing code
   只经 instrumentation stdout 交给当前 host 调用栈。
5. Host 使用固定 `aactl bridge open --device <serial> --json` argv，并把 pairing code
   通过 stdin 传入；不得放入 argv。
6. `stopScenario` 只停止固定 fixture 包；`closeBridge` 先关闭 aactl session/forward，
   再向 instrumentation endpoint 发送固定 stop 控制，等待其 finally 恢复原 secure
   settings、关闭 Bridge、撤销 token 并退出。
7. `inspect` 只接受当前 session 身份，检查 instrumentation 进程、Bridge forward、
   debug service 与本地 session 均为零。Production runner 的既有 finally 继续清
   App data、restore `clean` snapshot、停止 owned emulator 并检查八类残留。

成功、失败、取消、超时与 setup 中途失败均必须执行 teardown；不得调用共享
`adb kill-server`，不得移除非当前 serial/本 provider 创建的 forward、进程或文件。

## 类型化命令边界

- 不提供 shell 字符串、任意 argv、任意 instrumentation class/method 或任意 package。
- ADB 只允许实现内固定参数数组：
  `install --no-streaming -r <fixed-apk>`、固定 `am instrument`、固定
  `am force-stop`、固定 `pm clear`、固定 `forward --list`。
- Gradle、`aactl` 与 ADB 均使用 `execFile`/既有 `runCommand`，`shell:false`，有界
  stdout/stderr 与固定 timeout；所有设备命令显式 `-s <serial>`。
- Host 严格解析唯一 readiness/finish envelope，拒绝未知字段、重复 JSON key、尾随
  JSON、超预算输出、成功 stderr、身份漂移与旧 run ID。

## 允许修改

- `.trae/specs/n47-test-authorization-provider/`
- `android/test-control/core/`
- `android/app/src/debug/`
- `android/app/src/androidTest/` 中专用 test-control/authorization
- `test-lab/runner/src/production-fixture-ports.mjs`
- `test-lab/runner/src/production-fixture-runner.mjs`
- 对应定向测试和必要的固定 debug instrumentation/host provider 文件

## 禁止修改

- `docs/next-phase-tasks.md`、N48-N52、Go production、公共 protocol、SDK/cache、
  workflow、release 行为与真机授权
- 不新增 release permission、receiver、provider、service、activity 或 test-control
  class；release APK 静态扫描必须保持零入口
- 不把 JVM、Node、编译或静态扫描结果描述为设备验收，不勾选 N47

## 验收

- RED 先覆盖 capability 从 unavailable 变为 concrete provider、固定 APK/install/
  instrumentation/aactl argv、token 与 signer、真机/release/fingerprint/marker 拒绝、
  严格 endpoint、setup 中途失败和全路径 finally。
- GREEN 后 N47 全包保持 73/73，新增 provider 测试通过；Android core JVM 与
  `compileDebugAndroidTestKotlin` 通过。
- `assembleRelease` 后执行 `:test-control-core:verifyReleaseApk`，零 test-only 入口。
- 运行 `make comments`、`git diff --check`、`make test`、`make verify`、`make build`
  及 Android unit、lint、debug、androidTest、release 构建。
- 最终只列主线程可运行的 API 30/33/34 固定命令和仍缺设备证据，N47 保持未勾选。
