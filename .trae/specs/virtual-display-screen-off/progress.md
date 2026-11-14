# 虚拟显示与息屏运行进度

本文件 append-only，记录前提核实、RED/GREEN 与验证结果。

## Round 0：可行性实测与立项（2026-11-14）

- 基线 `f861a61`，开工时 `git status` clean，`git worktree list --porcelain` 只有
  主工作区。worktree `E:/Projects/wt-virtual-display`，分支
  `virtual-display-screen-off`。
- 用户决定实施 N59 第 3 项「多显示屏」，并要求先做虚拟显示与息屏运行。
- 在 AVD `osdk-test-35`（API 35，`-read-only -no-snapshot`，serial
  `emulator-5554`）上用一次性探针实测，结论见 `spec.md` 的「实测依据」。探针不是
  仓库产物，未提交。
- 实测中的两个反例直接决定了设计：
  - 主屏息屏或面板断电后 `screencap` 返回全黑帧，所以虚拟屏的画面必须由辅助
    进程自己提供。
  - 设备被 PIN 锁定后两个显示组都进入 Dozing，虚拟屏帧全黑，且向虚拟屏发
    `KEYCODE_WAKEUP` 唤醒的是主屏。所以锁定状态定为明确报错，不做唤醒尝试。
- 本轮没有任何实现，`tasks.md` 与 `checklist.md` 全部未勾选。

## Round 1：T1 设备端辅助进程（2026-11-14）

- 开工基线 `57e32d1`；worktree `E:/Projects/wt-virtual-display`、分支
  `virtual-display-screen-off`，工作区干净。另有主工作区 `main`，未修改。
- 已读完本规格四份文件、AGENTS.md、fixture 构建配置、注释检查器和仓库外探针；
  仅实施 T1，不开始 T2/T3/T4，不复制探针原样入库。
- RED 预期：先添加 `ProtocolTest` 12 项与 `PolicyTest` 11 项 JVM 测试，生产入口
  暂抛 `UnsupportedOperationException("RED")`。预期请求解析、尺寸/dpi/数量、
  uid 白名单、消息/帧上限、API 标志与凭据锁定策略测试失败，再实现纯逻辑。
- RED 实际：经 Makefile 同款 `osdk exec -t java -t android-platforms
  -t android-build-tools -t android-platform-tools -- ./android/gradlew.bat -p android
  --no-daemon :displayd:testDebugUnitTest`，23 tests completed, 23 failed；全部是
  预期占位异常，退出码 1（BUILD FAILED in 1m 18s）。
- 首次模块 GREEN：26 项通过（再补 3 项编码流/响应预算测试），模块 assembleDebug、
  lintDebug 成功。随后补当前凭据锁与普通滑动锁区别测试，共 27 项。
- 首次设备接入：`hello` 成功，进程在启动 shell 返回后存活（PID 3761）；
  `display.create` 返回 `FRAMEWORK_ERROR`，停止后续动作并读取日志，显示仅有主屏，
  没有残留虚拟屏。代码与 AOSP 对照发现 ActivityThread 在无 Looper 的 worker 初始化，
  其内部立即创建 Handler；改为在主 Looper 初始化，并补框架异常完整堆栈。
  旧进程通过 `shutdown` 返回 `stopped:true`，随后 `pidof` 无输出。
- 契约补充：API 33 的 IPowerManager 只有全局 `isInteractive()`，API 34+ 才提供
  `isDisplayInteractive(displayId)`。因此 API 33 的 `groupAwake` 如实返回 null，
  `hello.capabilities.groupAwakeQuery=false`；不以全局/主屏状态冒充独立组状态。
  这是 T1 状态查询在 API 33 上的已知限制，API 33/34 仍待设备验证。
  依据：[AOSP API 33](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android13-release/core/java/android/os/IPowerManager.aidl)、
  [AOSP API 34](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-gsi/core/java/android/os/IPowerManager.aidl)。
