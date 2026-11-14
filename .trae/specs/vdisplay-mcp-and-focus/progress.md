# 虚拟显示的 MCP 出口与焦点隔离进度

本文件 append-only，记录前提核实、RED/GREEN 与验证结果。

## Round 0：立项（2026-11-14）

- 基于分支 `virtual-display-screen-off`（PR lejunyang/ai-auto-android#2，尚未合入
  `main`）叠加。worktree `E:/Projects/wt-vdisplay-mcp`，分支 `vdisplay-mcp-focus`。
- 上一规格的集成方验收里再次复现了普通层级的焦点歧义：虚拟屏上启动应用后，不带
  目标显示的层级读取返回的是虚拟屏应用的内容，且坐标被裁到主屏尺寸。
- 上一轮实测中曾观察到在虚拟屏启动应用后 `mTopFocusedDisplayId` 变为虚拟屏编号，
  尽管虚拟屏设置了独立焦点标志。这说明「独立焦点」未必等于「不影响主屏输入」，
  本规格 M3 专门测量。该观察没有对应的受控实验，不能当作结论。
- 并行说明：另有一个文档清理任务拥有 `docs/next-phase-tasks.md` 等文件；本规格只
  修改 `spec.md` 列出的文档。后续项的路线图登记由集成方在汇合时处理。
- 本轮没有任何实现，`tasks.md` 与 `checklist.md` 全部未勾选。

## Round 1：M1 显式主屏 RED/GREEN（2026-11-14）

- 已完整阅读前置资料并核对 status/worktree；只有本规格四份新文件，先提交
  `6361df3 docs(spec): add MCP virtual display and focus change spec`。未改其它 worktree。
- RED：新增显式主屏的 CLI、service、fake-adb 测试，分别复现参数零值拒绝、
  helper 前置拒绝与截图拒绝。Go shim 对含管道的正则转义失败一次，随后用简单测试名执行。
- GREEN：CLI 的 observe 与五类 action 接受 0；display close 仍拒绝 0。
  service 显式零值绕过 helper，正整数继续 RequireDisplay；真实 ADB 的 New 也可在
  没有 helper 时构造主屏视图。截图用既有 screencap；输入带 -d 0，启动带 --display 0，
  层级 dump --windows 完整校验后只保留 display0，格式 uiautomator-windows-xml。
- fake-adb 逐字核对 tap/swipe/key/text、带/不带 Activity 的 launch、层级与截图；
  未指定显示的 argv 保持原样。此前断言 0 非法的测试迁移为 -1，仍验证无设备调用。
- 验证：make comments 退出0（589文件、16自测）；make test 退出0（协议56项、
  Skills3份及镜像、所有Go包、linux/darwin/windows交叉vet）。首次定向测试暴露旧的
  零值非法断言，修正后全量通过；git diff --check通过。
- 本阶段未启动模拟器、helper，未设PIN、修改设置或创建转发/设备文件。
  收尾 adb devices -l 为空，无 emulator/qemu 进程；因此无需设备清理动作。
- M1 仅有单元/命令证据，真实主屏隔离留待 M4，不提前勾选设备验收。

## Round 2：M2 MCP 出口 RED/GREEN（2026-11-14）

- 前置 M1 `2862aa3` 已通过 make test 并提交，才开始本阶段。串行、单一执行者。
- RED：新增 MCP 会话测试，目标显示参数被旧 schema 拒绝，四个管理工具不存在；
  完成后同一测试 GREEN。过程中未选定版本的 python shim 拒绝启动，改用 Node/补丁，
  没有安装软件或改 osdk 配置。
- 新工具为 android_display_list/status/create/close；采用独立
  requireDisplayManagementAllowed，只收四种操作。helper start/stop/shutdown 无出口。
  输入 additionalProperties:false，尺寸128..2048、dpi72..640、关闭编号至少1；
  数量上限2继续由 helper 强制。关闭工具描述明确销毁其上应用内容。
- 新显示路径将 apperr 映射为带 code/message/retryable 的工具错误；未启动返回
  DISPLAYD_NOT_RUNNING 并要求操作员先 aactl display start。已有错误码全部复用，
  未新增错误码，未改变省略显示时的错误/输出。observe 显式目标返回 displayId，
  层级 format 增加 uiautomator-windows-xml。
- 三道既有门 risk.go、bridge_action.go、file_transfer.go 的完整文件 diff 为空，
  准入标准、注释及名单均未改；文件传输语义不变。service/CLI 仅做接线：MCP 使用
  带显示后端且保留 Bridge 的服务，长按复用已有固定 input swipe 合成。

| android_action_execute 动作 | 可带 displayId | 依据与路由 |
| --- | --- | --- |
| ui.tap | 是 | risk.go 既有白名单；input -d N tap |
| ui.swipe | 是 | 既有白名单；input -d N swipe |
| ui.longClick | 是 | 既有白名单；固定同点 swipe，经相同 input -d N；不新增动作 |
| ui.pressKey | 是 | 既有白名单和原键枚举不变；input -d N keyevent |
| ui.setText | 是 | 既有白名单及原ASCII字符约束不变；input -d N text；不开放Unicode |
| app.launch | 是 | 已在直连白名单；am start --display N；可能迁移既有任务的限制仍适用 |
| ui.scroll | 否 | 既有实现先读不带显示的 wm size，再合成坐标；不能将主屏尺寸当目标屏尺寸。schema 明确拒绝，省略目标仍维持既有行为，接入留后续 |
| device.wake / device.sleep | 否 | wake 不在既有 MCP 白名单；sleep 按规格不开放；没有修改风险门 |
| 其它原来被拒动作 | 否 | 显示字段不改变原风险门，schema与服务双重拒绝 |

