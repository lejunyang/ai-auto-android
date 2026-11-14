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

## Round 9：M3b修订脚本首轮息屏后层级失败，停止验收（2026-11-14）

- 空屏语义已单独提交7676beb：internal/adb解析与测试、apperr新增码、service边界注释、
  CLI/MCP真实service路径测试、docs/virtual-display.md错误表及本规格状态，共10个文件。
  DISPLAY_EMPTY的CLI/MCP映射已通过本地测试；本轮没有增加MCP工具或参数，没有改变风险门。
- 修订原验收脚本：ASCII后用DELETE严格验证INPUT:E2EAsci；输入框仍focused时另发BACK，
  必须同时得到DISPLAY_EMPTY、display list仍归属且该显示无topResumedActivity；然后同屏
  重新启动必须恢复PAGE:MAIN。明确恢复原CLICK:1前提，后面的息屏点击仍要求CLICK:2。
  原文件从screen_off_timeout写入到无效编号测试之前的3557字符（归一化换行）逐字未改，
  隔离、息屏65秒、非黑帧、凭据锁/PIN清除、DISPLAY_ASLEEP与重建判据均未削弱。
- m3b-seriesA-1，UTC08:56:18.130→08:57:49.415，transport39；独立冷启动、唯一
  emulator-5554，osdk-test-35、API35、x86_64、320×640/160dpi、en-US、导航模式2，
  虚拟屏720×1280/240dpi。设备指纹仍为
  google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  helper SHA-256 747be5ca0be6f62f2127c2a63ff55d38ae17b4c4ccc13205072867a1f4204478，
  fixture SHA-256 1e88acf7a0b88f00df212648ca93c3487b37d0cb50e19b92569b80aa8462f6f8，
  新CLI SHA-256 3454c35cce1d44fca53d9ff3aa1b1c03b01a82f492e15b72fec0c5e73108292d。

| 本轮步骤 | 结果与证据 |
| --- | --- |
| 创建/列出/启动 | 通过；display2、PAGE:MAIN，主屏仍NexusLauncher |
| 点按隔离/帧 | CLICK:1、帧改变，720×1280非黑；主屏前台不变 |
| ASCII/非退出按键 | E2EAscii后DELETE精确得到INPUT:E2EAsci |
| 输入框focused时BACK | 根页退出、activity=null、owned=true、DISPLAY_EMPTY；主屏前台不变 |
| 同屏重新启动 | PAGE:MAIN及CLICK:1恢复，主屏前台不变 |
| 滑动 | VERTICAL_OFFSET正数；2-inputs完成于73460ms |
| 主屏息屏 | screen-state.on=false；随后目标帧成功，921600/921600像素非黑 |
| 息屏后的动作前层级 | ACTION_FAILED: Invalid or truncated UIAutomator display-window XML. |
| 息屏点按/65秒/PIN/重建 | 未执行；失败发生在tapNode动作前的hierarchy读取 |

- **完整回归失败，成功0/3**。seriesA-2/3未启动；按任务书在M3b停止，不重试、不更换
  判据、不放宽XML校验，也不把取帧成功外推为息屏保活/PIN往返通过。M3/M3b均未整体验收，
  M4真实MCP stdio冷启动仍未执行（0次），M5没有进入逐条核实、修订或Skills镜像同步。
  P01–P15均保持“已只读接收，待本分支代码核实”；未核实条目没有勾选或冒充已处理。
- 失败原因尚未定位：错误表明层级被严格解析器拒绝，不能断言系统实际输出必然损坏或
  截断。ADB临时dump已按原defer清理，没有保留这次失败的原始XML；failure-hierarchy.xml
  是上一份成功层级的脱敏缓存，不能代表失败现场。failure-fixture.png是息屏后最近一帧，
  已离线核对720×1280、非黑比例1并目视为本地fixture；它只证明取帧成功。
- 失败产物总102421字节：PNG80650、脱敏缓存XML16435、日志时间窗0、报告5336，均在
  1MiB预算内。对照构建/因果报告摘要已存Round7。记录后只清理本轮back-cause与
  m3b-seriesA-1两个忽略目录，不保留对照DEX jar、classes、截图或诊断探针。
- finally无cleanupError：恢复screen_off_timeout=2147483647，停止helper、清理固定
  辅助文件并确认forward为空，然后emu kill。尚未设置测试PIN，不将“清PIN”记作已执行。
  宿主随后确认adb devices为空、emulator/qemu进程列表[]；未改AVD/system image/osdk配置。

### 本轮本地验证与提交边界

- make comments退出0：594个手写文件、16个检查器自测。
- make test退出0：Go316个顶层Test定义（含原默认跳过的显式设备冒烟；定义数不是子用例
  执行数），全部包测试及vet通过，linux/darwin/windows交叉vet通过；协议56项、Skills3份
  及镜像检查通过。定向go test ./internal/adb ./internal/cli ./internal/mcpserver -run Empty
  -count=1通过，覆盖主屏0、归属屏7、不归属9、空组、坏尾与无回退。
- make verify、make build退出0。node --check通过；node --test scripts/emulator/focus.test.mjs
  scripts/emulator/virtual-display.test.mjs共10项通过（焦点4、回归脚本6），覆盖空屏不得
  借主屏/全局活动，以及错误码、归属、前台三条件缺一不可。
- make android-verify退出0，BUILD SUCCESSFUL（复核9秒、199任务）；testDebugUnitTest、
  assembleDebug、lintDebug通过，612项、0failure/error、1既有跳过：app564、fixture8、
  web-fixture6、displayd34。未修改任何android/displayd生产/测试跟踪文件。
- 脚本及其测试、本规格状态保存为test(emulator): adapt virtual display acceptance to
  IME-less displays；这是失败停止时的部分成果提交，不表示冷启动三次通过。三道既有
  风险门文件完全未改；未修改上一规格状态、移交worktree或docs/next-phase-tasks.md。
- 待集成方汇合的后续项：定位息屏后dump --windows被拒的原始输出形态/解析原因，并补齐
  有界失败现场证据后重跑完整三冷启动；fixture的API35 edge-to-edge遮挡另立规格修复；
  回归通过后才能执行M4/M5及P01–P15。继续保留API33未缓解、API34/真机/OEM/硬件键盘未验、
  虚拟屏IME/中文不支持，以及BACK直接作用于应用、根页会退出的边界。
- 收尾确认：两个本轮产物目录均先核对绝对路径在当前worktree内、无reparse point、无跟踪
  文件且匹配Git忽略规则，再以LiteralPath非强制递归删除，Test-Path均false。未删除其它
  轮次或用户文件。git diff --check通过，main仍f861a61、未push，保留本worktree供集成。

## 集成方定位：息屏后窗口列表为空（2026-11-14）

- 复核对象 `b2717b3`。`7676beb` 的空屏错误码与 `b2717b3` 的脚本修订范围已核对。
  codex 在「主屏息屏后读取虚拟屏层级」处停下并指出失败现场没有原始 XML，判断正确。
  以下为集成方在同一固定模拟器环境下抓取的原始现象。

| helper 构建 | 主屏状态 | 顶层焦点显示 | `uiautomator dump --windows` |
| --- | --- | --- | --- |
| 带 `1 << 16`（本分支） | 唤醒 | 0 | 37088 字节，含显示 0 与显示 2 |
| 带 `1 << 16` | 息屏（组 0 Dozing、组 1 Awake） | 0 | **命令打印「dumped」但不产生文件** |
| 带 `1 << 16` | 息屏，且刚对虚拟屏点按过 | 0 | 仍不产生文件 |
| 带 `1 << 16` | 重新唤醒 | 0 | 37091 字节，含显示 0 与显示 2 |
| 不带（上一规格验收时的构建 `681af2fb…`） | 唤醒 | 3 | 37092 字节，含显示 0 与显示 3 |
| 不带 | 息屏（组 0 Dozing、组 1 Awake） | 3 | 16396 字节，**只含显示 3** |

