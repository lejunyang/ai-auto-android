# N43 WebView 能力探针任务

- [x] 审计 API 30/33/34 完整场景失败边界
- [x] 创建独立 change spec/worktree
- [x] 增加只读 action matrix instrumentation
- [x] 增加禁止动作/坐标/DOM/IME 的 JVM 静态契约
- [x] 编译 Web fixture JVM 与 AndroidTest
- [x] API 30/33/34 各运行一次并记录 matrix
- [x] 分类 long-click、iframe、页面后置和 Back 的完整/混合边界
- [x] 清理设备、runtime、lock 和 lease
- [x] 运行 comments/diff-check、提交和集成

## 建议提交

`test(android): probe webview semantic capabilities`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