- 视觉候选/动作、wait/assert/scroll_until、Bridge工具与其它observe种类均未接入。
  有会话负测试，传 displayId（含0）明确拒绝、服务调用数为0。显示负数、小数、
  字符串、越界及管理未知字段/尺寸均拒绝；helper错误原码透传，无主屏输入回退。
  真实 service + vdisplay 客户端驱动 fake-adb 验证未启动时不发 input。
- README 工具数按一致性测试从16同步为20；未修改其它文档/协议，协议动作本身未变。
- 验证：定向 mcpserver/service/cli 全通过；make comments（591文件、16自测）、
  make test（Go313个顶层Test定义，含1个默认跳过的设备冒烟；协议56项、Skills3份、
  三系统交叉vet）、make verify、make build 全退出0。git diff --check通过。
- 未启动模拟器、helper，未设置PIN或改设置；阶段末 adb devices 为空，无
  emulator/qemu、设备文件、转发或进程残留。
- 汇合待办（只登记本规格）：ui.scroll 的目标尺寸路由；视觉/等待/断言/滚动直到与
  Bridge 的多显示支持。不得将这些未交付项记入历史MVP。

## Round 3：M3 主屏输入基线未建立，停止（2026-11-14）

- 前置 M2 `b9d9e2b` 的定向与全量检查通过并提交，才启动 M3。没有开始 M4，
  没有真实 MCP stdio 的设备验收；连续成功数为 **0/3（未执行）**。
- 新增的仅为 scripts/emulator 下的测量脚本、固定测试探针和解析测试。未修改
  android/app、fixture、displayd、Bridge、其它规格或任何未授权文档；没有实现产品缓解。
- 固定环境：AVD osdk-test-35，API35/google_apis/x86_64，emulator37.1.11，
  每次后台以 -avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio 启动；
  只在确认唯一 emulator-5554 在线、sys.boot_completed=1 后操作。
  初次启动等待过程中出现的 offline 仅作设备枚举，没有在 offline 状态执行设备动作。
  三次均320×640/160dpi、en-US、navigation_mode=2；虚拟屏720×1280/240dpi。
  指纹均为 F35：
  `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`。
- 固定本地产物 SHA-256（未下载/提交APK或二进制）：
  CLI d85bd0a3baa7e6d001fc0425db6e12b4f26da41417b23245a192c2dd2fd12f0f；
  helper b855ecc31fd631b5f663d565c199846fcb787b8bc9513cae20236e9689a1aa29；
  dev.aiauto.fixture debug 1/1.0.0：
  1e88acf7a0b88f00df212648ca93c3487b37d0cb50e19b92569b80aa8462f6f8。
  结束时重新 make build 的CLI摘要仍相同。

### 实际准备结果

| 尝试 | UTC开始→清理结束 | transport | 主屏输入基线准备 | 实际证据与停止原因 |
| --- | --- | --- | --- | --- |
| focus-A | 07:38:27.277→07:39:11.547 | 21 | 最新display0层级的text_input内85%高度点按，然后输入Primary | baseline复合断言失败；脱敏层级仍 focused=false。输入框bounds [12,31][308,79] 在标题栏覆盖范围内；未执行虚拟屏五步 |
| focus-B | 07:41:29.831→07:42:21.755 | 22 | 正常键盘TAB导航后，先验证输入节点焦点 | focused仍false，立即停止；没有继续DPAD_CENTER或输入，未盲目重复TAB |
| focus-C | 07:44:52.500→07:45:46.628 | 23 | 固定UiAutomation探针只查display0的dev.aiauto.fixture:id/text_input，ACTION_FOCUS/ACTION_CLICK | app_process返回Killed；没有成功聚焦证据。清理前截图中输入框完全被标题栏挡住、INPUT:为空、无软键盘；层级仍focused=false，停止 |

- focus-C 的固定探针仅是基线构造实验，**不是已验证可用能力**；Killed 的根因未确诊，
  不能推断为平台不支持或焦点隔离失败。没有通过改变AVD分辨率、系统设置、fixture布局、
  切换主屏测试应用或删除判据绕过基线。基线仍要求：输入节点focused=true、文本Primary、
  当前IME客户端是fixture且display0、mInputShown=true及display0 IME Insets可见。
- 另一输入应用准备成功：虚拟屏Settings搜索打开Settings Intelligence的
  `com.google.android.settings.intelligence:id/open_search_view_edit_text`。仅准备搜索输入，
  没有进入或改变任何系统设置。该准备过程不能冒充受控主屏输入测量。
- 原生框架查询发现虚拟屏的 getDisplayImePolicy 原值已是 HIDE=1。
  计划的对照为固定 IWindowManager.setDisplayImePolicy(id, LOCAL=0)，之后恢复原值；
  因主屏基线门未通过，**没有应用LOCAL、没有缓解后重测**。finally调用restore1并核对，
  不把同值恢复写为有效缓解。HIDE原本存在也不能证明不会打断主屏。

| 应测步骤 | 未缓解：主屏窗口/应用、IME客户端/显示、IME可见、输入内容与主屏字符落点 | 缓解后同项 |
| --- | --- | --- |
| 主屏输入基线 | 未建立，以上三次失败；不能把普通层级当主屏证据 | 未执行 |
| 虚拟屏点按非输入区 | 未执行 | 未执行 |
| 虚拟屏点按输入框 | 未执行 | 未执行 |
| 虚拟屏 action text | 未执行 | 未执行 |
| 虚拟屏按键 | 未执行 | 未执行 |
| 虚拟屏启动另一应用 | 未执行 | 未执行 |

- 因此**没有焦点隔离或输入法兼容性的结论**，也不能写成「缓解无效」；本阶段停在
  构造实验前提失败，tasks/checklist中的M3测量、缓解与M4全部保持未勾选。
  测量脚本后续阶段的代码尚未在设备上执行，不能声称其完整流程已验收。

### 固定系统App来源

全部来自F35原装system image，ABI以x86_64设备环境为准，没有抓取或重新分发APK。

