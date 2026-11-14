# 虚拟显示与主屏息屏

电脑侧 CLI 可通过 shell 身份的 `displayd` 创建虚拟屏，启动、观察与操作其上的应用，
并用 `action sleep` 使主屏息屏。该能力不经过 Android App 或 App Bridge；MCP 没有
虚拟屏出口，App 内 AI 会话原有的锁屏暂停边界保持不变。

## 构建与启动

辅助 APK 作为 `app_process` 的 CLASSPATH 使用，**不安装 APK**。当前发布制品没有
打包它，需要从本仓库构建。沿用 Makefile 的 osdk 工具链：

```bash
make build
make android-verify
aactl display start --device SERIAL --helper android/displayd/build/outputs/apk/debug/displayd-debug.apk --json
```

产物定位顺序为 `--helper PATH`、环境变量 `AACTL_DISPLAYD_APK`、`aactl` 可执行文件
同目录的 `aactl-displayd.apk`。显式指定的路径不存在时直接失败，不换用其它 APK。
部署会比对本地与设备 SHA-256，只有不同才推送；已有实例与本地产物不同，需先 stop。

辅助进程会脱离启动 shell，后续 CLI 调用共用它创建的屏幕。每次请求独立建立和清理
ADB forward；连接前核对 shell 属主、精确进程名与监听 socket 的 inode/fd 归属。
身份无法核实时拒绝连接，不采信同名未知套接字。

## 命令

全部命令要求显式 `--device SERIAL`；`--json` 输出既有紧凑信封。

| 命令 | 参数与效果 |
| --- | --- |
| `display start` | 可选 `--helper PATH`；部署、启动或复用匹配实例 |
| `display status` | 凭据锁、显示状态、显示组唤醒状态与保活诊断 |
| `display stop` | 释放全部虚拟屏，等待进程退出，清除固定 APK/日志 |
| `display create` | 必须给 `--width W --height H --dpi D`；返回 `displayId` |
| `display list` | 列出本进程拥有的虚拟屏 |
| `display close` | 必须给 `--display N`；销毁目标屏及其上应用内容 |
| `observe screenshot` | 支持 `--display N`；必须给 `--output PATH` |
| `observe hierarchy` | 支持 `--display N`；返回目标显示的窗口列表 |
| `action tap`、`swipe`、`text`、`key`、`launch` | 在原有参数外支持 `--display N` |
| `action sleep` | `device.sleep`；用幂等 `KEYCODE_SLEEP` 使主屏息屏，不接受 `--display` |

尺寸范围为宽、高各 128..2048，dpi 72..640；最多同时 2 块虚拟屏。
将下面的 `N` 替换成 create 实际返回的编号，不能假定编号跨重建保持不变：

```bash
aactl display create --device SERIAL --width 720 --height 1280 --dpi 240 --json
aactl display list --device SERIAL --json
aactl action launch --device SERIAL --display N --package dev.aiauto.fixture --activity .MainActivity --json
aactl observe hierarchy --device SERIAL --display N --json
aactl observe screenshot --device SERIAL --display N --output virtual.png --json
aactl action sleep --device SERIAL --json
aactl display status --device SERIAL --json
aactl action wake --device SERIAL --json
aactl display close --device SERIAL --display N --json
aactl display stop --device SERIAL --json
```

`tap/swipe` 的坐标必须来自同一目标显示的最新层级或截图；动作后再次观察。文本入口
仍只接受受限 ASCII，普通按键白名单没有扩大。失败时不能去掉 `--display` 向主屏重试。

## 层级形态与焦点歧义

指定显示时执行 `uiautomator dump --windows`，完整校验 XML 后只返回精确编号对应的
一组窗口。CLI 的 `format` 为 **`uiautomator-windows-xml`**，XML 根为 `displays`，
下有一个 `display id="N"`，其下是多个 `window` 及各自的 `hierarchy/node`。
窗口列表可能包含目标屏的系统窗口；调用方仍需按目标包选择节点。

