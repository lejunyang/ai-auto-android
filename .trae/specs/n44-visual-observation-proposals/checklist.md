# N44 验收清单

- [x] observation ID 与 package、屏幕、旋转、crop、时间、PNG hash 和 hierarchy 绑定
- [x] 同一 observation ID 的元数据漂移被拒绝且旧租约不被覆盖
- [x] OCR/template/model/manual 候选统一映射为归一化点、边界、置信度和来源
- [x] proposal 服务类型上没有执行能力，所有失败场景动作提交数为零
- [x] 过期、包变化、`FLAG_SECURE`、空图、低置信度和接近候选失败关闭
- [x] PNG 预算、临时文件和全部 ByteArray/byte slice 生命周期完成清零
- [x] 可信 verifier 来自进程内独立上下文，self-hash 不构成安全证明
- [x] 原图、Base64 和路径不进入 schema、脚本、日志或结构化结果
- [x] Android 与 Go 通过独立 visual schema canonical example 保持一致
- [x] MCP adapter 只读、独立测试且未修改共享 server/root 注册或 protocol manifest
- [x] Android 定向、Go race、schema、comments 与 diff-check 通过
- [x] 提交范围正确且 worktree 干净
