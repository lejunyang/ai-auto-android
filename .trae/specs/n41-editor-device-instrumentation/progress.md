# N41 编辑器设备 instrumentation 进展

本文件 append-only，记录设备 RED、静态门禁、最小生产缺口和后续设备执行证据。

## Round 1

- 从 `main` 的 `22916e0` 创建 `n41-editor-device-instrumentation` 分支和
  `/private/tmp/ai-auto-n41-editor-device-instrumentation` worktree；创建时干净，
  并行 N33 worktree 未被修改。
- 已审阅 N41 两份既有 spec、详情入口、编辑器 UI/ViewModel、observation holder、
  真实 store 和现有 JVM/Compose 测试。现有 instrumentation 仅直接挂载组件，没有
  验证生产 `RecordingHost` 的详情编辑路径。
- 确认 production Host 把 `observationHolder` 固定为 `null`；现有 selection 只有
  point，表单保存不携带授权 observation ID/hash，也没有 bounds 手势或表单状态。
  本 change 只增加 debug provider、设备 RED 和缺口文档，不越界修改生产 `main`。
- 当前主线程和 N33 可能操作 emulator；本 worktree 不启动、不选择、不操作设备，
  只完成可离线执行的编译、JVM、lint、comments 和 diff-check。

## Round 2

- 新增 `DebugAuthorizedScreenshotProvider`：仅在 debug source set 生成确定性棋盘位图，
  不读取页面、文件、网络或权限数据；每次只保留一个短 TTL 授权，旧 lease 立即撤销，
  释放时清零并回收位图。SHA-256 从固定尺寸与 ARGB 像素流增量计算，不保留截图副本。
- 新增真实 `RecordingHost`/store instrumentation 源码，覆盖详情进入编辑、步骤重排、
  启停、复制、删除、Undo/Redo、dirty 离开确认、脚本复制、外部 store 更新触发 CAS
  冲突，以及无 Accessibility snapshot 时 dry-run 首失败步骤定位。
- 新增授权截图 RED instrumentation：provider 生命周期测试应可通过；真实详情入口
  provider 注入、点选保存 observation ID/hash、框选保存 normalized bounds 三项在
  当前生产结构下预期失败。测试点击真实保存按钮并通过捕获型 save port 检查最终脚本，
  不把仅更新内存表单描述为保存验收。
- N41 JVM 定向 14/14 通过；`:app:compileDebugAndroidTestKotlin`、
  `:app:assembleDebugAndroidTest`、`:app:lintDebug`、`make comments` 和
  `git diff --check` 通过。Gradle 仅使用外置 SDK/cache，未调用 ADB、未启动或选择
  emulator；设备 RED 仍等待主线程完成 N43 独占矩阵并集成本提交后执行。

## Round 3

- 集成审查确认三项已知生产缺口若作为普通 `@Test`，会让
  `connectedDebugAndroidTest` 在缺口修复前永久失败，污染全量 instrumentation 信号。
- `redRealDetailEntryConsumesExplicitDebugAuthorization`、
  `redTapSavesPointWithAuthorizedObservationMetadata` 和
  `redDragSavesNormalizedBoundsWithAuthorizedObservationMetadata` 现以方法级
  `@Ignore` 保留，并用稳定 reason 分别指向 Host 授权注入、point 授权元数据保存和
  bounds 选择/保存缺口。缺口修复时应删除对应 `@Ignore` 并使契约转绿。
- provider 生命周期测试和 `RecordingEditorRealEntryDeviceTest` 保持可运行；因此
  全量 instrumentation 不再被故意失败污染，仍能报告真实回归。

## Round 4

- 实现提交 `64f06a6` 与 ignored RED 修复 `3c72ea6` 已分别以集成提交
  `6084c8a`、`86ffbe8` 落入 `main`；四个提交 Author/Committer 均为
  `lejunyang <lejunyang@qq.com>`，要求的 trailer 各恰好一次。
- 主线程在唯一 N31 API 34 clean `emulator-5568` 上运行固定两个 test class。
  JUnit XML 为 6 tests、0 failure、0 error、3 skipped：provider 生命周期 1 项和
  真实详情入口 2 项通过；Host 授权注入、point 授权 metadata 保存、bounds 选择/保存
  三项按稳定 reason 跳过，未伪报为通过。
- 真实详情入口设备证据覆盖步骤下移、启停、复制、删除、Undo/Redo、dirty 返回确认、
  脚本复制、真实 store revision conflict 与 dry-run 首失败定位。debug provider
  只生成合成棋盘图，验证单活跃 lease、短 TTL、旧授权撤销与释放清零。
- 结束后恢复 clean snapshot并由 N31 stop `emulator-5568`；最终设备、runtime 和
  AVD/port lease 为零。N41 的真实入口授权 screenshot 注入、带 observation/hash
  的 point 保存和 normalized bounds 框选仍是生产缺口，路线图主任务保持未完成。

## Round 5

- 原分支提交 `64f06a6` 与 `3c72ea6` 分别以等价集成提交 `6084c8a` 与
  `86ffbe8` 落入 `main`，stable patch-id 分别为
  `52d91c7778097d2b7c3dd82b210c3b6e6dfd3364` 与
  `15c9da4744fb3ff1567d2d241ad7ab2b3a1b9741`。
- 临时 worktree 在干净状态下非强制移除，随后执行 `git worktree prune` 并删除已
  等价集成的临时分支；没有覆盖主分支或并行 N43 worktree。
