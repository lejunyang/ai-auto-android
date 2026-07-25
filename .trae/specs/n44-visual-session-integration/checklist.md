# N44 授权视觉会话集成验收清单

- [x] 只有显式 screenshot 授权开启时创建视觉 observation
- [x] observation 绑定同一目标包、稳定 hierarchy、会话代次和最小 PNG
- [x] Provider prompt 包含 observation 元数据且不泄露路径或日志图片
- [x] hierarchy/包/授权漂移在 Provider 调用前失败关闭
- [x] sensitive/secure/超预算拒绝继续复用现有截图控制器
- [x] 成功、失败和取消后全部图片副本清零且 observation 撤销
- [x] 无截图纯语义流程保持兼容
- [x] 未新增视觉动作执行或放宽 Provider action 风险门
- [x] 定向、App 全量、lint、comments 与 diff-check 通过
- [x] 桌面 MCP 仍保持未注册并记录原因
- [ ] 提交身份、trailer、范围和 worktree clean
