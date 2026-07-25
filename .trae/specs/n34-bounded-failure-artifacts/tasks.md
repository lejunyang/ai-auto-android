# N34 Tasks

- [x] Task N34.1：审计 N31 基线、N34 目录所有权和隐私边界
- [x] Task N34.2：定义结果、阻断摘要和 20 轮统计的严格 Schema
- [x] Task N34.3：先编写 timeout、assertion、脱敏、预算和清理失败测试
- [x] Task N34.4：实现离线 collector、确定性预算与失败关闭
- [x] Task N34.5：实现成功清理、TTL 清理与残留复核
- [x] Task N34.6：实现 20 轮 product/device/infrastructure/flaky 统计
- [x] Task N34.7：增加 fake-input CI smoke 并完成专用验证
- [x] Task N34.8：修复截图信任锚并补充缺失、伪造、拒绝和异常回归
- [x] Task N34.9：审查允许范围并以单一职责提交

## 依赖

N34 依赖 N31 提交 `b7e9eda`、`6604f10` 和共享 Android 基线 `581f01c`。
N32、N33 在独立 worktree 并行推进；本任务不得读取其未提交实现或修改其目录。
