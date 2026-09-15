# 动作面扩展进度

本文件 append-only，记录每轮的审计结论、RED / GREEN、模拟器证据与未完成边界。

## Round 1 — 审计与规格

- 主工作区 `main` ahead `origin/main` 14 个提交，worktree 干净，无并行 worktree。
- 审计确认动作面三层现状与 MainAgent 结论一致：
  - `protocol/schema/v1/action.schema.json` 的 `oneOf` 为 14 项；
    `ProviderActionParser.validate` 的 `when(type)` 分支与之逐项对应。
  - `AccessibilityModels.kt`：`NodeAction` 9 项、`Gesture` 2 项、
    `GlobalAction` 3 项、`AccessibilityCommand` 7 个子类。
  - `AndroidAccessibilityBackend.kt`：`dispatchGesture` 只
    `addStroke(StrokeDescription(path, 0, durationMs))` 一笔；
    `validateTarget` / `openNodeSession` / `findNodeInActiveWindow` /
    `findUniqueNodeAt` 四处全部取 `service.rootInActiveWindow`，
    确认无 `service.windows` 调用。
  - `AccessibilitySnapshotter.toNodeAction()` 的映射表同样只认 9 个动作 id，
    因此即使节点支持 COPY / PASTE / SET_SELECTION，快照也不会上报——
    这是 A5 / A6 / A9 除路由外的第二个改动点，原缺口清单未点出。
  - `internal/adb/direct.go` `allowedKeys` 为 17 项（不是 18；
    实际计数：BACK / HOME / RECENTS / ENTER / TAB / ESCAPE / SPACE / DELETE /
    FORWARD_DEL / DPAD_UP / DPAD_DOWN / DPAD_LEFT / DPAD_RIGHT / DPAD_CENTER /
    VOLUME_UP / VOLUME_DOWN / VOLUME_MUTE），无光标移动键。
  - `internal/mcpserver/schemas.go` `actionSchema()` 的 `oneOf` 为 8 项，
    `ToolNames` 为 11 项。
  - `AndroidBridgeMethods.capabilities()` 为 8 项，其中 UI 相关仅
    `ui.snapshot`、`ui.foreground`、`action.execute`。
- 建立独立 change spec 与 `docs/action-surface-tasks.md`，未改
  `docs/next-phase-tasks.md`。

### 与原缺口清单的差异（需记录，不影响范围）

1. `allowedKeys` 实为 17 项而非 18 项。
2. `AccessibilitySnapshotter.toNodeAction()` 是节点动作补齐的必改点，
   原清单只提到 router / models / backend。
3. `internal/adb/gesture.go` 的 `ScrollDirection` 只有 up/down/left/right 四向，
   缺 forward/backward（直连侧无元素语义，属既有设计取舍，非本批缺口）。

### 环境

- serial `emulator-5554`，AVD `osdk-test-35`，API 35（Android 15），
  320x640，x86_64。
- `scripts/emulator/profiles.json` 声明的 API 30 / 33 / 34 arm64 镜像在本机
  **未安装**（`$ANDROID_HOME/system-images` 下只有 `android-35`），
  故本批只能给出 API 35 证据，30 / 33 / 34 矩阵验收保持未完成。

## Round 2 — 三层实现与首轮设备验收

### 实现落地

按串行顺序完成 A1（多窗口）、A7-核心（多 stroke 内核）、A2（drag）、
A8（连击）、A7-手势（pinch/rotate/双指滚动）、A3（scrollIntoView）、
A5/A6（文本编辑）、A9（节点动作）、A10（全局动作）、A17（路径手势）的
三层接线，共 14 个提交。

### 三条实测结论（推翻了实现前的假设，必须记录）

1. **`input motionevent` 不支持多指。** 它只接受一个坐标对，传入第三个参数时
   **静默忽略**并返回 exit 0——`input motionevent DOWN 100 300 NOTANUMBER`
   都「成功」，而参数不足时才抛 IllegalArgumentException。
   因此直连 ADB 通道**无法**合成多指手势，任何这样写的代码都会退化成单指
   且不报错。多指只能走无障碍的多 stroke `dispatchGesture`。
   原计划中「drag 与多指手势用 `input motionevent` 逐段合成」的判断是错的，
   已在 `internal/service/service.go` 与 `composite_gesture.go` 中改正。

