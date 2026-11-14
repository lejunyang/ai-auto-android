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
