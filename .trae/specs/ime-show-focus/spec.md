# ime.show 聚焦语义规格

## 目标

实现 `action.schema.json` 已声明但全仓零实现的 `ime.show`，并在协议、文档与返回
结果三处如实表达其能力上限。

同时更正 `docs/next-phase-tasks.md` 中 `task.finish` 的错误定位，并核实
`internal/visual` 的视觉动作三类型是否真的缺少执行分支。

## 任务一核实结论（前提不成立，不改代码）

用户提出 `internal/visual/attested_action.go:507` 的 `case VisualActionTap`
是执行分支、long-click 与 swipe 进不了执行。**核实后该前提不成立**：

- 507 行位于 `validDesktopVisualAction`（505-523 行），这是**校验**函数，
  三种类型本就全部覆盖。
- Go 侧没有按动作类型分支的执行代码。`ProposeAndExecute` 在 286 行把
  `request.Action` 整体转发给 `AttestedActionPort`，不解析类型。
- 类型分派在 Kotlin 侧：`AttestedVisualActionGateway.parseAction`（309-341 行）
  与 `AttestedAction.recordedAction`（466-499 行）都已完整处理 tap /
  long-click / swipe 三种，swipe 四方向的起止点计算也在 476-487 行。
- 一次性探针实测：五种组合（tap、long-click 无时长、long-click 800ms、
  swipe up、swipe down 400ms）全部 `portCalls=1`、`succeeded=true`、
  `commit=committed`，转发到端口的 Action 与输入逐字一致。

因此本项**不新增执行分支**，改为补测试把这一现状锁定，防止未来退化。
探针文件确认结论后已删除。

## 任务二：ime.show 的语义与上限

### 为什么不能走直连 ADB

`ime.hide` 走 `KEYCODE_BACK`（`internal/adb/device_state.go:266`）。Android
没有与之对称的「打开输入法」keycode，因此 `ime.show` 无法在直连通道实现。

### 实际语义

实现为**聚焦输入控件**：通过无障碍对当前输入焦点节点执行 `ACTION_FOCUS`。
键盘通常会随焦点出现，但这由输入法实现与当前焦点状态决定。

**能力上限（必须在三处如实表达）：**

1. 这不是确定的 IME 可见性开关。
2. 无障碍服务无法查询键盘当前是否可见，因此**无法验证**键盘是否真的弹出。
3. 结果只能如实反映「已聚焦」，不得表述为「已显示键盘」。
4. 当前没有可聚焦的输入控件时明确失败，不返回假成功。

三处表达位置：`action.schema.json` 的 `imeShow` 描述、`docs/protocol.md` 对应行、
以及执行结果的 route 与 JSON 字段命名。

### 实现落点

- `AccessibilityCommand` 新增 `ImeShow` 变体。
- `AccessibilityActionRouter` 新增 `executeImeShow`。
- `AccessibilityActionBackend` 新增 `focusInputControl()`，
  由 `AndroidAccessibilityBackend` 复用已有的 `focusedInputNode()`
  （639 行）与 `ACTION_FOCUS` 映射（842 行）实现。
- `AccessibilityCommandJsonParser` 新增 `"ime.show"` 分支。
- `AndroidBridgeMethods` 的 capability 如实声明该限制。

### 不做的

- 不引入 InputMethodService，不让 App 成为输入法。那会大幅扩大权限面。
- 不尝试用 `dumpsys input_method` 猜测键盘可见性：该输出跨 ROM 不稳定，
  基于它返回「已显示」是在制造一个不可靠的确定性承诺。
- 不给 `ime.show` 开 CLI 或 MCP 出口。本项只补桥侧执行，出口归属另议。

## 任务三：task.finish 定位更正

`docs/next-phase-tasks.md` 的 N59 第 8 项把 `task.finish` 列为待决项，属于误判。
核实结果：App 侧共 25 处引用，职责明确——

- `AutomationSessionEngine.kt:202` 据它判定会话结束。
- `AutomationRiskPolicy.kt:138` 在风险白名单内。
- `OpenAICompatibleProvider.kt:218` 写进给大模型的动作清单，`:82` 据它判定终止。
- `LocalDemoProvider.kt:42` 是演示序列终点。
- 录制、回放与中文化（`RecordingScriptStore.kt:603/752`、
  `StepHumanizer.kt:51`、`SkillLanguageGenerator.kt:240`）都认它。

`docs/protocol.md:168` 的「仅 App AI 会话终止信号」定位是准确的：电脑侧没有
「会话」可结束，它本就不该有 CLI 或 MCP 出口。改写为「已归属明确，非缺口」。

## 允许修改

- `.trae/specs/ime-show-focus/`
- `protocol/schema/v1/action.schema.json` 的 `imeShow` 描述
- `android/app/src/main/.../accessibility/`（model、action router、backend）
- `android/app/src/main/.../bridge/`（parser、capability 声明）
- `android/app/src/test/.../accessibility/` 对应测试
- `internal/visual/attested_action_test.go`（补三类型锁定测试）
- `docs/protocol.md`、`docs/next-phase-tasks.md`

## 禁止修改

- `.trae/specs/build-ai-android-automation-mvp/` 的已验收结论
- 文件传输相关代码与 `TestFileTransferHasNoCLIEntryPoint`
- 任意 keycode、组合键、多显示屏、OCR 等需权衡项
- `validPortResult` 的 not-committed / committed / unknown 三态约束
- attestation 与 commit 语义

## 验收

- 视觉三类型执行路径有测试锁定，含参数边界与 swipe 四方向。
- `ime.show` 有聚焦成功、无输入控件失败、结果不声称键盘可见三类测试。
- 协议描述、`docs/protocol.md` 与结果字段三处一致表达能力上限。
- `make comments` / `make test` / `make verify` / `make build` 通过。
- 改到 Kotlin，必须 `make android-verify` 通过。
- 无设备证据的项保持未勾选并标注「待设备验证」。