- 同一息屏状态下，普通 `uiautomator dump` 报
  `ERROR: null root node returned by UiTestAutomationBridge.`。
- `aactl observe hierarchy --display 2` 在「不产生文件」时把空输出当成畸形 XML，
  报 `ACTION_FAILED: Invalid or truncated UIAutomator display-window XML.`，掩盖了
  真实原因。
- 结论：顶层焦点留在已息屏的主屏时，无障碍侧拿不到任何窗口；不带该标志时顶层焦点
  在虚拟屏上，息屏后仍可读取。**「不抢焦点」与「息屏读层级」在 API 35 模拟器上
  互斥**。机制层面的解释（无障碍窗口跟踪依赖顶层焦点显示处于交互状态）是推断，
  未查源码证实。
- 取帧与对虚拟屏的输入注入在两种构建、主屏息屏时都正常（本轮点按返回成功；取帧
  非黑见 codex 上一轮记录）。
- 据此规格新增「修订二：焦点策略二选一」。
- 清理：两个构建的辅助进程均已停止，设备临时文件与转发已清，模拟器已关闭。

## Round 10：M3c独立原始窗口输出复现与策略实现（2026-11-14）

- 开工HEAD=5beaf3b，工作区干净，已核对worktree列表并重读修订二及集成方定位。
  两份helper在本worktree忽略目录focus-policy-baseline内经同一osdk javac/D8本地流水线
  生成，仅Policy来自不同固定标志版本；没有下载、安装helper或修改AVD配置。
  isolated jar SHA-256 46dabae719aa57be37e46061cbba183c9625c6855df137e640225c75d394f110；
  steal jar SHA-256 14a35b20ee490305fa2c38ce7540708353fcc58180fb0d8631fdcce4473d202f。
- raw-isolated-1：UTC09:19:16.370→09:20:26.645，transport41；raw-steal-2：
  UTC09:23:49.716→09:24:38.418，transport43。均独立冷启动osdk-test-35、唯一emulator-5554、
  boot_completed=1、固定F35指纹、320×640/160dpi、en-US、导航模式2、x86_64，虚拟屏
  720×1280/240dpi。CLI/fixture与Round9相同。每次dump前删除本次固定探针路径，逐次记录
  命令退出码/原始stdout/stderr、文件存在性、精确字节数、SHA-256及显示分组，不采信dumped提示。

| 构建/主屏 | 文件 | 字节数 | 显示分组 | 顶层焦点显示 | 取帧/输入 |
| --- | --- | --- | --- | --- | --- |
| isolated/唤醒 | 有 | 31465 | 0、2 | 0 | 非黑；Awake到达 |
| isolated/息屏 | 无 | 0 | 无 | 0 | 非黑 |
| isolated/息屏注入后 | 无 | 0 | 无 | 0 | 非黑；Sleep+DELETE已发送 |
| isolated/重新唤醒 | 有 | 31472 | 0、2 | 0 | 精确INPUT:AwakeSlee |
| steal/唤醒 | 有 | 85563 | 0、2 | 2 | 非黑；Awake到达 |
| steal/息屏 | 有 | 16405 | 2 | 2 | 非黑 |
| steal/息屏注入后 | 有 | 16413 | 2 | 2 | 非黑；Sleep+DELETE已发送 |
| steal/重新唤醒 | 有 | 37108 | 0、2 | 2 | 精确INPUT:AwakeSlee |

- 全部dump命令退出0且stdout为UI hierchary dumped to固定探针路径，stderr为空。
  isolated两次无文件，不能把提示当成功；steal首轮raw-steal-1采集器只有64KiB缓冲而读取
  失败，未测到息屏，已停止清理。探针改为与生产解析器一致的8MiB读取预算、原始证据
  落盘总限512KiB后，从新冷启动raw-steal-2完成测量；85563字节也说明原预算不足。
  失败轮不计为成功，保留诊断摘要；没有改变功能判据。两份有效结果与集成方定性结论一致，
  字节数不同（本轮有输入框/IME节点、显示编号不同），不借其手工数据充数。
- 两种构建的息屏帧均720×1280，921600像素全部非黑。输入注入不仅退出0，还以唤醒后
  精确文本AwakeSlee核对。每轮finally删除探针XML、停止helper、核对显示/转发/临时文件为0，
  emu kill；无PIN或设置修改。后续确认设备列表空、emulator/qemu为0。
- Android RED：ProtocolTest15项1失败，新focus字段旧解析器拒绝；PolicyTest18项1失败，
  旧实现没有二值标志映射。随后实现focus枚举（省略默认isolated），非法字符串返回
  INVALID_ARGUMENT，错误类型/原始flags/其它方法携带focus仍INVALID_REQUEST。固定标志
  API34+ isolated=130507、steal=64971；API33均15819，全部排除SECURE。
- hello.protocolVersion提升为2：创建/list/status新增必须可信的focus/focusIsolated字段，
  新客户端不能把旧helper缺字段误认作隔离成功；编码、预算和固定socket端点不变。
  hello.capabilities.focusIsolation为API34+，每屏focusIsolated只在API34+ isolated时true；
  API33请求isolated仍返回focus=isolated、focusIsolated=false，明确报告未隔离。
- Android GREEN：:displayd:testDebugUnitTest :displayd:assembleDebug :displayd:lintDebug通过
  （17秒、47任务），36项0failure/error；README同步v2、字段、两策略/数值/API33及BACK代价。
  Go客户端/服务/CLI/MCP透传及非法参数/缺失或矛盾能力响应测试通过；三道风险门文件未改。
  阶段完整设备验收尚未开始，本节不勾选两策略的三次冷启动或M4/M5。

## Round 11：M3c策略透传与主机校验（2026-11-14）

- displayd实现/测试/README与本规格计划、复现记录已保存e6eb6a4，共8文件。
- Go Create接受二值focus，省略/空内部值规范为isolated，非法值在建立forward前拒绝；
  每次hello必须v2且focusIsolation能力与API一致，create/list/status逐屏校验focus与
  focusIsolated，不接受缺策略、未知值或API34+矛盾隔离声明。API33单测明确验证false。
- service独立校验、CLI仅create接受--focus，MCP仅android_display_create接受focus枚举，
  默认isolated，schema及输出仍additionalProperties:false；响应/create/list/status包含
  focus与focusIsolated。没有增加工具，20个工具数量不变；没有helper启停出口或原始flags。
- 客户端真实握手/序列化与清理测试、service后端透传、CLI默认/两值/非法值、MCP schema/
  默认/两值/错误类型/额外字段/其它管理方法拒绝均通过。既有动作面、三道风险门完全未改。
- make test退出0：协议56项、Skills3份、Go包测试/vet及三系统交叉vet通过；make build
  退出0。设备端两策略完整冷启动与M4/M5尚未开始，不以本地测试代替设备验收。

## Round 12：M3c没有窗口输出的专用错误（2026-11-14）

- 策略主机端已单独提交d167811，共11文件，未包含ADB错误修改。
- RED：ADB空字节/明确文件不存在、CLI与MCP真实service路径的新增测试先失败，旧码
  ACTION_FAILED不符WINDOWS_UNAVAILABLE；实现后同样测试GREEN。WINDOWS_UNAVAILABLE
  为非自动重试错误、退出码仍10，提示系统未提供窗口，常见原因是主屏息屏且isolated，
  出路为Wake主屏或关闭/steal重建（销毁旧屏内容）。不声称已读取到实际策略或电源状态。
- 有效XML缺目标窗口仍DISPLAY_EMPTY，归属失败仍DISPLAY_NOT_FOUND。只对按显示路径
  分类空白输出或本次随机文件路径的明确ENOENT；超时、权限失败、输出截断和畸形XML
  继续返回原错误。无目标参数的命令及处理不变，没有新增ADB回退或放宽XML校验。
