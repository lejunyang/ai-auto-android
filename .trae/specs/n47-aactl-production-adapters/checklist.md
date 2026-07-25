# N47 aactl Adapter 验收清单

- [x] 独立 worktree/change spec 与允许范围符合约束
- [x] aactl 绝对路径、非 symlink、inode/size/hash 每次执行前复核
- [x] 固定 `execFile` argv、`shell:false`、显式 serial 且无 raw ADB
- [x] stdout 严格单信封、预算、重复 key、stderr 和尾随数据校验
- [x] observation 绑定 snapshot、目标包、时效和保守页面分类
- [x] semantic target 唯一、可见、启用、非敏感且 action 能力匹配
- [x] visual/hybrid 或 visual-state 未接端口时零提交失败关闭
- [x] Bridge 动作和 direct launch 只构造类型化固定 JSON/argv
- [x] executor 明确拒绝/成功/异常映射 false/true/unknown
- [x] input 未有 stdin 模式时零进程调用失败关闭且明文不进入 argv
- [x] lifecycle 四端口透传且不执行 raw ADB/系统授权
- [x] adapter + N47 全包 51 项、N34 49 项、comments 和 diff-check 通过
- [ ] 真实 emulator/Bridge/N45 visual adapter 保持未验收
- [ ] aactl 固定 stdin action 模式和 production input adapter 保持未完成
- [x] 实现提交身份、唯一 trailer 与 worktree 状态正确
- [ ] 规格证据提交身份和唯一 trailer 正确