经归属校验确认存在的显示（主屏0除外）没有窗口分组时返回 `DISPLAY_EMPTY`，先在该屏
启动应用再观察；未归辅助进程所有才返回 `DISPLAY_NOT_FOUND`。重复编号、畸形/截断
XML 或超过 8MiB 预算仍明确失败，不返回其它显示。
摘要与 `sizeBytes` 对应筛选后的 XML。它与普通 dump 的单个 `hierarchy` 根不同。

不带 `--display` 时仍发出原来的普通 dump 命令，`format=uiautomator-xml`。它读取的是
**活动窗口**，不保证是主屏：API35 模拟器中，最近一次输入发生在虚拟屏后，普通
层级实测返回 `dev.aiauto.fixture`，而主屏的前台 Activity 仍是 NexusLauncher。
因此不能用普通层级判断主屏是否受影响。验收脚本按 `dumpsys activity activities`
的 display 0 分段核对 `topResumedActivity`；普通层级歧义作为独立后续项登记。

## 息屏、锁定与恢复

存在虚拟屏时，helper 每 2 秒向其显示组上报用户活动；这能保持已唤醒的组，
**不能恢复已经休眠的组**。凭据锁定期间跳过保活，不尝试唤醒、解锁或输入凭据。

凭据锁定时取帧返回 `DEVICE_LOCKED`。API35 经历凭据锁定后，即使用户已解除锁定，
虚拟屏显示组仍可能休眠，此时取帧返回 `DISPLAY_ASLEEP`，不返回黑帧。
恢复步骤是 close、create，在新编号上重新启动应用；**原屏上的应用内容会被销毁**，
不会搬回主屏，不承诺保留状态。

另一个任务行为限制是：**已在主屏运行的应用再向虚拟屏启动时，整个任务会移过去**，
不会自动另开一份。选择目标包前要考虑对当前任务的影响。

## 版本与验证边界

| 能力 | 版本条件 |
| --- | --- |
| 本功能创建和管理虚拟屏 | API33+ |
| 底层 `dump --windows` 参数 | AOSP Android11/API30 起解析；CLI 虚拟屏流程仍要求 API33+ |
| 按屏查询显示组唤醒状态 | API34+ |
| API33 的显示组状态 | `groupAwake=null`，不使用主屏或全局状态代替 |

