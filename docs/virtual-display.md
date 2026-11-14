# 虚拟显示、焦点策略与主屏息屏

电脑侧 CLI 和 MCP 可通过 shell 身份的 displayd 在后台虚拟屏启动、观察与操作应用。
helper 的部署、启动与停止仍只由操作员通过 CLI 完成；App Bridge 和 App AI 没有多显示出口。
虚拟屏不绕过凭据锁，不捕获安全窗口；目标失效不会回退主屏。

## 构建、启动与升级

helper APK 只作为 app_process 的 CLASSPATH，**不安装 APK**。完整包和精简包都提供
与 CLI 同次构建的 `aactl-displayd.apk`；整体解压后自动解析同目录产物：

~~~bash
aactl display start --device SERIAL --json
~~~

从源码开发时仍可用固定工具链构建并显式指定 debug 变体：

~~~bash
make build
make android-verify
aactl display start --device SERIAL --helper android/displayd/build/outputs/apk/debug/displayd-debug.apk --json
~~~

产物定位顺序为 --helper PATH、AACTL_DISPLAYD_APK、CLI 同目录的 aactl-displayd.apk。
显式路径不存在即失败，不换用其它 APK；部署比对本地与设备 SHA-256。helper 脱离启动 shell，
每次调用独立建立并清理 forward，连接前后核对 shell 属主、进程名与 socket inode/fd 归属。

**helper 协议已升为 v2**，公共 CLI 信封与 App Bridge 仍为 1.0。新客户端必须获得策略和
实际隔离字段，不会把 v1 缺字段当作已隔离，因此拒绝 v1 握手。升级顺序：

1. 保留旧 CLI，先用它执行 aactl display stop --device SERIAL --json，停止运行中的 v1 helper。
2. 整体解压新完整包或精简包，替换 CLI 和匹配的 helper APK，用新版 CLI 执行无
   `--helper` 的 `aactl display start --device SERIAL --json`。源码构建才需要指定路径。
3. 检查 start 返回 protocolVersion:2，并通过 list/status 核对 focus、focusIsolated。

新版 CLI 不会向不匹配的 v1 helper 发 shutdown。固定 socket 名 aactl-displayd-v1 仍是
传输端点，不表示线协议版本；编码、预算及 uid 边界见 [displayd 契约](../android/displayd/README.md)。

发布包使用 `:displayd:assembleDebug` 产物；签名不影响 app_process 类路径用途。
App debug APK 是单独的 Release 资产，启动 helper 不需要安装 App。Windows 完整包已在
API35 模拟器完成 devices、主屏截图、无 `--helper` 的 start、create、close、stop，
同目录定位与协议 v2 已实测；macOS/Linux 包仅做结构/摘要检查，不能据此外推运行兼容性。

## 选择焦点策略

| focus | 适用场景 | API34+ 行为与代价 |
| --- | --- | --- |
| isolated（默认） | 用户同时在主屏输入 | 不抢主屏顶层焦点与输入法；虚拟屏没有输入法；主屏息屏时读不到层级 |
| steal | 无人值守、主屏息屏挂机 | 可息屏读取虚拟屏层级、输入和取帧；启动/点按会抢顶层焦点、打断主屏输入法 |

有人使用主屏时选择 isolated，并保持主屏唤醒才能读层级；需要息屏层级自动化时显式选择
steal，并接受主屏输入被打断。两种策略息屏时均可取帧和注入按键文本，凭据锁定保护相同。

API33 没有禁止抢焦点标志：两种策略标志都为 15819，focusIsolated:false；
hello.capabilities.focusIsolation:false，不能依赖 isolated 保护主屏输入。API34+ 分别为
130507 / 64971，均不含 SECURE。focusIsolated 表示当前 API 启用了标志，不是 OEM 实测保证。
API33/34、真机/OEM、硬件键盘仍未验收。

isolated 虚拟屏没有输入法，只能用按键注入支持的受限 ASCII 文本；中文与 IME 输入未接入。
BACK 直接作用于应用，在根页面会退出，**不会先收键盘**。steal 下输入法会服务虚拟屏，
本机实测第一次 BACK 收键盘且保留根页面。不要将一种策略的 BACK 行为用于另一种策略。

## CLI 命令与显示编号 0

所有设备命令显式指定 --device SERIAL，--json 使用既有紧凑信封。