- 主 Looper 修正后全链路成功：PID 3951；displayId=2、720×1280、240 dpi、
  displayGroupId=1、state=2、groupAwake=true，保活时间戳递增且错误为 null。
  Settings 在 display 2 前台，主屏仍为 NexusLauncherActivity；PNG 78827 字节，
  解码 720×1280，921600/921600 像素非黑，目视确认 Settings 首页。
  第二块屏 displayId=3 可列出并关闭；第三块屏返回 DISPLAY_LIMIT；捕获主屏 0
  返回 DISPLAY_NOT_FOUND。shutdown 后仅剩主屏，进程、forward、设备临时文件均无残留，
  `emu kill` 后设备列表为空，无 emulator/qemu 进程。
- 提交前产物审查发现 AGP 9 自动注入 Kotlin 标准库，已在本模块设置
  `enableKotlin=false`，避免纯 Java 进程携带第三方运行依赖。增加 JSON 非法转义与
  解析期尺寸拒绝测试，最终应为 29 项；重跑 Android 验证与最终产物冒烟。
- 最终 GREEN：29 项（ProtocolTest 14、PolicyTest 12、FrameOutputTest 3），全部通过，
  无跳过。`make comments` 通过：571 个手写文件、16 个检查器正反例；
  `make android-verify` 通过（完整主 App/fixture/displayd 单测、assembleDebug、lintDebug；
  全部 607 项、0 失败/错误、1 项既有 App 测试跳过）。最终构建后模块 lint 为
  0 errors / 4 warnings（2 个必要隐藏接口反射、无安装用途的 backup/icon 提示），
  未关闭任何 lint 检查。`make test`、`make verify`、`make build` 均退出 0，
  `git diff --check` 通过。
- `:displayd:dependencies --configuration debugRuntimeClasspath` 明确输出
  `No dependencies`；复用仓库已有 JUnit 仅用于 JVM 测试。移除旧增量 APK 后重封装，
  最终产物 43427 字节（未提交），SHA-256
  `bfc3bb1a7d1ddb59e305242f70b55e9dc6b938f3dea0a6b14039d99ead4272d6`，
  设备端 `sha256sum` 一致。
- 最终产物原始 adb 冒烟：
  - 以 `emulator -avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio`
    后台启动；操作前唯一在线设备 `emulator-5554`，`sys.boot_completed=1`，API 35，
    google_apis x86_64；320×640、160 dpi、en-US、navigation_mode=2、无快照。
    指纹 `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`。
    未修改 AVD/system image/osdk、屏幕超时、导航、语言或锁屏 PIN。
  - 使用 README 中的 `CLASSPATH=... nohup app_process / --nice-name=aactl-displayd
    dev.aiauto.displayd.Main </dev/null >... 2>&1 &`；启动 shell 返回后独立连接见
    PID 3827，日志 `displayd ready socket=aactl-displayd-v1 pid=3827`。
  - `adb -s emulator-5554 forward tcp:0 localabstract:aactl-displayd-v1` 分配 52380；
    电脑侧临时 Node 标准库客户端连接该端口，无 Go 客户端、无额外依赖。
  - `hello`：protocolVersion=1、apiLevel=35、supported=true；
    `display.create`：displayId=2、720×1280、240 dpi；`display.list` 列出该屏。
  - `adb -s emulator-5554 shell am start --display 2 -a android.settings.SETTINGS`：
    SettingsHomepageActivity 在 display 2，主屏仍为 NexusLauncherActivity。
  - `display.capture`：JSON 头 bytes=78827，随后恰好等长二进制 PNG；解码尺寸
    720×1280、非黑像素 921600/921600。前次同内容 PNG 已目视确认为 Settings 首页。
  - `status`：credentialLocked=false、state=2、displayGroupId=1、groupAwake=true、
    lastActivityUptimeMs=267612、keepAwakeError=null；`shutdown`：stopped=true。
  - 退出后 pidof 无输出，dumpsys display 的 Display Devices: size=1，仅剩 display 0；
    删除 tcp:52380 后 `forward --list` 空；删除设备 APK/日志后 ls 明确 No such file。
    `adb -s emulator-5554 emu kill` 返回 OK，随后复核设备与进程退出。
