# N47 通用场景 Runner 任务

- [x] 审计 N33/N34/N42/N43/N45/N46 与独立 worktree 边界
- [x] 创建 change spec 并固定 DSL、分类、产物和清理契约
- [x] 先增加严格 Schema、runner、聚合器和 fixture 场景 RED 测试
- [x] 实现 DSL 严格验证、页面分类和 route 安全门
- [x] 实现类型化执行、后置验证、N34 结果映射和失败产物端口
- [x] 实现全路径清理和确定性兼容聚合
- [x] 增加 native/Web/Canvas fixture 场景并覆盖全部动作
- [x] 运行 N47 smoke、N34 Schema、注释和差异门禁
- [x] 审查允许范围并按单一职责提交

## 依赖与边界

N47 依赖主线已完成的 N33、N34、N42、N43、N45 安全内核以及 N46 verifier 基线。
真实第三方 App 仍依赖用户提供合法 APK，不阻塞本任务的 fixture-first runner 实现。

## 建议提交

`test(lab): add cross-app scenario runner`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