| 命令 | 参数与结果 |
| --- | --- |
| display start / stop | 仅操作员 CLI；可选 start --helper PATH，stop 销毁全部屏上内容并清临时 APK/日志 |
| display create | --width W --height H --dpi D；可选 --focus isolated\|steal，默认 isolated |
| display list / status | 每屏返回 displayId、尺寸、focus、focusIsolated；status 另含凭据锁、显示组与保活诊断 |
| display close | --display N；只能关闭 helper 拥有的正整数显示，销毁其上内容 |
| observe screenshot / hierarchy | 可选 --display N；截图须 --output PATH |
| action tap / swipe / long-press / text / key / launch | 在原参数外接受 --display N |
| action sleep / wake | 只作用于主屏，不接受 --display；MCP 不开放这两个动作 |

宽、高各 128..2048 像素，dpi 72..640，最多同时两块屏。create 不接受原始 flags 或 socket；helper 的部署路径只由 CLI start 接受。使用 create 实际返回的编号，重建后编号可能变化：

~~~bash
aactl display create --device SERIAL --width 720 --height 1280 --dpi 240 --focus steal --json
aactl display list --device SERIAL --json
aactl action launch --device SERIAL --display N --package dev.aiauto.fixture --activity .MainActivity --json
aactl observe hierarchy --device SERIAL --display N --json
aactl observe screenshot --device SERIAL --display N --output virtual.png --json
aactl observe hierarchy --device SERIAL --display 0 --json
aactl display close --device SERIAL --display N --json
aactl display stop --device SERIAL --json
~~~

**编号 0 明确指向主屏，不要求 helper 运行**。层级发 dump --windows 后只取 display id="0"，
输入与启动明确带 -d 0 / --display 0。正整数必须归 helper 所有。

不带目标显示的旧 argv 逐字保持，普通 hierarchy 读的是**活动窗口**，可能是虚拟屏，
不能据此认定主屏内容。无显示编号的缺文件错误仍是历史问题，未在本次修复。
坐标必须来自目标屏最新层级或截图，每步后重新观察；失败时不能去掉显示参数重试。

## MCP 工具与参数

MCP 总工具数为 20。本规格增加的四个管理工具走独立风险门，既有三道风险门不变：

| 工具 | 参数 |
| --- | --- |
| android_display_list | device |
| android_display_status | device |
| android_display_create | device、width、height、dpi；可选 focus，枚举 isolated/steal，默认 isolated |
| android_display_close | device、displayId（正整数）；销毁目标屏及其上应用内容 |

管理输入 schema 均为 additionalProperties:false。MCP 无 helper 启停、部署、任意 shell、
原始标志或 socket/path 参数；未运行时返回 DISPLAYD_NOT_RUNNING，提示操作员先 display start。

android_observe 仅 screenshot、hierarchy 接受可选 displayId，0 明确主屏，正整数先验归属。
层级结果带 displayId、format、xml、sizeBytes、sha256；截图返回 PNG image content 与元数据。
android_action_execute 的以下六类既有动作接受同名 displayId，依据是 service 的目标路由与
既有模型白名单的交集；没有扩大动作面：

| action | 既有参数与路由依据 |
| --- | --- |
| ui.tap | x、y；固定 input -d N tap |
| ui.swipe | startX/startY/endX/endY、可选 durationMs；固定 input -d N swipe |
| ui.longClick | x、y、可选 durationMs；同点长时 swipe，不增加长按语义能力 |
| ui.setText | text；固定 input -d N text，仅受限 ASCII |
| ui.pressKey | key；固定 input -d N keyevent，沿用 21 键白名单 |
| app.launch | package、可选 activity；固定 am start --display N，沿用既有准入 |

ui.scroll 目前依据主屏尺寸，不接受 displayId，包括 0。其它观察、等待/断言/滚动直到、
视觉与 Bridge 工具均拒绝 displayId；不得通过删除参数改向主屏。Bridge 的 Unicode 文本工具
也不能作为虚拟屏中文输入的替代。app.stop、device.sleep/wake 不因目标显示而获准。

示例 tools/call 的 name 与 arguments：

~~~json
{"name":"android_display_create","arguments":{"device":"SERIAL","width":720,"height":1280,"dpi":240,"focus":"isolated"}}
{"name":"android_observe","arguments":{"device":"SERIAL","kind":"hierarchy","displayId":2}}
{"name":"android_action_execute","arguments":{"device":"SERIAL","action":"ui.tap","displayId":2,"x":200,"y":300}}
{"name":"android_display_close","arguments":{"device":"SERIAL","displayId":2}}
~~~

示例编号 2 须替换为实际返回值，坐标须从当前目标层级计算。

## 层级、空屏与无输出

按显示读取的 format 为 uiautomator-windows-xml，根为 displays，只含精确编号对应的
一个 display 及其 window/hierarchy/node；完整校验全部 XML 后才筛选。窗口可能包含系统窗口，
仍需按包定位。重复编号、畸形、截断或超过 8MiB 都失败，绝不返回其它显示。

