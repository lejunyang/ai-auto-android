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

## Round 3：T2 Go 显示目标与 displayd 客户端（2026-11-14）

- 前置 T1b 已提交 ae9486f 且验证通过、模拟器已清理；本轮仅修改 internal/adb、
  internal/vdisplay、internal/apperr 新错误码与本规格状态文件。
- RED：fake-adb 的 4 个 input 显示分支、launch 显示分支、uiautomator 参数不支持拒绝、
  localabstract 转发均按预期失败；legacy 分支通过。假服务端初始 RED 覆盖有效帧、
  超限/坏响应清理、6 种设备错误、身份拒绝、缺进程及握手失败清理。
- 实现：WithDisplay 复制客户端，不改既有方法签名；input -d、am start --display、
  uiautomator dump --display-id；dump 前核对 help，不支持则明确失败。省略目标时
  Tap/Swipe/Key/Text/Launch（含 monkey 与显式 Activity）/Hierarchy 的 argv 均有逐字测试。
  WithDisplay.Screenshot 明确拒绝 screencap。Sleep 专用 KEYCODE_SLEEP 原语在本轮允许的
  adb 目录预备，普通按键白名单未变；service/CLI 接线仍属 T3。
- 身份：forward 前核对精确进程名 aactl-displayd、属主 shell、唯一 PID，以及
  /proc/net/unix 的监听 inode 是否出现在该 PID 的 fd。建立转发后再次核对同一 PID
  与归属；查不到、伪造名称、root 属主、inode 不符、套接字被其他进程占用均拒绝，
  不向未知 socket 发送请求。测试覆盖这些反例，API 35 的 /proc 可读性有设备证据。
- 部署：--helper、AACTL_DISPLAYD_APK、可执行文件同目录 aactl-displayd.apk 的优先级；
  显式缺失不降级。比对远端 SHA-256，同摘要不推送，缺失/变化才推送，推送后复核；
  已运行实例摘要不符先拒绝，要求 stop 后再部署，避免覆盖存活实例。
- 协议：每次调用自己申请并移除 forward（含坏头、超限、断连、dial 失败、版本不符）；
  每个方法前单独连接 hello，固定 16384 字节响应头与 16MiB 帧上限后再分配，按 bytes
  精确读取，不依赖 EOF；校验目标、尺寸与 PNG 解码。错误保持独立：DISPLAY_NOT_FOUND、
  DISPLAYD_NOT_RUNNING、DISPLAYD_IDENTITY_MISMATCH、DISPLAYD_HELPER_MISSING、UNSUPPORTED_API、
  DEVICE_LOCKED、DISPLAY_ASLEEP；设备端其它固定错误透传，未知错误码拒绝。
- API 35 Go 客户端冒烟：AVD 与参数不变，唯一 emulator-5554，指纹
  google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  首次开机检查读到 boot_completed 为空，但同批命令继续启动了冒烟，这是顺序偏离；
  已在测试中添加 boot_completed=1 硬检查，并确认系统启动完成后重新运行作为证据。
  重跑 TestDisplaydEmulatorLifecycle 通过：hello v1/API35 → displayId=3 → 在目标屏
  启动 Settings → groupAwake=true → PNG 78529 字节（SHA-256
  d4bd467a929d6cdafaf2941fade191f415c87f7cac9851df8d2ea4a29b573f98）→ list/close/stop。
  本轮没有设置 PIN 或修改超时，不代替 T4 的真实 CLI 验收。
- 验证：make comments 通过（579 文件、16 自测）；make test 通过（协议 56 项，
  Skills 3 份与镜像；Go 共 287 个顶层 Test 定义，adb 94、vdisplay 10，默认跳过
  1 个显式设备测试，设备测试已按上述单独启用）。定向 adb/vdisplay 测试均通过。
  git diff --check 通过。
- 清理：客户端 Stop 后无 PID、仅剩 display 0、forward 列表空、固定 APK/日志不存在；
  emu kill 返回 OK，随后 adb devices 为空且无 emulator/qemu 进程。工作树未含构建产物。

## Round 4：T3 service 与 CLI 出口（2026-11-14）