2. **缺 `flagRetrieveInteractiveWindows` 时 `service.windows` 恒为空。**
   两份 accessibility-service 配置原本都没声明它。首轮设备测试
   `enumerateWindows()` 返回 0 个窗口，而同一时刻 `dumpsys window windows`
   显示存在 9 个窗口（含 InputMethod、StatusBar、Taskbar）。
   这说明多窗口定位在代码完全正确的情况下也一定失效。补上后测试通过。

3. **无事件手势会被归因机制误杀。** 缩放与旋转由 View 内部处理，不产生可监听的
   View 语义事件，事件预算必然为空；而 `runAttributedAction` 在预算为空时无法
   arm，直接返回 false，手势根本不会派发。表现为 `ui.pinch` 稳定
   GESTURE_FAILED。这不是安全检查生效，而是「无事件可归因」被误当成
   「归因失败」。补上预算判空后通过。

### 设备验收

serial `emulator-5554`，AVD `osdk-test-35`，API 35（Android 15），320x640，
x86_64，`capabilities=161`，`Crashed services:{}`。
`ActionSurfaceDeviceTest` 走生产 router 与 backend，结果 `OK (4 tests)`：
多窗口枚举、多 stroke 拖拽、双击、双指缩放。

设备测试的两个前置条件也记录在案：instrumentation 默认挂起第三方无障碍服务，
必须用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`；且它会重建目标进程，
旧进程的 `onServiceConnected` 不会重放，因此还要在测试内部重新写一次
secure 设置触发绑定。自动写设置前校验 `Build.FINGERPRINT` 拒绝真机。

### 仓库门禁

`make comments`、`make test`、`make verify`、`make build` 全部通过；
Android `testDebugUnitTest` 402 项通过，`lintDebug`、`assembleDebug` 通过。

### 未完成边界（不得描述为已交付）

- A3、A5、A6、A9、A10、A17 与 zoom/旋转/双指滚动：三层实现完成但**无设备证据**。
  A5/A6/A9 缺可编辑控件与可调节控件的 debug 夹具（现有 `ScreenshotTestActivity`
  只有 Button 与 ScrollView）。
- A4 只有 MOVE_HOME/MOVE_END 能走无障碍通道，其余键仍需 ADB direct，属部分实现。
- A11、A12、A13、A14、A15、A16 未开始。
- 全局动作「快捷键帮助面板」确认无法实现：常量为 `@hide`，仅系统应用可用。
- API 30 / 33 / 34 矩阵未验收。

### 一次排查记录（避免重复踩）

首轮设备测试曾出现 `Crashed services` 包含生产服务，一度怀疑是 backend 改动
导致崩溃。实际原因是重装 APK 会把包置为 `enabled=0` 并重置
`accessibility_enabled`，服务在半禁用状态下绑定失败。`pm enable` 后重新写
secure 设置即恢复，`Crashed services:{}`。logcat 与 crash buffer 均无应用异常
堆栈，可据此排除代码崩溃。

## Round 3 — 节点级能力的设备验收

新增 debug 夹具 `ActionSurfaceFixtureActivity`（EditText × 2、SeekBar、
CheckBox、可展开容器、24 行填充、屏外滚动目标）与
`ActionSurfaceNodeActionDeviceTest`。单独成一个 Activity 而非扩充
`ScreenshotTestActivity`：后者已被四组既有测试共用，加控件会改变其节点树
与坐标，可能让既有断言失效。

### 又一条实测结论：归因判据本身是错的

Round 2 末尾用「事件预算非空」决定走不走归因路径，这个判据不成立。
`UserTouchMonitor` 只观察 CLICKED / LONG_CLICKED / SCROLLED / TEXT_CHANGED
四类，`armAutomationAction` 会把预算过滤到这四类，过滤后为空则返回 null，
`runAttributedAction` 随即返回 false——动作根本不执行。

因此「预算非空但无一项可监听」与「预算为空」是同一种失效。SET_SELECTION
恰好落在这个缺口：它只产生 TYPE_VIEW_TEXT_SELECTION_CHANGED，不在监视范围，
于是稳定报 ACTION_FAILED。修正为新增 `hasMonitorableBudget`，把判据收敛到
监视器内部，手势与节点动作两处共用，避免两边各自维护事件清单而逐渐不一致。

### 另两条平台行为

- **SET_SELECTION 需要输入焦点。** 节点未获焦点时平台拒绝该动作——选区是
  输入状态的一部分，没有焦点就没有插入点。已在 selectText 与 paste 前
  各加一次 FOCUS；聚焦失败不提前终止，让真正的动作去报错，
  否则错误信息会指向一个调用方没有请求的操作。
- **状态变化必须显式广播。** 夹具的可展开控件切换状态后未发
  TYPE_WINDOW_CONTENT_CHANGED，框架继续沿用缓存节点信息，展开后再查仍只有
  ACTION_EXPAND，于是 COLLAPSE 被判为「节点不支持」。

### 设备验收

`ActionSurfaceNodeActionDeviceTest`：`Tests run: 5, Failures: 1`。
通过：文本选区（断言真实 selectionStart/End）、复制+粘贴到另一个输入框
（断言目标框文本等于被选片段）、SeekBar 进度（断言 progress==42）、
展开折叠（连续两步，同时验证状态广播）。
`ActionSurfaceDeviceTest` 仍为 `OK (4 tests)`。

### 未完成：B1

`scrollIntoView` 的滚动手势在本设备上稳定 `GESTURE_FAILED`。已排除目标可见性、
轮次逻辑、归因判据（同一修正让 pinch 与文本选区通过）与坐标校验。
剩余可疑点是 `GesturePlanner.scroll` 在 320x640 这一较小屏上算出的起止点
落入系统手势区，被系统在派发前截走。

下一步是把起止点改为避开上下系统手势内边距的可用区并复测。该改动会影响既有
`ui.scroll` 的坐标降级路径，属行为变更，因此单独一项处理而不混入本批修复。
`ui.scroll` 本身不受影响——它优先走 `ACTION_SCROLL_*` 节点动作。

## Round 4 — A11 至 A16

按 Organizer 排期先做这六项：它们彼此独立、也不依赖 B1，是当前最大的未交付面。

### 先探测再实现

本轮开工先对六项能力逐一做设备探测，而不是照文档写代码。这个顺序直接避免了
一次返工：探测发现设备上**不存在** `cmd clipboard` shell 命令
（返回 "No shell command implementation"），而 `service list` 里确有
`clipboard: [android.content.IClipboard]`。若按「剪贴板走 ADB」的原计划实现，
会得到一份编译通过、运行报错的代码。该结论记为 F6。

### 通道归属的最终结论

- 直连（ADB）：旋转（`settings put system user_rotation`）、唤醒
  （`KEYCODE_WAKEUP`）、解锁、亮屏检测（`dumpsys display` 的 mScreenState）、
  IME 枚举与切换（`ime list -s` / `ime set`）、收起键盘（BACK）、
  deeplink（`am start -a VIEW -d`）、文件传输（`adb push`/`pull`）。
- App Bridge：剪贴板（`ClipboardManager`）、Toast 捕获
  （`TYPE_NOTIFICATION_STATE_CHANGED`）。

### 三条本轮新增的平台事实

1. **旋转必须先关 accelerometer_rotation。** 该值为 1 时系统按传感器覆盖
   `user_rotation`，于是「设置成功但方向没变」。实测写入后
   `mDisplayRotation=ROTATION_90`、bounds 变为 640x320，确认方向真的生效。
2. **Toast 必须由前台应用发出。** 自 Android 11 起后台应用的 Toast 被平台抑制，
   不显示也就不产生无障碍事件。设备测试最初恒失败正因如此，
   把夹具推到前台后立即通过——这与捕获逻辑无关。
3. **两份 accessibility-service 配置的订阅集合必须一致。** debug 测试服务
   原本缺 `typeNotificationStateChanged`，导致设备测试里 Toast 捕获恒为空，
   而生产服务其实能收到该事件。这是「测试测到的能力与生产不同」的典型。

### 设备验收

- `TestDeviceStateAgainstRealDevice` PASS：旋转三态并复位、亮屏检测、
  IME 枚举与非法切换拒绝、文件往返比对。
- `TransientMessageDeviceTest` OK (2 tests)：Toast 捕获（断言文本、toast
  标志位与来源包名）、剪贴板前台读写往返。
- deeplink 限包实测生效（intent 含 `pkg=`），避免弹应用选择器卡住自动化。

### 未实现且已确认无法实现

- IME「显示键盘」：无障碍与 ADB 都没有公开的弹出输入法入口，
  只能靠聚焦可编辑节点间接触发。A12 因此只交付枚举/切换/收起，保持未勾选。
- 有凭据锁屏的解锁：刻意不实现，属用户必须亲自完成的操作。

### B2：把一个偶发失败查到根因

`internal/mcpserver` 的
`TestSharedServerVisualCallReturnsSameObservationAndCleansAfterWrite`
在一次 `make test` 中报 `pending visual cleanups = 1`。没有简单归为环境抖动，
而是查到根因：`visualCleanupConnection.Write` 先把响应写出去、之后才调
`runtime.cleanup`，而测试在拿到响应后立即断言 `pendingCount()==0`，
两者构成竞态。

用 `main` 基线 worktree 交叉验证：基线单包 5/5、`make test` 4/4 通过；
本分支单包 8/8、`make test` 4/4 通过。确认与本批改动无关，且不影响生产
（cleanup 最终一定执行）。修复属 N44/N45 视觉模块范围，不在本批职责内，
已记入状态源第 10 节。验证用的 worktree 已非强制移除并 prune。

## Round 5 — B1 修复与剩余能力验收

### B1：原假设被实测推翻

上一轮怀疑「320x640 小屏起止点落入系统手势区」。本轮先用 `dumpsys window`
量出真实手势区：顶部 mandatory [0,0][320,24]、底部 mandatory [0,608][320,640]、
左右 systemGestures 各 30px。而 `GesturePlanner.scroll` 在 amount=0.6 时算出的
起点 y=474、终点 y=166 都不在任何手势区内——**坐标不是原因，原假设不成立**。

改用可观测的排查方式：新增三层对照（平台原生 `dispatchGesture` / router 的
`ui.scroll` / `scrollIntoView`）并在每步前后采集可见节点指纹。指纹直接给出了
关键证据：`container@0` → `Filler row 4@0` → `Filler row 12@0`，
说明滚动确实生效、签名确实会变。据此查出三个叠加原因：

1. 滚到尽头后容器不再提供 `ACTION_SCROLL_FORWARD`，`executeScroll` 降级到手势
   路径；手势路径带着 match 去 arm 归因，等一个「已经到底、不会再发生」的
   `TYPE_VIEW_SCROLLED`，必然超时并报 `GESTURE_FAILED`。这是「滚不动了」被
   误报成「手势被系统拒绝」。
2. 无进展签名只取节点标识不取坐标。ScrollView 把全部子节点保留在树里，
   滚动只改坐标，标识集合始终不变，于是首轮就被判为已到尽头。
3. 单轮签名相同不足以判定到底。滚动动画与快照采集是两条独立时序，
   快照恰好落在动画未推进的窗口内就会与上一轮相同。

修复分别是：`dispatchSwipe` 新增 `requireScrollEvent` 开关（逐轮滚动传 false，
并把 `GESTURE_FAILED` 当成一次无进展）、签名纳入 `bounds` 的 top/left、
要求连续 3 轮无变化才判定到底。三者缺一都不足以修好。

B1 在独立分支 `fix/scroll-into-view-gesture-insets` 完成，非快进合并回主分支
以保留提交边界。定位用的三层对照探针在完成后删除——它以 AssertionError 输出
观测值，是排查工具而非回归断言，留下会成为一个永远失败的测试。调试服务保留
`visibleNodeFingerprint` 与 `dispatchRawSwipe` 两个只读探针供后续复用。

### 剩余能力验收：两项新的平台限制

补齐 zoom（scale<1）、旋转、双指滚动、路径手势、三连击、剪切与
FOCUS / SELECT / CLEAR_SELECTION 的设备验收，全部通过。另发现两项限制：

- **B3 `SCROLL_TO_POSITION` 在 ScrollView 上不可用**：该动作要求容器提供
  `CollectionInfo`，ScrollView 不是集合容器。在 RecyclerView 上的可用性
  **未验证**，夹具没有该控件。
- **B4 `CLEAR_FOCUS` 被平台拒绝**：拒绝方式是 `ACTION_FAILED` 而非
  `ACTION_NOT_SUPPORTED`，即节点声明了该动作但 View 的
  `performAccessibilityAction` 返回 false。先怀疑「无焦点可清」，
  用 `uiautomator dump` 确认 `focused=false` 后补上 FOCUS 前置，行为不变。

两者都在设备测试中**断言其明确失败**而非跳过：把平台真实行为写进断言，
将来版本行为变化时能被测试发现；跳过则会静默掩盖能力缺口。

### 断言强度的取舍

多指与路径手势只断言「平台接受了派发」，不断言视觉结果——夹具没有响应缩放
旋转的控件，强行断言会写出一个假测试。而剪切断言剩余文本，因为剪切与复制的
区别正是「原文被移除」，只断言动作成功无法区分二者。

### 收口

设备用例合计 15 项全部通过（4 + 5 + 2 + 4）。Android 单测 408 项、
`lintDebug`、`assembleDebug` 通过；`make comments` / `make test` /
`make verify` / `make build` 全部通过。

## Round 6 — 补齐两处验证缺口

本轮处理的两项都属「验证缺口」而非方向选择：夹具缺控件导致无法判定能力是否
可用、以及会中断会话的动作此前没有验证策略。两者都补上后不再有「因为没测所以
不知道」的空白。

### SCROLL_TO_POSITION：从「未验证」到「边界完整」

夹具补入平台自带 `ListView`（40 行 / 视口约 3 行）。选它而非 RecyclerView 的
理由是依赖边界：后者需新增 androidx 依赖，而依赖会进入所有变体，仅为测试夹具
引入生产依赖不划算；ListView 继承 AbsListView，平台已为其提供 `CollectionInfo`，
正是该动作生效的前提。夹具本身仍只在 debug 变体。

两处踩坑：
1. ListView 最初放在 24 行填充行之后，落在视口外、节点不进入无障碍树，
   报的是 `SELECTOR_NOT_FOUND` 而非能力不支持——差一点被误判成平台限制。
   移到填充行之前解决。
2. 断言不能写 `firstVisiblePosition == 请求行`。平台语义是「把该行滚到可见」
   而非「置顶」：实测请求第 20 行后 `firstVisiblePosition=18`。改为断言位置
   离开顶部且请求行在可视范围内——既证明真的滚动了，又不把平台的正确行为
   判成失败。

结论：该能力**在集合容器上可用**。与 ScrollView 上的失败断言合起来，
B3 从「未验证」变为「边界完整刻画」。

### A10：按可中断性分档

- 可恢复档（通知栏 / 快捷设置 / 电源菜单）：断言窗口集合确实变化，
  再 BACK 复位并**断言回到夹具**。复位写成断言而非静默尽力——
  残留面板会让后续用例在错误界面上执行。允许 3 次尝试，因为快捷设置
  从完全展开态需要两次 BACK。
- 不改变界面档（截屏）：验证重点是「不破坏会话」。不断言文件落盘——
  那要读媒体库、需额外权限，而本项要证明的是动作可用且无意外副作用。
- 会中断档（锁屏）：独立用例，动作后主动 WAKEUP + MENU 解锁，
  再断言服务仍能观察窗口。这一条才是关键：若锁屏后服务不可用，
  该动作在实际自动化里就无法安全使用。有凭据设备上解锁会失败，
  那属用户必须亲自完成的操作，已在注释写明边界。

五项全部通过。测试后复查 `mScreenState=ON`、焦点回到 launcher，无残留状态。

调试探针 `performGlobalActionForTest` 复用 router 内部那个 backend 实例而非
另建：全局动作的 API 分级判定写在 backend 里，另建实例可能走不同代码路径，
那样测过的就不是被交付的实现。

### 收口

设备用例合计 21 项全部通过（4 + 5 + 2 + 5 + 5）。D4 已确认并写入决策记录，
D1–D4 全部已决。