| package | versionCode / versionName | 系统APK SHA-256 |
| --- | --- | --- |
| com.android.settings | 35 / 15 | e2152dfa52fc0c54b058ba0b43ddb328c5a21c9db011f7aa2bd36fd036df1815 |
| com.google.android.settings.intelligence | 1000230943 / 1.1.0.638941663.sr | 49f4be9f075a290893009d515eeb24fd58ca597e620b353ed93fb4288d5abf74 |
| com.google.android.deskclock | 76002074 / 7.5 (563137996) | 1389710d90b0c8d1f77a6517ca3ffda91b45ae2239a738efb265a179e1927867 |
| com.google.android.inputmethod.latin | 143544272 / 14.2.09.629370537-preload-x86_64 | 74dbae9c0057304784c54294bbebc9d4475ced94fdbee4f6d87079f0d98ba511 |

### 脚本复现与限制

- 测量入口 measure-virtual-display-focus.mjs，依赖 focus-session.mjs；选项均为本地产物
  --device emulator-5554 --adb 实际adb.exe --aactl 本地aactl.exe --helper 本地debug辅助APK
  --fixture 本地debug-fixture.apk --policy 本地policy.jar --focus 本地focus.jar --run 唯一轮次名。
  aactl使用AACTL_ADB_PATH指向实际adb.exe，子进程MSYS_NO_PATHCONV=1。
- 测试探针由本地osdk工具链构建：javac -encoding UTF-8 --release 17；PrimaryInputFocus
  的classpath为osdk where android-platforms下的android.jar；D8使用osdk where
  android-build-tools下lib/d8.jar，--min-api35，输出policy.jar/focus.jar。命令均经
  osdk exec -t java -t android-platforms -t android-build-tools -t android-platform-tools --。
  Java connect/disconnect是固定反射调用，未向调用方暴露类名、方法名或任意框架入口。
- parseFocus只取Display:mDisplayId=0分段的当前焦点/IME Insets，以及当前mCurClient、
  mCurTokenDisplayId、mInputShown；客户端PID由ps映射，不用活动窗口或历史条目代替。
  node --test scripts/emulator/focus.test.mjs三项通过，覆盖显示顺序反转、证据缺失拒绝、
  当前客户端归属、历史值排除及非目标serial守卫；两份mjs的node --check通过。
- 失败证据总量分别29715、29453、65063字节，各小于1MiB。早期A/B采集器只有脱敏层级
  与报告，未保存截图/日志时间窗，是本轮证据采集的偏离，不能事后补造过去状态。
  C补齐主屏截图35573字节、脱敏层级24297字节、displayd日志窗口0字节、时间线与环境
  报告5193字节；截图已目视核对只含无敏感数据的本地fixture。摘要记录后清理本轮
  证据目录与探针构建目录，不提交图片/日志/缓存/二进制。
- 三次finally均停止helper、确认只剩主屏、forward为空、清理辅助APK/日志与两份探针jar；
  未设置PIN、未改全局设置，策略恢复确认后emu kill。每轮关机后确认无emulator/qemu，
  最终adb devices为空，宿主进程查询结果[]。没有操作真机、其它serial或其它worktree。
- 路线图汇合待办：先在不改测量判据的前提下修正主屏fixture输入基线，调查固定探针
  Killed原因，再从M3重新执行受控测量；M3通过后才能恢复M4的真实stdio三次冷启动。
  API33/34、真机/OEM、硬件键盘以及M2表中的未接入动作仍未覆盖，未改路线图文件。

### 证据归属更正与采集器收尾

- 更正上文focus-C的层级表述：逐行复核脚本调用顺序后确认，原failure-hierarchy.xml是
  探针执行前的最后一次层级缓存，focused=false只能说明动作前状态；探针返回Killed后
  没有成功重新抓层级，不能据此宣称探针后的节点仍为false。动作后的证据只有主屏截图
  （无IME、INPUT:为空），仍不足以建立输入基线。保留上文历史并在此明确更正。
- 原A是在snapshot时读取的动作后层级，B是在TAB后明确读取的层级；上述更正仅针对C。
  没有补造过去状态，没有新增模拟器尝试。以后采集器在失败后只重新观察，不重试动作；
  抓取失败时报告cached层级的displayId/observedAt和capturedAfterFailure=false，
  防止后续误读。该收尾变更仅经本地语法/解析测试，未宣称已有设备验收。

### 本轮最终本地验证与保存边界

- make comments退出0：596个受管手写文件、16个检查器自测；新增五份脚本/探针均纳管。
- make test退出0：Go313个顶层Test定义（含1个默认跳过的显式设备冒烟）、各包测试/vet、
  linux/darwin/windows三次交叉vet；协议14schemas、14合法+38非法+4兼容=56项，
  Skills3份及镜像通过。M3没有额外启用Go设备冒烟，不能把默认跳过算设备成功。
- make verify、make build退出0；node --test scripts/emulator/focus.test.mjs三项通过，
  两个生产mjs的node --check通过。最后的失败证据来源标记仅改新脚本，定向检查已复核，
  没有产品代码变化需要重跑Go或设备场景。
- make android-verify经Makefile的osdk exec退出0，BUILD SUCCESSFUL（5m6s、199任务）：
  testDebugUnitTest/assembleDebug/lintDebug通过，612项，0failure、0error、1既有跳过；
  app564、device-fixture8、web-fixture6、displayd34。本轮未修改android/源码，构建检查
  也不改变M3/M4的设备验收状态；既有弃用/编译警告未越界修补。
- git diff --check通过；三道既有风险门完整文件相对基线diff为空。main未改、未push。
  证据与探针构建目录均在核对绝对路径、无跟踪文件及无symlink后删除，确认不存在。
  最终设备列表空、emulator/qemu进程列表[]；保留当前独立worktree供集成，不移除分支。
