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
| A1 | 多窗口模型（`service.windows` 遍历） | 已交付 | 30 / 33 / 34 / 35 | E1 |
| A2 | `drag`（按住—停顿—移动） | 已交付（桥侧+直连） | 30 / 33 / 34 / 35 | E2、E5 |
| A3 | `scrollIntoView` / `scrollUntil` | 已交付 | 30 / 33 / 34 / 35 | E10 |
| A4 | Bridge 侧 `pressKey` | 部分实现 | — | 仅 MOVE_HOME/MOVE_END 可走无障碍，其余键需 ADB；无设备证据 |

### P1

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A5 | 文本选择（`ACTION_SET_SELECTION`） | 已交付 | 30 / 33 / 34 / 35 | E6 |
| A6 | 复制 / 剪切 / 粘贴 | 已交付 | 30 / 33 / 34 / 35 | E7、E17 |
| A7 | 多指与复合手势（pinch / zoom / 旋转 / 双指滚动） | 已交付 | 30 / 33 / 34 / 35 | E4、E18 |
| A8 | 双击 / N 连击 | 已交付 | 30 / 33 / 34 / 35 | E3、E19 |
| A9 | 节点动作补齐（8 项） | 部分已交付：EXPAND / COLLAPSE / SET_PROGRESS / FOCUS / SELECT / CLEAR_SELECTION / DISMISS / SCROLL_TO_POSITION 可用；仅 CLEAR_FOCUS 被平台拒绝 | 30 / 33 / 34 / 35 | E8、E9、E20、E22 |
| A10 | 全局动作补齐（5 项） | 已交付 | 30 / 33 / 34 / 35 | E23 |
| A11 | 设备状态动作 | 已交付 | 30 / 33 / 34 / 35 | E11 |
| A12 | IME 控制 | 部分已交付：枚举/切换/收起键盘已验证；显示键盘未实现 | 30 / 33 / 34 / 35 | E12 |

### P2

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A13 | 剪贴板读写 | 已交付（桥侧） | 30 / 33 / 34 / 35 | E13 |
| A14 | deeplink 打开 | 已交付 | 30 / 33 / 34 / 35 | E14 |
| A15 | 文件 push / pull | 已交付 | 30 / 33 / 34 / 35 | E15 |
| A16 | Toast 捕获 | 已交付 | 30 / 33 / 34 / 35 | E16 |
| A17 | 路径手势 | 已交付 | 30 / 33 / 34 / 35 | E21 |

### 设备证据编号

**E1–E23 的原始取证环境**：serial `emulator-5554`，AVD `osdk-test-35`，
API 35（Android 15），**320x640 / 160dpi**，x86_64，无障碍 `capabilities=161`，
`Crashed services:{}`。执行方式：走生产 `AccessibilityActionRouter`
与 `AndroidAccessibilityBackend`，非测试专用简化通道。