- ADB整包测试通过；CLI/MCP映射覆盖主屏0及helper归属屏7，每次固定5次ADB调用并清理，
  无回退。新增否定用例覆盖超时、权限失败、截断和损坏XML不能冒充“无窗口输出”。
  docs/virtual-display.md只同步专用错误表，M4完整使用说明仍等待设备验收。

## Round 13：M3c steal连续三次冷启动完整通过（2026-11-14）

- 原verify-virtual-display.mjs显式create/recreate --focus steal，协议检查改为v2；文本后
  DELETE精确得到INPUT:E2EAsci，再核对当前IME客户端显示为目标且mInputShown=true后发BACK，
  要求IME隐藏且根Activity/窗口仍在。按策略恢复BACK语义，不使用isolated的退出断言。
- 除重建时显式透传steal外，原息屏/PIN段归一化换行后逐字相同：主屏活动按display0的
  dumpsys分段核对，息屏后读层级/点按CLICK:2/帧改变，65秒不发显示动作，凭据锁拒绝帧，
  清测试PIN后DISPLAY_ASLEEP无帧，再close/create/relaunch恢复正常帧，全部保留。
- 三轮同一脚本SHA-256 5555e8f922bceb4c84ec8578fd2b51c07cc5f1b43120dc988bff2deac456ebea，
  CLI 10c4ba81c9c96a84954607545cafd2cd2297b41a7f97d4ea140ba005da436ab7，
  helper 626142bb4c8a26740b05560876f29d246b50c839cbf3e54e606c724f5310deee，
  fixture仍1e88acf7a0b88f00df212648ca93c3487b37d0cb50e19b92569b80aa8462f6f8。
  每轮独立冷启动固定AVD/-read-only/-no-snapshot，唯一emulator-5554、完整F35环境未变。

| 轮次/transport | UTC起止 | 主屏/输入/息屏层级与帧 | 无输入保活 | PIN往返与重建 | 清理 |
| --- | --- | --- | --- | --- | --- |
| m3c-steal-A1 / 45 | 09:38:38.096→09:41:23.587 | 全通过，主屏NexusLauncher不变 | 66.022秒，groupAwake=true | DEVICE_LOCKED→DISPLAY_ASLEEP→新屏正常帧 | 通过 |
| m3c-steal-A2 / 46 | 09:42:40.987→09:45:38.157 | 全通过，同上 | 66.030秒，groupAwake=true | 同上 | 通过 |
| m3c-steal-A3 / 47 | 09:47:29.956→09:50:18.766 | 全通过，同上 | 66.001秒，groupAwake=true | 同上 | 通过 |

- 三次实际设备验收连续成功，无失败轮拼接；helper/显示/forward/设备临时文件均0，
  测试PIN已清、screen_off_timeout恢复2147483647。A2后冷启动驱动立即检查时仍有
  emulator.exe退出尾部进程，守卫停止在启动第三轮之前；稍后核对其自行退出、设备列表
  空且无emulator/qemu才启动A3，没有强杀进程或启动额外设备轮次。A3后等待宿主进程退出
  并再次确认0。isolated完整场景尚未运行，因此M3c整体/M4/M5仍未验收。

## Round 14：isolated首轮主屏搜索基线失败，停在M3c（2026-11-14）

- 焦点测量脚本完整保留原9步和所有原基线/缓解判据，并增加--scenario acceptance：
  原焦点测量后显式关闭含时钟的屏、新建isolated屏，以空任务前提验证根BACK→DISPLAY_EMPTY→
  同屏重新启动；再要求主屏焦点/IME/输入内容保持，息屏取帧与文本/DELETE正常、层级返回
  WINDOWS_UNAVAILABLE、唤醒后精确文本及两屏层级恢复。息屏时不猜坐标。
  以上新增设备步骤尚未执行，不能因脚本写完或Node测试通过而勾选。
- m3c-isolated-A1：UTC09:52:17.090→09:52:58.626，transport49，固定F35环境与Round13相同。
  脚本SHA-256 0e6ad696c10e8abbc10b5d72f230389c222a4df9dafa8c2e7772c8ab02f07bc3，
  CLI/helper/fixture摘要仍为Round13同一组；四个系统App的版本/系统APK摘要再次采集并一致。
- 已完成动作：主屏启动Settings，按最新层级进入搜索、点按输入框、发送Primary文本。
  随后的主屏基线快照在parseFocus中抛出Primary input window not unique；没有baseline事件，
  未完成输入节点focused、精确文本、当前IME客户端S/0、mInputShown及显示0可见IME的全套核验。
  此时尚未调用startHelper，也没有创建虚拟屏，不能把失败归因于新策略或声称虚拟屏打断了输入。
- 失败后重新读取display0层级成功（failureHierarchy.capturedAfterFailure=true），其脱敏
  XML26150字节只有com.android.settings和com.android.systemui、两个window，没有搜索输入
  resource-id=open_search_view_edit_text。它是失败后观察，不能冒充抛错瞬间原始输入。
  当前页面不符合脚本仅fixture/设置搜索截图采集条件，因此没有截图；displayd日志窗口0字节，
  报告4814字节，总证据30964字节。根因未定位，没有收集到系统搜索的崩溃或生命周期证据。
- **isolated完整通过0/3，首轮失败，后两轮未启动；M3c整体未验收**。未降低窗口唯一性、
  focused/文本/IME判据，没有重试来拼凑结果，也没有更换基线应用。steal三次通过的事实保留，
  不能代替isolated验收。按用户停止条件不继续设备实验，不开始M4或M5。
- finally核对helper/显示/forward/固定临时文件均0、emu kill；本轮从未设PIN、未改全局设置。
  宿主确认adb devices为空、emulator/qemu列表[]。三个steal轮次的PIN/超时清理见Round13。
- P01–P15全部仍为未核实（已只读接收，M5阶段未开始），没有修订README/architecture/
  protocol/Skills或同步镜像；不把现有make skills-check通过当作这些条目已处理。

### 停止后的全量本地检查与保存

- make comments与make verify退出0：595个手写文件、16个检查器自测；metadata有效。
- make test再次退出0：Go325个顶层Test定义（定义数不等于子用例执行数，含原默认跳过
  的显式设备冒烟）、全部包测试/vet、linux/darwin/windows交叉vet通过；协议56项、Skills3份
  与镜像一致性通过。ADB整包及CLI/MCP无窗口输出映射的定向测试通过。
- make build退出0，CLI摘要仍Round13所列。make android-verify退出0（10秒、199任务），
  testDebugUnitTest/assembleDebug/lintDebug通过；614项、0failure/error、1既有跳过，分布
  app564、fixture8、web-fixture6、displayd36。Android README补充实际验证边界及v1→v2升级顺序。
- Node焦点5项/原验收脚本6项，共11项通过；脚本node --check通过。三道既有风险门完整文件
  相对5beaf3b无diff，没有放宽XML校验。MCP工具数量仍20，本轮只新增创建的focus参数及输出字段。
- 按职责保存e6eb6a4（displayd，8文件）、d167811（主机策略，11文件）、4c9468c（错误，9文件）；
  脚本、模块README与本规格状态另行保存test(emulator): verify both focus policies end to end。
  此提交是失败停止时的部分成果，不是两场景全部通过，不包含M4/M5交付。
- 需要汇合登记的后续项：定位并稳定系统设置搜索基线，补充输入前后Activity/窗口/IME/PID
  及有界生命周期/崩溃现场证据，保持判据后从头执行isolated三冷启动；再执行M4/M5。
  fixture的API35 edge-to-edge遮挡仍另立规格；API33未隔离、API34/真机/OEM/硬件键盘未验，
  两种策略在API35的层级/焦点代价不能外推到所有系统。虚拟屏文本仍限ASCII。
- 未改android/app、internal/bridge、其它规格、路线图或其它worktree；未下载依赖/二进制，
  未改AVD/system image/osdk配置或main，未push。本轮没有越过阶段依赖或改写历史验收结果。
