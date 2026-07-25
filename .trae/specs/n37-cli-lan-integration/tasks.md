# N37 LAN CLI 接线任务

- [x] 复核 N37 核心、CLI 根命令和独立 worktree 边界
- [x] 先增加 interfaces/listen 输出与生命周期 RED 测试
- [x] 实现类型化 LAN CLI 服务和依赖注入
- [x] 注册 `bridge lan` 且确保纯 LAN 命令不解析 ADB
- [x] 覆盖 timeout、cancel、切网、重复 accept 和 secret redaction
- [x] 运行 race、仓库门禁和生产单地址清理 smoke

## 依赖与汇合

本任务依赖已合入的 N36/N37 Go 核心，只修改桌面 Go CLI 范围。Android 真实出站 wire
adapter 在独立任务中串行汇合，当前不得修改或假设其已可双向收发 RPC。

## 建议提交

`feat(cli): connect lan bridge listener`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