> **关于 320x640**：这一档并非有意的测试策略，而是手工创建 AVD 时沿用了模板
> `config.ini` 里 `hw.lcd.width/height/density=320/640/160` 的默认值。它离真机
> 太远（真机普遍 1080x2400 上下），E1–E23 各条**单看**只能证明「该能力在小屏
> 上可用」。**E25 已在 1080x2400 / 420dpi 等三档真机档位复跑了这些用例的全集**，
> 因此这些能力的结论现已覆盖真机档位——详见第 6.1 节。下方 E1–E23 各条保留
> 原始 320x640 的观测细节（坐标、bounds 等数值是在该档位下量出的），
> 不改写为新分辨率，以免把「当时看到的现象」说成在别的档位也是同样数值。

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
| E22 | `scrollsACollectionContainerToAGivenPosition` PASS。夹具新增平台自带 `ListView`（40 行 / 视口约 3 行）作为 `CollectionInfo` 容器。断言首行下标离开顶部且请求行在可视范围内——平台语义是「把该行滚到可见」而非「置顶」，实测请求第 20 行后 `firstVisiblePosition=18`，断言 `== 20` 会把正确行为判成失败。与 E20 的 ScrollView 失败断言合起来才完整刻画该能力边界。 |
| E23 | `GlobalActionDeviceTest` `OK (5 tests)`。按可中断性分档：通知栏 / 快捷设置 / 电源菜单断言窗口集合变化后用 BACK 复位并**断言回到夹具**；截屏断言夹具窗口仍在（不破坏会话）；锁屏独立用例，动作后主动 WAKEUP + MENU 解锁并断言服务仍能观察窗口——若锁屏后服务不可用，该动作在实际自动化里就无法安全使用。测试后复查 `mScreenState=ON`、焦点回到 launcher，无残留状态。 |
| E24 | B5 / B6 修复后的四 API 复测，入口 `scripts/emulator/verify-ui-regressions.mjs`，serial 均为 `emulator-5554`（AVD `aactl-matrix-30/33/34` 与 `osdk-test-35`，**320x640** / x86_64，逐个冷启动）。**修复前**：`MainActivityLaunchModeInstrumentedTest` 在 API 35 三轮稳定失败（`Failed to inject touch input`，`Reason` 为找不到「录制操作」节点）、30/33/34 通过；`RecordingEditorScreenInstrumentedTest` 四个 API 全部失败（`expected:<step-two> but was:<null>`）。**修复后**：两个类在 API 30 / 33 / 34 / 35 上全部 `pass`。清理：每个 API 跑完卸载测试 APK 并关闭模拟器，一次性 AVD 用后删除。**注**：本条是 320x640 下的对照，真机档位的复跑见 E25。 |
| E25 | **真机分辨率档位复跑**（本轮新增），入口 `scripts/emulator/matrix-avd.mjs` + `action-surface-matrix.mjs`，serial 均为 `emulator-5554`，x86_64，逐档冷启动。每档启动后用 `wm size` / `wm density` 设置并**回读校验**实际生效值（`verified:true`），而非仅凭设置命令返回码。<br>**主档 1080x2400 / 420dpi**：API 30 / 33 / 34 / 35 各 9 个类 **28/28 通过、0 失败**（含 21 项动作面用例与 B5/B6 两项 UI 回归），`screen` 回读均为 `Physical size: 1080x2400`。<br>**高低对照档（API 35）**：`720x1600 / 320dpi` 28/28 通过；`1440x3200 / 560dpi` 28/28 通过。<br>合计本条共 6 次全量执行、168 次用例执行，无一失败。清理：四个一次性 AVD 全部删除，用户自有 AVD 未触碰。 |

最近一次执行结果：
- `ActionSurfaceDeviceTest`：`OK (4 tests)`。
- `ActionSurfaceNodeActionDeviceTest`：`OK (5 tests)`。
- `TransientMessageDeviceTest`：`OK (2 tests)`。
- `ActionSurfaceRemainingDeviceTest`：`OK (5 tests)`。
- `GlobalActionDeviceTest`：`OK (5 tests)`。
- `internal/adb` 全量 PASS（含 `TestDeviceStateAgainstRealDevice`）。

设备用例合计 21 项全部通过。
Android 单测 408 项、`lintDebug`、`assembleDebug` 通过；
`make comments` / `make test` / `make verify` / `make build` 通过。

## 10. 已修复与已知未完成项

