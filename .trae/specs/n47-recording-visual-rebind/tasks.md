# N47 录制视觉 Rebind 任务

- [x] 审计 `EditStepAuthorizedVisualTarget`、`ReplayEngine.explicitVisualReplay`、
  observation store/provider 与 production recording wiring
- [x] 创建独立 change spec，固定历史 metadata 非授权和 current-run lease 边界
- [x] 先增加 fresh rebind、漂移、清理与 unknown commit RED 测试
- [x] 实现一次性 run authorization 和 per-step rebind lease
- [x] 实现 N45 executor 对 fresh lease 的消费、重绑和动作后重新观察
- [x] 接入 `RecordingHost`/`RecordingViewModel` production replay 组合
- [x] 运行 Android JVM、lint、assemble、comments 与 diff-check
- [x] 审查允许范围并按单一职责提交

## 依赖与边界

本 change 复用 N41 当前授权编辑元数据、N44 observation/candidate 模型和 N45 显式
视觉 planner/executor。Bridge、desktop N47 adapter 和设备矩阵保持后续独立任务，
不能通过扩大本 change 范围补齐。

## 建议提交

`feat(recording): require fresh visual replay rebind`
