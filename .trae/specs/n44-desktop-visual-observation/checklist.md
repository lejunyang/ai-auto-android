# N44 桌面可信视觉 Observation 验收清单

- [x] 独立 change spec、分支和 worktree
- [x] 输入只接受明确 device、expectedPackage 和只读 target
- [x] 前后设备、hierarchy、package 与 rotation 漂移失败关闭
- [x] PNG、完整 capture crop、screen 和脱敏 hierarchy 绑定同 observation
- [x] password/sensitive 节点及后代不返回 label
- [x] 生产 provider 只从同 observation hierarchy 生成 template 候选
- [x] 低置信度、歧义、空候选和过期 observation 动作提交数为零
- [x] 共享 MCP visual 工具只读且 schema 不包含执行、路径或原图字段
- [x] 成功、失败、取消与关闭路径清零全部图片并撤销 observation
- [x] N31 emulator 单次只读 visual propose 有设备证据
- [x] 定向、race、全量、comments 与 diff-check 通过
- [ ] 提交身份、trailer、集成与 worktree 清理正确