- 边界与未验收项：API 33 的 groupAwake=null 是上述明确记录的契约限制；API 33/34、
  真机/OEM、PIN 锁定端到端、15 秒超时下 60 秒保活、主屏息屏与输入/层级链路、无线 ADB
  存活均未验收，保持对应项未勾选，未开始 T2/T3/T4。锁定决策仅以 JVM 单测验证。
- 最终清理复核：`adb devices -l` 为空，无 emulator/qemu 进程；仅删除本模块
  build/smoke 内本轮临时 PNG、客户端、DEX 检查副本与日志，保留忽略的构建 APK。
  变更仅为 displayd 模块、settings 注册和本规格 T1 状态；无 APK、截图、缓存、密钥或
  构建产物加入提交，不修改 main，不 push。T1 完成后停止。

## Round 1 复核：集成方独立验证（2026-11-14）

- 复核对象 `b6fd7d0`。提交范围只含 `android/displayd/`、`android/settings.gradle.kts`
  与本规格状态文件，无二进制；主工作区 `main` 未变，未 push。
- 独立重跑：`make comments` 通过（571 个手写文件）；`:displayd:testDebugUnitTest
  assembleDebug lintDebug` BUILD SUCCESSFUL，JUnit XML 为 29 项 0 失败 0 跳过。
- 独立设备复测：AVD `osdk-test-35`、`-read-only -no-snapshot`、唯一设备
  `emulator-5554`、同一指纹。客户端是集成方另写的一次性 Node 脚本，未复用 T1 的
  冒烟脚本。进程属主为 `shell`，握手、建屏、取帧（78827 字节）成立；主屏 0 取帧
  返回 `DISPLAY_NOT_FOUND`，越界尺寸返回 `INVALID_ARGUMENT`，未知方法返回
  `UNKNOWN_METHOD`，多余字段返回 `INVALID_REQUEST`。
- 提前验证了属于 T4 的两个场景（不据此勾选，T4 仍须按脚本复现）：
  - `screen_off_timeout=15000`、主屏 `KEYCODE_SLEEP` 后等待 45 秒：显示组 0 为
    Dozing、显示组 1 为 Awake，`status.groupAwake=true`；对虚拟屏点按后层级变化，
    取帧 73182 字节非黑。保活成立。
  - 设置测试 PIN 并息屏：`display.capture` 返回 `DEVICE_LOCKED`，
    `status.credentialLocked=true`。约 6 秒后虚拟屏显示组转为 Dozing。测试 PIN 已清除。
- **发现缺陷（必须在 T2 之前修复）**：清除 PIN 并解除锁屏后，
  `credentialLocked=false`、`lastActivityUptimeMs` 恢复递增，但虚拟屏显示组保持
  Dozing（`groupAwake=false`），此时 `display.capture` 返回 `ok:true` 和一张
  6358 字节的全黑帧。也就是说，只要虚拟屏显示组因任何原因休眠，取帧就会静默给出
  黑图，且 `userActivity` 上报无法把已休眠的组唤醒。这与规格「返回明确错误而不是
  黑图」的要求不符。
- 另记两处需在后续任务处理的风险：
  - 抽象套接字名固定，设备上的任意应用可以抢先绑定同名套接字并伪造响应；电脑侧
    经 `adb forward` 无法校验对端身份。Go 侧必须先确认名为 `aactl-displayd`、属主
    为 `shell` 的进程存在且由本次或已知启动产生，再信任该套接字。
  - `Main.accept()` 在拒绝连接时 `client.close()` 若抛出 `IOException` 会落入外层
    处理并使整个辅助进程退出。
- 清理：`shutdown` 返回 `stopped:true`，`pidof` 无输出，无残留虚拟屏与转发，设备
  临时文件已删，模拟器已关闭。

