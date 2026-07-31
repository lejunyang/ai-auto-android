# N37 持续 LAN RPC 任务

- [x] 审计现有握手、Session frame 与 CLI 关闭边界
- [x] 创建独立 change spec/worktree
- [x] 增加多次 RPC、错配响应、认证失败和关闭 RED 测试
- [x] 实现 `Session.Call` 的严格 Bridge RPC 复用
- [x] 为 CLI 增加固定只读 RPC probe，不开放任意动作
- [x] 运行 LAN/CLI 定向 race、Go 全量、verify/build/comments
- [x] 与 N38 汇合后运行真实跨端 socket 互操作
- [x] 提交并集成

## 建议提交

`feat(bridge): keep lan rpc sessions active`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