- T2 已提交 6e065ef、验证与设备清理通过。仅修改 internal/service、internal/cli 与本规格
  状态文件；未修改现有一致性测试、direct_surface_test.go 或其它出口。
- RED：CLI 5 个动作显示参数、2 个观察显示参数、6 个 display 管理子命令与 sleep
  按预期失败（Unknown option/command）。service 路由、5 种错误不回退、目标动作与
  Sleep 专用操作回归测试同样先失败，能捕获原实现忽略 DisplayID 后触达主屏的问题。
- GREEN：ObserveRequest/ActionRequest 增加可选 DisplayID；CLI 直连构造器单独装配
  displayd 后端；通用 Automation 接口不增加显示管理方法，DisplayAutomation 独立。
  虚拟屏截图走 Capture；层级与动作先 RequireDisplay，再使用复制的目标 ADB 客户端。
  只允许规格中的 screenshot/hierarchy/tap/swipe/text/key/launch 指定显示，其他组合拒绝。
- 新 CLI：display start --helper（可选）、status、stop、create --width --height --dpi、
  list、close --display；全部要求 --device。上述 7 个观察/动作入口支持正整数 --display。
  action sleep 使用 service 的 device.sleep 与 T2 的专用 KEYCODE_SLEEP，拒绝 --display，
  只影响主屏。未修改普通按键白名单。
- 没有一致性测试要求把 device.sleep 登记到公共 Schema，因此按任务书条件不改 protocol。
  MCP 的现有 schema、模型动作白名单与工具列表均未变化；internal/mcpserver、Bridge、
  Android App 均未修改，文件传输既有边界测试保持原样且通过。
- 验证：make comments 通过（583 文件、16 自测）；make test、make verify、make build
  退出 0。Go 共 295 个顶层 Test 定义，service 8、CLI 57（本轮各新增 4 个顶层测试，
  含路由/管理/无效参数等表驱动子例）；协议 56 项、Skills 3 份与镜像通过。
  定向 go test ./internal/service 与 CLI -run TestDisplay 均通过；git diff --check 通过。
- 本轮未启动模拟器或创建转发、设备文件、PIN；阶段结束 adb devices 为空，无
  emulator/qemu 进程。尚不以命令面单测勾选 CLI 设备功能，T4 须使用 make build 产物验收。

## Round 5：T4 CLI 验收阻塞，未完成（2026-11-14）

- T3 已提交 d33eca8，前序验证通过、工作树干净。新增三个 scripts/emulator 文件：
  verify-virtual-display.mjs、virtual-display-png.mjs、virtual-display.test.mjs。
  它们尚未提交；未修改既有模拟器脚本、internal/adb 或 T4 允许范围外文件。
- 脚本只接受 emulator-5554，核对唯一在线设备、transport、固定指纹、AVD、API、
  分辨率、密度、语言、导航模式与 boot_completed=1；只用真实 bin/aactl.exe
  完成辅助进程/观察/动作。PIN 与超时仅是 test-only 模拟器路径；无下载或第三方 APK。
  有界 PNG 像素检查、失败 hierarchy 全文本脱敏、日志时间窗与 fixture 帧限额
  （合计 <1MiB）已实现，但端到端流程未通过，不能声称脚本已验收。
- 脚本单测 node --test scripts/emulator/virtual-display.test.mjs：3 项通过。
  node --check 通过；make comments 通过（586 文件、16 自测）；make build 通过。
  本轮全量 make test/verify/android-verify 未作为 T4 最终验收执行；前序阶段结果仍保留。
- 前置尝试 run1：尚未进入功能场景，Windows emulator 控制台 avd name 输出 CRCRLF，
  首行残留 CR 导致 Unexpected AVD；已按实际输出修正逐行 trim，仍严格匹配名称。
  该次未设置 PIN/超时、未部署 helper；已 emu kill 并确认无设备/模拟器进程。