- 本次M3仅保存失败准备与真实证据摘要，提交信息为
  `test(emulator): record blocked primary-display focus setup`，**不是M3验收提交**；
  与原建议完成提交信息的差异来自阶段停止。M4工具设备验收和文档交付均未执行。

## 集成方焦点实测（2026-11-14）

- 复核对象 `0acf58b`。M1、M2 的提交范围与三道既有风险门文件（diff 为空）已核对；
  M3 停在基线未建立，判断正确。以下是集成方为定位卡点做的手工实验，环境与前面
  各轮相同（AVD `osdk-test-35`、`-read-only -no-snapshot`、唯一设备 `emulator-5554`、
  同一指纹、主屏 320×640/160dpi、虚拟屏 720×1280/240dpi）。
- **基线失败的根因**：没有辅助进程时把 fixture 放到主屏，`--display 0` 的层级显示
  输入框 `bounds=[12,31][308,79]`、`focusable=true`、`focused=false`，而状态栏占
  `[0,0][320,24]`、标题栏延伸到 y=80。输入框被标题栏完全盖住，点按落在标题栏上。
  这是 fixture 在 API 35 edge-to-edge 下的布局问题，不是焦点现象。
- **换用系统设置搜索框后基线成立**：`am start -a android.settings.SETTINGS`，按
  `--display 0` 层级点按搜索栏，`aactl action text --display 0 --text Primary`。
  结果：输入框 `text=[Primary] focused=true`；`mCurClient` 属于
  `com.google.android.settings.intelligence`、`mSelfReportedDisplayId=0`；
  `mInputShown=true`；显示 0 的窗口列表含输入法窗口；`mTopFocusedDisplayId=0`。

### 未缓解（当前 displayd，标志位 64971）

| 步骤 | 主屏输入法是否显示 | 输入法服务的客户端 | 顶层焦点显示 | 主屏窗口 active/focused | 主屏输入框 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 是 | 设置搜索（显示 0） | 0 | true/true | `Primary`，有焦点 |
| 启动辅助进程并创建虚拟屏 | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 在虚拟屏启动 fixture | **否** | **fixture（显示 2）** | **2** | **false/false** | 文本不变 |
| 虚拟屏点按非输入区 | 否 | fixture（显示 2） | 2 | false/false | 文本不变 |
| 虚拟屏点按输入框 | **是，且显示在主屏** | **fixture（显示 2）** | 2 | false/false | 文本不变 |

结论：未缓解时，在虚拟屏上启动应用就会打断主屏输入——主屏键盘收起、输入法改为
服务虚拟屏应用。点按虚拟屏输入框后键盘重新出现在**主屏**上，但服务对象是虚拟屏的
输入框，用户此时在主屏键盘上敲的字会进入虚拟屏应用。虚拟屏的输入法策略原值为
HIDE 并不能阻止这一点。后续步骤（文本、按键、启动另一应用）在已被打断的状态下
没有继续测量。

### 缓解后（一次性探针，标志位 130507 = 64971 | 1<<16）

探针即 Round 0 的一次性程序（非仓库产物），只改了创建标志。`Display.getFlags()`
由 20360 变为 24456（多出 `1 << 12`，即显示层的「禁止抢占顶层焦点」）。

| 步骤 | 主屏输入法是否显示 | 输入法服务的客户端 | 顶层焦点显示 | 主屏窗口 active/focused | 主屏输入框 |
| --- | --- | --- | --- | --- | --- |
| 重新建立基线 | 是 | 设置搜索（显示 0） | 0 | true/true | `Primary`，有焦点 |
| 创建虚拟屏 | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 在虚拟屏启动 fixture | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 虚拟屏点按非输入区 | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 虚拟屏点按输入框 | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 虚拟屏 `input -d N text Virtual` | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 虚拟屏 `input -d N keyevent KEYCODE_DEL` | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 在虚拟屏启动时钟 | 是 | 设置搜索（显示 0） | 0 | true/true | 不变 |
| 主屏 `action text --display 0 --text X` | 是 | 设置搜索（显示 0） | 0 | true/true | `PrimaryX` |
| 不带显示的 `input text Q` | 是 | 设置搜索（显示 0） | 0 | true/true | `PrimaryXQ` |

虚拟屏一侧同时成立：取帧目视为 fixture，`INPUT:Virtua`（输入 Virtual 后删一字）、
`CLICK` 计数递增；`dumpsys activity activities` 中显示 0 的前台仍是设置搜索，虚拟屏
前台按步骤变化；`uiautomator dump --windows` 仍包含该虚拟屏的显示分组。

结论：在 API 35 模拟器上，追加该标志后虚拟屏上的启动、点按、文本、按键都不再影响
主屏的输入法与焦点，且虚拟屏自身的输入仍然有效。未带显示的按键落在主屏。

- 这些是手工实验数据，用来确定方向；验收证据仍须由 M3 的脚本独立复现两组结果。
- 未验证：API 34（标志自该版本起存在，未上机）、真机与 OEM、硬件键盘、虚拟屏上
  需要输入法才能完成的输入（中文等，带该标志后输入法不会服务虚拟屏）。
- 清理：探针已停止，设备临时文件与转发已清，模拟器已关闭。


## Round 4：恢复M3，独立未缓解测量（2026-11-14）

