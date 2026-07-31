# N33 原生纵向滚动 Surface 重设计规格

## 目标

将已被 API 30 设备证据否定的自定义及平台 `ScrollView` 纵向 surface 替换为平台
`ListView`，保留稳定 resource ID 与 `contentDescription`，并以列表真实可见位置
计算 `VERTICAL_OFFSET`，由独立 `VERTICAL_SCROLL` 节点报告状态变化。

## 设计

- `vertical_scroll_target` 改为固定高度的 `ListView`，列表项使用固定高度和稳定
  `vertical_scroll_item_label` resource ID。
- instrumentation 通过 `fixtureScenario=vertical` 只在最新 `ListView` bounds 内
  执行一次向上 swipe，不调用 `scrollTo`、`scrollBy` 或状态写入来伪造动作成功。
- offset 根据 `firstVisiblePosition`、首个可见子项 `top`、列表顶部 padding 和固定
  项高度计算；只有真实 offset 大于零时才记录 `VERTICAL_SCROLL:1`。
- Reset 可将测试 fixture 恢复到列表首项，但该复位不作为滚动动作验收证据。

## 依赖与并行

- 依赖 N31 提供固定 API 30 emulator、显式 serial、快照和零残留清理。
- 与 N46 passive-smoke 并行时目录所有权互斥；本 change 只拥有
  `android/device-fixture/` 与本规格目录。
- 共享路线图、其他规格状态和主分支由集成线程单独维护，本 change 不修改。

## 实施步骤

1. 审计既有 fixture、测试和 API 30 `VERTICAL_OFFSET:0` 失败证据。
2. 先增加拒绝 `ScrollView`、要求 `ListView` 及真实 offset 算法的 RED 测试。
3. 实现平台 `ListView`、固定列表项、真实 offset 渲染和独立纵向场景。
4. 运行模块 unit、androidTest 编译、lint、APK 构建、`make comments` 与差异检查。
5. 仅在不会与集成线程设备任务冲突时，使用 N31 API 30 和显式 serial 执行新纵向
   场景一次；失败立即停止，不重试。

## 允许修改

- `android/device-fixture/`
- `.trae/specs/n33-native-scroll-redesign/`

## 禁止修改

- 生产 App、Bridge、协议、SDK、共享 Gradle 配置和 `docs/next-phase-tasks.md`
- `.trae/specs/` 下其他规格状态
- 已被设备证据否定的自定义或平台 `ScrollView` 纵向 surface
- 直接写入滚动成功状态、测试侧程序化滚动或放宽真实 offset 后置断言

## 验收证据

- RED 证据明确显示新 offset 契约在生产实现前失败。
- unit 测试覆盖跨列表项和首项内部位移的 offset 计算。
- androidTest 可编译且 `fixtureScenario=vertical` 只执行纵向动作与真实后置断言。
- `lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`make comments` 和
  `git diff --check` 通过。
- 若设备无冲突，API 30 单轮记录 serial、fingerprint、动作结果、真实 offset/state
  和 N31 清理结果；否则明确保留集成缺口。

## 提交与回滚

- 建议单一职责提交：`fix(android): redesign native vertical scroll fixture`
- 提交 Author 与 Committer 均为 `lejunyang <lejunyang@qq.com>`，并在末尾保留一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`。
- 失败时停止设备动作并仅清理本 change 启动的 N31 emulator/runtime/lease；不强制
  移除 dirty worktree，不修改或回退并行分支。
