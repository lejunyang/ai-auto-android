# N45 授权视觉接线进度

本文件 append-only，记录 RED/GREEN、visual context 生命周期、安全拒绝和验证证据。

## Round 1

- worktree `/private/tmp/ai-auto-n45-visual` 位于
  `phase2-n45-authorized-visual-ports`，基线 `bc04770`，创建时干净。
- 已审计 N44 App 授权截图路径、N45 explicit executor、Provider parser/risk policy、
  Session Engine、AccessibilityRuntime 和现有 JVM 测试。
- 当前 `ProviderSessionPlanner` 在 Provider 返回 action 后立即关闭 visual lease；
  `RuntimeSessionExecutor` 随后把 `ui.tap/ui.swipe` 直接交给通用坐标 parser。该路径
  无法验证 observation ID、PNG hash、候选置信度、前台包、screen 或后置状态。
- 设计采用 session-internal planned action/context，不修改 `ProviderAction`、
  recording models 或公共协议。Engine 必须在风险、确认、precheck、执行、postcheck
  全程持有 context，并在所有返回路径 finally 关闭。
- 首轮只接当前授权 App 会话中的 model visual tap/long-click/swipe；历史脚本 rebind、
  桌面 N47 visual route 和 API 设备命中不在本 change spec。

## Round 2

- RED 定向编译先因 `SessionPlannedAction` 与 `SessionActionContext` 不存在而失败。
  已新增 session-only 计划对象，Engine 在 risk、人工确认、precheck、execute 和
  postcheck 全程持有 context，并在成功、拒绝、超时、停止、取消和异常路径关闭。
- Provider action 由 OpenAI parser 与 Planner 双重严格校验。视觉 target 固定绑定
  package、observation ID、PNG SHA-256、candidate UUID、`model` source、>=0.70
  confidence、point 和 bounds；无授权截图、裸坐标、hash/ID/source/置信度/边界漂移
  均在动作前失败。
- 从 N45 executor 提取纯 `ExplicitVisualReplayPlanner`，App 会话与录制回放复用同一
  observation/candidate/expiry/screen/density/crop/rotation 前置门。source screenshot
  在 Provider 返回后继续存活，执行前要求新授权 screenshot hash 一致，执行后要求
  hierarchy 或 PNG hash 可信变化且时间更新。
- production typed tap/long-click/swipe 在 Accessibility dispatch 时再次绑定实际 source
  package；Tap duration 本地限制 1..10000 ms。明确平台拒绝保持零提交；后置截图、
  geometry 或状态验证失败固定记录 `after one commit`，不自动重放。
- Provider 图片副本和 source/pre/post observation 在全部路径清零。visual swipe
  的 start/end 明确定义为候选 bounds 内 0..1 相对端点，session adapter 只在内存中
  转为 N45 ephemeral 录制坐标，再由同一 planner 映射和目标包绑定执行。

## Round 3

- 防御链为三层：OpenAI response parser 严格解析、`ProviderSessionPlanner` 对任意
  Provider 再验证、risk policy 与 Accessibility backend 在执行前和 dispatch 时
  逐字校验目标包。裸坐标、畸形 visualTarget 和包漂移均零提交。
- Engine context 生命周期测试覆盖 risk block、人工拒绝、pause/stop、preflight、
  action rejection、post verification、超时、取消和 close 异常；普通语义 action
  的 context=null 路径保持原行为。后置失败固定包含 `after one commit` 且不重放。
- 模型 point/bounds 明确定义为 screenshot crop-local 坐标，Planner 复用 N44
  `VisualProposalService` 完成 crop/rotation 到自然全屏的映射；非零 crop + 90°
  回归锁定映射结果，避免把模型局部坐标直接当全屏坐标。
- 最终 App JVM 337/337 通过；`lintDebug` 0 error/28 warning，AndroidTest APK
  55 个 task 编译通过。N47 59/59、N52 21/21、`make test`、`make verify`、
  `make build`、comments 368 文件和 diff-check 均通过。
- 本轮未启动 N45 设备命中测试。API 30/33/34 三分辨率/旋转 fixture、历史 visual
  脚本 rebind 和 N47 desktop visual route 仍未验收，不更新对应路线图勾选。
