# N41 授权截图编辑器生产接线进展

本文件 append-only，记录 N41 三项生产 RED 的实现、验证与设备证据。

## Round 1

- 从 `main` 的 `a2325c7` 创建独立分支和 worktree；开始时工作区干净，未读取或修改
  并行 QR worktree。
- 审计确认 `RecordingHost` 把 `observationHolder` 固定为 `null`；
  `ObservationSelection.Selected` 只携带 point；`EditStepCoordinate` 只能更新坐标，
  无法把 geometry、observation ID 与 image hash 作为一个领域事务提交。
- 当前实现将采用调用方显式提供、默认 `null` 的短生命周期 provider；图片字节仍由
  provider 租约持有，编辑器只接收 draw callback 和可持久化 metadata。
- 本 worktree 禁止启动、选择或操作设备。三项 instrumentation 转绿后的 API 34
  执行留给主线程统一完成。

## Round 2

- 新增 `RecordingEditorObservationProvider`，`RecordingHost` 仅在调用方显式传入时
  acquire 当前 holder；生产 App 和既有测试调用仍使用默认 `null`，普通详情入口不
  显示 screenshot surface。
- `AuthorizedObservationLease` 现携带经校验的 observation ID 与小写 SHA-256；
  point 和反向 drag bounds 均由 holder 生成 canonical normalized selection。过期、
  释放和非法坐标继续失败关闭，图片仅存在于调用方 draw lease。
- 新增 `EditStepAuthorizedVisualTarget`，在单个命令中校验并提交 point 或 bounds、
  observation ID、image hash、provenance 和 action target；任何字段非法都不创建
  history。手动 `EditStepCoordinate` 会同时清除旧 bounds、observation/hash 和图片
  引用，避免复用不一致 provenance。
- ViewModel 收到授权 selection 后立即应用原子命令；表单提交只有在用户真实修改 point
  时才执行手动坐标命令，因此不会把授权 metadata 二次覆盖。Compose 手势基于当前
  Canvas 尺寸和平台 touch slop 区分 tap/drag，不持久化截图或猜测设备坐标。
- 三项 instrumentation 已删除 `@Ignore`：真实 Host 显式授权 surface、point 完整
  metadata 保存、bounds 完整 metadata 保存均保留原断言。普通真实入口新增无 surface
  断言；API 34 设备执行仍由主线程完成。
- RED 阶段按预期在缺少 Host provider、selection metadata 与原子命令时编译失败。
  GREEN 阶段定向 JVM 17 项先通过；App 全量 JVM、AndroidTest 编译/组装、lint、
  debug/release build 共 134 个 Gradle task 通过，`make comments` 与
  `git diff --check` 通过。本轮未启动、选择或操作设备。

## Round 3

- 最终代码收紧 observation ID 格式与非法 normalized selection 的失败关闭语义，并把
  新原子命令纳入所有编辑命令的 Undo/Redo 通用矩阵。
- 最终 App 全量 JVM 为 364 tests、0 failure、0 error；N41
  `ScriptEditorSessionTest`、`RecordingEditorObservationTest` 与
  `RecordingEditorViewModelTest` 合计 18 tests 全部通过。
- 最终 Android 复验仍为 134 个 task 通过，包含 AndroidTest 编译/组装、lint 以及
  debug/release build；仓库级 `make test`、`make verify`、`make build` 全部退出
  0。`make verify` 再次确认协议、Skills、中文注释与 repository metadata。
- 提交前扫描确认生产代码与测试不含机器专属工具路径；main editor 与 domain 不持有
  `Bitmap`、`ByteArray` 或 Base64，手动坐标路径只显式清空旧图片引用。
- 本 change 未运行 connected test、未启动或选择设备。API 34 三项转绿
  instrumentation 仍由主线程在明确 serial 上执行。
