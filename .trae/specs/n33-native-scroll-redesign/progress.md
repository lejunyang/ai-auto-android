# N33 原生纵向滚动 Surface 重设计进度

本文件 append-only，记录设计、RED、验证、设备证据和清理结果。

## Round 1

- 开工前 `main` 干净但领先 `origin/main` 7 个提交；并行 N46 位于
  `/private/tmp/ai-auto-passive-smoke`，本 change 使用独立
  `/private/tmp/ai-auto-n33-scroll-redesign` 和 `n33-native-scroll-redesign`。
- 历史 API 30 证据已否定自定义 `DeterministicScrollView` 与纯平台
  `ScrollView`：前者 bounds 内 swipe 后 `VERTICAL_OFFSET:0`，后者语义 scroll
  返回 false 或 swipe 后仍为 0。新设计不再尝试这两条路线。
- 选择平台 `ListView`，避免引入 RecyclerView 依赖；真实 offset 由首个可见位置、
  首个子项顶部与固定项高度共同计算，状态只能由真实 offset 变化产生。

## Round 2

- RED 先增加 `VerticalListOffsetTest` 与 `GestureResourceContractTest` 新契约；
  `:device-fixture:testDebugUnitTest` 按预期在
  `compileDebugUnitTestKotlin` 失败，三处错误均为生产
  `VerticalListOffset` 尚不存在。
- 随后将纵向 surface 重设计为平台 `ListView`，使用 12 个固定 48dp 行、
  `vertical_scroll_item_label` resource ID 与逐项固定 `contentDescription`。
- `VERTICAL_OFFSET` 由 `firstVisiblePosition`、首个可见子项 `top`、列表 padding
  和实际行高计算；只有真实 offset 大于 0 才记录 `VERTICAL_SCROLL:1`。
- instrumentation 新增 `fixtureScenario=vertical`，只在最新目标节点执行一次
  `UiObject2.scroll(Direction.UP, 0.8f)`，随后重新观察正 offset 和独立状态。
- `:device-fixture:testDebugUnitTest` 24 tasks 通过；instrumentation Kotlin 编译、
  `assembleDebug`、`assembleDebugAndroidTest` 与 `lintDebug` 共 73 tasks 通过。

## Round 3

- 设备发现前 `aactl doctor` 全部通过，设备列表、N31 runtime 与 AVD/port locks
  均为空；并行 N46 已结束，因此安全启动唯一 owned API 30 emulator。
- N31 从 clean snapshot 启动明确 serial `emulator-5558`，Android 11/API 30，
  fingerprint 为
  `51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707`。
- 按一次执行上限运行 `fixtureScenario=vertical`、`fixtureRepeat=1`；新 `ListView`
  已被定位并完整可见，但 `UiObject2.scroll(Direction.UP, 0.8f)` 直接返回 false，
  instrumentation 1/1 失败，未进入正 offset 后置验证。本轮立即停止且未重试。
- 最终实现据此改为在每轮最新 `ListView.visibleBounds` 内执行一次向上 swipe，
  继续要求真实 `VERTICAL_OFFSET>0` 与独立 `VERTICAL_SCROLL:1`；因设备动作上限已用完，
  只做非设备验证并等待集成线程复验，不能将该最终 route 描述为设备通过。
- N31 已停止 `emulator-5558`；有限 10 秒设备 watch 后 `aactl devices list` 为
  0，runtime、AVD/port lease 与 owned emulator 均无残留。
- 主线程随后声明独占 emulator 运行 N43 三 API 各 20 轮矩阵；本 change 不再启动、
  观察或操作任何设备，最终 bounds 内 swipe route 明确等待主线程后续集成复验。