- 开工HEAD=d70eacb，status干净；重读规格修订、集成方实测和移交P01–P15。单人串行，未派发Sub-Agent，移交worktree仅只读。按用户增量新增M5计划和镜像同步范围，尚未开始M4/M5实施。
- 主屏改为系统设置搜索S（com.google.android.settings.intelligence/.modules.search.SearchActivity）；虚拟屏仍为fixture F；C为com.google.android.deskclock。基线完整要求输入节点focused=true、文本Primary、当前IME客户端S/显示0、mInputShown=true、显示0 IME Insets可见，且窗口列表存在TYPE_INPUT_METHOD。没有修改fixture、AVD或系统设置。
- 三次采集器准备失败均未计为有效测量：resume-raw-1（transport26）错误依赖InputMethod标题；实际截图/TYPE_INPUT_METHOD及其bounds证实键盘可见，改按稳定窗口类型识别。resume-raw-2（transport27）当前客户端PID未能与ps行关联，改为严格PID+任意空白分隔，并增加缺失诊断；没有据此宣称焦点失败或完全确诊当次时序。resume-raw-3（transport29）完整基线和字符a成功后，读取空虚拟屏的窗口组返回DISPLAY_NOT_FOUND；创建步骤改读helper状态，应用启动后才读取层级。每次均停止并清理、冷启动重跑，不拼接结果。
- 有效未缓解轮次resume-raw-4：2026-11-14T08:15:10.776Z → 2026-11-14T08:18:34.228Z，transport31；原helper SHA-256 b855ecc31fd631b5f663d565c199846fcb787b8bc9513cae20236e9689a1aa29，创建标志64971（尚未修改）。测量脚本SHA-256 a9ddcc9ddc04962f62220f47d2b29d4ef2923a2bc88aa2d41ec3cf72a9260dd4，缓解后使用同一脚本。
- AVD osdk-test-35，API35/google_apis/x86_64，emulator37.1.11；后台参数-read-only -no-snapshot -no-boot-anim -no-audio，唯一emulator-5554，boot_completed=1。主屏320×640/160dpi、en-US、导航模式2；虚拟屏720×1280/240dpi。指纹与Round3的F35相同：google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。CLI和fixture SHA、四个系统App版本/系统APK SHA均由本轮再次独立采集，与Round3记录一致。

表中是每次虚拟屏动作后、向主屏输入探针字符之前的状态；窗口=主屏窗口active/focused，S窗口表示SearchActivity，主屏焦点应用始终是S，输入节点focused始终true。IME三列依次为服务mInputShown、显示0 Insets可见、显示0 TYPE_INPUT_METHOD窗口存在。

| 步骤 | 顶层焦点显示 | 主屏焦点窗口 | 窗口active/focused | 当前IME客户端/显示 | IME服务/Insets/窗口 | 主屏输入框（探针前→后） | 虚拟输入 | 主屏字符落点 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 主屏基线 | 0 | S窗口 | true/true | S/0 | true/true/true | Primary → Primarya | — | a：主屏 |
| helper启动/建屏 | 0 | S窗口 | true/true | S/0 | true/true/true | Primarya → Primaryab | — | b：主屏 |
| 虚拟屏启动fixture | 2 | null | false/false | F/2 | false/false/false | Primaryab → Primaryabc | 空 | c：主屏 |
| 虚拟屏点按非输入区 | 2 | null | false/false | S/0 | true/true/true | Primaryabc → Primaryabcd | 空 | d：主屏 |
| 虚拟屏点按输入框 | 2 | null | false/false | F/2 | true/true/true | Primaryabcd → Primaryabcde | 空 | e：主屏 |
| 虚拟屏ASCII Virtual | 2 | null | false/false | S/0 | true/true/true | Primaryabcde → Primaryabcdef | Virtual | f：主屏 |
| 虚拟屏DELETE | 2 | null | false/false | S/0 | true/true/true | Primaryabcdef → Primaryabcdefg | Virtua | g：主屏 |
| 不带显示DELETE之后 | 0 | S窗口 | true/true | S/0 | true/true/true | Primaryabcdef → Primaryabcdefh | Virtua | h：主屏 |
| 虚拟屏启动时钟 | 2 | null | false/false | C/2 | false/false/false | Primaryabcdefh → Primaryabcdefhi | — | i：主屏 |

- 每行主屏字符探针之后，独立复查均为topFocusedDisplay=0、S/0、三项IME可见true、主屏窗口active/focused=true；这说明显式display0键注入会恢复主屏，**不代表虚拟屏动作隔离成功**。与集成方没有逐步主屏探针的连续操作不同，本轮非输入区点按/text/DELETE之后，IME客户端有时仍是S/0，但顶层显示已经变为2、主屏窗口失焦；如表原样记录，不调整方法去迎合集成方表。
- 不带显示DELETE的实测前提：此前主屏字符g已将焦点恢复显示0。主屏Primaryabcdefg→Primaryabcdef，虚拟屏Virtua不变；之后主屏探针h成功。不能把这一结果外推为未缓解、虚拟屏持有顶层焦点时的任意按键去向。
- 虚拟侧确有CLICK:1，ASCII Virtual→DELETE后Virtua；时钟启动目标包正确，720×1280截图921600/921600像素非黑。原始测量不是必须无影响的测试，以上打断均作为结果保存。
- 原两份Java探针已从脚本路径及仓库移除，Killed根因未查清，不阻塞本轮。没有使用IME策略探针或同值策略设置当作缓解。Go侧只核对hello/API/预算和groupAwakeQuery，不假设虚拟屏flags；协议拒绝调用方flags的负fixture无需修改。
- 本轮finally完成helper停止、只剩主屏、无forward/固定临时文件、emu kill；未设PIN、未改全局设置。模拟器关闭后确认无emulator/qemu才进入标志位RED/GREEN。


## Round 5：固定标志RED/GREEN与缓解后独立测量（2026-11-14）

