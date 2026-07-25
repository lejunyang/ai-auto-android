# N41 实施任务

## T1 状态适配层

- [x] 为 `ScriptEditorSession` 提供 Compose 可观察状态包装。
- [x] 支持复制、步骤命令、完整表单提交、Undo、Redo 和保存。
- [x] 明确非法表单、未保存离开和 revision conflict 状态。

## T2 短生命周期观察

- [x] 定义调用方授权的 observation view model 和显式释放租约。
- [x] 点选只提交归一化坐标，不读取、编码、持久化或记录图像内容。
- [x] 覆盖过期、旋转重建和离开释放。

## T3 Compose 界面

- [x] 实现脚本工具栏、步骤列表、步骤操作和字段表单。
- [x] 实现离开确认、冲突、dry-run 报告及失败步骤定位。
- [x] 实现只消费授权 observation 的点选表面。

## T4 验证

- [x] 运行 N41 定向 JVM 测试。
- [x] 编译 N41 Compose instrumentation 测试。
- [x] 运行 `:app:testDebugUnitTest`、`:app:lintDebug`、`make comments`。
- [x] 运行 `git diff --check` 并记录 instrumentation 真实执行缺口。