| 编号 | 项 | 结论 |
| --- | --- | --- |
| B1 | `scrollIntoView` 报 `GESTURE_FAILED` | **已修复**。原假设（小屏起止点落入系统手势区）经实测被推翻：`dumpsys` 量出 320x640 的手势区为顶 24px、底 32px、左右各 30px，而 planner 算出的起点 y=474、终点 y=166 均不在其中。改用三层对照（平台原生派发 / router 的 ui.scroll / scrollIntoView）+ 可见节点指纹定位，查出三个叠加原因：①滚到尽头后节点动作不可用，降级到手势路径时带 match 去 arm 归因、等一个不会发生的 SCROLL 事件而超时，把「滚不动了」误报成「手势被拒」；②无进展签名只取节点标识不取坐标，而 ScrollView 滚动只改坐标，导致首轮即判定到底；③单轮签名相同不足以判定到底——滚动动画与快照采集是独立时序，快照可能落在动画未推进的窗口内。修复分别为 `requireScrollEvent` 开关、签名纳入 bounds、连续 3 轮无变化才判定。见 E10 |
| B2 | `internal/mcpserver` 视觉清理测试偶发失败 | **与本批无关，保留记录**。`visualCleanupConnection.Write` 先发响应、后做 cleanup，测试拿到响应即断言 `pendingCount()==0`，构成竞态。基线 `main` 单包 5/5、`make test` 4/4 通过；本分支单包 8/8、`make test` 4/4 通过。不影响生产（cleanup 最终一定执行）。修复属 N44/N45 视觉模块范围，按 Organizer 指示本批不改 |
| B3 | `SCROLL_TO_POSITION` 在 ScrollView 上不可用 | **平台限制，已完整刻画**。该动作要求容器提供 `CollectionInfo`（ListView / RecyclerView 等），ScrollView 不是集合容器。补入 `ListView` 夹具后确认该能力**在集合容器上可用**（E22），ScrollView 上的失败断言保留（E20）。两条合起来定义边界，不再有「未验证」的空白 |
| B4 | `CLEAR_FOCUS` 被平台拒绝 | **未完成**。拒绝方式是 `ACTION_FAILED` 而非 `ACTION_NOT_SUPPORTED`，即节点声明了该动作但 View 的 `performAccessibilityAction` 返回 false。已排除「无焦点可清」：用 `uiautomator dump` 确认 `focused=false` 后补上 FOCUS 前置，行为不变。影响：无法主动清除输入焦点；变通做法是聚焦另一个节点。已在设备测试中断言其失败，若将来平台行为变化会被测试发现 |
| B5 | `MainActivityLaunchModeInstrumentedTest` 在 API 35 失败 | **已修复**（`03a49cc`）。报错写作 `Failed to inject touch input`，看起来像触摸注入与 API 35 不兼容——**这个表述把人带偏了**。完整堆栈的 `Reason` 是「找不到满足 `Text contains '录制操作'` 的节点」：注入从未发生，失败在查找阶段。真因是首页为 `LazyColumn`，「录制操作」在第 6 个 item，320x640 上落在首屏之外，而 Lazy 列表**不组合**屏外内容，节点因此根本不存在。API 30/33/34 通过不是因为注入能力更好，而是可用高度不同、该 item 恰好进了首屏。修法用 `performScrollToNode` 而非固定坐标或放宽断言，使用例与屏幕高度、字体缩放、edge-to-edge 压缩解耦。四个 API 全部通过（E24） |
| B6 | `RecordingEditorScreenInstrumentedTest` 四个 API 一致失败 | **已修复**（`47ea099`）。有两个叠加的失败点，原因不同、修法也不同：①**步骤卡片**——`performClick` 默认点节点中心，而卡片中心 `(160,393)` 正好落在内部「停用」按钮 `(156,384,214,424)` 内，点击被子按钮消费，`onSelectStep` 收不到；这不是坐标算错，是 `EditorStepCard` 把「整卡可点选中」与按钮行叠放、按钮行又横跨垂直中线的结构使然，改点标题行解决。②**表单提交按钮**——确实在视口外，用 `performScrollToNode` 解决。四个 API 全部通过（E24） |
| B7 | `:web-fixture:lintVitalRelease` 失败 | **既有问题，非本批引入，未修**。`AndroidManifest.xml:15: Error: MainActivity must extend android.app.Activity [Instantiatable]`。已用基线 worktree 在本批之前的提交 `2df8c62` 上复现同一错误，确认非本轮改动导致（`git diff 2df8c62..HEAD -- android/web-fixture/` 为空）。影响：`assembleRelease` 全工程聚合任务会被它拦下；`:app:assembleRelease` 单独执行通过，故 app 的 release 产物与「零 test-only 入口」结论不受影响。属 web-fixture 模块自身问题，修它应单独立项 |

## 10.1 B5 / B6 的定位过程（两次假设被推翻）

这两项都不是动作面代码引入的，但**是多 API 矩阵第一次把它们照出来**：此前设备
验证只在 API 35 上跑动作面用例，既没覆盖其他 API，也没覆盖这两个既有用例。

**跨 API 才能区分「版本差异」与「一直就坏」。** B5 只在 API 35 失败，B6 四个 API
一致失败——若只在单一 API 上跑，B6 会被误判成 API 35 特有的问题。

**B6 的两个假设都被实测推翻，值得记下来避免重走**：
1. 「确认框遮挡步骤行」——把初始态改成不弹框后**依然失败**；
2. 「目标在视口外」——补 `performScrollToNode` 后**依然失败**。

