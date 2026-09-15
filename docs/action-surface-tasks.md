# 动作面扩展任务跟踪

本文件是「动作面扩展」这批工作的**唯一状态源**。规格见
[`.trae/specs/action-surface-expansion/spec.md`](../.trae/specs/action-surface-expansion/spec.md)，
任务勾选见同目录 `tasks.md`，逐轮记录见同目录 `progress.md`。

本批工作**不**写入 `docs/next-phase-tasks.md`。

## 1. 目标

当前动作面底层只有两类原语：`NodeAction`（CLICK / LONG_CLICK / SET_TEXT / 六向
SCROLL）与 `Gesture`（Tap / Swipe）。`protocol/schema/v1/action.schema.json` 的
`oneOf` 仅 14 项。三条通道封闭且一致，但真实 App 场景（弹窗、输入法、拖拽排序、
缩放、长列表定位、文本编辑）无法表达。

本批把 17 项缺失能力补齐为**三层贯通**的产品能力：

```
protocol schema  →  Android（models / router / backend / bridge parser / capability）  →  Go（service / adb 或 bridge / mcp / cli）
```

只改一层不算完成。

## 2. 能力清单与当前状态

状态取值：`未开始` / `进行中` / `已实现未验证` / `已交付` / `未完成`。
只有三层实现 + 定向测试 + 模拟器实测证据齐备才能记 `已交付`。

### P0（真实 App 会直接卡死）

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A1 | 多窗口模型（`service.windows` 遍历） | 已交付 | 35 | E1 |
| A2 | `drag`（按住—停顿—移动） | 已交付（桥侧+直连） | 35 | E2、E5 |
| A3 | `scrollIntoView` / `scrollUntil` | 已交付 | 35 | E10 |
| A4 | Bridge 侧 `pressKey` | 部分实现 | — | 仅 MOVE_HOME/MOVE_END 可走无障碍，其余键需 ADB；无设备证据 |

### P1

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A5 | 文本选择（`ACTION_SET_SELECTION`） | 已交付 | 35 | E6 |
| A6 | 复制 / 剪切 / 粘贴 | 已交付 | 35 | E7、E17 |
| A7 | 多指与复合手势（pinch / zoom / 旋转 / 双指滚动） | 已交付 | 35 | E4、E18 |
| A8 | 双击 / N 连击 | 已交付 | 35 | E3、E19 |
| A9 | 节点动作补齐（8 项） | 部分已交付：EXPAND / COLLAPSE / SET_PROGRESS / FOCUS / SELECT / CLEAR_SELECTION / DISMISS 可用；CLEAR_FOCUS 与 SCROLL_TO_POSITION 被平台拒绝 | 35 | E8、E9、E20 |
| A10 | 全局动作补齐（5 项） | 已实现未验证 | — | 通知栏/快捷设置/锁屏/截屏/电源菜单已按 API 分级接线；快捷键面板确认无法实现（见第 4.2 节 D1） |
| A11 | 设备状态动作 | 已交付 | 35 | E11 |
| A12 | IME 控制 | 部分已交付：枚举/切换/收起键盘已验证；显示键盘未实现 | 35 | E12 |

### P2

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A13 | 剪贴板读写 | 已交付（桥侧） | 35 | E13 |
| A14 | deeplink 打开 | 已交付 | 35 | E14 |
| A15 | 文件 push / pull | 已交付 | 35 | E15 |
| A16 | Toast 捕获 | 已交付 | 35 | E16 |
| A17 | 路径手势 | 已交付 | 35 | E21 |

### 设备证据编号

统一环境：serial `emulator-5554`，AVD `osdk-test-35`，API 35（Android 15），
320x640，x86_64，无障碍 `capabilities=161`，`Crashed services:{}`。
执行方式：`ActionSurfaceDeviceTest`，走生产 `AccessibilityActionRouter`
与 `AndroidAccessibilityBackend`，非测试专用简化通道。