- 未缓解resume-raw-4完整测量与清理完成后，先更新PolicyTest，API34/35/36要求130507、API33仍15819，逐API排除SECURE并明确API33不含1<<16。RED：:displayd:testDebugUnitTest --tests dev.aiauto.displayd.PolicyTest共17项、1失败（fixedFlagsRespectApiAndSecureBoundary，旧64971不符130507）；再改Policy.flags的固定API34+分支追加1<<16，不增加调用方参数或通用反射。
- GREEN：经osdk exec运行:displayd:testDebugUnitTest :displayd:assembleDebug :displayd:lintDebug，BUILD SUCCESSFUL（26秒），helper全部34项0failure/error；PolicyTest17项全通过。同步android/displayd/README的数值、API33未缓解及IME不服务虚拟屏/ASCII注入限制。Go没有标志位假设，三道既有风险门完整文件diff仍为空。
- 缓解后resume-fixed-1：2026-11-14T08:21:56.742Z → 2026-11-14T08:25:31.239Z，transport33，display2；实际重建/部署helper SHA-256 747be5ca0be6f62f2127c2a63ff55d38ae17b4c4ccc13205072867a1f4204478。与未缓解使用同一测量脚本SHA-256 a9ddcc9ddc04962f62220f47d2b29d4ef2923a2bc88aa2d41ec3cf72a9260dd4，CLI/fixture/system image/profile未变。未调用已删除的聚焦/IME策略探针。

以下仍为每个动作后、主屏字符探针之前的状态；主屏焦点窗口与焦点应用均为S SearchActivity，当前IME客户端均S/显示0，IME服务是原LatinIME。

| 步骤 | 顶层焦点显示 | 主屏窗口active/focused | IME服务/Insets/窗口 | 主屏输入框（探针前→后） | 虚拟屏输入框 | 主屏字符落点 |
| --- | --- | --- | --- | --- | --- | --- |
| 主屏基线 | 0 | true/true | true/true/true | Primary → Primarya | — | a：主屏 |
| helper启动/建屏 | 0 | true/true | true/true/true | Primarya → Primaryab | — | b：主屏 |
| 虚拟屏启动fixture | 0 | true/true | true/true/true | Primaryab → Primaryabc | 空 | c：主屏 |
| 虚拟屏点按非输入区 | 0 | true/true | true/true/true | Primaryabc → Primaryabcd | 空 | d：主屏 |
| 虚拟屏点按输入框 | 0 | true/true | true/true/true | Primaryabcd → Primaryabcde | 空 | e：主屏 |
| 虚拟屏ASCII Virtual | 0 | true/true | true/true/true | Primaryabcde → Primaryabcdef | Virtual | f：主屏 |
| 虚拟屏DELETE | 0 | true/true | true/true/true | Primaryabcdef → Primaryabcdefg | Virtua | g：主屏 |
| 不带显示DELETE之后 | 0 | true/true | true/true/true | Primaryabcdef → Primaryabcdefh | Virtua | h：主屏 |
| 虚拟屏启动时钟 | 0 | true/true | true/true/true | Primaryabcdefh → Primaryabcdefhi | — | i：主屏 |

- 独立核对全部9行的前/后状态、主屏字符落点、相邻行主屏文本：除明确的不带显示DELETE外，虚拟屏动作没有修改主屏内容。每行主屏输入节点始终focused=true；虚拟屏CLICK:1、text=Virtual、定向DELETE后Virtua，启动时钟包名与720×1280非黑帧正常。
- 不带显示DELETE前top=0，主屏Primaryabcdefg→Primaryabcdef，虚拟屏Virtua不变；两个模式在各自主屏字符探针之后都得到此结果。它是本脚本时序的实测，不等同于未缓解且虚拟屏持有顶层焦点时的去向保证。
- 结论仅限本次API35固定模拟器：固定标志使已测虚拟屏启动/点按/ASCII/按键不再抢占主屏焦点或IME客户端；当前结果与集成方缓解后结论一致，来源为本轮独立脚本，不复用其手工数据。API33保持未缓解；API34代码/单测不等于设备验收，真机/OEM、硬件键盘与虚拟屏中文仍未验。
- 两轮都finally停止helper、确认只剩主屏、无转发/临时文件，关闭模拟器，无PIN/全局设置变更。Node焦点4项、旧验收脚本4项本地测试通过，测量脚本node --check通过。M3仍等待原verify-virtual-display.mjs在新标志下的完整回归，未提前进入M4。

## Round 6：原验收脚本回归失败，停在M3（2026-11-14）

- 原样运行 scripts/emulator/verify-virtual-display.mjs，文件相对d70eacb无diff，SHA-256
  fe6a675075fee147e6c3c5254820d669160024fa7e34d255cec5e465ebd6df2d。没有删检查、改判据或
  改写原脚本。AACTL_ADB_PATH指向真实adb.exe，MSYS_NO_PATHCONV=1，显式emulator-5554。
- focus-flag-regression-1：UTC 08:27:02.167→08:28:08.485，transport34；固定F35环境、
  新helper SHA 747be5ca0be6f62f2127c2a63ff55d38ae17b4c4ccc13205072867a1f4204478，
  同一CLI/fixture。创建/列出/启动成功，主屏保持NexusLauncher；CLICK:1及帧变化通过，
  取帧720×1280且全部921600像素非黑。普通dump返回主屏NexusLauncher（预期的顶层焦点
  变化，argv没有改变），不再是旧标志下的fixture，仍只作为观察记录。
- **回归失败**：ASCII输入/BACK这一段随后读取目标层级时返回
  DISPLAY_NOT_FOUND: Requested display has no window group in UIAutomator output。
  报告最后的显式动作事件是ui.setText。原脚本在这段还执行BACK，但未对每条命令记录
  单独时间线，因此现有报告不能严格区分错误发生在text后还是BACK后的层级读取；
  不将“BACK一定关闭应用”写成已证实根因，也不把缓存层级或截图当作失败后的状态。
  原脚本的“2-inputs”没有完成，未执行滑动、主屏息屏、65秒保活或PIN往返。
- 按用户明确的“有变化就如实记录并停下来报告”，本轮在这里停止设备验收；
  **M3整体未验收，M4、M5均未开始**。没有为了过回归调整键值、重启fixture或更改原脚本，
  没有启动MCP stdio设备验收（仍未执行，0/3），不把两组焦点测量通过代替完整回归。
