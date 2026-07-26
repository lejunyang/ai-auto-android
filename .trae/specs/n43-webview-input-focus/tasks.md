# N43 WebView 聚焦输入任务

- [x] 审计 API 30 真实失败证据和现有 `ui.setText` 提交语义
- [x] 创建独立 change spec 和 worktree
- [x] 临时增加显式 click/focus 后重新观察的 device test
- [x] 编译 Web fixture JVM 与 instrumentation
- [x] 在固定 API 30 clean AVD 运行单轮并确认方案失败关闭
- [x] 撤销被设备证据否定的代码实验
- [x] 验证设备、runtime、lock 和 lease 清理
- [ ] 设计 WebView 91 实际支持且不违反安全边界的类型化输入 route
- [ ] API 30 单轮通过后再运行二十轮并记录统计
- [ ] 提交实现和 append-only 设备证据

## 建议提交

`test(android): focus webview input before set text`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
