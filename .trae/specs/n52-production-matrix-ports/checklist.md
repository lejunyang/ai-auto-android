# N52 Production Matrix Ports 验收清单

- [x] 独立 worktree/change spec 与允许范围符合约束
- [x] CLI 固定外置工具根、仓库外报告路径且拒绝全部参数
- [x] capability probe 在 lifecycle start 前完成并失败关闭
- [x] production factory 不以 fake、skip 或默认 serial 降级
- [x] lifecycle/scenario/residue 均验证 serial/fingerprint/clean snapshot 绑定
- [x] 五场景只允许固定方法映射
- [x] 八类 residue inspector 类型化、固定顺序且结果全为非负整数
- [x] N31 lifecycle 适配器完成当前可交付接线
- [x] N47/recording 缺失 provider 返回稳定 unavailable 错误
- [x] stop、residue、drift 和 unknown commit 均失败关闭
- [x] 报告以 `0600` 临时文件同步并原子 rename，失败清除临时文件
- [x] 定向 fake 测试不实际运行 300 轮设备矩阵
- [x] N52 Schema/21 项、N47/59 项、N34/49 项回归通过
- [x] `make comments test verify build` 与 `git diff --check` 通过
- [ ] Author/Committer 与 trailer 符合要求
- [ ] 真实 API 30/33/34 × 五场景 × 20 轮保持未验收