| 编号 | 内容 |
| --- | --- |
| E1 | `enumeratesMoreThanTheActiveWindowOnRealDevice` 通过。前置观察：`dumpsys window windows` 显示 9 个窗口；补 `flagRetrieveInteractiveWindows` 前 `enumerateWindows()` 返回 0，补后返回多个窗口，含被测 Activity 窗口且其根节点有子节点，layer 严格降序。 |
| E2 | `dispatchesMultiStrokeDragThroughTheProductionRouter` 通过，证明多 stroke + `continueStroke` 续接被平台接受。 |
| E3 | `dispatchesMultiTapThroughTheProductionRouter` 通过（双击）。 |
| E4 | `dispatchesTwoFingerPinchThroughTheProductionRouter` 通过，证明同一 `GestureDescription` 内两笔并行可用——这是多指能力唯一的真实证据。 |
| E5 | 直连通道 `input draganddrop` 与 `input motionevent` 在同一设备上实测可用；同时确认 `input motionevent` 只接受一个坐标对（传第三个参数被静默忽略且返回 exit 0），故直连**无法**合成多指。 |
| E6 | `setsTextSelectionOnTheEditableField` 通过。断言的是控件真实 `selectionStart/End` 等于请求区间（2,6），而非只看动作返回值——`ACTION_SET_SELECTION` 在参数越界时也可能返回 true。 |
| E7 | `copiesTheSelectedTextAndPastesItIntoAnotherField` 通过。选区→复制→粘贴到**另一个**输入框，断言目标框文本等于被选片段 `cdef`。粘贴到源框无法区分「粘贴成功」与「内容本来就在那」。剪切未单独验证。 |
| E8 | `setsSeekBarProgress` 通过，断言 `SeekBar.progress` 实际等于 42。 |
| E9 | `expandsAndCollapsesTheSection` 通过。展开后必须能再折叠——夹具按当前状态只暴露一个动作，若展开未真正改变状态，折叠会因 `ACTION_COLLAPSE` 不存在而失败，故这条同时验证了状态变化被正确广播。 |
| E10 | `scrollsAnOffscreenTargetIntoView` PASS。前置断言目标初始不可见，后置断言其进入视口——仅断言动作成功不够，实现可能没滚动就返回成功。修复过程见第 10 节 B1。 |
| E11 | `TestDeviceStateAgainstRealDevice` PASS。旋转按 landscape → portrait → auto 顺序执行并复位；写入 `user_rotation=1` 后 `dumpsys window` 显示 `mDisplayRotation=ROTATION_90`、窗口 bounds 变为 640x320，确认方向真的生效而不只是设置写成功。亮屏检测读到 `mScreenState=ON`。 |
| E12 | 同上用例中 IME 枚举返回 2 个输入法且格式合法；对未启用输入法的切换请求被拒（证明校验发生在 `ime set` 之前）；`ime set` 切回默认输入法返回 "selected for user #0"。收起键盘走 BACK 键实测 exit 0。**显示键盘未实现**：无障碍与 ADB 都没有公开的「弹出输入法」入口，只能靠聚焦可编辑节点间接触发。 |
| E13 | `writesAndReadsClipboardTextInTheForeground` PASS。先把夹具推到前台再读写——API 29+ 要求应用持有输入焦点，否则平台拒绝读取。写入后读回内容与写入值一致。 |
| E14 | `am start -a VIEW -d https://example.com/probe -p com.android.chrome` 实测成功，intent 含 `pkg=com.android.chrome`，确认限包生效（避免弹出应用选择器卡住自动化）。参数校验层另有 6 项断言覆盖畸形 URI 与 shell 元字符。 |
| E15 | 同 E11 用例中文件往返 PASS：push 后 pull 回本地并比对内容一致，设备端文件用 defer 清理。另有 11 项参数校验断言覆盖远端路径的注入字符与父目录逃逸。 |
| E16 | `capturesAToastShownByTheAppUnderTest` PASS。断言覆盖文本内容、`toast` 标志位与来源包名。`dumpsys accessibility` 确认运行时 eventTypes 含 `TYPE_NOTIFICATION_STATE_CHANGED`。 |
| E17 | `cutsSelectedTextOutOfTheField` PASS。断言剩余文本等于被切走片段之后的内容——剪切与复制的区别正是「原文被移除」，只断言动作成功无法区分二者。 |
| E18 | `dispatchesZoomRotateAndTwoFingerScroll` PASS。覆盖 scale<1 的 zoom 方向、45 度旋转与双指竖向滚动。只断言平台接受派发：夹具没有响应缩放旋转的控件，强行断言视觉结果会写出假测试。 |
| E19 | `dispatchesAPathGestureAndTripleTap` PASS 中的三连击部分，覆盖 `taps > 2` 分支。 |
| E20 | `performsFocusSelectionAndScrollToPositionNodeActions` PASS。FOCUS、SELECT、CLEAR_SELECTION 通过；`SCROLL_TO_POSITION` 与 `CLEAR_FOCUS` 被断言为**明确失败**（原因见第 10 节 B3、B4），断言其失败而非跳过，以便平台行为变化时能被测试发现。 |
| E21 | 同 E19 用例中的三点折线路径手势 PASS，模拟图案解锁那类需精确经过中间点的轨迹。 |

