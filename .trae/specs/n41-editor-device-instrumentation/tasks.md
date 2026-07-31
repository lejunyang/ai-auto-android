# N41 编辑器设备 instrumentation 任务

- [x] 审阅 N41 组件 spec、详情集成 spec、现有 UI 与测试边界
- [x] 创建 `n41-editor-device-instrumentation` 独立分支和 worktree
- [x] 新增 debug-only 短生命周期合成 screenshot provider
- [x] 新增真实 store 与详情入口 Compose instrumentation
- [x] 新增点选、框选和 visual metadata RED instrumentation
- [x] 运行 JVM、AndroidTest 编译、lint、comments 与 diff-check
- [x] 创建单一职责 Conventional Commit
- [ ] 等待主线程集成后在明确 serial 的 disposable emulator 执行设备 RED

## 建议提交

`test(android): add n41 editor device instrumentation`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