最后靠一次性探针打印真实几何才定位：节点 bounds 完全在视口内（338–448 / 640）、
语义里有 `OnClick`，但回调就是不触发；把子按钮几何一并打出来，才看到卡片中心
恰好被「停用」按钮占据。**如果当时按假设 1 或 2 直接改，测试可能因别的原因转绿，
真正的中心重叠问题会留在原地。**

**一条没能立住的断言，及其处理**：定位过程中我曾加过一个「确认框存在时点不到
身后内容」的模态性断言，实测它失败——但这不代表模态失效。原因是
`performTouchInput` 直接在 Compose owner 层注入事件，绕过了真正拦截触摸的 Dialog
窗口层。用它写断言会得到一个「测试通过但与真实行为无关」的假结论，因此**删掉了
该断言**并在测试类注释里写明原因与将来的正确覆盖方式（走 UiAutomator 之类的真实
注入路径），而不是留一个看起来有覆盖、实则测错对象的用例。


## 11. 本批未实现且已确认无法实现的项

| 项 | 原因 | 替代路径 |
| --- | --- | --- |
| 全局动作「快捷键帮助面板」 | 常量 `GLOBAL_ACTION_KEYBOARD_SHORTCUTS` 是 `@hide`，仅系统应用可用 | 无。按 D1 如实上报 `ACTION_NOT_SUPPORTED` |
| IME「显示键盘」 | 无障碍与 ADB 都没有公开的「弹出输入法」入口 | 对可编辑节点执行 FOCUS 或 CLICK 间接触发；A12 只交付枚举/切换/收起 |
| 节点动作 `CLEAR_FOCUS` | 见 B4，平台拒绝执行 | 聚焦另一个节点以转移焦点 |
| 节点动作 `SCROLL_TO_POSITION`（在**非集合**容器上） | 见 B3，需要 `CollectionInfo` 容器。在 ListView 等集合容器上**可用**（E22） | 非集合容器用 `scrollIntoView` 按目标元素滚动 |
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

以下十条都是设备实测结论，不是文档推断。每一条都曾导致一次"代码看起来正确
但行为不对"的排查，写在这里避免同类坑重复踩。

| 编号 | 结论 | 影响范围 |
| --- | --- | --- |
| F1 | `input motionevent` 只接受**一个**坐标对，传第三个参数被**静默忽略**并返回 exit 0（`DOWN 100 300 NOTANUMBER` 也"成功"），参数不足才抛异常 | 直连 ADB 通道**无法**合成多指手势。任何这样写的代码会退化成单指且不报错 |
| F2 | 不声明 `flagRetrieveInteractiveWindows` 时 `AccessibilityService.windows` 恒为空列表 | 多窗口定位在代码完全正确时也一定失效。新增无障碍服务配置必须带此 flag |
| F3 | `UserTouchMonitor` 只观察 CLICKED / LONG_CLICKED / SCROLLED / TEXT_CHANGED 四类事件；预算过滤后为空则 arm 失败、`runAttributedAction` 返回 false，动作**根本不执行** | 任何新增动作若其事件不在这四类内（或不发事件），必须经 `hasMonitorableBudget` 判断后直接派发，否则稳定报"系统拒绝" |
| F4 | `ACTION_SET_SELECTION` 要求节点已获输入焦点——平台把选区视为输入状态的一部分 | 依赖插入点的动作（选区、粘贴）须先 FOCUS |
| F5 | View 状态变化后必须显式发 `TYPE_WINDOW_CONTENT_CHANGED`，否则框架沿用缓存节点信息 | 自定义控件的动态动作集（如 EXPAND↔COLLAPSE 互换）不广播就查不到新动作 |
| F6 | 设备上**不存在** `cmd clipboard` shell 命令（`No shell command implementation`），但 `service list` 里有 `clipboard: [android.content.IClipboard]` | 剪贴板读写无法走 ADB 通道，只能经 App Bridge 的 `ClipboardManager`；且 API 29+ 起读取剪贴板要求应用处于前台 |
| F7 | API 35 上 `settings put secure enabled_accessibility_services ""` 返回 `Bad arguments` 且**旧值残留**；API 30/33/34 接受空串。清空必须用 `settings delete secure ...` | 任何"重置无障碍再重新启用"的脚本在 API 35 上会静默失效，表现为服务停在 `Crashed services`、`Bound services` 恒空，极易误判为"能力回归"。已修入 `scripts/emulator/action-surface-matrix.mjs` |
| F8 | 重装 APK 会把包置为 `enabled=0` 并重置 `accessibility_enabled`，服务进入半禁用状态而出现在 `Crashed services` | 每次重装后必须 `pm enable <pkg>` 再重写 secure 设置，且以 `dumpsys accessibility` 的 `Bound services` 非空为就绪判据——写 settings 只是改字符串，bind 是异步结果 |
| F9 | Compose 测试的 `performClick` 点的是**节点中心**；若被测节点自身可点、内部又有子按钮横跨其垂直中线，点击会被子按钮消费而外层回调收不到。实测：卡片 `(16,338,304,448)` 中心 `(160,393)` 落在子按钮 `(156,384,214,424)` 内 | 「整块可点 + 内部按钮行」这种布局，测试必须显式指定点击位置（如标题行）而非依赖默认中心。另：`Failed to inject touch input` 的报错文案会把人引向"注入不兼容"，实际失败常在**查找阶段**——必须读 `Reason` 子句。Lazy 列表不组合屏外内容，节点会直接不存在；`verticalScroll` 则节点存在但在视口外，两者现象相似而修法不同 |
| F10 | 手工创建 AVD 时若只改 `image.sysdir.1` 而沿用模板的 `hw.lcd.*`，会静默继承模板的屏幕参数（本仓库模板是 320x640 / 160dpi）。`wm size` / `wm density` 的返回码**不能**作为生效证据 | 任何自建 AVD 的脚本都必须显式写 `hw.lcd.width/height/density` 三项（缺一会得到畸形组合），并在启动后**回读** `wm size` / `wm density` 校验。此前 84 次设备执行全部跑在 320x640 上而无人察觉，就是因为缺这道回读 |