## Round 2：T1b 休眠拒帧与拒绝连接隔离（2026-11-14）

- 开工核对 clean，HEAD=d76e054，分支 virtual-display-screen-off；两处 worktree 与用户描述
  一致，未写主工作区。完成全部前置阅读后只修改 android/displayd 与本规格状态文件。
- RED：新增 5 项回归测试，临时沿用原策略接线；`:displayd:testDebugUnitTest` 实际为
  34 tests completed, 3 failed。失败分别为组休眠但 Display 仍 ON、API 33 非 ON、
  拒绝连接 close 抛 IOException。锁定错误优先级与 API 33 未知组状态用例通过。
- GREEN：编码前后检查凭据锁、显示有效性、按屏 interactive（API 34+）和 Display 状态，
  组为 false 或屏非 ON 返回 DISPLAY_ASLEEP，不携带二进制体。拒绝连接统一使用已测试的
  closeRejected，close IOException 不会逃到 accept 外层。API 33 继续返回未知组状态，
  仅可拒绝非 ON，无法保证识别 state=ON 但组休眠的黑帧，README 明确保留此限制。
- 恢复结论：明确错误 + 关闭重建，不自动唤醒。调查 AOSP android15-release 的
  PowerManagerService.wakeUp，DEVICE_POWER 路径固定 DEFAULT_DISPLAY_GROUP；
  applyWakeLockFlagsOnAcquireLocked 可按显示组，但依赖 TURN_SCREEN_ON/兼容策略。
  本次 shell targetSdk=35，有 DEVICE_POWER、WAKE_LOCK，无 TURN_SCREEN_ON；未引入依赖
  兼容豁免的唤醒路径，更没有在凭据锁定期间尝试唤醒。
  来源：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/power/PowerManagerService.java
- API 35 原始 adb 复测（一次性有界 Node 标准库客户端，非 CLI 验收）：唯一设备
  emulator-5554，启动参数与任务书一致，boot_completed=1；320×640、160 dpi、
  navigation_mode=2。AVD osdk-test-35，google_apis x86_64，指纹
  google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  未修改超时、AVD/system image/osdk 配置。构建 APK SHA-256 与设备端一致：
  e3f5350b5736e84ac97a560a0966e2942f6e7319d3f25c4ff94945951f0f3364。
  - shell 进程 PID 3790；forward 分配 tcp:61534；hello v1，建屏 2，720×1280/240dpi。
  - 启动 Settings 后取帧 78827 字节；设置测试 PIN 后 KEYCODE_SLEEP，
    credentialLocked=true 时 capture 返回 DEVICE_LOCKED。
  - 观察到 groupAwake=false 后清 PIN，KEYCODE_WAKEUP、wm dismiss-keyguard；
    credentialLocked=false、state=2、groupAwake=false、保活时间戳恢复递增，
    capture 返回 DISPLAY_ASLEEP，未返回旧缺陷中的 6358 字节黑帧。
  - close 2、create 3、重新启动 Settings，groupAwake=true；78827 字节 PNG，
    720×1280，已目视确认为正常 Settings 首页。关闭的应用内容须重新启动，README 明示。
- 验证：make comments 通过（571 文件、16 个自测）；make android-verify 通过，
  displayd 34 项，全 Android JUnit XML 汇总 612 项、0 failure/error、1 项既有跳过。
  未开始 Go 或 CLI 实现；功能清单现有 T1 原始协议证据不代表 CLI 已验收。
- 清理：shutdown stopped=true，pidof 无输出；仅剩 display 0；移除 tcp:61534 后
  forward --list 空；设备 APK/日志 ls 返回不存在；测试 PIN 已清除。
  emu kill 返回 OK，随后 devices 为空，emulator/qemu 进程已退出（退出存在短暂延迟）。
  临时客户端、PNG 与日志只位于忽略的 displayd/build 下，未加入 Git。