最近一次执行结果：
- `ActionSurfaceDeviceTest`：`OK (4 tests)`。
- `ActionSurfaceNodeActionDeviceTest`：`OK (5 tests)`。
- `TransientMessageDeviceTest`：`OK (2 tests)`。
- `ActionSurfaceRemainingDeviceTest`：`OK (4 tests)`。
- `internal/adb` 全量 PASS（含 `TestDeviceStateAgainstRealDevice`）。

设备用例合计 15 项全部通过。
Android 单测 408 项、`lintDebug`、`assembleDebug` 通过；
`make comments` / `make verify` / `make build` 通过。

## 10. 已修复与已知未完成项

| 编号 | 项 | 结论 |
| --- | --- | --- |
| B1 | `scrollIntoView` 报 `GESTURE_FAILED` | **已修复**。原假设（小屏起止点落入系统手势区）经实测被推翻：`dumpsys` 量出 320x640 的手势区为顶 24px、底 32px、左右各 30px，而 planner 算出的起点 y=474、终点 y=166 均不在其中。改用三层对照（平台原生派发 / router 的 ui.scroll / scrollIntoView）+ 可见节点指纹定位，查出三个叠加原因：①滚到尽头后节点动作不可用，降级到手势路径时带 match 去 arm 归因、等一个不会发生的 SCROLL 事件而超时，把「滚不动了」误报成「手势被拒」；②无进展签名只取节点标识不取坐标，而 ScrollView 滚动只改坐标，导致首轮即判定到底；③单轮签名相同不足以判定到底——滚动动画与快照采集是独立时序，快照可能落在动画未推进的窗口内。修复分别为 `requireScrollEvent` 开关、签名纳入 bounds、连续 3 轮无变化才判定。见 E10 |
| B2 | `internal/mcpserver` 视觉清理测试偶发失败 | **与本批无关，保留记录**。`visualCleanupConnection.Write` 先发响应、后做 cleanup，测试拿到响应即断言 `pendingCount()==0`，构成竞态。基线 `main` 单包 5/5、`make test` 4/4 通过；本分支单包 8/8、`make test` 4/4 通过。不影响生产（cleanup 最终一定执行）。修复属 N44/N45 视觉模块范围，按 Organizer 指示本批不改 |
| B3 | `SCROLL_TO_POSITION` 在 ScrollView 上不可用 | **平台限制，非缺陷**。该动作要求容器提供 `CollectionInfo`（RecyclerView / ListView 等），ScrollView 不是集合容器。已在设备测试中断言其明确失败而非跳过，避免静默掩盖。在 RecyclerView 上的可用性**未验证**——夹具没有该控件 |
| B4 | `CLEAR_FOCUS` 被平台拒绝 | **未完成**。拒绝方式是 `ACTION_FAILED` 而非 `ACTION_NOT_SUPPORTED`，即节点声明了该动作但 View 的 `performAccessibilityAction` 返回 false。已排除「无焦点可清」：用 `uiautomator dump` 确认 `focused=false` 后补上 FOCUS 前置，行为不变。影响：无法主动清除输入焦点；变通做法是聚焦另一个节点。已在设备测试中断言其失败，若将来平台行为变化会被测试发现 |