## 4.2 已拍板的取舍（D1–D9）

| 编号 | 决定 | 理由 |
| --- | --- | --- |
| D1 | 快捷键帮助面板**如实上报不支持**，不硬编码 `@hide` 常量 | `GLOBAL_ACTION_KEYBOARD_SHORTCUTS` 仅对系统应用开放。硬编码数值虽能调通，但依赖未公开 API，后续 Android 版本会静默失效——那比明确的"不支持"更难排查 |
| D2 | 接受**多指手势仅在 App Bridge 连接时可用**，不做 root + `sendevent` 直写 `/dev/input` | 见 F1，直连通道物理上做不到。`sendevent` 方案与设备输入驱动强耦合且需 root，不适合真机交付 |
| D3 | ~~只交付 **API 35** 证据，30/33/34 如实标注未验证~~ **已被后续轮次推翻**：API 30/33/34 x86_64 镜像已装齐并完成矩阵，结果见第 6.1 节 | 当时的理由是本机 `system-images` 下只有 `android-35` 且 `profiles.json` 声明 arm64。后续用户要求补齐多 API 证据并接受下载成本，该取舍随之失效。保留此行是为了让「为什么曾经只有 API 35 证据」可追溯 |
| D4 | 行为变更采用「独立分支 + 非快进合并保留边界 + 定位探针用完即删」 | B1 的实践验证了这套流程的价值：独立分支让行为变更不与功能提交混杂；非快进合并保留了排查过程的提交边界，便于回溯「为什么改成这样」；定位探针（三层对照 + 可见节点指纹）以 AssertionError 输出观测值，是排查工具而非回归断言，留在仓库里会成为永久失败的测试，因此完成即删，只保留可复用的只读探针。作为后续行为变更的范式 |
| D5 | 多 API 矩阵改用 **x86_64** 镜像，但**不改** `scripts/emulator/profiles.json` 的 arm64 三项，也不新增 x86_64 profile | 原 `api-30/33/34` 声明 `arm64-v8a`，是早期 Apple Silicon 环境的遗留：本机 Windows x86_64 上 arm64 镜像无法启动。但 N31 runner 对 profile 有强校验——`packageSha256` 必须是真实的官方 archive SHA-256，并要与 `package-receipts.json` 逐字一致。osdk 安装镜像时不产生这份 receipt，我也无法在不下载官方 archive 的前提下算出其 SHA-256。填空串会触发 `CONFIG_INVALID`，填全零则是**伪造校验值**——它看起来像真的却不是，比明确的空缺更危险。因此本轮矩阵不经 N31 runner，直接用 `avdmanager` + `emulator` 驱动，profile 文件保持原样。arm64 三项对仍在 Apple Silicon 上工作的环境依然有效 |
| D6 | 镜像通过 `osdk install "android-system-images@android-NN;google_apis;x86_64"` 安装，`osdk.toml` 的 pin 保持只写 API 35 | 矩阵用的三个镜像是**测试基础设施**而非构建依赖：`osdk.toml` 的 pin 决定「构建这个项目需要什么」，而 API 30/33/34 镜像只在跑设备矩阵时需要。把它们 pin 进去会让每个只想编译的人都被迫下载约 5GB。安装走 osdk 命令而非手工编辑配置，符合 osdk.toml 注释里的要求 |
| D7 | ~~N31 runner 的多 API x86_64 接入记为**未完成**而非绕过~~ **已在后续轮次完成**，见 D9 | 当时的缺口是拿不到官方 archive 的真实 SHA-256。后来发现 osdk 缓存里留有原始 archive，算出真实值后该缺口被真正填上，而不是绕过 |
| D8 | 设备矩阵分辨率取 **1080x2400 / 420dpi 为主档**，并在 API 35 上加 `720x1600 / 320dpi` 与 `1440x3200 / 560dpi` 两个对照档 | 主档直接沿用 `profiles.json` 里既有 profile 的定义，不另立标准；这一档覆盖当前主流真机。对照档沿用 N45 历史上验过的另两档，取高低两端以暴露布局在极端密度下的问题。**对照档只在 API 35 上跑**而非四个 API 全跑：分辨率相关的布局问题不依赖 API 版本——B6 已经证明这一点（它在四个 API 上表现完全一致），因此用一个 API 换两个额外档位，比在四个 API 上重复同一档更有证据价值。密度与分辨率成套配置（420/320/560dpi），避免「高分辨率配低密度」这种真机上不存在的畸形组合 |
| D9 | 让矩阵回到 runner 的正式路径：放开 runner 的 ABI / API 白名单并补齐真实 `packageSha256`；但 **AVD 创建仍用单独脚本** | 前半部分修的是真问题：runner 的 toolchain pin 早已与 `osdk.toml` 脱节，导致它对 **arm64 profile 也**报 `SDK_TOOL_VERSION_MISMATCH`，即 N31 在当前工具链下本就跑不起来。补齐后 `cli.mjs verify` 通过，D7 的缺口真正关闭。后半部分是环境限制而非取舍：osdk 把同一 system image 同时暴露在规范目录与 `~v1~<percent-encoded>` 别名目录下，`avdmanager` 的仓库扫描据此判为重复并报 `contains no system images`；尝试用只含规范布局的 junction SDK root 也无效（avdmanager 仍会扫 osdk 的 install 根）。故只把「创建 AVD」这一步交给 `matrix-avd.mjs`，其分辨率处理照抄 runner 并**增加回读校验**——上一轮正是因为缺回读，320x640 才一路蒙混过关 |

