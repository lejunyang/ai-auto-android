# UIAutomator 能力缺口对齐规格（Wave A/B/C）

## 目标

消解 `artifacts/uiautomator-gap/uiautomator-capability-gap.md` 第 4、5 节记录的
规格自相矛盾与「已实现但无出口」缺口，分三个波次：协议与文档一致性（Wave A）、
CLI 表面补齐（Wave B）、MCP 语义动作入口（Wave C）。

本规格只覆盖该文档分类为 `可补充` 的条目。分类为 `需权衡`（T1–T8）与
`有意不做`（N1–N10）的条目一律不实施，其中需要用户决策的项记入本文件「待决项」。

## 范围

### Wave A — 协议与文档一致性（单一 owner 串行先行）

| 条目 | 内容 | 判定 |
| --- | --- | --- |
| C13 / X1 | `README.md` 称 MCP 暴露「六个类型化工具」，实际注册 11 个 | 文档修正 |
| C14 / X2 | `bridge-request.schema.json` 的 `method` 枚举缺 `visual.action.execute`，而三处实现已收发该方法 | 补枚举 + `$defs` 分支 + fixture |
| C7 / X4 | `ui.pressKey` 键枚举四处不一致：协议 21 / 直连 ADB 17 / MCP 17 / 桥侧实际投递 2 | 收敛到单一权威来源 |
| C8 | MCP `ui.scroll` 的 `direction` 缺 `forward` / `backward` | 对齐协议 |
| C9 / X3 | MCP `actionSchema()` 声明 `app.stop`，风险门必拒 | 消解矛盾 |
| C15 / X5 / X7 | `ime.show`、`task.finish` 声明与执行不匹配 | 标注语义归属 |

### Wave B — CLI 表面补齐（目录：仅 `internal/cli/`）

- C1：把 `internal/service/service.go:500-542` 已分派、但 CLI 无入口的动作接线为
  子命令：`drag`、`multi-tap`、`path-gesture`、`rotate`、`wake`、`unlock`、
  `ime-hide`、`ime-select`、`deeplink`。
- C2：把 `internal/service/device_state.go:16-53` 已实现、零引用的观察能力接线为
  `observe screen-state` 与 `observe input-methods`。

`file.push` / `file.pull` **不在本波次**：它属于需权衡项 T4（路径穿越风险，
需先定义路径白名单与确认策略），见「待决项」。

### Wave C — MCP 语义动作（目录：仅 `internal/mcpserver/`）

- C3：新增受白名单约束的语义动作工具，使模型能按 `resourceId` /
  `contentDescription` / `text` 选择器操作元素，而非只能算坐标。
- C16：让 `ui.setText` 的 ASCII 限制对模型可自解释，不再表现为裸正则拒绝。

## 键枚举收敛方向（C7 的关键决策）

`protocol/version.json` 的 `compatibility.requiresNewMajor` 明确包含
`narrow-valid-range`。因此**不得**把协议的 21 键收窄到 17 键——那是破坏性变更，
需要新 major。

收敛方向固定为：**以 `protocol/schema/v1/action.schema.json` 的
`uiPressKey.params.key` 为单一权威来源，下游实现向上对齐。**

- `internal/adb/direct.go` 的 `allowedKeys` 补齐 `MOVE_HOME`、`MOVE_END`、
  `PAGE_UP`、`PAGE_DOWN` 四个已在协议内声明的光标与翻页键。
- `internal/mcpserver/schemas.go` 的 `ui.pressKey` 枚举同步补齐同样四个键。
- 桥侧保持只真正投递 `MOVE_HOME` / `MOVE_END`，并继续由
  `AndroidBridgeMethods.kt:167-177` 的 capability 如实上报该限制；本波次不改桥侧行为。
- 新增跨实现一致性测试，防止四处再次漂移。

**边界**：本条只做「对齐既有协议白名单」。不引入 `pressKeyCode(int)` 任意键
（T1）、不引入组合键（T2）。四个新增键均为光标 / 翻页语义，不触及电源、通话或
系统安全设置。

## app.stop 矛盾的消解方向（C9 的关键决策）

