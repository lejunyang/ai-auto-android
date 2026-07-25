# N45 Progress

本文件 append-only，记录显式视觉坐标回放、安全拒绝、测试和设备证据。

## Round 1

- 已确认 `/private/tmp/ai-auto-n45` 位于独立分支
  `phase2-n45-visual-replay`，基线 `39711ea`，启动时 worktree 干净。
- Git author/committer 配置为 `lejunyang <lejunyang@qq.com>`；N41 与 N44
  integration 位于其他互斥 worktree，本任务不修改或吸收其分支。
- 已审计 N39 脚本模型、N42 fixture 基线、N44 observation/candidate、现有
  `ReplayEngine`、coordinate transformer 与 typed Accessibility gesture。
- 现有视觉步骤会进入通用 ReplayGateway，并可能直接执行录制时像素；默认生产
  wiring 没有 observation、当前窗口元数据或动作后 verifier。设计因此采用显式
  fail-closed 端口，缺少端口时不得回落旧坐标。
- 坐标约定固定为：N44 候选处于自然方向归一化空间；先逆映射到原 observation
  crop 相对位置，再映射至当前窗口扣除系统栏 inset 后的可交互区域。swipe 的旧
  像素端点只在绑定区域内转为相对参数，绝不直接复用。
- 下一步先创建 N45 定向失败测试。生产实现、验证、设备矩阵和提交均尚未完成。

## Round 2

- 先编写 `ExplicitVisualReplayTest`，首次因 N45 类型缺失而失败；随后实现纯函数
  natural/rotation/window/inset 映射、crop 来源校验、类型化 tap/long-click/swipe、
  observation/candidate/screen 前置门和只读后置 verifier。
- 低置信度、候选歧义、过期、包变化、screen/density 漂移、secure window 和越界
  均在成功动作提交前失败，commit count 为 0；typed executor 成功后 verifier
  缺失、旧 evidence、无状态变化或抛错明确返回 commit count 1，且不进入 retry。
- ReplayEngine 对 visual/coordinate/manual 跳过 semantic selector 和旧 gateway，
  显式端口缺失时返回 `VISUAL_REPLAY_UNAVAILABLE`；semantic/imported 原回放测试
  保持通过。生产 UI/Bridge 目前未注入 observation/candidate port，因此 visual
  用户路径安全失败关闭，不能描述为已可执行。
- N45 独立与 ReplayEngine 定向 20/20、App JVM 全量 292/292、`lintDebug`、comments
  与 diff-check 通过。API 30/33/34 fixture 实际命中和 production port 接线均未
  执行，设备 checklist 保持未勾选。