D1–D9 全部已决，无待用户拍板事项。

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
| AVD | `aactl-matrix-30/33/34/35`（一次性，用后删除） |
| API | 30 / 33 / 34 / 35 |
| 分辨率（主档） | **1080x2400 / 420dpi** |
| 分辨率（对照档） | 720x1600 / 320dpi 与 1440x3200 / 560dpi（仅 API 35） |
| ABI | x86_64 |

分辨率档位的取舍见 4.2 节 D8。E1–E23 是在早期的 320x640 / 160dpi 下取证的，
其中的坐标与 bounds 数值仍属那一档；能力结论已由 E25 在上述真机档位复跑覆盖。

### 6.1 多 API 矩阵实测结果

镜像通过 `osdk install "android-system-images@android-NN;google_apis;x86_64"`
安装（D6），AVD 由 `scripts/emulator/matrix-avd.mjs` 创建并显式写入
`hw.lcd.width/height/density`，启动后再用 `wm size` / `wm density` 设置并
**回读校验**（`verified:true`）。
每次冷启动、`-no-snapshot-save`，跑完卸载测试 APK。执行入口：
`scripts/emulator/action-surface-matrix.mjs`。

四个 API 上跑的是**同一批** 21 项动作面设备用例（`ActionSurfaceDeviceTest` 4 +
`ActionSurfaceNodeActionDeviceTest` 5 + `ActionSurfaceRemainingDeviceTest` 5 +
`GlobalActionDeviceTest` 5 + `TransientMessageDeviceTest` 2），外加 7 项既有
无门控用例作为回归对照。