- 原脚本finally恢复原screen_off_timeout=2147483647、停止helper并核对forward为空、
  清理固定辅助文件、emu kill；尚未设置测试PIN，无需伪造清PIN操作。随后宿主确认
  adb devices为空、emulator/qemu进程列表[]。原脚本没有cleanupError。
- 回归失败证据98297字节：缓存fixture PNG78353、脱敏XML16044、日志时间窗0、报告3900；
  已目视截图只含本地fixture。缓存PNG在ASCII/BACK之前，不能证明失败后画面。
  三次准备失败证据分别116057、30968、118150字节，均小于1MiB；raw-2未保存截图，
  当时新鲜display0层级包名为com.android.settings和systemui，不符合只采集fixture/
  设置搜索截图的条件。其具体生命周期变化未确诊，不能把PID关联失败简单断言成排版问题。
  有效未缓解/缓解报告分别32429/33206字节；摘要写入本规格后按策略清理本轮本地产物。
- P01–P15处理状态：已只读接收移交，尚未进入逐条代码核实、修订或Skills镜像同步。
  它们不是“核实通过”，也不以当前make skills-check通过冒充M5已完成。M5保持全部未勾选。
- 后续汇合项：独立规格修正fixture在API35的edge-to-edge输入框遮挡；定位新标志下原
  回归ASCII/BACK段窗口组消失，先恢复M3回归再执行M4；M4通过后才核实P01–P15。
  继续保留API33未缓解、API34/真机/OEM/硬件键盘未验与虚拟屏IME/中文不支持边界。
  未改docs/next-phase-tasks.md、其它规格或移交worktree。

### 停止前的全量本地检查

- make comments退出0，594个手写文件、16个检查器自测；两份不用的Java探针已删除。
- make test退出0：Go313个顶层Test定义（含1个默认跳过的显式设备冒烟）、包测试和vet，
  linux/darwin/windows交叉vet均通过；协议56项，Skills3份及镜像通过。
- make android-verify退出0（18秒、199任务，复用缓存），单测/assembleDebug/lintDebug通过；
  612项、0failure/error、1既有跳过（app564、fixture8、web-fixture6、displayd34）。
- Node焦点解析/基线4项、原验收脚本守卫/PNG/主屏归属4项均通过，node --check通过；
  三道既有风险门完整文件未改。以上本地检查不替代已经失败的设备回归。
- 本轮按职责保存固定标志实现与独立测量两个提交；这两份提交不表示M3整体通过。
  M4/M5没有实现或文档交付提交，不继续越过依赖阶段。

### 本轮保存与清理确认

- make verify、make build均退出0；重新构建CLI仍为
  d85bd0a3baa7e6d001fc0425db6e12b4f26da41417b23245a192c2dd2fd12f0f。
  git diff --check通过；原回归脚本与三道既有风险门完整文件未改。
- 固定标志实现已单独保存为41c4e7c（feat(displayd): keep virtual displays from stealing top focus），
  仅Policy.java、PolicyTest.java、android/displayd/README.md三个文件。
- 测量脚本、两份不用探针删除及本规格状态另行保存为
  test(emulator): measure primary-display focus under virtual display input；包含本轮新增的M5计划，
  没有提前修订M5所拥有的文档或Skills镜像。这是M3部分成果保存，不是M3验收通过。
- 本地focus证据根与本轮单个回归证据目录经绝对路径、无symlink、无跟踪文件及Git忽略
  规则核对后，用非强制Remove-Item清理，Test-Path均false。未删除其它运行或用户文件。
  设备列表空、emulator/qemu进程列表[]，未push、main未改；保留当前worktree供集成。

## 集成方定位：回归失败不是窗口组异常消失（2026-11-14）

- 复核对象 `5ac8c79`。在同一固定模拟器环境下，用本分支构建的 `aactl` 与带新标志的
  displayd 逐步执行，并在每个动作后直接读取 `uiautomator dump --windows` 的显示分组：

| 动作 | 显示 0 | 显示 2 |
| --- | --- | --- |
| 创建虚拟屏 | 2 个窗口 | 无分组（屏上没有任何窗口） |
| 启动 fixture | 2 个窗口 | 1 个窗口（Device Fixture） |
| 点按按钮 | 2 个窗口 | 1 个窗口 |
| 点按输入框 | 2 个窗口 | 1 个窗口 |
| `action text --display 2` | 2 个窗口 | 1 个窗口 |
| `action key --display 2 --key back` | 2 个窗口 | **无分组** |

- BACK 之后 `dumpsys activity activities` 的 `Display #2` 下已没有任何 Activity：
  fixture 的根 Activity 被 BACK 结束了，虚拟屏变空，自然没有窗口分组。此时
  `display list` 仍列出该屏，`observe hierarchy --display 2` 返回 `DISPLAY_NOT_FOUND`。
- 这是新标志带来的**行为变化，而不是缺陷**：未加标志时，点按虚拟屏输入框会让输入法
  为它服务，随后的 BACK 先被输入法消费（收起键盘）；加标志后输入法不再服务虚拟屏，
  BACK 直接交给 Activity，根 Activity 随即结束。上一规格验收脚本里「输入文本后按
  BACK」这一步隐含依赖了前一种行为。该因果链中「未加标志时 BACK 被输入法消费」
  一环是推断，未单独实验。
- 由此暴露一个真实的可用性问题：屏仍归辅助进程所有、只是上面没有窗口时，返回
  `DISPLAY_NOT_FOUND` 会误导调用方以为屏不存在。
- 清理：辅助进程已停止，设备临时文件与转发已清，模拟器已关闭。

## Round 7：M3b独立BACK因果链（2026-11-14）

