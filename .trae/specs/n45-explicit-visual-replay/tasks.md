# N45 实施任务

- [x] 审计 N39/N42/N44、ReplayEngine、录制动作模型与 accessibility typed gesture
- [x] 固定自然屏幕、rotation、crop、window offset、inset 和 density 的映射契约
- [x] 先编写三分辨率、四旋转及全部安全拒绝场景的失败测试
- [x] 实现纯函数视觉坐标映射、类型化 tap/press/swipe 计划
- [x] 实现 observation/candidate/current-screen 前置校验和只读后置 verifier
- [x] 将 visual/coordinate/manual 来源接入 ReplayEngine，保持 semantic 原行为
- [x] 运行 N45 定向测试、App 全量单测、lint、comments 与 diff-check
- [x] 审查允许范围、提交身份、trailer 和 worktree 清洁状态

## 依赖与边界

N45 依赖主线现有 N39 脚本模型、N42 fixture 基线和 N44 observation/candidate 数据。
本任务与 N41、N44 session integration 使用互斥 worktree；不修改其文件，也不承担
共享路线图或设备矩阵状态。