参数下界依据是 AOSP 的
[Android10 DumpCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
尚无 `--windows`，而
[Android11 DumpCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
已解析该参数并按显示枚举窗口。帮助文本没有列出它，能力判断不依赖 help。

API33 只能额外检查 `Display.getState()` 是否 ON；如果系统在组休眠时仍报告 ON，
无法保证识别该情况，不能承诺拒绝所有休眠黑帧。安全窗口不被捕获，系统仍会遮黑。

当前设备证据限于 API35 的 `osdk-test-35`、google_apis x86_64。API33/34、真机/OEM、
无线 ADB 重连，以及用户在主屏输入时的焦点/输入法兼容性均未验收；中文与输入法管理
不属于本功能。重复验收结果和设备指纹以[本规格进度](../.trae/specs/virtual-display-screen-off/progress.md)
为准，不能外推为真机结论。

### API35 连续三次冷启动证据

2026-11-14，使用同一 `make build` CLI、同一 helper 与本地 debug fixture，
seriesB-1/2/3 连续完整通过；每次均只连接 emulator-5554，独立冷启动并清理关闭。

| 轮次 | 建屏/启动/主屏前台 | 点按、滑动、按键、ASCII、层级与帧 | 息屏与保活 | PIN 往返 | stop 清理 |
| --- | --- | --- | --- | --- | --- |
| seriesB-1 | 通过 | 通过 | 66.679秒，组唤醒、帧非黑 | DEVICE_LOCKED → DISPLAY_ASLEEP → 重建正常帧 | 通过 |
| seriesB-2 | 通过 | 通过 | 66.191秒，组唤醒、帧非黑 | 同上 | 通过 |
| seriesB-3 | 通过 | 通过 | 66.579秒，组唤醒、帧非黑 | 同上 | 通过 |

三轮普通层级均返回虚拟屏 fixture；主屏 Activity 保持 NexusLauncher。
指纹均为 `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`，
环境为320×640/160dpi、en-US、导航模式2，虚拟屏720×1280/240dpi。
每轮恢复超时、清除测试PIN，无残留helper、虚拟屏、forward与设备临时文件。

此前 seriesA-1 因输入框中心被标题栏遮挡而失败，未计入这三次结果；修正脚本点选
与精确焦点校验后，从头执行seriesB。三次样本不能证明长期无 flaky 或真机兼容。

## 错误与清理

| 错误码 | 处理 |
| --- | --- |
| `DISPLAYD_HELPER_MISSING` | 构建并指定 helper 路径，或按上文放置产物 |
| `DISPLAYD_NOT_RUNNING` | 显式 start 后再操作 |
| `DISPLAYD_IDENTITY_MISMATCH` | 停止操作，核查设备进程、socket 或产物冲突 |
| `UNSUPPORTED_API` | 虚拟屏要求 API33+，不降级 |
| `CAPABILITY_UNAVAILABLE` | 层级所需 API 无法可靠读取或低于门槛 |
| `DISPLAY_NOT_FOUND` | 重新 list，使用该 helper 拥有的有效编号 |
| `DISPLAY_EMPTY` | 屏存在但没有窗口；先在该屏启动应用再读层级，不能去掉显示参数回退主屏 |
| `WINDOWS_UNAVAILABLE` | 按显示读取时无文件/空输出；兼容 exec-out 退出0、stdout为本次随机路径完整ENOENT诊断，以及退出1、stderr分离形态。常见于主屏息屏且目标为 isolated；唤醒主屏或以 steal 重建（销毁原屏内容） |
| `DEVICE_LOCKED` | 等用户正常解除凭据锁，不自动解锁 |
| `DISPLAY_ASLEEP` | close/create，接受原屏应用内容被销毁，再重新启动 |
| `FRAME_NOT_READY` | 重新观察状态后可另发取帧请求，不重复输入动作 |

设备端其它固定错误与线协议见 [displayd README](../android/displayd/README.md)。调用方应
按 `error.code` 分支；新增 helper 错误目前使用通用退出码 10，不能只靠退出码区分。
正常结束执行 `display stop`；不要删除身份未知的进程或转发。

## 模拟器验收脚本

新增脚本不改既有 emulator runner。先在后台按下列固定参数启动一次性模拟器，确认只有
`emulator-5554` 在线且 `sys.boot_completed=1`，再运行：

```text
emulator -avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio
node scripts/emulator/verify-virtual-display.mjs --device emulator-5554 --adb PATH_TO_ADB --aactl PATH_TO_AACTL --helper PATH_TO_HELPER_APK --fixture PATH_TO_LOCAL_DEBUG_FIXTURE_APK --run RUN_NAME
```

Windows 应传入实际 `.exe` 路径；可通过 `osdk where android-platform-tools` 找到 adb，
CLI 使用 `make build` 产物。脚本严格核对设备与固定环境，只允许一次性模拟器设置
测试 PIN/屏幕超时；每次运行 finally 清理并关闭模拟器。连续三次须分别冷启动，
任一失败则重新开始计数。产物暂存在忽略的 `scripts/emulator/build/virtual-display/`，
失败证据限额且脱敏，记录摘要后清理，不提交截图、日志或 APK。

脚本守卫、主屏归属与 PNG 判据的独立测试入口为 `make emulator-vdisplay-test`，
该目标不启动模拟器、不操作设备。