下表为**主档 1080x2400 / 420dpi** 的结果（证据 E25）。同一批用例此前在
320x640 下也全部通过（E24 及更早），两档结论一致。

| 能力组（用例数） | API 30 | API 33 | API 34 | API 35 |
| --- | --- | --- | --- | --- |
| A1 多窗口 / A2 drag / A3 scrollIntoView / A8 双击连击（4） | 通过 | 通过 | 通过 | 通过 |
| A9 节点动作：EXPAND/COLLAPSE/SET_PROGRESS/FOCUS/SELECT/CLEAR_SELECTION/DISMISS/SCROLL_TO_POSITION（5） | 通过 | 通过 | 通过 | 通过 |
| A5 文本选区 / A6 复制剪切粘贴 / A7 多指 / A11 设备状态 / A13 剪贴板 / A17 路径手势（5） | 通过 | 通过 | 通过 | 通过 |
| A10 全局动作 5 项（5） | 通过 | 通过 | 通过 | 通过 |
| A16 Toast 捕获（2） | 通过 | 通过 | 通过 | 通过 |
| **动作面小计** | **21/21** | **21/21** | **21/21** | **21/21** |

既有能力回归对照（本批未改动的用例）：

| 用例 | API 30 | API 33 | API 34 | API 35 |
| --- | --- | --- | --- | --- |
| `AndroidScriptEnvironmentProviderDeviceTest`（1） | 通过 | 通过 | 通过 | 通过 |
| `RecordingScreensInstrumentedTest`（3） | 通过 | 通过 | 通过 | 通过 |
| `MainActivityLaunchModeInstrumentedTest`（2） | 通过 | 通过 | 通过 | 通过 |
| `RecordingEditorScreenInstrumentedTest`（1） | 通过 | 通过 | 通过 | 通过 |

后两行原为「API 35 失败」与「四个 API 全失败」，已在 B5 / B6 修复后复测转绿。

### 6.1.1 分辨率档位对照（API 35）

| 档位 | 回读校验 | 结果 |
| --- | --- | --- |
| 720x1600 / 320dpi | `verified:true` | 9 类 **28/28 通过** |
| 1080x2400 / 420dpi（主档） | `verified:true` | 9 类 **28/28 通过** |
| 1440x3200 / 560dpi | `verified:true` | 9 类 **28/28 通过** |

**三档结果完全一致，没有出现「小屏掩盖」或「大屏新暴露」的问题。** 这一点值得
明确记录，因为事前预期恰恰相反：B5 / B6 的根因都与小屏强相关（LazyColumn 屏外
不组合、卡片中心落在子按钮上），本来很可能在换档后表现不同。它们没有——因为
两处修法都做成了与屏幕尺寸解耦（`performScrollToNode` 按语义滚动、点击位置按
控件结构而非绝对坐标指定），而不是针对 320x640 打的补丁。**如果当时用固定坐标
或放宽断言"修绿"，这次换档就会重新失败。**

反向的担心（此前在小屏下"通过"的用例依赖紧凑布局、大屏下会失败）同样没有发生：
21 项动作面用例的断言都打在控件真实状态上（selection 区间、progress 值、
剪贴板内容、窗口集合等），这类断言本身不依赖屏幕尺寸。

**未纳入本轮矩阵的用例及原因**（不是「通过」，是没跑）：
`AccessibilityScreenshotCapabilityTest`、`UserTouchDeviceTest`、
`N45VisualDeviceMatrixTest`、`LanCrossRuntimeDeviceTest`、
`DebugTestControlPlaneDeviceTest`、`ProductionFixtureAuthorizationDeviceTest`、
`RecordingEditorAuthorizedScreenshotRedDeviceTest`、
`RecordingEditorRealEntryDeviceTest` —— 这些依赖录屏授权、LAN 双运行时或
真人触摸等本轮未预置的外部条件，跨 API 结论保持**未验证**。

### 6.2 跨 API 行为差异

只列真实观测到的差异，不含推测。