- 收尾产物清理：本轮对照构建184156字节，三个原始探针目录分别68290/68271/160898字节，
  三个steal报告各6599字节，isolated失败目录30964字节。先核对八个绝对路径均在当前
  worktree内、无reparse point、无跟踪文件且匹配Git忽略规则，再用LiteralPath非强制
  Remove-Item递归清理；八项Test-Path均false，未删除其它轮次或用户文件。

## Round 15：按新停机规则恢复M3c，脚手架修复第1次（2026-11-14）

- 开工HEAD=f0bb80e、工作区干净，worktree/main身份核对无变化；只修脚本，不修改产品代码。
  用户已明确区分脚手架与产品失败，同一问题修复3次仍不稳定才停止；M3c → M4 → M5继续串行。
- 现象：旧轮在搜索输入基线抛Primary input window not unique。静态核实该错误把0个和多个
  匹配窗口合并报告；Round14失败后层级确实没有搜索输入框，不能据旧事后采集断言原时刻重复。
  脚手架根因是未等待首页/搜索页稳定、未确认页面转换完成即继续输入，诊断也缺数量信息；
  旧轮具体Android转换时序已无法追溯，不伪造其原始证据。
- 改法：目标节点连续两次一致才点按；搜索页输入框轮询最多6次，未出现先保存原始观察/
  截图/系统分段/时间线，再重新观察和定位，最多再点一次；输入前同样等待focused稳定，
  原完整基线条件最多观察6次等待成立，不重复文本注入。节点消失/变化会重置稳定计数。
  没有更换应用、延长固定睡眠或放宽原focused/文本/当前IME客户端/显示0键盘判据。
- 诊断现在包含matchingWindows、inputNodes、包名及原始窗口头；0与多个明确区分。
  DisplaySession保留触发判据的原始CLI层级和同次focus快照，在重试/清理前落盘，完整脱敏
  XML不以新dump替换，并采截图、原始系统分段与时间线；证据总额限制1MiB。
- 新脚本测试覆盖缺失重置稳定计数、有上限结束、重试先保存且坐标来自新层级、0/2个窗口
  诊断、失败保留原始观察而不重读hierarchy。Node15项通过；make comments通过596文件/16自测。
- 新系列isolated-B1已独立冷启动，transport51，原完整基线全部通过后才启动helper。
  目前仍在设备验收中，不将基线通过冒充完整场景通过；先前失败轮不计数。

## Round 16：保留原始现场后的脚手架诊断与修复（2026-11-14）

- isolated-B1仍在helper启动前失败：完整基线曾通过，主屏字符a后搜索输入窗口为0。
  当时原始CLI层级62285字节、截图33625字节、window48416、IME前缀32768、activity31522、
  processes116、timeline3473、report7261均已在cleanup前保存到忽略目录focus/isolated-B1。
  截图显示Settings首页，原始XML只有SystemUI/IME，原窗口与进程证据保留，没有事后dump覆盖。
  初始当前IME客户端PID1645，失败采集进程中搜索为3790；证明页面/进程发生变化，不足以
  单独断言崩溃类型。本轮未到被测虚拟屏操作，不计通过。
- isolated-diagnostic-C1在系统App元数据读取时遇到本地ADB daemon连接10060超时，尚无
  首份UI层级；report保留原始错误、环境与时间线，明确evidenceError=No original observation。
  无法在不可达时伪造层级/截图；连接在finally已恢复，helper/显示/forward/临时文件均0，
  emu kill成功。宿主随后核对同一本地adb进程和5037监听恢复、无模拟器，不修改系统配置。
- ADB脚手架修复第1次：仅对固定白名单的只读shell查询，在daemon瞬断后重新确认唯一
  serial、同一transport、指纹，最多重发一次。不重发输入、安装、启动、删除或清理命令，
  不更改端口、密钥或系统设置。单测覆盖只读成功、身份不符拒绝、输入/安装/rm一律不重发。
- isolated-diagnostic-C2（transport54）9步焦点测量全部通过、主屏探针全部落点正确。
  随后新建显示3、重启fixture并点按时失败Fresh virtual tap failed，已保留当时原始XML、
  截图、系统分段、crash/exit-info和时间线。截图明确CLICK:2、INPUT:Virtua；本轮不计完整通过。
- fixture脚手架修复第1次：只读源码确认FixtureProcessState.current是进程内单例，关闭
  显示/Activity不保证清空点击和输入。改用最新层级中现有reset_target按钮建立CLICK:0及
  空输入前提；BACK重新启动后同样显式复位。原CLICK:1、BackCheck、Awake/DELETE和息屏
  AwakeSlee等精确断言全部保留，没有修改fixture，也不尝试启动其不可导出的debug组件。
- 搜索基线脚手架修复第2次：由一次完整基线改为连续两次全部原判据满足、当前IME客户端
  相同后才继续。C2的exit-info记录旧搜索PID1532在本轮早期因STATE CHANGE（reason15，
  status0，description=change ... state=empty）退出，crash缓冲为空；这说明冷启动存在
  非崩溃包状态切换，不能倒推B1也是同一退出原因。新等待有界6次，不延长固定睡眠、不恢复
  或重建已开始测量时的主屏状态，不能掩盖产品导致的焦点变化。
- 证据采集补强：发现IME注册信息本身超过旧32KiB前缀预算，当前客户端字段在其后。
  改为保留当前IME客户端/服务/显示/可见性等原始行，并增加crash缓冲及exit-info；单测验证
  大段注册信息不能挤掉末尾当前字段。原始触发XML始终完整脱敏保存，不采新层级替换。
- 以上脚本测试共19项通过；新的isolated-D1/2/3系列从冷启动重新计数。B1、C1、C2均未算通过，
  D系列尚在运行。当前搜索基线已修2次，fixture状态与ADB瞬断各修1次，未触发同问题修3次
  仍不稳定的停机阈值；如后续触发，保留各轮原始证据并停止，不能继续盲改。

## Round 17：有原始证据的搜索状态切换与电源等待修复（2026-11-14）

- isolated-D1（transport55）第一次搜索未出现，6次轮询均0输入节点；重试前的原始XML、
  PNG、系统分段、时间线、exit-info已保存。exit-info精确记录当轮搜索PID1571在
  10:28:42.319以STATE CHANGE（reason15/status0，change ... state=empty）退出，crash为空。
  当前IME客户端是Settings PID994/显示0、mInputShown=false。这次直接证实冷启动包状态
  切换造成搜索页丢失，不能把它写成虚拟屏焦点异常或应用崩溃。原地重新观察/定位后仅再点
  一次，搜索页出现并完成连续两次完整基线，再进入被测操作；原失败观察没有被覆盖。
- D1原9步、虚拟屏复位后的CLICK:1/BackCheck、BACK→DISPLAY_EMPTY、同屏重新启动、
  主屏焦点/IME/文本保持、AwakeX→DELETE得到Awake、取帧均通过。随后脚本紧接着注入
  KEYCODE_SLEEP便检查on=false，返回on=true，轮次失败、后两轮未启动，不计通过。
- 电源脚手架修复第1次：注入返回只代表按键已提交；改成最多8次、每次间隔250ms的
  screen-state条件轮询，逐次保存原始状态；唤醒同样等待on=true。不重复按键，不增加固定
  睡眠，不放宽最终on=false/true判据。超时在抛错前采关联层级/窗口/IME/Activity/截图并
  保留原始错误响应，再清理。若持续不达标，作为产品/环境证据继续分类，不能无限重试。
- D1电源失败时原层级是息屏前最后一次观察，不能当作息屏后的层级；该证据来源时间已
  在报告保留。这一局限由新超时关联采集修正，没有把旧缓存重新标为新现场。
- 单测新增电源状态true,true,false的有限观察、不重复输入、持续true时严格超时且先保存
  现场；Node共20项通过。新isolated-E1/2/3系列从冷启动开始计数，D1不算通过。

## Round 18：补齐实际响应与最小原始复现（2026-11-14）