## 11. 本批未实现且已确认无法实现的项

| 项 | 原因 | 替代路径 |
| --- | --- | --- |
| 全局动作「快捷键帮助面板」 | 常量 `GLOBAL_ACTION_KEYBOARD_SHORTCUTS` 是 `@hide`，仅系统应用可用 | 无。按 D1 如实上报 `ACTION_NOT_SUPPORTED` |
| IME「显示键盘」 | 无障碍与 ADB 都没有公开的「弹出输入法」入口 | 对可编辑节点执行 FOCUS 或 CLICK 间接触发；A12 只交付枚举/切换/收起 |
| 节点动作 `CLEAR_FOCUS` | 见 B4，平台拒绝执行 | 聚焦另一个节点以转移焦点 |
| 节点动作 `SCROLL_TO_POSITION`（在 ScrollView 上） | 见 B3，需要 `CollectionInfo` 容器 | 用 `scrollIntoView` 按目标元素滚动 |
| 直连通道的多指手势 | `input motionevent` 只接受单坐标对（F1） | 走 App Bridge 的多 stroke `dispatchGesture`，即 D2 |
| 直连通道的剪贴板 | 设备上不存在 `cmd clipboard`（F6） | 走 App Bridge 的 `ClipboardManager`，已交付 |
| 有凭据锁屏的解锁 | 需要输入用户密码 | 刻意不实现：属用户必须亲自完成的操作，自动化绕过它是安全边界问题 |



## 3. 实施步骤与依赖

```
A1（多窗口，地基）
  └─ 所有后续能力的节点定位都依赖它

A7-核心（dispatchGesture 多 stroke + willContinue 续接）
  ├─ A2  drag
  ├─ A8  双击 / N 连击
  ├─ A7-手势 pinch / zoom / 旋转 / 双指滚动
  └─ A17 路径手势

A3  scrollIntoView（依赖 A1 的多窗口快照）
A5 → A6（文本选择先落地，复制/粘贴复用其选区）
A4（按键白名单扩展同时服务 A5 的光标移动键）
A9 / A10 / A11 / A12（相互独立）
A13 / A14 / A15 / A16（相互独立）
```

**串行顺序**：A1 → A7-核心 → A2 → A3 → A5 → A6 → A8 → A9 → A4 → A7-手势 → A10 →
A11 → A12 → A13 → A14 → A15 → A16 → A17。

## 4. 并行波次与目录所有权

本批 17 项**不适合并行**：全部集中修改同一组核心文件——
`AccessibilityModels.kt`、`AccessibilityActionRouter.kt`、
`AndroidAccessibilityBackend.kt`、`action.schema.json`、`service.go`、
`schemas.go`。并行 worktree 会产生必然冲突。

因此采用**单分支串行**（`feat/action-surface-expansion`），以 Conventional Commit
的单一职责保证可回溯：每项能力一个提交。

若后续确需并行，唯一可切分的波次是：

| 波次 | 内容 | 独占目录 |
| --- | --- | --- |
| W1 | A1 + A7-核心 | `accessibility/`（必须先单独完成，是所有人的地基） |
| W2 | A14 + A15 | `internal/adb/`（纯 Go 侧，不碰 Android 动作模型） |
| W3 | A16 | `accessibility/observe`（新增文件，不改路由） |

`protocol/` 清单、根 Gradle 配置与本文件由单一集成负责人串行修改。

## 4.1 平台事实沉淀（后续实现必须先看这一节）

以下六条都是设备实测结论，不是文档推断。每一条都曾导致一次"代码看起来正确
但行为不对"的排查，写在这里避免同类坑重复踩。

