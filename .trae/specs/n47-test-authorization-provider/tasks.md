# N47 Test-only 授权 Provider 任务

- [x] 建立独立 branch/worktree 并核对用户与并行改动
- [x] 审阅 N31 身份、N32 test-control、N45 visual、N47 runner 与 release scanner
- [x] RED：新增 host provider 与 Android 安全边界失败测试
- [x] GREEN：实现固定 debug/androidTest instrumentation 授权 endpoint
- [x] GREEN：实现 host provider 与 production runner 生命周期接线
- [x] 验证 core JVM、androidTest 编译、release 零入口和 N47 73/73
- [x] 运行 comments/diff、Make 全量与 Android unit/lint/debug/release
- [x] 审查允许范围并创建单一职责 Conventional Commit

## 依赖与汇合点

- N31 继续独占 emulator lifecycle；provider 不自行发现或选择设备。
- Android instrumentation 独占 secure settings 与 Bridge 授权；host 只持有类型化
  lifecycle，不直接写 secure settings。
- N47 production orchestrator 是唯一 host 集成点；不修改 N48-N52。
- 本 change 不操作设备；API 30/33/34 命中由主线程在提交后串行执行。

## 建议提交

`feat(test-lab): add fixture authorization provider`

## 失败回滚与清理

- 离线测试只使用 fake command、临时文件和 JVM 模型，不启动 ADB 或 emulator。
- instrumentation 的所有退出路径恢复原 secure settings、关闭 Bridge 并撤销 token。
- host 的所有退出路径关闭 aactl session/forward、停止专用 instrumentation，并由既有
  production runner 清 App data、restore snapshot 与停止 owned emulator。
