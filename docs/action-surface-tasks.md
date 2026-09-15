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
| A3 | `scrollIntoView` / `scrollUntil` | 未完成 | 35 | E10：实现完成但设备验证失败，原因见第 10 节 B1 |
| A4 | Bridge 侧 `pressKey` | 部分实现 | — | 仅 MOVE_HOME/MOVE_END 可走无障碍，其余键需 ADB；无设备证据 |

### P1

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A5 | 文本选择（`ACTION_SET_SELECTION`） | 已交付 | 35 | E6 |
| A6 | 复制 / 剪切 / 粘贴 | 已交付（复制+粘贴）；剪切未验证 | 35 | E7 |
| A7 | 多指与复合手势 | pinch 已交付；zoom/旋转/双指滚动已实现未验证 | 35 | E4 |
| A8 | 双击 / N 连击 | 已交付（双击）；三连及以上未验证 | 35 | E3 |
| A9 | 节点动作补齐（8 项） | 部分已交付：EXPAND/COLLAPSE/SET_PROGRESS 已验证；FOCUS 间接验证；SELECT/CLEAR_*/DISMISS/SCROLL_TO_POSITION 未验证 | 35 | E8、E9 |
| A10 | 全局动作补齐（5 项） | 已实现未验证 | — | 通知栏/快捷设置/锁屏/截屏/电源菜单已按 API 分级接线；快捷键面板确认无法实现（见第 9 节 D1） |
| A11 | 设备状态动作 | 未开始 | — | — |
| A12 | IME 控制 | 未开始 | — | — |

### P2

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A13 | 剪贴板读写 | 未开始 | — | — |
| A14 | deeplink 打开 | 未开始 | — | — |
| A15 | 文件 push / pull | 未开始 | — | — |
| A16 | Toast 捕获 | 未开始 | — | — |
| A17 | 路径手势 | 已实现未验证 | — | 桥侧与直连实现完成，无设备证据 |

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
| E10 | `scrollsAnOffscreenTargetIntoView` **失败**：`GESTURE_FAILED`（swipe 被系统拒绝）。前置观察已确认目标初始不可见，滚动轮次进入且到达派发环节，但 `dispatchGesture` 被拒。原因分析见第 10 节 B1。 |

最近一次执行结果：
- `ActionSurfaceDeviceTest`：`OK (4 tests)`。
- `ActionSurfaceNodeActionDeviceTest`：`Tests run: 5, Failures: 1`（仅 E10 失败）。

Android 单测 402 项、`lintDebug`、`assembleDebug` 通过；
`make comments` / `make test` / `make verify` / `make build` 通过。

## 10. 已知未完成项与原因

| 编号 | 项 | 现象 | 已排除 | 影响 |
| --- | --- | --- | --- | --- |
| B1 | `scrollIntoView` 的滚动手势在 API 35 模拟器上被拒 | 稳定 `GESTURE_FAILED`，`dispatchGesture` 返回 false | 已排除：目标不可见前置成立、轮次逻辑进入派发、归因判据已修正（同一修正让 pinch 与文本选区通过）、坐标校验通过。剩余可疑点是 `GesturePlanner.scroll` 在 320x640 这一较小屏上算出的起止点落入系统手势区（底部导航区约 48dp），被系统在派发前截走 | `ui.scrollIntoView` 在小屏设备上不可用；`ui.scroll` 的既有节点动作路径不受影响（它优先走 `ACTION_SCROLL_*`，只有节点不支持时才降级到手势） |

B1 的下一步是把滚动手势的起止点从「视口中心 ± 位移」改为「避开上下各一个
系统手势内边距的可用区」，并在同一设备上复测。该改动会影响既有 `ui.scroll`
的坐标降级路径，属于行为变更，因此单独作为一项处理而不混在本批修复里。



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

