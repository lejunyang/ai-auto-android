# 虚拟显示与息屏运行规格

## 目标

让电脑侧 `aactl`（直连 ADB 后端）能在设备上创建**后台虚拟显示**，在其上启动、
观察和操作应用，并在**主屏息屏**时保持可用。用途是：不打断用户在主屏上的操作，
以及让一台专用设备在不亮屏的情况下长期处理任务。

本规格只交付电脑侧 CLI 与 service 能力，并以模拟器证据验收。

## 决策记录

`docs/next-phase-tasks.md` 的 N59 第 3 项把「多显示屏」列为必须先落安全决策的
能力。用户于 2026-11-14 决定实施，本规格即该决策的落点，边界如下：

1. **只做电脑侧出口**。本规格不改 MCP、不改 Bridge、不改 Android App
   （包括 `AutomationSessionService` 的「锁屏即暂停会话」边界，它保持不可关闭）。
2. **设备端新增一个 shell 身份辅助进程 `displayd`**，由
   `adb shell app_process` 启动，不安装到系统、不需要 root、不申请任何权限。
3. **不绕过锁屏**。设备被凭据锁定时返回稳定错误，不尝试唤醒、解锁或输入凭据。
4. **版本下限 API 33**（虚拟显示的独立显示组与免锁屏标志从该版本起可用）；
   更低版本返回明确的 unsupported，不降级为「看起来能用」。独立焦点标志仅在
   API 34+ 设置。
5. **不捕获安全窗口**：不设置允许显示安全内容的标志，`FLAG_SECURE` 窗口在帧里
   保持黑色。

## 实测依据（2026-11-14）