两种消解方式：收窄 MCP schema，或放开风险门让行为与声明一致。

**选择收窄 schema**，理由：

1. `internal/mcpserver/risk.go` 的白名单是安全权威，放开它等于扩大模型可发起的
   动作面，属于范围扩张，本轮明确不做。
2. `app.stop` 会改变应用生命周期，与既有「模型只能触发低风险动作」的设计一致——
   风险门的判断是对的，错的是 schema 多声明了一个永远会被拒的分支。
3. MCP 工具输入 schema 不是协议 v1 契约面，收窄它不触发 `narrow-valid-range`
   的 major 约束；CLI 侧 `action stop` 不受影响，用户仍可显式停止应用。

## 安全边界（不得违反）

- 不引入任意 shell、任意 keycode、组合键、安装卸载、Root / Shizuku。
- 不放宽 `internal/mcpserver/risk.go` 既有风险门；Wave C 的新工具必须有自己的
  独立白名单，且不得成为绕过 `requireModelActionAllowed` 的旁路。
- 不改变录制回放的 `CONFIRMATION_REQUIRED` 语义。
- 不改 ADB 配对、Bridge 会话建立与权限授予的通道归属。
- Wave C 的语义动作依赖已建立的 Bridge 会话；无会话时必须给出稳定的能力不可用
  错误，不得静默降级为坐标动作。

## 允许修改

- `.trae/specs/uiautomator-gap-alignment/`
- Wave A：`protocol/schema/v1/bridge-request.schema.json`、
  `protocol/fixtures/manifest.json`、`internal/adb/direct.go` 的键映射、
  `internal/mcpserver/schemas.go`、`README.md`
- Wave B：`internal/cli/` 及其测试
- Wave C：`internal/mcpserver/` 及其测试
- 收尾：`docs/next-phase-tasks.md` 追加未纳入缺口

## 禁止修改

- `.trae/specs/build-ai-android-automation-mvp/` 下任何已验收结论
- Android 生产代码的动作执行行为（本轮不改桥侧键投递）
- `internal/mcpserver/risk.go` 的既有白名单
- 协议 v1 已有字段的语义与取值范围（只允许按 compat 政策新增）

## 验收

- Wave A：协议全量 fixture 通过且新增 `visual.action.execute` 合法与非法 fixture；
  新增键枚举跨实现一致性测试；README 工具数与 `mcpserver.ToolNames` 长度一致。
- Wave B：每个新子命令有定向测试，断言其映射到与既有命令同一套
  `service.ActionRequest` 语义；usage 串更新。
- Wave C：in-memory transport 测试覆盖「白名单外动作被拒」「白名单内动作抵达
  service」「无 Bridge 会话时稳定失败」；`ToolNames` 一致性测试自动覆盖新工具。
- `make comments`、`make test`、`make verify`、`make build` 全部通过并逐条报告。
- Android 侧本轮无代码改动；若 Gradle 可用则跑一次确认无回归，不可用则明确标注
  未验证及原因。
- 无真机 / 模拟器证据的项保持未勾选并标注「待设备验证」。

## 待决项（需用户决策，本轮不实施）

1. **T4 文件传输出口**：`Service.PushFile` / `PullFile` 已实现但无 CLI 出口。
   暴露前需要先定义远端路径白名单、是否要求当次确认、以及本地路径的写入边界。
   本轮不接线。
2. **T1 / T2 键能力**：是否开放 `pressKeyCode(int)` 任意键与组合键。本轮只对齐
   既有 21 键白名单。
3. **T6 视觉动作类型扩展**：`android_visual_action_execute` 目前只允许
   tap / long-click / swipe，是否扩展需要单独的安全评估。
4. **T7 OCR**：`visual-observation-proposal.schema.json` 的 `source` 已预留 `ocr`，
   但 README:113 明确排除 OCR。要实现必须先修改 MVP 边界。
5. **C15 的实现方向**：`ime.show` 在协议内但无任何执行实现，`task.finish` 只在
   App 会话内有语义。本轮只在协议注释里标注归属，是否补桥侧执行需产品决策。