- isolated-E1（transport56）基线及9步、复位/BACK/重启均通过；Sleep后的原始轮询为
  ON→OFF（277073ms→277603ms），证实前轮即时判断属于等待时机问题。息屏虚拟帧720×1280
  且全部921600像素非黑。随后requireWindowsUnavailable抛泛化错误，旧脚本没有保存实际
  CLI响应，不能据它判断真实错误码或把缓存层级当成那次响应。E1不计通过，后两轮未启动。
- 证据脚手架修复：envelope现在记录真实响应，finish在截图/清理覆盖缓存之前先保存
  failure-response.json，证据包也保存原始响应；错误断言带实际ok/code/message。补测试
  证明后续截图响应不能覆盖触发失败的原信封。没有修改产品错误映射或放宽错误判据。
- 新增最小diagnose-isolated-hierarchy.mjs：保留完整主屏基线，重建isolated屏、输入、息屏
  后先保存原始dump stdout/stderr/文件存在性/字节数/原始脱敏XML，再保存目标CLI完整响应、
  display/power原文与截图、窗口/IME/Activity/退出记录，然后才断言错误契约。它标记diagnosticOnly，
  不冒充完整9步或三个冷启动验收。
- isolated-raw-F1（transport57）仍在helper前因搜索页退出导致0输入窗口，已保存真实原始
  层级/截图/系统分段/响应，未触及产品，未计通过。
- 搜索基线脚手架修复第3次：仅在helperStarted=false、已经先保存原始失败证据、重新读到
  Settings首页时，允许完整重建一次，再执行全部原基线及相同客户端连续两次判据。若最新
  状态不明确或已经启动helper，禁止恢复；最多两次构建，仍不稳定就按用户阈值停止。
  单测覆盖先保存再观察再构建、无首页不能重试、两次严格上限、helper启动后拒绝恢复。
- Node共22项通过，make comments通过597文件/16自测。isolated-raw-F2（transport58）正在
  最小复现：第一次构建丢失后按最新首页重建，尚未计通过。当前不进入M4/M5，待真实响应
  分类；如契约矛盾则按产品问题停止，不自行修改产品实现。

## Round 19：实际CLI错误契约失败，按产品问题停止M3c（2026-11-14）

- isolated-raw-F2：UTC10:51:26.811→10:54:10.307，transport58。第三次基线修复在首次
  包状态切换后仅按当前Settings首页重建一次，完整原基线及相同客户端两次观察均通过；
  helper随后才启动，创建/重建isolated显示3、复位、点按、Diagnostic文本均通过。
  唤醒状态下主屏top=0、当前IME客户端搜索PID3710/显示0、IME服务/Insets/窗口均可见，
  主屏文本Primary未变。搜索第3次修复在本次构建有效，但没有三个完整冷启动稳定性结论。
- 设备环境仍为osdk-test-35/API35/google_apis/x86_64、320×640/160dpi、en-US、导航模式2，
  虚拟屏720×1280/240dpi；唯一emulator-5554，-read-only/-no-snapshot/-no-boot-anim/-no-audio。
  指纹google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
  CLI仍10c4ba81c9c96a84954607545cafd2cd2297b41a7f97d4ea140ba005da436ab7，helper仍
  626142bb4c8a26740b05560876f29d246b50c839cbf3e54e606c724f5310deee，fixture仍Round13摘要。

| 最小复现步骤 | 清理前保存的原始结果 |
| --- | --- |
| 主屏睡眠条件等待 | 第1次ON、第2次OFF；没有重复Sleep输入 |
| 虚拟屏取帧 | 720×1280、921600/921600像素非黑，文件83051字节 |
| 原始dump --windows | 退出0，stdout=UI hierchary dumped to固定诊断路径，stderr空 |
| 诊断路径test -f | 文件不存在，0字节、无显示组；原始结果JSON287字节 |
| 紧随其后的CLI observe hierarchy --display 3 | ok=false，ACTION_FAILED，Invalid or truncated UIAutomator display-window XML.；实际响应456字节 |
| 关联display原文 | controller0=OFF、controller3=ON |
| 契约断言 | 期望可操作WINDOWS_UNAVAILABLE，实际不符，失败 |

- 这是已进入被测能力后的错误契约阻塞，按用户产品问题停机：不修改产品映射、不把
  ACTION_FAILED改作可接受值、不用本地单测替代设备结果，不继续完整isolated/M4/M5。
  原始独立dump和CLI是先后两个命令，期间没有输入动作；不能声称前者是后者内部临时文件
  的字节。CLI内部cat原始字节未导出，需下一步定位其诊断通道与XML解析分支，不能冒充
  根因已经完全确认。新旧本地build摘要一致且二进制包含WINDOWS_UNAVAILABLE，非漏部署该码。
- 关键证据保留在scripts/emulator/build/focus/isolated-raw-F2（Git忽略，未提交）：
  actual-cli-response.json SHA-256 fab993cc53d95627200dd34c5d2be204c293556a7833ff00f59a84a75b2c884d；
  raw-result.json SHA-256 3558ddd9d6b4cd2b062d503b1f1aceebff21c8b4a7cdb5a0cb23036e2aaa0409；
  virtual-asleep.png SHA-256 2e126871c73313257023d693378057d8df57ba94486dfa9109c9d14f2e081990。
  还有display.txt39683字节、power.txt214311字节、原始响应、当时的脱敏层级/无层级错误、
  主屏截图、Window/IME/Activity/进程/退出记录和时间线。全目录604337字节，小于1MiB。
- 各轮原始证据继续保留供审阅，每轮均小于1MiB：B1=219466、C1=2186（初始化ADB不可达，
  无首份UI观察，明确记录不可采）、C2=280663、D1=368979、E1=476266、F1=178217、F2=604337。
  各次清理前先落盘可得证据；旧采集缺口见Round16–18，不能把新采集方式倒填为历史证据。
- 临时补充只读电源观察器在E1 emulator关闭时有非终止PowerShell错误，随后两次明确serial
  的只读请求被ADB以offline拒绝，没有触达设备；补充样本1无有效数据，不作验收证据。
  已停止该观察器，不再使用其流程；补单测确认统一DisplaySession遇离线/多设备/transport
  变化时在进一步设备读取之前停止。这是本轮设备纪律的偏离，未隐式切换或操作其它设备。
- F2 finally无cleanupError/evidenceError：helper/虚拟屏/forward/固定临时文件均0，诊断XML已删，
  emu kill成功；本轮从未设置PIN或修改全局设置。宿主再次确认adb devices空、emulator/qemu[]。
  原始失败证据按本轮要求保留在忽略目录，不提交截图、APK、缓存或密钥。
- isolated完整冷启动成功0/3，所有失败轮未计数，E2/E3未启动；最小F2不冒充完整场景。
  steal原三次通过保留。M4真实MCP stdio仍未执行，M5 P01–P15全部未核实/未处理。

### 本轮最终本地验证与后续汇合项

- make comments退出0：597文件、16检查器自测。Node焦点/脚手架17项、既有虚拟屏脚本6项，
  共23项通过，新增脚本node --check通过；基线全部原判据的逐项否定测试仍通过。
- make test退出0：Go325个顶层Test定义（非子用例执行数，含既有默认跳过设备冒烟）、
  全部包测试/vet和linux/darwin/windows交叉vet通过；协议56项、Skills3份/镜像通过。
- make verify、make build退出0；CLI重建仍10c4ba81...，与设备运行产物一致。
  make android-verify退出0（56秒、199任务）：单测/assembleDebug/lintDebug通过，614项，
  0failure/error、1既有跳过（app564、fixture8、web-fixture6、displayd36）。
- 本轮只提交脚手架、测试、模块README验证边界和本规格状态，标题为
  test(emulator): harden focus setup and preserve failure responses；不包含任何产品Go/Java实现
  或M4/M5交付。三道既有风险门未改，未改其它规格/文档/skills/fixture/App/Bridge/main，未push。