- 功能尝试 pass1：不是成功轮次。设备 emulator-5554（transport_id=11），
  AVD osdk-test-35，-read-only -no-snapshot -no-boot-anim -no-audio，API35，
  x86_64、320×640/160dpi、en-US、navigation_mode=2、原超时2147483647，指纹：
  google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  2026-11-14T03:53:07Z 开始，03:53:25Z 停止。
  - make build 产物 aactl SHA-256：
    a5908712d444101f4849c06ae2df6c4b9aefcb4b1114b30fe39ce27e1f2cdb36。
  - helper SHA-256：681af2fb0d9c23051deaae137530ab6c737aefa135da8be0b3138949dcd15ec3；
    本地 device-fixture debug APK（versionCode=1/versionName=1.0.0）SHA-256：
    1e88acf7a0b88f00df212648ca93c3487b37d0cb50e19b92569b80aa8462f6f8。
  - CLI 主屏 unlock/HOME/hierarchy、display start/create/list 成功，displayId=2；
    action launch --display 2 --package dev.aiauto.fixture --activity .MainActivity 返回成功。
  - **首个 observe hierarchy --display 2 失败**：CAPABILITY_UNAVAILABLE，
    UIAutomator does not support --display-id; refusing primary-display fallback.
    未获得目标层级，所以未继续点击、截图、主屏隔离、息屏、65 秒保活或 PIN 往返。
  - 这是 T2 新增的 help 能力探测误拒绝：internal/adb/direct.go 的 Hierarchy 在
    uiautomator help 文本不含 --display-id 时直接失败，尽管本规格 Round 0/1 复核
    已实测该 API35 支持 dump --display-id。单测把 help 文本人为写成包含该参数，
    没有覆盖真实帮助输出，Go 生命周期冒烟也没有读取层级，所以此前未发现。
- **停在 T4，不跨范围回修**。修复需要改 internal/adb 的能力判定和真实 help 回归
  fixture/测试，超出本阶段允许修改范围；需要重新开放该范围后先验证修复，再从
  第一次冷启动开始完成连续三次 CLI 验收。不删除保护，不采用主屏 dump 作成功证据。
- 当前三次连续成功为 0/3，没有第 2/3 次结果。checklist 功能节已按 CLI 证据取消
  T1 原始协议对应的组合勾选，T2/T3 的层级事项重开；历史 progress 未改动。
  未开始宣称已交付的架构/使用/路线图/Skill 文档更新，也未做 T4 两个提交。
- 失败后 finally 的真实 display stop 成功（其内部等待 PID 消失后清 APK/日志），
  forward --list 为空；screen_off_timeout 恢复原值，本次未设置测试 PIN。
  emu kill 返回后外部复核 adb devices 为空、无 emulator/qemu 进程。
  限额脱敏报告与失败产物已读入本轮摘要后清理，不提交截图、APK、日志或缓存。
- 另记执行偏离已在 Round 3 如实记录：T2 首次冒烟没有等待 boot_completed=1，
  已添加硬检查并在启动完成后重跑；本轮未重复该问题。

## Round 6：T2b 源码调查发现前提不成立，停止实施（2026-11-14）

- 用户重新开放 T2b 的 internal/adb、internal/vdisplay（仅测试/冒烟）、新增错误码与
  本规格状态范围。开工 git status/worktree 核对：HEAD=d33eca8，分支与 worktree 正确，
  只有 Round 5 的三份状态文件与三个未提交模拟器脚本；没有覆盖或丢弃上轮产物，
  主工作区 main 仍为 f861a61。未派发 Sub-Agent。
- **关键更正：未查到支持 --display-id 的最低 API；所查发行分支均没有解析该参数。**
  上轮“API35 参数支持但帮助漏列”是错误推断，源码不支持该结论。本节追加更正，
  不改 Round 0/1/5 的历史记录。
