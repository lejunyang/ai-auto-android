# N33 原生纵向滚动 Surface 重设计任务

- [x] 审计根 `AGENTS.md`、N33 fixture/测试和 API 30 历史失败证据
- [x] 创建 `n33-native-scroll-redesign` 独立分支与 worktree
- [x] 先增加 ListView surface 与真实 offset 的 RED 测试
- [x] 实现稳定 resource ID、`contentDescription`、真实 offset 与独立状态节点
- [x] 增加仅运行纵向滚动的 instrumentation 场景
- [x] 运行定向 unit、androidTest 编译、lint 和 Debug APK 构建
- [x] 运行 `make comments` 与 `git diff --check`
- [x] 无并行冲突时执行一次 N31 API 30 新纵向场景并确认零残留
- [x] 检查允许修改范围、提交身份和 trailer 后创建单一职责提交

## 汇合点

本 change 只提交实现、测试和本规格证据。`docs/next-phase-tasks.md`、其他规格状态与
更大设备矩阵均等待集成线程处理。