- 后续登记在本规格而非路线图文件：先定位主屏OFF/isolated下实际cat输出与错误映射，
  保持畸形/截断拒绝，修复后重新执行isolated三个完整冷启动，再推进M4/M5。fixture的
  edge-to-edge布局问题仍待独立规格；API33/34、真机/OEM/硬件键盘和中文输入边界仍未覆盖。

## Round 20：更正exec-out诊断通道假设，最小复现通过（2026-11-14）

- 开工确认本分支HEAD为普通合并1a5b356、工作区干净；文档清理成果已合入。仅在本worktree工作，按用户新增授权后续可更新合并后的路线图；未修改其它worktree。
- 更正4c9468c：其假adb用退出1/stderr模拟缺文件，只符合shell。集成方实测exec-out旧exec服务把stderr并到stdout、退出0，导致真实诊断被送入XML校验。保留此历史记录，不回写旧验收。
- 先RED：go test ./internal/adb -run TestExecOutMissingWindowDumpMatchesOnlyCurrentDiagnostic -count=1，两个目标显示分支实际ACTION_FAILED，断言WINDOWS_UNAVAILABLE失败。随后只为显式目标添加退出0、stderr空、trim(stdout)完整等于本次UUID路径ENOENT行的分支；保留退出1/stderr精确识别。截断/超限检查在分类之前，默认无显示路径不改。
- GREEN：go test ./internal/adb -count=1通过（8.711秒）。覆盖显示0/7、首尾空白、分离stderr旧形态；不同路径、多一行、仅前缀、权限诊断、畸形/超限/截断、离线反例；不带显示编号仍按旧判。make build通过。
- 最小复现execout-fixed-minimal-1：UTC11:17:22.220→11:19:54.921，transport61；复用isolated-raw-F2流程，不计完整场景次数。主屏Settings搜索基线全部判据通过、显示3输入Diagnostic成功；主屏第1次ON、第2次OFF；虚拟帧720×1280，非黑921600/921600。原始dump退出0、stdout报告dumped、stderr空，test -f确认无文件、0字节、无显示组；随后真实CLI返回WINDOWS_UNAVAILABLE、retryable=false、details.displayId=3，消息包含唤醒主屏或用steal重建的出路。
- 指纹仍google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys，API35、320×640/160dpi、en-US、导航2。CLI SHA256=8838361972da00ac63833780e2debedbfd4e1e40011db0463d03dfd4ca99ef20；helper626142bb…、fixture1e88acf7…不变。原始CLI响应、raw-result、截图、系统分段和时间线保留在忽略目录scripts/emulator/build/focus/execout-fixed-minimal-1，清理前保存；无evidenceError/cleanupError。
- 自查本规格新增ADB调用：显示0截图复用exec-out screencap及完整PNG校验；显式目标input/am及getprop/sha256sum/proc操作走shell；helper管理通过转发上的JSON/帧协议。新增exec-out cat缺文件分类仅此一处；诊断脚本先test -f再读取，未发现其它把exec-out错误当作分离stderr的新增假设。本次只查，不扩改历史默认层级问题，该项待路线图登记。
- helper/显示/转发/临时文件均0，未设置PIN或修改全局设置，emu kill成功，宿主emulator/qemu为空。下一步完整isolated三次从冷启动重新计数，未把最小复现计入通过。

## Round 21：isolated一轮完整通过，搜索基线三修后仍不稳定，按阈值停止（2026-11-14）

- 使用feabd87产物及完全未改的cd15435脚手架，从冷启动串行安排A1/A2/A3。没有追加第四次搜索修复、放宽判据、重复盲注入或把失败计入通过。API35指纹、helper/fixture/CLI摘要与Round20一致；唯一emulator-5554，固定只读启动参数与profile。

| 轮次 | UTC起止 / transport | 九步焦点与主屏探针 | BACK/重启 | 息屏帧/输入/错误/恢复 | 结果 |
| --- | --- | --- | --- | --- | --- |
| execout-isolated-A1 | 11:23:58.953→11:30:20.791 / 62 | 全9步通过，top=0、S/0、IME服务/Insets/窗口可见、输入focused=true、探针全部落主屏 | DISPLAY_EMPTY、owned=true、Activity=null；重启与复位后Awake | 息屏WINDOWS_UNAVAILABLE，前后720×1280帧全部非黑；注入Sleep/DELETE不唤醒主屏，唤醒后INPUT:AwakeSlee，主屏Primaryabcdefhi不变 | 完整通过1轮 |
| execout-isolated-A2 | 11:31:22.771→11:32:26.160 / 63 | 52.443秒完整基线通过，57.329秒首个字符a动作，随后搜索输入窗口0；helper尚未启动，未进行虚拟屏操作 | 未执行 | 未执行 | 基线失败，连续计数归零 |
| execout-isolated-A3 | 未启动 | — | — | — | 按三修阈值停止 |

- A1第一次打开搜索时先读到输入、随后消失；原脚手架先保存原始证据，再重读Settings首页重新定位，只重试一次即成功。这是既有有界恢复的执行记录，未改脚本；整个完整轮次最终通过。没有用这次成功抹去A2失败。
- A2因果链由清理前原始证据确认：基线当前IME客户端搜索PID1670/显示0，所有原判据成立；11:32:17.028系统令PID1670以reason15 (STATE CHANGE)、status0、description=change ... state=empty退出，crash缓冲为空。随后原XML仅有SystemUI及Settings首页，matchingWindows=0、inputNodes=0；不是重复窗口，更不是虚拟屏产品抢焦点。当前恢复只包住Primary基线构建，首个字符探针在其外；本轮没有把恢复边界向后挪动来做第四次修补。
- 同一搜索基线已连续做过三次脚手架修复：Round15稳定节点/有界重定位、Round16完整基线与同客户端两次一致、Round18仅在helper前按新首页证据重建一次。第三版在A2仍失稳，触发用户“同一个脚手架问题连续修了3次仍不稳定，按产品行为问题对待：停下来报告”的规则。最新用户保留此规则，未授权重置次数；因此停止M3c，不进入M4/M5。A1是独立完整成功，不满足连续3次要求。

### 各次修复后的原始证据索引

以下均在清理前保存，位于Git忽略的scripts/emulator/build/focus/；不提交原始制品。旧轮证据缺口仍按Round16记录，不事后补造。

| 修复后失败 | 目录/总字节 | 原始脱敏XML SHA-256 | 截图 SHA-256 | 原始系统与时间线 |
| --- | --- | --- | --- | --- |
| 第1次后 | isolated-B1 / 219466 | 1834f6bc5b7bae44645438729e024605c436592bcf4ca46ed2d5e959d25433d6 | e6581ed43e08e66db0d97017c86e617323c29abd2a91f08c97f472e7d37c52f2 | window/IME前缀/activity/processes/timeline；旧轮无exit-info，不能据此断言退出原因 |
| 第2次后 | isolated-raw-F1 / 178217 | c7eaf32b6190ec72c87b71f0b33acb87a196a665e9e3891e511858fafbe13e7f | 61066c2464280fcaeab730e4b5f2095b26ac79fe73a57b8e64aa528cb2429345 | window/当前IME/activity/processes/crash/exit-info/timeline及真实响应 |
| 第3次后 | execout-isolated-A2 / 211466 | 1958349a950ef67f9e412b4e6c7022889156a2dea732e792b708d7a0406665c4 | e61be079197f59fe98c6e9b380936e6dc3b443660c69c302c376bac82656c9f3 | 同上；exit-info SHA256 808fc5cd765954e4e80af14f42cd73dac9a9465905907f70c4cd0eebb167eadc |