环境：AVD `osdk-test-35`，API 35，`google_apis` x86_64，emulator 37.1.11，
以 `-read-only -no-snapshot` 启动，指纹
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`。
探针是一次性 Java 程序（非仓库产物），以 `app_process` 用 shell uid 运行。

| 验证项 | 结果 |
| --- | --- |
| shell 持有 `ADD_TRUSTED_DISPLAY`、`ADD_ALWAYS_UNLOCKED_DISPLAY`、`INJECT_EVENTS`、`DEVICE_POWER` | 是（`dumpsys package com.android.shell`） |
| 以 shell 身份 `DisplayManager.createVirtualDisplay`，标志 64971 | 成功，得到独立显示组、`FLAG_TRUSTED`、`FLAG_ALWAYS_UNLOCKED` |
| `am start --display N` | 成功，主屏前台不变 |
| `input -d N tap` / `input -d N keyevent` | 成功，虚拟屏页面跳转，主屏不变 |
| `uiautomator dump --display-id N` | **结论已更正，见下**：命令返回了虚拟屏层级，但并非该参数生效 |
| 取帧（辅助进程持有的 `ImageReader`） | 成功，PNG 内容正确 |
| 主屏 `KEYCODE_SLEEP`，无锁屏凭据 | 主屏显示组 Dozing，虚拟屏显示组 Awake，点按、层级、取帧全部正常 |
| 主屏息屏并被 PIN 锁定 | 两个显示组都 Dozing；虚拟屏上启动、点按、层级仍可用，但帧全黑；`input -d N keyevent KEYCODE_WAKEUP` 唤醒的是主屏而不是虚拟屏 |
| `cmd display power-off 0`（只关面板） | 系统保持唤醒，主屏点按与层级正常，但 `screencap` 返回全黑帧 |
| 主屏真正息屏、无虚拟屏 | 层级读不到（null root），`screencap` 全黑 |

**已知注意点**：已在主屏运行的应用再向虚拟屏启动时，整个任务会被移到虚拟屏，
而不是另开一份。

**更正（2026-11-14，Round 6）**：AOSP 的 android13 至 android16 发布分支与 main 的
`DumpCommand` 都不解析 `--display-id`，只识别 `--compressed` 与 `--windows`。普通
dump 读取的是**活动窗口**（`getRootInActiveWindow`），未知参数被忽略。Round 0 里
拿到虚拟屏层级，是因为当时焦点恰好在虚拟屏上，不是因为参数起了作用。由此得出
两条约束：

1. 按显示读取层级不能用 `--display-id`，改用 `uiautomator dump --windows`，其输出
   按显示分组并带显示编号，可以核对目标。
2. 不带目标显示的普通层级读取拿到的是活动窗口。虚拟屏持有焦点时，它可能返回
   虚拟屏的内容而不是主屏的。这是既有主屏命令在虚拟屏存在时的一个已知歧义。

**未验证**：真机与 OEM ROM、API 33/34、虚拟屏显示组自身的息屏超时（该 AVD 的
`screen_off_timeout` 为无限大）、用户在主屏输入时输入法与焦点是否受影响、虚拟屏
上的输入法行为。这些不得在本规格中描述为已交付。

## 设计

### 设备端辅助进程 `displayd`

- 位置：新 Gradle 模块 `android/displayd/`（`com.android.application`，无 Activity、
  无权限声明、无 AndroidX 依赖），构建产物 APK 即 `app_process` 的 `CLASSPATH`。
  不安装该 APK，只推送到 `/data/local/tmp/` 下的固定文件名。
- 入口类在 `app_process` 下运行，按 `com.android.shell` 身份构造 `DisplayManager`
  （探针已验证的做法：反射创建 `ActivityThread`、取 system context、用
  `ContextWrapper` 覆盖包名与 `AttributionSource`）。
- 监听 `localabstract` 套接字。**每个连接必须校验对端凭据**，只接受 uid 0 与
  uid 2000，其余立即关闭。不监听任何 TCP 端口。
- 协议为有界的按行 JSON 请求/响应；帧数据用「JSON 头 + 定长二进制体」返回，
  不做 base64。所有消息、帧尺寸、显示数量都有上限，超限即拒绝。
- 方法集合固定且类型化，**不提供任意命令执行**：
  - `hello`：协议版本、API level、能力位。
  - `display.create`：宽、高、dpi（有范围约束），返回 displayId。
  - `display.list` / `display.close`。
  - `display.capture`：返回该虚拟屏最新一帧 PNG。
  - `status`：每块虚拟屏的显示状态、所在显示组是否唤醒、设备是否被凭据锁定。
  - `shutdown`：释放全部虚拟屏后退出。
- 虚拟屏标志在代码里固定，不由调用方传入。关闭时销毁其上内容，不把应用移回
  主屏。
- 进程脱离发起它的 `adb shell` 会话常驻，使多次 CLI 调用共用同一组虚拟屏；
  进程退出时虚拟屏随之释放。
- **保持虚拟屏唤醒**：存在虚拟屏时，在其显示组上周期性上报用户活动，避免该组
  自行息屏。设备被凭据锁定时不做任何唤醒尝试。
- 设备被凭据锁定时，`display.capture` 返回明确的「设备已锁定」错误，而不是
  一张黑图。

### Go 侧

- `internal/adb`：`Forward` 支持转发到 `localabstract`；点按、滑动、按键、ASCII
  文本、启动应用增加可选的目标显示参数，分别对应 `input -d N`、
  `am start --display N`。不带该参数时行为与现在逐字一致。
- 按显示读取层级：发出 `uiautomator dump --windows`，解析输出后**只返回目标显示
  编号对应的那一组窗口**。输出里没有该显示编号、无法解析或被截断时明确失败，
  不回退到普通 dump，也不返回其它显示的内容。该能力按 AOSP 源码里 `--windows`
  被解析的最低版本做 API 门槛，低于门槛或读不到 API level 时拒绝且不发出命令。
  返回的 XML 是窗口列表形态，与普通 dump 的单根形态不同，文档里要写明。
- 不带目标显示的普通层级读取保持命令行逐字不变。它在虚拟屏持有焦点时的歧义
  不在本规格内修复，但验收必须实测并如实记录，同时登记为后续项。
- 新包 `internal/vdisplay`：辅助进程的部署（推送、远端 SHA-256 校验）、启动、
  探活、类型化客户端与清理。产物路径由 `--helper` 或环境变量显式给出，缺省在
  `aactl` 可执行文件同目录查找；找不到时报出可操作的错误。
- `internal/service` 与 `internal/cli`：
  - `aactl display start|status|stop --device SERIAL`
  - `aactl display create|list|close --device SERIAL`
  - `observe screenshot|hierarchy` 与 `action tap|swipe|text|key|launch` 增加
    `--display N`；虚拟屏截图走辅助进程，不走 `screencap`。
  - `aactl action sleep --device SERIAL`：主屏息屏，类型化动作 `device.sleep`，
    用幂等的 `KEYCODE_SLEEP`，与既有 `device.wake` 对称。
- 目标显示不存在、辅助进程未运行、API 过低、设备已锁定，各自返回稳定且可区分
  的错误；任何情况下都不得悄悄回退到主屏执行。

### 协议与文档

- 若既有一致性测试要求 `device.sleep` 登记进协议动作清单，允许对 `protocol/`
  做该动作所需的最小修改，并同步 `docs/protocol.md`。除此之外不改协议。
- `docs/architecture.md` 增加辅助进程与虚拟显示数据流；`docs/next-phase-tasks.md`
  的 N59 第 3 项改写为指向本规格；未做的后续项（MCP 出口、发布打包、真机矩阵、
  摄像头）登记进 `docs/next-phase-tasks.md`。

## 不做的

- MCP 工具、Bridge 方法、App 内 AI 会话对虚拟屏的支持。
- 任何解锁、凭据输入或锁屏绕过；锁定状态下的取帧。
- 主屏在面板断电时的画面获取（镜像主屏）。
- 虚拟屏上的中文输入与输入法管理。
- 摄像头注入。
- 把辅助进程产物打进 `scripts/release.sh` 的发布制品。
- 任何真机或 OEM 兼容性结论。

## 允许修改

- `.trae/specs/virtual-display-screen-off/`
- `android/displayd/`（新模块）、`android/settings.gradle.kts`、
  `android/gradle/libs.versions.toml`（仅在确有必要时）
- `internal/adb/`、`internal/vdisplay/`（新包）、`internal/service/`、
  `internal/cli/`、`internal/apperr/`（仅新增错误码）
- `protocol/` 中 `device.sleep` 所需的最小改动
- `scripts/emulator/` 下新增的验收脚本（不改既有脚本的行为）
- `Makefile`（仅新增目标）、`scripts/check-comment-coverage.mjs`（仅在新文件类型
  需要纳管时）
- `docs/architecture.md`、`docs/protocol.md`、`docs/next-phase-tasks.md`、
  `docs/README.md`、`README.md`、`skills/` 中与新命令直接相关的说明

## 禁止修改

- `.trae/specs/build-ai-android-automation-mvp/` 及其它既有规格的验收结论
- `android/app/`（主 App，含 `AutomationSessionService` 的锁屏暂停边界）
- `internal/mcpserver/`、`internal/bridge/`
- 文件传输出口与 `TestFileTransferHasNoCLIEntryPoint`
- 按键白名单的既有成员（只允许为 `device.sleep` 增加其专用路径）
- release 变体的安全边界、动作确认策略、真机授权流程

## 验收

- 辅助进程：协议解析、范围校验、对端凭据判定、上限拒绝有单元测试。
- Go：`internal/adb` 的显示参数有 fake-adb 测试，证明带参数时命令行正确、不带
  参数时与原先逐字一致；`internal/vdisplay` 用假服务端覆盖正常、超限、断连、
  版本不符与错误映射；CLI 有命令面测试。
- 模拟器端到端（API 35，`-read-only`，显式 serial，记录设备指纹）：
  1. 创建虚拟屏，在其上启动 `android/device-fixture` 应用，主屏前台不变。
  2. 对虚拟屏点按后，层级与帧都反映变化；主屏层级不变。
  3. 主屏 `device.sleep` 后，重复第 2 步仍成立；把 `screen_off_timeout` 设为
     15 秒并等待 60 秒以上，虚拟屏显示组仍唤醒、取帧非黑。
  4. 设置测试 PIN 并锁定后，取帧返回「设备已锁定」错误而不是黑图；随后清除
     测试 PIN。
  5. `display stop` 后无残留进程、无残留虚拟屏、无残留转发与临时文件。
- `make comments`、`make test`、`make verify`、`make build`、`make android-verify`
  通过。
- 无设备证据的项保持未勾选并标注「待设备验证」。

## 回滚与清理

- 新能力全部是增量入口；回滚即移除 `android/displayd/`、`internal/vdisplay/`、
  新 CLI 子命令与显示参数。
- 验收失败时：`aactl display stop`、`adb forward --remove`、删除
  `/data/local/tmp/` 下的辅助进程文件、清除测试 PIN、关闭模拟器。