| 差异 | 观测 | 影响 |
| --- | --- | --- |
| **G1** `settings put secure enabled_accessibility_services ""` 在 API 35 被拒 | API 35 返回 `Bad arguments` 且旧值残留，服务停在 `Crashed services` 而 `Bound services` 恒空；API 30/33/34 接受空串 | 清空该设置必须用 `settings delete`。按旧写法在 API 35 上会表现为「无障碍能力全部失效」，而真因只是清空没生效——极易误判为能力回归。已修入矩阵脚本 |
| **G2** `MainActivityLaunchModeInstrumentedTest` 仅在 API 35 失败（**已修复**，见 B5） | `Failed to inject touch input`，API 35 上 3/3 稳定复现；30/33/34 全通过 | **这条最终不是 API 行为差异，而是首屏可用高度差异**。真因是「录制操作」在 `LazyColumn` 第 6 个 item，320x640 的 API 35 上落在首屏外而 Lazy 不组合屏外内容，节点不存在；30/33/34 上该 item 恰好进了首屏。修复后四个 API 均通过。教训：`Failed to inject touch input` 这条报错文案会把人引向「注入不兼容」，必须读 `Reason` 才能看到真实失败阶段 |
| **G3** `RecordingEditorScreenInstrumentedTest` 在四个 API 一致失败（**已修复**，见 B6） | `expected:<step-two> but was:<null>`，30/33/34/35 全部失败 | **不是** API 差异，而是此前未被发现的既有失败。原因有两个且都与最初假设无关：卡片中心被内部「停用」按钮占据、表单提交按钮在视口外。修复后四个 API 均通过 |

**没有观测到差异的方面**（值得记录，因为事前预期这里最可能出现分歧）：
多窗口枚举、`dispatchGesture` 多 stroke 与 `willContinue` 续接、
`ACTION_SET_SELECTION` / `ACTION_COPY` / `ACTION_PASTE`、SeekBar `SET_PROGRESS`、
`SCROLL_TO_POSITION`、五项全局动作、Toast 捕获，在 API 30–35 上行为一致，
21 项用例四个 API 全部通过。`flagRetrieveInteractiveWindows`（F2）与
`TYPE_WINDOW_CONTENT_CHANGED`（F5）这两条约束在四个 API 上同样成立。

**仍未验证**：API 31 / 32 / 36 未测（未安装镜像）；**真机未测**——三档分辨率都是
模拟器上用 `wm size` / `wm density` 覆盖出来的，与真机的物理屏幕、厂商 ROM、
真实输入驱动仍有差别；横屏（rotation 90）下的全量矩阵未跑（A11 的旋转能力本身
有单项证据，但不是全量矩阵）；平板 / 折叠屏的大宽高比与分屏未测。


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
| D3 | API 30 / 33 / 34 镜像在本机未安装（`$ANDROID_HOME/system-images` 下只有 `android-35`），且 `profiles.json` 声明的是 arm64 镜像而本机为 x86_64。 | ①仅交付 API 35 证据，多版本矩阵标注未完成；②下载 x86_64 版 30/33/34 镜像后补齐矩阵（需数 GB 下载与较长时间） | **已按 ② 完成**。用户要求补齐并接受下载成本；三个镜像经 `osdk install` 装齐，21 项动作面用例在 API 30/33/34/35 全部通过，结果见第 6.1 节，跨 API 差异见第 6.2 节。 |
| D4 | 文本编辑组（A5/A6）与节点动作组（A9）缺少设备夹具：现有 `ScreenshotTestActivity` 只有 Button 与 ScrollView，没有 EditText、SeekBar、可展开控件。 | ①扩充 debug 夹具 Activity 以覆盖这些控件（仅 debug 变体，不进 release）；②不做设备验证，这些能力长期标注为「已实现未验证」 | **已按 ① 完成**，夹具只进 debug 变体，release manifest 经核零 test-only 入口。此外 Organizer 已确认把「独立分支 + 非快进合并保留边界 + 定位探针用完即删」作为后续行为变更的范式，理由见 4.2 节 D4 行。 |

**结论：D1–D9 全部已决，无待用户拍板事项。**

本轮新增的 B5 / B6 两个既有失败**不需要用户拍板**：它们的性质（非本批引入、
影响范围、为何按 D4 应单独立项）都已查实并记录在第 10 节，属于后续独立任务的
输入而非当前决策点。