- A2实际触发CLI层级响应是成功的Settings首页XML，不是WINDOWS_UNAVAILABLE回归：failure-response.json 29037字节，SHA256=5293a2b7064e895b38c9b26f6a83bfa1f6ab1af68c30b41b240b05e81a011848；时间线SHA256=fc56e4522bd4f0316b4db7caf4838218b290e4c597d6531bcaf0bbd515ff1499。现场XML/截图/系统分段/时间线无evidenceError，目录小于1MiB。
- A1/A2均finally完成helper/虚拟屏/forward/临时文件为0、未设置PIN或修改全局设置、emu kill成功，无cleanupError/killError；宿主复核adb devices空、emulator/qemu进程为空。

### 本轮验证与未完成范围

- make comments退出0：597文件、16检查器自测；make test退出0：Go327个顶层Test定义（不是子用例执行数，含既有默认跳过设备冒烟）、全部包测试/vet及linux/darwin/windows交叉vet，协议56项，Skills3份与镜像检查通过。定向ADB包go test -count=1通过。
- make verify退出0；make build退出0，重建CLI仍8838361972da00ac63833780e2debedbfd4e1e40011db0463d03dfd4ca99ef20，与物理运行产物一致。
- make android-verify退出0（BUILD SUCCESSFUL in 9s，199任务，4执行/195缓存）：单测、assembleDebug、lintDebug通过；614项、0failure/error、1既有跳过，app564、fixture8、web-fixture6、displayd36。
- Node脚手架/原验收单测本轮再次23/23通过。三道既有风险门相对基线完整diff为空。没有新增MCP工具、没有修改App/Bridge/fixture/其它规格/main，未push。
- 本轮产品补丁提交feabd87，8文件：internal/adb/direct.go及测试、模块README、虚拟显示错误表、本规格4文件。当前收尾提交仅更新模块README与本规格状态，保存真实未完成结果。
- 本轮没有新增脚手架修复：沿用此前六项，遇到搜索第三次修复仍失稳即按阈值停止。M4真实MCP stdio三次未执行，仍0/3；M5 P01、P02、P03、P04、P05、P06、P07、P08、P09、P10、P11、P12、P13、P14、P15均未进入处理/未验收。合并后移交文档已只读查看，未把准备阅读当作交付。
- 用户列出的路线图后续项尚未写入docs/next-phase-tasks.md，待M3c解除阻塞后按串行阶段收尾：虚拟屏ui.scroll；wait/assert/scrollUntil、视觉与Bridge多显示；fixture API35 edge-to-edge遮挡；无显示编号层级活动窗口与缺文件误报；isolated无IME/中文输入；API33/34、真机/OEM、硬件键盘矩阵。搜索基线STATE CHANGE与恢复边界也需先解决或调整三修停机规则。未把这些待办标成完成。

## Round 22：替换主屏输入基线，独立复核与先失败测试（2026-11-14）

- 开工ba60258，status干净；worktree/main身份未变。项目负责人依据集成方实测决定放弃独立搜索进程，改用Settings主进程的WIFI_DIALOG；本轮不是旧搜索恢复的第四次加固。所有旧历史结论原样保留。
- 独立复核wifi-baseline-review-1，UTC13:30:19.752→13:33:39.023，transport2。显式am start -W --display 0 -a com.android.settings.WIFI_DIALOG返回Status: ok、WifiDialogActivity。窗口Add network，topFocusedDisplay=0；ssid bounds[36,114][240,162]、focused=true、空值text精确Enter the SSID。当前IME LatinIME、mUid=1000/PID990/显示0、mInputShown=true。没有点输入框或Save。
- 四次输入p1/p2/p3/p4分别严格得到p1、p1p2、p1p2p3、p1p2p3p4；第一动作到末次观察97秒，Settings PID990保持。独立复核不是集成方记录的转引，完整焦点/IME九步仍由后续完整验收逐项验证。
- 收尾依据最新层级定位android:id/button2，包com.android.settings、文字Cancel，点按一次后SSID节点消失。cmd wifi list-networks前后完整原文相等，只有系统AndroidWifi的open/owe两行，无新增网络。未设置PIN或修改全局设置，finally清理全0、emu kill成功，无宿主模拟器残留。原始脱敏初始XML/系统快照与时间线在忽略目录focus/wifi-baseline-review-1。
- 新增基线测试先RED（缺少新导出函数），实现后GREEN；删除搜索入口重试/搜索进程重建及其两项测试，不保留第二套基线；最小复现脚本同步。精确区分提示语与预置Primary，重复SSID、错误包/文本/失焦、错误Cancel/Save、网络列表变化及取消不关闭都拒绝；取消最多一次后只读观察，失败现场先保存再清理。
- Node基线/焦点/原回归25项通过。新增.test.mjs的测试注释最初不满足检查器对mjs脚本的固定用途前缀，补脚本用途注释；不涉及设备判据或动作修改。
- steal原三次的主屏基线是NexusLauncher，按dumpsys display0分段验证前台Activity，从未使用主屏文本或设置搜索；原脚本完全未改。故保留Round13三次完整65秒/PIN/重建证据，不因本次主屏输入基线替换重跑。
- 新系列wifi-isolated-A1/A2/A3使用同一新脚本从冷启动计数；尚不提前勾选整体通过。

## Round 23：状态等待脚手架处理可重试观察失败，三次重新计数（2026-11-14）

- wifi-isolated-A1/A2完整通过；A3在全部九步、BACK/空屏、重启后主屏全部焦点/IME判据均通过。Sleep只注入一次，第一观察为ON；第二次observe screen-state返回ACTION_FAILED、retryable=true（The device screen state could not be read from ADB output.），等待脚手架用cli()立即抛错，未用完原8次观察预算。该轮失败，不把两次成功拼作连续通过。
- 脚手架根因：把既有只读API明确标记可重试的失败当作有界状态等待的终止，而非一次未满足的观察。此次只为screen-state的ACTION_FAILED且retryable=true在原8次预算内重新读取，不重复Sleep/Wake，不改变最终on必须等于目标的判据。离线/其它错误码/不可重试错误立即失败，耗尽预算仍失败；没有修改产品解析器或接受缺失状态为OFF。
- A3真实失败响应、当时可得的脱敏层级/截图/WMS/IME/Activity/退出记录/时间线都在清理前保存到focus/wifi-isolated-A3，no evidenceError。其XML来源为最后一次唤醒时观察，不能冒称为CLI内部display输出；CLI内部原始dumpsys display未导出，具体缺字段原因未核实，作为局限保留。
- 新增保留方法：每次可重试状态失败先写实际CLI响应，再采相关原始dumpsys display、power分段和当前UIA/截图，区分关联采集与CLI内部字节；保存后才继续只读观察。仍每轮1MiB限额，超额/采集失败不悄悄跳过。
- 先写失败脚本测试（旧wait用cli导致两项失败），再实现；覆盖错误先保存再重读、同一总预算、持续失败终止、不可重试/离线立即停止；另测关联采集不能覆盖原响应。不是搜索页的第四次修复；本轮不增加新基线重建。
- A3最终Cancel一次关闭成功、网络列表不变；helper/显示/forward/临时文件为0、未设PIN/改全局设置、emu kill成功。新系列wifi-isolated-B1/B2/B3从冷启动从零计数，不纳入A系列任何轮。

## Round 24：新基线isolated连续三冷启动完整通过，M3c完成（2026-11-14）

| 轮次 | UTC起止 | transport | 完整结果 |
| --- | --- | --- | --- |
| wifi-isolated-B1 | 2026-11-14T13:55:30.193Z → 2026-11-14T14:00:47.860Z | 10 | 9步/全部主屏探针、BACK空屏/重启、息屏与恢复、取消/网络不变、清理均通过 |
| wifi-isolated-B2 | 2026-11-14T14:02:01.452Z → 2026-11-14T14:07:41.372Z | 11 | 9步/全部主屏探针、BACK空屏/重启、息屏与恢复、取消/网络不变、清理均通过 |
| wifi-isolated-B3 | 2026-11-14T14:08:42.645Z → 2026-11-14T14:13:24.404Z | 12 | 9步/全部主屏探针、BACK空屏/重启、息屏与恢复、取消/网络不变、清理均通过 |