| 错误码 | 含义与处理 |
| --- | --- |
| DISPLAYD_HELPER_MISSING | 构建并明确指定本地 helper |
| DISPLAYD_NOT_RUNNING | 操作员经 CLI start 后再调用 |
| DISPLAYD_IDENTITY_MISMATCH | 核查进程、socket 或产物冲突，停止操作 |
| UNSUPPORTED_API | 创建/管理要求 API33+，不降级 |
| CAPABILITY_UNAVAILABLE | dump --windows 所需 API 不能可靠确认或低于 API30 |
| DISPLAY_NOT_FOUND | 目标确实不归 helper 所有；重新 list，不能回退主屏 |
| DISPLAY_EMPTY | 归属有效（或主屏 0），有效 XML 中没有该显示的窗口；先在该屏启动应用 |
| WINDOWS_UNAVAILABLE | UIAutomator 没有输出文件或输出为空，拿不到任何窗口；常见于主屏息屏且 isolated，唤醒主屏或用 steal 重建 |
| DEVICE_LOCKED | 等用户正常解除凭据锁，不自动输入凭据或绕过 |
| DISPLAY_ASLEEP | 关闭并重建显示，再启动应用；原屏内容会销毁 |
| FRAME_NOT_READY | 重新观察后可另发取帧请求，不重发输入动作 |

WINDOWS_UNAVAILABLE 只识别无输出及本次 UUID 路径的完整 ENOENT 诊断：兼容 exec-out
退出 0／stdout 合并诊断，以及退出 1／stderr 分离形态；相似文本、其它 cat 错误、截断、
超限、畸形 XML 保持拒绝。不带显示编号的旧路径不改。CLI 新显示错误走通用退出码 10，
MCP 保留相同 code；自动化按 code 分支，不靠英文消息猜测。

## 息屏、凭据锁与任务迁移

helper 每 2 秒向虚拟屏电源组上报活动，只能保住已唤醒的组；凭据锁定期间跳过，
不调用 wakeUp。锁定时拒帧 DEVICE_LOCKED，解锁后该组仍可能休眠并返回 DISPLAY_ASLEEP，
需要 close/create 重建；不会把旧屏应用内容搬回主屏。API33 groupAwake:null，
只能额外检查 Display.getState()，不能保证发现所有组休眠黑帧。SECURE 窗口仍由系统遮黑。

已在主屏运行的应用再向虚拟屏启动时，整个任务可能移过去，不自动另开实例。
观察和输入失败不提供主屏回退；App AI 原有的凭据锁暂停边界保持。

## 焦点实测与验收证据

设备为 osdk-test-35，API35/google_apis/x86_64，主屏320×640/160dpi、en-US、导航模式2，
虚拟屏720×1280/240dpi；只连接 emulator-5554，read-only 冷启动。
指纹为 google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。

主屏新基线为 Settings 的 Add network 对话框，SSID 自动聚焦，空值提示语精确为
Enter the SSID，随后预置 Primary；不点击 Save，结束时按最新节点点 Cancel，
逐字比较前后网络列表。设置搜索进程 STATE CHANGE 的失败和旧夹具遮挡均保留为历史，
未把它们计为通过，亦未修改 fixture。

新基线两策略的逐步数据如下。完整原始记录、旧两组测量及失败证据索引见
[本规格进度](../.trae/specs/vdisplay-mcp-and-focus/progress.md) Round4/5、Round22之后。
主屏字符探针会恢复主屏焦点，表中的每步状态取自探针之前，不能把探针后的恢复误当隔离成功。


S=com.android.settings，F=dev.aiauto.fixture，C=com.google.android.deskclock；三项可见依次是mInputShown、显示0 IME Insets、显示0输入法窗口。主屏输入节点始终focused=true，焦点应用始终WifiDialogActivity。steal数据来自wifi-steal-focus-1，isolated来自B3；每步主屏探针均成功。

| 步骤 | steal 顶层/主屏焦点窗口/active/focused | steal IME客户端/显示；三项可见 | isolated 对应状态 | 主屏文本（探针前→后，两策略相同） | 虚拟输入 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 0/Add network/true/true | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primary → Primarya | — |
| 启动helper/建屏 | 0/Add network/true/true | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primarya → Primaryab | — |
| 虚拟屏启动fixture | 2/null/false/false | F/2；否/否/否 | 0/Add network/true/true；S/0；是/是/是 | Primaryab → Primaryabc | 空 |
| 点按非输入区 | 2/null/false/false | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabc → Primaryabcd | 空 |
| 点按输入框 | 2/null/false/false | F/2；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcd → Primaryabcde | 空 |
| ASCII Virtual | 2/null/false/false | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcde → Primaryabcdef | Virtual |
| 定向DELETE | 2/null/false/false | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcdef → Primaryabcdefg | Virtua |
| 不带显示DELETE后 | 0/Add network/true/true | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcdef → Primaryabcdefh | Virtua |
| 虚拟屏启动时钟 | 2/null/false/false | C/2；否/否/否 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcdefh → Primaryabcdefhi | — |