| 编号 | 结论 | 影响范围 |
| --- | --- | --- |
| F1 | `input motionevent` 只接受**一个**坐标对，传第三个参数被**静默忽略**并返回 exit 0（`DOWN 100 300 NOTANUMBER` 也"成功"），参数不足才抛异常 | 直连 ADB 通道**无法**合成多指手势。任何这样写的代码会退化成单指且不报错 |
| F2 | 不声明 `flagRetrieveInteractiveWindows` 时 `AccessibilityService.windows` 恒为空列表 | 多窗口定位在代码完全正确时也一定失效。新增无障碍服务配置必须带此 flag |
| F3 | `UserTouchMonitor` 只观察 CLICKED / LONG_CLICKED / SCROLLED / TEXT_CHANGED 四类事件；预算过滤后为空则 arm 失败、`runAttributedAction` 返回 false，动作**根本不执行** | 任何新增动作若其事件不在这四类内（或不发事件），必须经 `hasMonitorableBudget` 判断后直接派发，否则稳定报"系统拒绝" |
| F4 | `ACTION_SET_SELECTION` 要求节点已获输入焦点——平台把选区视为输入状态的一部分 | 依赖插入点的动作（选区、粘贴）须先 FOCUS |
| F5 | View 状态变化后必须显式发 `TYPE_WINDOW_CONTENT_CHANGED`，否则框架沿用缓存节点信息 | 自定义控件的动态动作集（如 EXPAND↔COLLAPSE 互换）不广播就查不到新动作 |
| F6 | 设备上**不存在** `cmd clipboard` shell 命令（`No shell command implementation`），但 `service list` 里有 `clipboard: [android.content.IClipboard]` | 剪贴板读写无法走 ADB 通道，只能经 App Bridge 的 `ClipboardManager`；且 API 29+ 起读取剪贴板要求应用处于前台 |

## 4.2 已拍板的取舍（D1–D3）

| 编号 | 决定 | 理由 |
| --- | --- | --- |
| D1 | 快捷键帮助面板**如实上报不支持**，不硬编码 `@hide` 常量 | `GLOBAL_ACTION_KEYBOARD_SHORTCUTS` 仅对系统应用开放。硬编码数值虽能调通，但依赖未公开 API，后续 Android 版本会静默失效——那比明确的"不支持"更难排查 |
| D2 | 接受**多指手势仅在 App Bridge 连接时可用**，不做 root + `sendevent` 直写 `/dev/input` | 见 F1，直连通道物理上做不到。`sendevent` 方案与设备输入驱动强耦合且需 root，不适合真机交付 |
| D3 | 只交付 **API 35** 证据，30/33/34 如实标注未验证 | 本机 `system-images` 下只有 `android-35`，且 `profiles.json` 声明的是 arm64 而本机为 x86_64。补齐需数 GB 下载，收益不足以阻塞当前交付 |

D4（B1 的修复是否作为独立行为变更处理）保持待用户确认，倾向独立处理。

## 5. 允许修改范围

- `.trae/specs/action-surface-expansion/`
- 本文件 `docs/action-surface-tasks.md`
- `protocol/schema/v1/action.schema.json`、`protocol/fixtures/`
- `android/app/src/main/java/dev/aiauto/android/accessibility/**`
- `android/app/src/main/java/dev/aiauto/android/bridge/**`
- `android/app/src/main/java/dev/aiauto/android/provider/ProviderActionParser.kt`
- `android/app/src/test/java/dev/aiauto/android/**`
- `internal/service/`、`internal/adb/`、`internal/bridge/`、`internal/mcpserver/`、
  `internal/cli/`
- `scripts/emulator/`
- `docs/README.md`（仅新增一行导航）

**禁止修改**：`docs/next-phase-tasks.md`、已完成 MVP 与其它 spec 的
`tasks.md` / `checklist.md` / `progress.md`、`.github/workflows/`、`osdk.toml`。
release 变体不得引入任何 test-only 入口。