- 三轮同一实现，均0次状态读取失败/重试；未拼接A系列结果。主屏始终top=0、窗口Add network active/focused=true、SSID focused=true，IME客户端Settings/显示0，服务mInputShown/Insets/IME窗口可见全部true。每步主屏字符探针成功；虚拟屏CLICK:1、Virtual→DELETE得Virtua。未指定显示DELETE在本时序落主屏，虚拟输入不变。
- BACK时owned=true、前台Activity=null、DISPLAY_EMPTY；重启后AwakeX→DELETE得Awake，主屏Primaryabcdefhi不变。Sleep条件均明确观察到OFF，虚拟屏帧720×1280全部非黑，层级严格WINDOWS_UNAVAILABLE；息屏Sleep/DELETE注入不唤醒主屏，唤醒后INPUT:AwakeSlee、主屏文本不变、层级恢复。
- 每轮最新层级Cancel一次关闭添加网络对话框，前后网络列表原文相等，仅系统AndroidWifi open/owe；从未点Save。helper/显示/forward/临时文件0，未设PIN/改全局设置，emu kill成功，换轮前无emulator/qemu。一次性复核探针文件已删除，仅保留忽略目录的原始证据。
- 指纹google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys；AVD osdk-test-35/API35/google_apis/x86_64，320×640/160dpi/en-US/导航2，虚拟720×1280/240dpi。helper626142bb4c8a26740b05560876f29d246b50c839cbf3e54e606c724f5310deee，fixture1e88acf7a0b88f00df212648ca93c3487b37d0cb50e19b92569b80aa8462f6f8，CLI8838361972da00ac63833780e2debedbfd4e1e40011db0463d03dfd4ca99ef20。
- 本地Node27/27、comments598文件/16自测、diff --check通过。原steal脚本未改，Round13三次65秒保活/PIN往返/重建保持；其主屏判据是Launcher/dumpsys，不涉及本次输入基线。M3/M3b/M3c按修订二完成，提交后进入M4；另补测一次新SSID基线steal焦点供文档对照，不作为原三次回归的替代。

## Round 25：新SSID基线的steal焦点对照与M4准备（2026-11-14）

- 补充wifi-steal-focus-1：2026-11-14T14:14:22.747Z→2026-11-14T14:17:28.414Z，emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:14，同helper/CLI/fixture与新SSID基线，9步测量完成、Cancel与网络不变、清理通过；不是65秒/PIN回归的替代。该补测在M3c三次通过后串行执行，MCP设备验收等其关闭模拟器后再开始。

S=com.android.settings，F=dev.aiauto.fixture，C=com.google.android.deskclock；三项可见依次是mInputShown、显示0 IME Insets、显示0输入法窗口。主屏输入节点始终focused=true，焦点应用始终WifiDialogActivity。steal数据来自wifi-steal-focus-1，isolated来自B3；每步主屏探针均成功。

| 步骤 | steal 顶层/主屏焦点窗口/active/focused | steal IME客户端/显示；三项可见 | isolated 对应状态 | 主屏文本（探针前→后，两策略相同） | 虚拟输入 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 0/Add network/true/true | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primary → Primarya | — |
| 启动helper/建屏 | 0/Add network/true/true | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primarya → Primaryab | — |
| 虚拟屏启动fixture | 2/null/false/false | F/2；否/否/否 | 0/Add network/true/true；S/0；是/是/是 | Primaryab → Primaryabc |  |
| 点按非输入区 | 2/null/false/false | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabc → Primaryabcd |  |
| 点按输入框 | 2/null/false/false | F/2；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcd → Primaryabcde |  |
| ASCII Virtual | 2/null/false/false | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcde → Primaryabcdef | Virtual |
| 定向DELETE | 2/null/false/false | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcdef → Primaryabcdefg | Virtua |
| 不带显示DELETE后 | 0/Add network/true/true | S/0；是/是/是 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcdef → Primaryabcdefh | Virtua |
| 虚拟屏启动时钟 | 2/null/false/false | C/2；否/否/否 | 0/Add network/true/true；S/0；是/是/是 | Primaryabcdefh → Primaryabcdefhi | — |

- 结果与先前同样逐步主屏探针方法一致：steal启动fixture/时钟后顶层2、主屏焦点窗口null、IME服务/Insets/窗口均隐藏，客户端F/2或C/2；点虚拟输入框后键盘在主屏但服务F/2。每步主屏探针会恢复S/0，因此部分行IME仍为S/0但主屏窗口失焦，不能改写为隔离成功。不带显示DELETE前顶层已由探针恢复0，只据此说明本时序落主屏。
- M4新增纯Node内置stdio客户端/测试及真实验收脚本，不增加生产MCP工具或依赖。已测试分帧/多字节/乱序响应、通知、坏帧/预算、超时/断连/管道错误不重发动作、真实结构字段判错；5项通过。MCP冷启动三次正在执行，不提前记录通过。

## Round 26：M4真实stdio连续三次通过（2026-11-14）

### 真实MCP stdio三冷启动结果

| 轮次 | UTC起止 | transport | tools/call次数 | 结果 |
| --- | --- | --- | --- | --- |
| mcp-A1 | 2026-11-14T14:18:38.948Z → 2026-11-14T14:20:43.532Z | 16 | 64 | isolated/steal、全部观察/动作/管理、stdio退出/清理通过 |
| mcp-A2 | 2026-11-14T14:21:37.858Z → 2026-11-14T14:23:34.604Z | 18 | 64 | isolated/steal、全部观察/动作/管理、stdio退出/清理通过 |
| mcp-A3 | 2026-11-14T14:24:29.414Z → 2026-11-14T14:26:31.984Z | 19 | 64 | isolated/steal、全部观察/动作/管理、stdio退出/清理通过 |

三次都通过initialize/initialized、tools/list（20工具，无helper启停）及真实tools/call，非内存会话或CLI替代。每轮先在helper未启动时读显示0层级与PNG，再验证管理入口DISPLAYD_NOT_RUNNING；CLI启动helper后，通过MCP对两种focus分别创建、列表/状态核对、启动fixture、复位、点按/长按/滑动/ASCII/DELETE、层级/帧/主屏归属、关闭及DISPLAY_NOT_FOUND。每轮stdio退出0、清理为0，未设置PIN或修改系统设置。

- 唯一设备仍emulator-5554，read-only冷启动、boot_completed=1、每次检查transport与相同API35指纹。helper/fixture/CLI SHA与Round24相同；MCP协议2025-11-25，helper独立协议2，公共CLI/Bridge仍1.0。三轮原始报告在scripts/emulator/build/mcp/mcp-A1/A2/A3（忽略目录）。
- 每屏六类动作及依据已在Round2和docs/virtual-display.md逐项登记；ui.scroll拒绝目标是因为主屏尺寸路由，等待/断言/滚动直到、视觉/Bridge等未接入工具继续拒绝。模型sleep/wake仍不开放，helper start/stop没有MCP工具，不放宽三道旧风险门。
- 补测两组新基线数据、默认isolated/steal取舍、API33实际不隔离、BACK/无IME/中文限制、空屏与无输出错误，以及v2升级先旧CLI停止v1步骤均写入README、协议与virtual-display文档。
- Node32项通过；comments601文件/16自测；skills-check通过3份与镜像。make test第一次在Node spawnSync go时因PATH为go.cmd而ENOENT，全部Go包此前通过；随后显式GO=E:/osdk-data/data/installs/go/1.26.5/bin/go.exe重跑make test及make verify均退出0，三系统cross-vet通过。未改Makefile、PATH持久配置或安装软件。
- make android-verify退出0（1m5s、199任务，6执行/193缓存）；单测614项、0失败、1既有跳过，assembleDebug/lintDebug通过。构建CLI在MCP会话结束后单独执行，避免占用中的Windows可执行文件；未在设备验收中替换CLI。
