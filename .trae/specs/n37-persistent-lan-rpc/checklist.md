# N37 持续 LAN RPC 验收清单

- [x] 独立 change spec/worktree
- [x] 复用现有 Bridge Request/Response 与稳定错误
- [x] 同一 session 支持多个严格顺序 RPC
- [x] LAN token 只存在于加密 plaintext
- [x] 协议或认证违约失败关闭且不重放
- [x] CLI 只暴露固定只读 probe
- [x] 定向、race、全量与构建验证通过
- [ ] 跨 Go/Kotlin 真实 socket 互操作通过
- [x] 提交身份、trailer 和集成状态正确
