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