- AOSP 实际文件位于 frameworks/base/cmds/uiautomator/**cmds/uiautomator**/src/…，
  不是少一层 cmds/uiautomator 的路径。查阅以下完整文件（Google Gitiles format=TEXT
  与官方 aosp-mirror 源码交叉读取；仅在内存解析文本，未下载二进制或新依赖）：
  - [android13-release / API33 DumpCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
  - [android14-release / API34 DumpCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
  - [android15-release / API35 DumpCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
  - [android16-release / API36 DumpCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
  - [main DumpCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
- 上述参数循环都只识别 --compressed 与 --windows；其它带连字符的参数被忽略，
  普通值被当作输出文件路径，最后的显式路径覆盖前面的数字。普通分支调用
  getRootInActiveWindow，显示尺寸/旋转使用 Display.DEFAULT_DISPLAY；不是按指定
  displayId 查节点。活动窗口若恰在虚拟屏，返回其 XML 也不能证明参数生效。
  因此不能把 API35（甚至36）设为允许门槛，也不能把未知参数运行结果当作成功。
- 排除上层隐式解析：已查
  [android15-release Launcher.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/Launcher.java)，
  它只消费子命令名称并将剩余参数交给 Command.run，没有全局 --display-id 处理。
- 额外结果校验的结论：已查
  [android15-release AccessibilityNodeInfoDumper.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/cmds/uiautomator/library/core-src/com/android/uiautomator/core/AccessibilityNodeInfoDumper.java)。
  普通 XML 的 hierarchy 根只有 rotation，不含可核对的 displayId；包名、文字或尺寸
  也不能可靠证明显示目标。不存在的 displayId 负探针能揭露忽略，但不能使不支持的
  参数成为可用能力。本轮未向不支持的系统发送目标 dump，更未采信其结果。
  --windows 路径另有 display 元素及 id，可作为调整契约后的候选方案；未擅自替换
  任务书要求的 dump --display-id，未实现或宣称该候选方案已验收。
- 真实 API35 help fixture：internal/adb/testdata/uiautomator-help-api35.txt。
  通过唯一在线 emulator-5554（transport_id=12）读取，boot_completed=1，固定
  osdk-test-35、-read-only -no-snapshot -no-boot-anim -no-audio，指纹
  google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  help 退出0，stdout=0字节、stderr=1659字节；fixture 是 stderr 原始字节，SHA-256：
  cb5d9049b8bcddbf3c9157588b1cf5782f79bc75a8d6daf6369f8e712a76ee28。
  内容没有 --display-id，与源码相符。未在人为 fixture 中补参数。
- **阶段停止**：无法同时满足“API35 成功”“按真实支持版本放行”“不采信未知参数”
  三项验收要求。未修改生产 Go 逻辑或测试去制造成功，未运行新的虚拟屏 dump，
  未启动 helper、安装 fixture、设置 PIN 或修改 screen_off_timeout。
  尚未执行本阶段 make test/verify/build 与 Go/CLI 层级冒烟，T2b 未提交。
  T4 未恢复，连续成功仍为0/3，也没有新的 T4 文档或提交。
- 清理：确认 pidof 无 helper、forward --list 为空、固定设备 APK/日志不存在；
  emu kill 返回OK，随后确认 adb devices 为空且无 emulator/qemu 进程。保留真实帮助
  fixture 作为可评审证据；其余上轮文件未改。本轮状态、帮助 fixture 均未提交。

## Round 7：T2b 新契约 RED/GREEN 与真实 CLI（2026-11-14）

- 开工 HEAD=f544271，已重读 spec 的实测更正与 Go 侧；保留上轮未提交脚本/状态/帮助
  fixture，不覆盖用户更正，不修改主工作区。单人串行，仅在本阶段授权目录实现。
- --windows 源码下界确定为 Android11/API30：
  - [android10-release / API29](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java) 没有该分支。
  - [android11-release / API30](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java) 解析 --windows，使用 getWindowsOnAllDisplays。
  - [android12-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java) 与 Round 6 的 Android13+ 均保留该路径。
  源码通过 Google Gitiles format=TEXT 读取核实。整体虚拟显示仍要求 API33，与这个
  单独命令的 API30 门槛不同；API33/34 设备兼容性尚未验收。
- 真实 fixture 采集：唯一 emulator-5554、transport_id=13，boot_completed=1，AVD/启动
  参数遵照任务书，指纹 google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  用既有真实 aactl 建屏2，在其上启动本地 debug dev.aiauto.fixture/.MainActivity，
  主屏是 NexusLauncher。仅为 fixture 采集执行原始 adb dump --windows，设备独立路径
  /data/local/tmp/aactl-t2b-windows.xml。原输出37089字节，根 displays，下有 display
  id="0" 与 id="2"，每组有 window/hierarchy/node，显示归属明确可靠。
- 保存 internal/adb/testdata/windows-api35.xml，37571字节；只脱敏 text/content-desc/title，
  保留真实分组、窗口、包名、资源ID、状态与坐标，未手写替代结构。SHA-256
  550cba5db7d1f157e5d30fb85cac254c16b49c0ac916235b96fec17d80c04792。
  既有真实 help fixture 一并保留作不依赖 help 的回归依据。
- RED：selectDisplayWindows 安全占位拒绝，真实目标组与缺失错误测试失败；旧代码的
  help 门禁使真实帮助回归失败，截断清理测试也按预期失败。再实现：
  - 显式目标时先读 SDK，低于30、缺失/非数字、读取失败/截断均 CAPABILITY_UNAVAILABLE，
    在随机文件分配与 dump 之前停止。完全移除 help 探测，不发送 --display-id。
  - dump --windows 后完整严格解析输入；只复制精确 display.id 的那一组，目标缺失
    DISPLAY_NOT_FOUND；畸形、重复ID、多个根、错误分组、超深、截断及8MiB超限拒绝。
    即使目标已找到仍验证后续XML，不能把损坏尾部当作成功。失败也清远端文件。
  - XML返回 displays 根且只含目标 display 的窗口列表；format 明确为
    uiautomator-windows-xml，透传 service/CLI。普通命令 argv 与 uiautomator-xml 不变。
- GREEN：真实 fixture 覆盖主屏/虚拟屏、调换顺序、多显示隔离、缺失、畸形、重复、超限；
  fake-adb 覆盖API29拒绝、API30边界、API35正确命令、缺API与截断清理。
  service/CLI 格式透传均有单测，既有普通命令兼容测试保持逐字检查。
- 真实 make build 产物 CLI：observe hierarchy --display 2 成功，format=
  uiautomator-windows-xml，16378字节，SHA-256
  442282146756123cd0977721598b514e84b8e99933b6abbea03f5327410f1012；断言包含
  dev.aiauto.fixture，且不含 nexuslauncher 或 display0，证明仅返回目标屏。
- Go 设备冒烟补层级后通过（1项）：额外建屏3并启动 Settings，返回目标窗口列表22511
  字节，SHA-256 7cf0ccdde53bc2b98c445782c61f8acdedc9ab07826d5dd16b1ce001cbd7bb75，
  包名正确且无主屏桌面；取帧78827字节，随后 close/stop 完成。
- 验证：make comments（588文件、16自测）、make test、make verify、make build 均退出0；
  Go 303个顶层 Test 定义（含默认跳过的1个显式设备测试，本轮已另行启用通过），
  协议56项与Skills3份及镜像通过；adb/service/CLI/vdisplay 定向测试通过。
- 阶段清理：Go Stop 后 pidof无输出、仅剩display0、forward列表空、helper APK/日志
  已删除；删除本次原始XML后ls确认不存在。未设PIN、未改超时；emu kill 返回OK，
  随后 adb devices 空、无 emulator/qemu。git diff --check通过；未开始T4操作。
- 提交检查补充：帮助输出原始采集含 CRLF 与末尾空行；首次暂存被 Git 报末尾空行。
  已只规范化行尾与末尾空白，正文没有补写/改写，复核暂存 diff --check 通过；
  Round6 的原始 SHA-256 对应采集时字节，规范化后的版本作为回归 fixture。

## Round 8：T4 首次尝试失败与脚本修正（2026-11-14）

- 前置 T2b 已提交 afa8a1c，四项验证与真实CLI/Go层级均通过，阶段清理完成后才恢复T4。
  仅修改新增模拟器脚本、文档、Makefile新增测试目标与本规格状态；没有回修产品目录。
- 脚本按新契约核对 format 与唯一目标display组；主屏隔离改用 dumpsys activity activities
  中 display0 的 topResumedActivity，不把普通层级当作主屏。普通层级仅记录实际来源。
  PIN严格断言 DEVICE_LOCKED → 清PIN/无凭据解锁 → DISPLAY_ASLEEP → close/create/relaunch。
- seriesA-1 是一次失败的完整尝试，不计入成功次数：2026-11-14T04:18:53Z开始，
  04:19:41Z清理结束，emulator-5554、transport_id=14，固定AVD/参数、API35，指纹
  google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  建屏/列屏/启动、CLICK:1、帧变化与主屏前台保持通过；普通层级返回fixture。
  ASCII后置校验失败后立即停止，第2/3轮没有执行，helper/forward/文件清理并关闭模拟器。
  未到设置测试PIN/超时步骤。
- 失败证据约98KiB（fixture PNG 78353字节、脱敏XML16047字节、日志时间窗0字节、报告
  3807字节），未超1MiB。检查可见字段与截图后定位为脚本点选问题：text_input的真实
  bounds为[18,47][702,119]，中心y=83落在标题栏下沿（约y84）被遮挡；精确输入节点
  focused=false，但脚本原来匹配到了window/fixture_root的focused=true。
  读取AOSP InputShellCommand确认text路径为每个KeyEvent设置displayId，未据此更改产品。
- 修正仅限固定profile的验收脚本：每次从最新窗口列表与截图观察，选择输入框bounds内
  可见下部（85%高度）；必须核对text_input本身的focused=true才输入。动作失败不重试，
  后续系列从冷启动重新计数，没有拼接seriesA步骤。失败XML还补充title脱敏。
- 脚本独立Node测试4项通过：非模拟器拒绝、黑/非黑像素区分、PNG预算/截断、按display0
  读取主屏Activity（含输出顺序颠倒与缺主屏）；node --check通过。新Makefile目标
  emulator-vdisplay-test仅执行这些本地测试，不启动设备，不改变既有目标行为。
- seriesB从新冷启动系列重新开始，固定同一CLI/helper/fixture产物，未混用seriesA结果。
  三轮最终结果与全量验证在下一轮追加记录；本节不把正在运行的轮次计作完成。

## Round 9：T4 连续三次完整通过与最终验证（2026-11-14）

- seriesB-1/2/3 使用同一修正后的验收脚本（SHA-256
  fe6a675075fee147e6c3c5254820d669160024fa7e34d255cec5e465ebd6df2d）、同一make build
  真实aactl，逐次冷启动，任何失败即停止后续轮次。没有复用seriesA部分步骤。
  本轮初版脚本1次失败、修正后连续3/3通过；仅3次样本，不宣称长期无flaky。
- 三次均为唯一 emulator-5554，AVD osdk-test-35、API35 google_apis x86_64、
  emulator37.1.11；后台启动参数固定为
  -avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio，boot_completed=1。
  环境均320×640/160dpi、en-US、navigation_mode=2；虚拟屏720×1280/240dpi。
  三轮设备指纹均为F35：
  google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
- 固定产物（均由本仓库构建，未下载/提交APK）：
  - CLI：8b223c6a8d4f5eb0671f520c24478e50beebc6f3ae8476d8de6311e50f7e757d。
  - helper：681af2fb0d9c23051deaae137530ab6c737aefa135da8be0b3138949dcd15ec3。
  - dev.aiauto.fixture debug APK（versionCode1/versionName1.0.0；设备ABI x86_64）：
    1e88acf7a0b88f00df212648ca93c3487b37d0cb50e19b92569b80aa8462f6f8。

| 轮次（均F35） | UTC开始→结束 | transport | 建屏/启动与隔离 | 输入/层级/帧 | 息屏/保活 | PIN往返与重建 | 停止与清理 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| seriesB-1 | 04:23:56.281→04:26:46.235 | 15 | create/list/launch通过，display2；主屏桌面不变 | CLICK:1、ASCII、BACK、滑动正偏移通过；点按后PNG78353字节 | CLICK:2通过；等待66679ms，groupAwake=true；非黑PNG80934字节 | DEVICE_LOCKED→清PIN/解锁→DISPLAY_ASLEEP；close2/create3/relaunch，PNG81259字节正常 | stop通过，无PID/虚拟屏/forward/临时文件，恢复超时，关模拟器 |
| seriesB-2 | 04:27:32.662→04:30:11.914 | 16 | 同上 | 同上，点按后PNG78353字节 | CLICK:2通过；等待66191ms，groupAwake=true；非黑PNG80934字节 | 同上，重建PNG81259字节正常 | 同上 |
| seriesB-3 | 04:31:06.132→04:34:15.590 | 18 | 同上 | 同上，点按后PNG78353字节 | CLICK:2通过；等待66579ms，groupAwake=true；非黑PNG80721字节 | 同上，重建PNG81259字节正常 | 同上 |

- 三轮每个正常帧均解码720×1280，921600/921600像素非黑；主屏screen-state在sleep后
  和保活后均on=false。超时临时设15000，原值2147483647在finally恢复；测试PIN均明确
  清除后才调用无凭据wake/unlock，没有在凭据锁定期间唤醒虚拟组。
- 主屏隔离不用普通层级：display0的topResumedActivity一直是
  com.google.android.apps.nexuslauncher/.NexusLauncherActivity；目标窗口列表只返回
  display2/重建display3，format=uiautomator-windows-xml。
- **普通层级歧义实测**：最近一次输入在虚拟屏之后，三轮真实不带--display的
  observe hierarchy均ok=true、format=uiautomator-xml、package仅dev.aiauto.fixture，
  即返回虚拟屏内容，而主屏Activity仍为桌面。仅观察记录，不作为通过条件或主屏判据；
  文档限制与后续N60.5已准备登记。
- 边界反例：不存在的display2147483647返回DISPLAY_NOT_FOUND；stop后status返回
  DISPLAYD_NOT_RUNNING。锁定与休眠错误都不生成输出PNG，不以黑帧当成功。
- 全量最终验证退出0：make comments（588手写文件/16检查器自测）、make test
  （Go303个顶层Test定义，含默认跳过的1个显式设备测试；本阶段前的Go设备冒烟另行通过；
  协议14schemas、14合法+38非法+4兼容=56项；Skills3份与镜像）、make verify、make build、
  make android-verify（单测/assembleDebug/lintDebug，612项，0failure/error、1既有跳过；
  displayd34项，Gradle配置/任务缓存复用）。make emulator-vdisplay-test 4项通过，
  node --check通过；44个本地文档链接存在，git diff --check通过。
- 所有轮次finally清理，外层每轮确认无emulator/qemu进程后才启动下一轮；最终adb devices
  空、无emulator/qemu。失败产物seriesA-1总98207字节，三份成功报告各5927字节，均未超限；
  已将摘要记入本节后清理整个本轮证据目录，未提交截图、日志、缓存或构建产物。
- 文档已准备：操作指南、架构/协议/导航/README、Skills导航说明；N59第3项指向本规格，
  N60登记MCP、发布打包、API33/34、真机/OEM、普通层级歧义和摄像头决策。先提交脚本与
  验收证据，再提交文档，不把尚未提交的文档标为已交付。禁止目录与main未改，未push。

## Round 10：T4 文档交付与最终范围复核（2026-11-14）

- T4验收提交74be592只含新增脚本、Makefile新增本地测试目标与规格状态；此前完整三次
  证据、失败与重计数、全量验证已记Round8/9。此后没有改产品代码或验收脚本。
- 文档交付：新增docs/virtual-display.md操作指南，并同步architecture/protocol/导航、
  根README、Skills导航说明。写清helper定位/SHA/身份、全部CLI参数、两种XML format、
  普通层级返回活动窗口的三轮实测、主屏独立判据、锁定/休眠错误与close/create销毁语义、
  主屏任务整体迁移、API30命令下界/整体API33门槛，以及API33 groupAwake=null限制。
  不宣称真机/OEM、API33/34、中文/IME、MCP、发布打包或无线重连已验收。
- docs/next-phase-tasks.md的N59第3项指向本规格；N60登记MCP、发布打包、API33/34、
  真机/OEM、普通层级焦点歧义与摄像头安全决策，各有依赖、步骤、目录所有权/汇合点、
  证据、建议提交边界和回滚清理，不修改MVP或其它规格历史。
- 相关Skill使用说明位于skills/README.md，未改三份Skill正文/镜像，保留通用技能的
  现有确认与禁止动作边界，不向它们开放测试PIN路径。该选择保持本轮允许目录与
  既有镜像一致性检查，所有3份Skills及镜像验证通过。
- 文档本地链接44项存在，git diff --check通过；只有文档/状态在全量验证后收尾，
  没有代码变化需要重复全套设备验收。最终make build产物SHA仍与三轮实际CLI一致。
- 偏离/修正均已记录：Round3的首次boot顺序（已加硬检查重跑）、Round5的CRCRLF预检、
  Round6纠正--display-id假设、Round8的输入框遮挡与精确焦点判据；本轮没有越界修补，
  最终三次seriesB没有失败或拼接。中途失败不被删除或改写成成功记录。
- 全部本轮设备证据临时目录已清；最终adb devices空、无emulator/qemu，未设真实设备PIN、
  未操作非模拟器、未改AVD/system image/osdk或用户配置、未下载依赖/二进制/APK。
  main仍f861a61，未push；仅提交允许目录的源码、脱敏文本fixture、脚本、文档与状态。

## 集成方最终复核（2026-11-14）

- 复核对象 `1690312`（分支共 9 个提交，基线 `f861a61`）。逐提交核对范围：未触及
  `android/app/`、`internal/mcpserver/`、`internal/bridge/` 与其它规格目录；无二进制；
  主工作区 `main` 未变；未 push。
- 代码审阅：`internal/adb/displayd.go` 的身份核对（shell 属主、精确进程名、
  `/proc/net/unix` inode 与进程 fd 归属）、`internal/vdisplay/client.go` 的有界读取与
  forward 清理、`internal/adb/hierarchy_windows.go` 的显示分组筛选，均与规格一致，
  失败路径都拒绝而不回退主屏。
- 独立重跑：`make comments`（588 个手写文件）、`make test`、`make verify`、
  `make build`、`make android-verify` 全部退出 0。
- 独立设备验收：AVD `osdk-test-35`、`-read-only -no-snapshot`、唯一设备
  `emulator-5554`、同一指纹；用单独构建的 `aactl`（同一提交）手工执行，未使用
  T4 验收脚本。
  - 未启动时 `display status` 返回 `DISPLAYD_NOT_RUNNING`；指向不存在的 `--helper`
    返回 `DISPLAYD_HELPER_MISSING`；helper 放在可执行文件同目录时 `display start`
    无需参数即成功。
  - `display create` 得到 displayId 2；`--display 99` 与 `--display 7` 返回
    `DISPLAY_NOT_FOUND`，`--display 0` 返回 `INVALID_ARGUMENT`，均未落到主屏。
  - `action launch --display 2` 后 display 2 前台为 fixture，display 0 前台仍为桌面。
  - `observe hierarchy --display 2` 返回 `uiautomator-windows-xml`，只含
    `display id="2"`，42 个节点全部属于 fixture 包。
  - 不带 `--display` 的 `observe hierarchy` 同样返回 fixture 内容（且坐标被裁到主屏
    尺寸）——普通层级歧义再次复现，与文档记录一致。
  - `action tap --display 2` 后计数由 `CLICK:0` 变为 `CLICK:1`。
  - `screen_off_timeout=15000`、`action sleep` 后等待 40 秒：显示组 0 为 Dozing、
    显示组 1 为 Awake，`display status` 的 `groupAwake=true`，虚拟屏截图 78353 字节，
    目视为 fixture 界面且计数为 1。
  - `display stop` 后无进程、无虚拟屏、无转发、设备临时文件已清；再取帧返回
    `DISPLAYD_NOT_RUNNING`。模拟器已关闭。
- 复核中另记一条既有行为（非本规格引入，未处理）：主屏息屏时不带 `--display` 的
  `observe screenshot` 返回成功和一张 2195 字节的全黑图。
