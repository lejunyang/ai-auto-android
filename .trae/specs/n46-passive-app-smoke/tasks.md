# N46 五包被动启动观察任务

- [x] 审计 N46 lifecycle、N31 install runner 与 `aactl` 直接观察边界
- [x] 创建独立 change spec、分支和 worktree
- [x] 增加固定三调用被动 scenario 与 hierarchy 脱敏分类
- [x] 增加五个固定 manifest 的矩阵 CLI 和 0600 报告
- [x] 增加参数注入、额外动作、输出歧义、敏感字段和清理测试
- [x] 在 N31 API 30/33/34 clean emulator 上运行五包被动 smoke
- [x] 运行 N46 全量、扫描、注释、差异与零残留检查
- [ ] 创建单一职责提交并集成

## 建议提交

`test(lab): add passive verified app smoke`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
