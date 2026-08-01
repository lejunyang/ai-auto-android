# 可移植工具根与 Windows CI 任务

- [x] 审计仓库硬编码路径、外置 shell 配置和 GitHub Actions 公开失败记录
- [x] 创建独立 change spec、分支和 worktree
- [x] 增加共享显式工具根解析与跨平台路径包含校验
- [x] 迁移 LAN、N43、N46 runner 与测试
- [x] 固定 Git checkout 行尾并增加 Go 格式脚本
- [x] 修复 Windows artifact/emulator 测试的平台假设
- [x] 运行跨平台相关全量验证与 CRLF 模拟
- [x] 更新文档和路线图证据
- [ ] 创建单一职责提交并集成

## 建议提交

`fix(ci): make toolchain paths and windows checks portable`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