## 6. 验收证据要求

每项能力必须记录：

1. **三层实现位置**：protocol / Android / Go 三侧的具体文件与符号；
2. **定向测试**：测试名与通过结果；
3. **模拟器实测**：显式 serial、API 版本、前置观察、执行的动作、后置验证、
   清理结果；
4. **未验证范围**：如实标注哪些 API 版本未验证，不以「应该可用」代替。

### 当前验证环境

| 项 | 值 |
| --- | --- |
| serial | `emulator-5554` |
| AVD | `osdk-test-35` |
| API | 35（Android 15） |
| 分辨率 | 320x640 |
| ABI | x86_64 |

`scripts/emulator/profiles.json` 声明的 API 30 / 33 / 34 镜像在本机**未安装**，
故本批默认只能给出 API 35 证据；30 / 33 / 34 的矩阵验收保持未完成。

## 7. 提交边界

每项能力一个提交，格式见
[`.trae/specs/action-surface-expansion/tasks.md`](../.trae/specs/action-surface-expansion/tasks.md)
的「建议提交边界」表。提交末尾保留且仅保留一次
`Co-authored-by: TRAE CLI <noreply@bytedance.com>`。

不提交 APK、密钥、token、截图、缓存或构建产物。

## 8. 失败与回滚清理

| 情形 | 处理 |
| --- | --- |
| 某能力模拟器验证失败 | 保留提交，在第 2 节写 `未完成` + 原因 + 影响，不勾选 `tasks.md` |
| 模拟器进入不确定状态 | `adb -s <serial> emu kill` 后从 clean 快照重启；**不用** `adb kill-server` |
| schema 改动破坏既有 fixture | 回滚该提交单独修，不与能力实现混在一个提交里 |
| worktree 清理 | 确认干净后非强制 `git worktree remove` + `git worktree prune`；删分支前确认已合入 |

## 9. 需用户决定的事项

（本批执行过程中累积，最终统一呈现。）

| 编号 | 事项 | 备选 | 当前处理 |
| --- | --- | --- | --- |
| D1 | 全局动作「快捷键帮助面板」无法实现。其常量 `GLOBAL_ACTION_KEYBOARD_SHORTCUTS` 是 `@hide`，仅对系统应用开放，编译 SDK 上不可见。 | ①保持不支持并如实上报 `ACTION_NOT_SUPPORTED`（当前做法）；②硬编码常量数值调通，但依赖未公开 API，后续 Android 版本可能静默失效 | 已选 ①。若需 ②，请确认可接受未公开 API 风险。 |
| D2 | 多指手势只能走 App Bridge。实测确认 `input motionevent` 只接受单坐标对且静默忽略多余参数，直连 ADB 通道无法合成多指。 | ①多指仅在桥已连接时可用（当前做法）；②改用 root + `sendevent` 直接写 `/dev/input`，可在直连通道实现多指，但需要 root 且与设备输入驱动强耦合 | 已选 ①。②仅在一次性模拟器上可行，不适合真机交付；如需支持请确认。 |
| D3 | API 30 / 33 / 34 镜像在本机未安装（`$ANDROID_HOME/system-images` 下只有 `android-35`），且 `profiles.json` 声明的是 arm64 镜像而本机为 x86_64。 | ①仅交付 API 35 证据，多版本矩阵标注未完成（当前做法）；②下载 x86_64 版 30/33/34 镜像并修正 profiles.json 后补齐矩阵（需数 GB 下载与较长时间） | 已选 ①。是否要补齐多版本矩阵请指示。 |
| D4 | 文本编辑组（A5/A6）与节点动作组（A9）缺少设备夹具：现有 `ScreenshotTestActivity` 只有 Button 与 ScrollView，没有 EditText、SeekBar、可展开控件。 | ①扩充 debug 夹具 Activity 以覆盖这些控件（仅 debug 变体，不进 release）；②不做设备验证，这些能力长期标注为「已实现未验证」 | 倾向 ①，将在后续实施；如不希望扩充夹具请指示。 |