- 开工HEAD=ed944ce、工作区干净，已重读集成方定位。M3b → M4 → M5单人串行，未派发Agent。
- 两份对照构建全部在本worktree的忽略目录scripts/emulator/build/back-cause中生成：
  当前五份Java生产源码复制到独立src目录，旧Policy取git show d70eacb的原文；经同一本地
  osdk javac --release17与D8流水线生成两个CLASSPATH DEX jar，不安装、不下载APK。
  二进制逐类比较仅Policy.class不同，没有改动android/displayd跟踪文件或其它worktree。
- 两次均独立冷启动固定osdk-test-35，-read-only -no-snapshot -no-boot-anim -no-audio，
  唯一emulator-5554、boot_completed=1，F35指纹、320×640/160dpi、en-US、导航模式2，
  虚拟屏720×1280/240dpi。CLI仍d85bd0a3...、fixture仍1e88acf7...（完整摘要见前轮）。
- 旧构建old-cause：UTC08:48:20.821→08:49:08.215，transport37；helper jar SHA-256
  14a35b20ee490305fa2c38ce7540708353fcc58180fb0d8631fdcce4473d202f，标志64971。
- 新构建new-cause：UTC08:50:52.763→08:51:35.927，transport38；helper jar SHA-256
  46dabae719aa57be37e46061cbba183c9625c6855df137e640225c75d394f110，标志130507。

| 构建与观察点 | 虚拟屏topResumedActivity | 主屏窗口数 / 虚拟屏窗口数 | 当前IME客户端显示 / mInputShown | helper仍拥有显示2 | 层级 |
| --- | --- | --- | --- | --- | --- |
| 64971，点按输入框后 | fixture MainActivity | 3 / 1 | fixture显示2 / true，IME token显示0 | 是 | 成功 |
| 64971，第一次BACK后 | fixture MainActivity | 2 / 1 | fixture显示2 / false | 是 | 成功 |
| 64971，键盘已隐藏后再BACK | 无前台Activity | 2 / 无分组 | launcher显示0 / false | 是 | 旧错误DISPLAY_NOT_FOUND |
| 130507，点按输入框后 | fixture MainActivity | 2 / 1 | launcher显示0 / false | 是 | 成功 |
| 130507，第一次BACK后 | 无前台Activity | 2 / 无分组 | launcher显示0 / false | 是 | 旧错误DISPLAY_NOT_FOUND |

- 实验补齐了旧标志下“第一次BACK收键盘且保留根页面、下一次BACK才退出”的行为链；
  新标志没有虚拟屏IME，第一次BACK直接使根页面退出。不是helper丢屏，也不是损坏的XML。
  因果链以窗口/IME/按显示分段的topResumedActivity共同核对，不采信普通活动窗口dump。
  诊断报告的宽泛ActivityRecord提及列表可能包含全局尾部，因此表只使用该显示的
  topResumedActivity，不把尾部Launcher提及当作虚拟屏活动。
- 每次动作前均最新观察、显式serial、记录预期；第二次旧BACK是在已确认IME隐藏且
  fixture仍在的状态下作对照，不是失败后的盲目重试。没有输入文本、PIN或修改设置。
  两轮finally清理helper/显示/转发/设备XML、关闭模拟器，无emulator/qemu后才换构建。
- RED：先新增ADB有效空输出、空组、坏尾部测试，以及真实service/ADB驱动的CLI/MCP
  空屏与不归属区别测试。原实现均把空屏报DISPLAY_NOT_FOUND而失败；主屏0同样覆盖。
  后续实现只改变空屏分类，保留完整XML校验、随机文件清理和不回退边界。

## Round 8：M3b空屏错误RED/GREEN（2026-11-14）

- 新增DISPLAY_EMPTY，retryable=false，消息要求先在目标显示启动应用；有效XML无目标组
  或目标组零窗口时返回该错误。只在完整校验全部XML之后分类，坏尾部仍ACTION_FAILED，
  超限/重复编号/截断仍沿用原拒绝。主屏0适用且不要求helper；正整数在service中先验归属。
- DISPLAY_NOT_FOUND仍由helper归属门处理不归属目标。没有把空屏改成成功、空XML输出或
  普通dump回退；service只补边界注释，MCP既有通用apperr映射自然保留新code，三道风险门
  文件完全未改。ExitCode映射未改，两种显示错误仍为既有通用退出码10，应按error.code分支。
- RED三处实测失败：ADB解析器、CLI真实service/ADB路径、MCP真实service/ADB路径都把
  DISPLAY_EMPTY误报DISPLAY_NOT_FOUND；实现后同样测试GREEN。CLI/MCP均覆盖0、已归属7、
  不归属9；9在发出ADB读取前拒绝，0不查helper，已存在空屏每次仅固定5次ADB调用并清理，
  没有回退。额外覆盖有效空组与无目标组后面的畸形XML。
- make comments（594文件/16自测）、make test（含三系统交叉vet、协议56项、Skills3份）、
  make verify、make build通过；定向ADB/CLI/MCP空屏测试通过。docs/virtual-display.md
  同步错误表和先验归属/空屏语义，未提前修改M4/M5其余文档。
- 修订脚本正在冷启动验收：DELETE需严格得到INPUT:E2EAsci；另按BACK要求根页退出、
  归属仍在、DISPLAY_EMPTY且该显示无topResumedActivity；重新启动同屏PAGE:MAIN恢复。
  不承诺进程内状态跨根页面退出保留，仅必要时显式恢复原CLICK:1前提，后续仍断言CLICK:2。
  主屏隔离、65秒无输入等待、非黑帧、DEVICE_LOCKED/PIN清除/DISPLAY_ASLEEP/重建原判据不变。
  6个脚本本地测试通过，包括“不归属/错错误码/仍有前台不能算空屏”的否定用例。
  本节不把正在运行的冷启动轮次计为成功，M4尚未开始。
