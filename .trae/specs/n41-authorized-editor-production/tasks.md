# N41 授权截图编辑器生产接线任务

- [x] 审计三项 ignored RED、observation holder、Compose surface、ViewModel 与 N40 命令
- [x] 创建独立 change spec
- [x] 新增默认 `null` 的 Host 授权 provider 接口
- [x] 新增 point/bounds + observation/hash 原子领域命令
- [x] 实现确定性 tap/drag selection 与短生命周期释放
- [x] 删除三项 `@Ignore` 并补 JVM/Compose 契约
- [x] 运行 JVM、AndroidTest 编译/组装、lint、debug/release build、comments 与 diff
- [x] 创建单一职责 Conventional Commit
- [ ] 由主线程在明确 serial 的 API 34 emulator 上运行 instrumentation

## 建议提交

`feat(android): wire authorized recording editor observations`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
