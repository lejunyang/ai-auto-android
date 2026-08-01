# N52 Production Matrix Ports 任务

- [x] 检查主工作区与并行 worktree，创建独立 branch/worktree
- [x] 审计既有 N52 内核、N31 lifecycle、N47 adapters 与外置工具根
- [x] 创建独立 change spec，固定 CLI、ports、报告和失败关闭边界
- [x] 先增加 production CLI/ports RED 测试
- [x] 实现固定 CLI、可注入 factory 与 production contract
- [x] 接入当前可用 N31 lifecycle，缺失 provider 稳定失败关闭
- [x] 实现八类 residue 类型化映射和原子报告发布
- [x] 运行定向、N52/N47/N34 与仓库全量验证
- [ ] 审查范围、diff、身份和提交 trailer
- [ ] 真实 3 × 5 × 20 设备矩阵验收

## 建议提交

`feat(test-lab): add production matrix ports`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
