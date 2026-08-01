# N43 Clean Snapshot 重复矩阵任务

- [x] 审计现有 N43 instrumentation、N31 runner 与历史单轮失败证据
- [x] 创建独立 change spec、分支和 worktree
- [x] 增加固定 profile 的 20 轮 clean restore 编排器
- [x] 增加 JUnit XML 新鲜度、唯一 test case 与失败分类解析
- [x] 增加参数拒绝、阈值、清理与 stop 重试测试
- [x] 运行 API 30、33、34 各 20 轮真实矩阵
- [x] 更新 N43 append-only 证据与路线图状态
- [x] 运行定向、全量、构建、comments 和 diff-check
- [x] 创建单一职责提交并集成

## 建议提交

`test(android): run clean webview semantic matrix`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
