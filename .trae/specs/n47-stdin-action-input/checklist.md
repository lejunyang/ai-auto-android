# N47 stdin Input 验收清单

- [x] 独立 worktree/change spec 与允许范围符合约束
- [x] `--action`/`--stdin` 恰好选择一个且兼容现有命令
- [x] stdin 有界、UTF-8、单 object、递归重复 key 与尾随值严格拒绝
- [x] 失败在 Bridge RPC 前发生且错误不回显输入
- [x] action 可控 byte slice 在所有路径清零
- [x] adapter input argv 不含 value/action JSON
- [x] child stdin 固定写入 JSON+LF 并处理 stream/error/提前退出
- [x] input 目标唯一、可见、启用、非敏感且具备 setText
- [x] 明确拒绝/成功/异常映射 false/true/unknown
- [x] route token 单次消费且 lifecycle 后失效
- [x] Go race、N47 59 项、N34 49 项、comments 和 diff-check 通过
- [ ] 真实 emulator input 验收保持未完成
- [x] 实现提交身份、唯一 trailer 与 worktree 状态正确
- [ ] 规格证据提交身份和唯一 trailer 正确