steal在启动/点按虚拟屏后主屏失焦；启动fixture和时钟时输入法收起，点按虚拟输入框后键盘在主屏可见但服务F/2。中间主屏探针会把IME客户端恢复S/0，所以部分行客户端仍是S；这不改变其顶层显示2/主屏失焦事实，与旧测量一致。不带显示DELETE前顶层已被探针恢复为0，故本时序落在主屏，不能外推任意键盘焦点状态。

isolated 新基线 B1/B2/B3 连续完整通过：每轮9步全部主屏判据、BACK→DISPLAY_EMPTY→重启、
息屏帧/输入/WINDOWS_UNAVAILABLE、唤醒层级恢复和 Cancel/网络不变。
steal 回归保留本规格 Round13 的三次冷启动：65秒保活、PIN往返、重建均通过，
其主屏判据是 Launcher/dumpsys，不使用设置搜索，因此此次不需重跑。
上一规格 seriesB 三次是更早的历史证据，不代替当前两策略验收。
三次样本不代表长期无 flaky，也不代表 API33/34、真机/OEM 或硬件键盘兼容。


### 真实MCP stdio三冷启动结果

| 轮次 | UTC起止 | transport | tools/call次数 | 结果 |
| --- | --- | --- | --- | --- |
| mcp-A1 | 2026-11-14T14:18:38.948Z → 2026-11-14T14:20:43.532Z | 16 | 64 | isolated/steal、全部观察/动作/管理、stdio退出/清理通过 |
| mcp-A2 | 2026-11-14T14:21:37.858Z → 2026-11-14T14:23:34.604Z | 18 | 64 | isolated/steal、全部观察/动作/管理、stdio退出/清理通过 |
| mcp-A3 | 2026-11-14T14:24:29.414Z → 2026-11-14T14:26:31.984Z | 19 | 64 | isolated/steal、全部观察/动作/管理、stdio退出/清理通过 |

三次都通过initialize/initialized、tools/list（20工具，无helper启停）及真实tools/call，非内存会话或CLI替代。每轮先在helper未启动时读显示0层级与PNG，再验证管理入口DISPLAYD_NOT_RUNNING；CLI启动helper后，通过MCP对两种focus分别创建、列表/状态核对、启动fixture、复位、点按/长按/滑动/ASCII/DELETE、层级/帧/主屏归属、关闭及DISPLAY_NOT_FOUND。每轮stdio退出0、清理为0，未设置PIN或修改系统设置。

## 复现入口

先单独启动一次模拟器并等 boot_completed=1；每个 run 结束均清理并关闭模拟器，
连续三次必须分别冷启动，失败从头计数：

~~~text
emulator -avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio
node scripts/emulator/verify-virtual-display.mjs --device emulator-5554 --adb PATH_TO_ADB --aactl PATH_TO_AACTL --helper PATH_TO_HELPER_APK --fixture PATH_TO_LOCAL_DEBUG_FIXTURE_APK --run RUN_NAME
node scripts/emulator/measure-virtual-display-focus.mjs --device emulator-5554 --adb PATH_TO_ADB --aactl PATH_TO_AACTL --helper PATH_TO_HELPER_APK --fixture PATH_TO_LOCAL_DEBUG_FIXTURE_APK --mode mitigated --scenario acceptance --run RUN_NAME
node scripts/emulator/verify-virtual-display-mcp.mjs --device emulator-5554 --adb PATH_TO_ADB --aactl PATH_TO_AACTL --helper PATH_TO_HELPER_APK --fixture PATH_TO_LOCAL_DEBUG_FIXTURE_APK --run RUN_NAME
~~~

三个脚本分别用于 steal 息屏回归、isolated 与真实 MCP stdio。Windows 传真实 .exe 路径，
CLI 的 AACTL_ADB_PATH 指向实际 adb.exe；Git Bash 先 export MSYS_NO_PATHCONV=1。
只允许一次性模拟器：steal 回归会设置测试 PIN/超时并在 finally 恢复；输入基线脚本绝不保存网络。
失败先保存限额脱敏层级、截图、系统分段、实际响应与时间线，再清理。
制品在忽略的 scripts/emulator/build/ 下，不提交截图、APK 或缓存。
