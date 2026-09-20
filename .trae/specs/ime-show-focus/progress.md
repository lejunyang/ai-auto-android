# ime.show 聚焦语义进度

本文件 append-only，记录前提核实、RED/GREEN 与验证结果。

## Round 1：前提核实

- 基线 `289fca8`（含用户修好的 Windows `make build`），开工时 `git status` clean，
  `git worktree list --porcelain` 只有主工作区。worktree
  `E:/Projects/wt-ime-show`，分支 `ime-show-focus`。

### 任务一前提不成立

用户指 `internal/visual/attested_action.go:507` 的 `case VisualActionTap` 是执行
分支、long-click 与 swipe 进不了执行。逐行核实后该前提不成立：

- 507 行在 `validDesktopVisualAction`（505-523 行）内，是**校验**函数，
  三种类型本就全覆盖，且参数约束各自写好。
- Go 侧根本没有按动作类型分支的执行代码：`ProposeAndExecute` 在 286 行把
  `request.Action` 整体转发给端口，不解析 Type。
- 类型分派在 Kotlin：`AttestedVisualActionGateway.parseAction`（309-341）与
  `AttestedAction.recordedAction`（466-499）已完整处理三种，swipe 四方向起止点
  在 476-487 行。
- 写一次性探针 `probe_tmp_test.go` 实测五种组合，全部
  `portCalls=1 / succeeded=true / commit=committed`，转发的 Action 与输入逐字
  一致。据此判定无需补执行分支，改为补测试锁定现状。探针已删除。

### 任务二前提成立

`ime.show` 在 `action.schema.json:115/1105/1110` 有声明，
`ProviderActionParser.kt:73` 有 App 侧校验，但全仓**无任何执行实现**。
对照 `ime.hide` 有 `device_state.go:266`、`service.go:63/537`、
`direct.go:587` 完整链路。确认是真缺口。

`ime.show` 不能走直连 ADB：`ime.hide` 用 `KEYCODE_BACK`，而 Android 没有对称的
「打开输入法」keycode。只能走无障碍。基础齐备：`focusedInputNode()`
（`AndroidAccessibilityBackend.kt:639`）、`ACTION_FOCUS` 映射（842 行）、
`NodeAction.FOCUS` 均已存在。

### 任务三前提成立

`task.finish` 在 App 侧 25 处引用，13 处在 main 生产代码，职责链完整：会话终止
判定、风险白名单、给大模型的动作清单、演示序列终点、录制回放与中文化。
`docs/protocol.md:168` 的「仅 App AI 会话终止信号」定位准确，确实不该有电脑侧
出口。N59 把它列为待决项是误判，应改写而非保留。

## Round 2：实现与验证

### 任务一（锁定现状，未改生产代码）

`internal/visual/attested_action_test.go` 新增两组表驱动测试：

- `TestDesktopAttestedActionForwardsEveryActionTypeToThePort`：tap、long-click
  （默认 / 1ms / 10000ms）、swipe 四方向共 8 个子用例，断言 `port.calls == 1`
  且转发到端口的 Action 与输入逐字相等（不被改写）。
- `TestDesktopAttestedActionRejectsOutOfRangeActionParameters`：long-click 越界
  上下界、swipe 越界、swipe 缺方向、swipe 未知方向、tap 带时长、tap 带方向、
  未知类型共 8 个子用例，断言 `port.calls == 0`——非法参数绝不能触达设备。

实测 16 个子用例全部 PASS。期间修正一处类型错误：`VisualSwipeDirection` 是独立
类型而非 `string`，切片字面量与拼接处需显式转换。

### 任务二（ime.show）

先写 RED 测试再实现。改动：

- `AccessibilityModels.kt`：新增无参数的 `data object ImeShow`。
- `AccessibilityActionRouter.kt`：后端接口新增 `focusInputControl()`（默认 false，
  表示本实现不支持），`execute` 两处 when 补 `ImeShow` 分支，新增 `executeImeShow()`。
- `AndroidAccessibilityBackend.kt`：实现 `focusInputControl()`，复用已有的
  `focusedInputNode()` 与 `ACTION_FOCUS`。
- `AccessibilityCommandJsonParser.kt`：新增 `"ime.show"` 分支。
- `AndroidBridgeMethods.kt`：新增 `action.ime.show` 能力声明，reason 写明
  「聚焦可编辑控件；键盘可见性不保证也无法验证」。

能力上限在三处如实表达：`action.schema.json` 的 `imeShow.description`、
`docs/protocol.md` 的动作表与其后的说明段、以及 capability 的 reason。
结果侧不新增任何「键盘可见」字段，并用测试把该约束钉住。

过程中发现并修正：首轮只给 `FakeActionBackend` 加了字段却没写 override，
三个测试因走接口默认实现 `false` 而失败（这正是 RED 应有的表现），补上 override 后转 GREEN。

顺带修正 `docs/protocol.md` 的既有错误：原文称「Schema v1 可表达 14 种动作」，
实际 schema 声明 45 种（脚本统计 const 去重）。本轮新增两行后该数字自相矛盾，
故一并改为 45 并说明表格只列常用动作。

### 任务三（文档更正）

`docs/next-phase-tasks.md` 的 N59 第 8 项由「`ime.show` 与 `task.finish` 的归属
需产品决策」改写为「`task.finish` 的归属：已明确，非缺口」，并附 App 侧引用证据；
`ime.show` 已实现，从该清单移除并指向本 spec。

### 门禁结果（全部实跑）

| 命令 | 结果 |
| --- | --- |
| `make comments` | 通过，覆盖 555 个手写文件，检查器自测 16 个正反例 |
| `make test` | 通过，Go 各包全绿；协议与 skill 校验通过 |
| `make verify` | 通过，协议 14 schemas / 14 valid / 38 invalid / 4 compat = 56 项 |
| `make build` | 通过，产出 `bin/aactl.exe` |
| `make android-verify` | BUILD SUCCESSFUL，152 tasks；单测 543 项 0 失败 |

Kotlin 侧逐条核对 JUnit XML 而非只看聚合结果：
`AccessibilityActionRouterTest` 12 项 0 失败（含 3 项 ime show），
`AccessibilityCommandJsonParserTest` 2 项 0 失败。

### 未验证项

真机 / 模拟器上 `ime.show` 是否真的令键盘弹出——**未验证，无设备**。这正是本项
刻意不承诺的部分：即便有设备，键盘弹出与否也取决于输入法实现，不构成可断言的契约。
可验证的是「聚焦成功」，已由单测覆盖。checklist 中设备项保持未勾选。
