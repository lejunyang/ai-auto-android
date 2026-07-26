# N47 stdin Input 任务

- [x] 审计 CLI Bridge action、process Options 和 adapter execFile 边界
- [x] 创建独立 change spec 并固定 stdin/secret/提交状态契约
- [x] 先增加 Go CLI 与 Node adapter RED 测试
- [x] 实现有界 UTF-8 单 object strict stdin parser
- [x] 接入互斥 `--stdin` CLI 选项并清零 action bytes
- [x] 实现 child stdin 写入、错误处理和 input semantic route
- [x] 运行 Go race、N47、N34、comments 与 diff-check
- [x] 审查范围并提交

## 建议提交

`feat(cli): accept bridge actions from stdin`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
