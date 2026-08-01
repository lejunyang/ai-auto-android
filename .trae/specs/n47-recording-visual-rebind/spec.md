# N47 录制视觉步骤运行期 Rebind 规格

## 目标

历史保存的 `visual`、`coordinate` 或 `manual` 步骤不得把持久化的 observation ID、
图片 hash 或归一化坐标当作未来运行授权。production recording replay 只有在当前
运行获得 fresh authorized observation、显式生成运行期 rebind，并消费同轮一次性
lease 后才可提交类型化视觉动作。

## 授权与 Rebind 契约

- 持久 `VisualTarget` 只保留编辑提示和审计元数据，不是 observation lookup key，
  也不能单独证明当前图片、包、屏幕或候选仍可信。
- 当前运行的 observation provider 必须显式返回绑定 script ID/revision、step ID、
  run ID、设备 serial、目标包、授权起点、过期时间、fresh observation、唯一候选、
  当前 screen environment 和动作后重新观察回调的一次性内存 lease。
- fresh observation ID 必须不同于历史步骤保存的 ID；图片 hash 即使内容相同，也只
  能在新的 observation、授权时窗和候选完整校验通过后使用。
- rebind 只在内存中把 fresh observation ID、image hash 和 candidate geometry 写入
  本轮 `VisualReplayRequest`；不得把图片 bytes、运行 lease 或 post observation
  写入脚本、SavedState、日志或仓库。
- lease 每个 step 只能消费一次，成功、拒绝、失败、取消和 run 结束都必须关闭；
  未消费步骤也由 run 级清理撤销。

## 失败关闭

- provider 缺失、run/script/revision/step 不匹配、旧 observation ID 复用、serial、
  package、screen、hash、candidate、geometry、confidence 或 expiry 漂移时，typed
  action 调用数为零。
- 当前屏幕、前台包、density、rotation、window、secure 状态或 observation 生命周期
  不可确认时失败关闭，不新增裸坐标、历史像素或 selector fallback。
- 动作明确拒绝记零提交；动作调用抛错记 `ACTION_COMMIT_UNKNOWN`，不得按 retry
  policy 重放。
- 动作返回成功后必须通过同一 rebind lease 的授权 provider 重新观察；旧 evidence、
  状态未变化、包或屏幕漂移、provider 异常都记一次已提交并停止。

## Production 组合

- `RecordingHost` 将编辑 observation provider 与 replay authorization provider
  分成两个独立参数；点击回放时只把显式 replay provider 交给
  `RecordingViewModel`。普通入口 replay provider 为 `null`，历史视觉步骤默认
  失败关闭。
- `RecordingViewModel` 只把 provider 为本次调用生成的 run authorization 交给
  `RecordingController`/`ReplayEngine`，并为 production engine 注入 N45 类型化
  visual replay port。
- 编辑器继续通过当前短生命周期 observation 原子写入 geometry、observation ID 和
  hash；该保存结果仍需未来运行再次 rebind，不能提升为长期授权。
- 本 change 不新增 Bridge、Go、protocol 或 desktop N47 路由；桌面端后续只能通过
  同一 run authorization 契约接入。

## 允许修改

- `.trae/specs/n47-recording-visual-rebind/`
- `android/app/src/main/java/dev/aiauto/android/automation/recording/`
- `android/app/src/main/java/dev/aiauto/android/ui/recording/RecordingHost.kt`
- `android/app/src/main/java/dev/aiauto/android/ui/recording/RecordingViewModel.kt`
- `android/app/src/main/java/dev/aiauto/android/ui/recording/RecordingEditorViewModel.kt`
- 上述范围对应的 `android/app/src/test/` 测试
- 仅在无法完成 production dependency wiring 时修改 `AiAutoAndroidApp.kt`

## 禁止事项

- 不修改 `AndroidBridgeMethods.kt`、Bridge、Go、protocol、N45、N51、
  `docs/next-phase-tasks.md`、SDK/cache 或共享 test-lab runner。
- 不把历史 hash 当作当前授权，不注册无授权截图入口，不持久化图片 bytes。
- 不新增裸坐标 fallback，不把 unknown commit 描述为零提交或自动重放。
- 不勾选 N47，不把 JVM、lint 或构建证据描述为设备验收。

## 验收

- 先增加 provider 缺失、旧 ID、fresh rebind、serial/package/screen/hash/candidate/
  expiry 漂移、单次消费、动作后重新观察及 unknown commit 的 RED 测试。
- 相关 Android JVM 定向测试和至少定向 `:app:testDebugUnitTest` 通过。
- `:app:lintDebug`、`:app:assembleDebug`、`make comments` 与
  `git diff --check` 通过。
- 最终明确 Bridge/N47 desktop port 和 API 设备证据仍未接入。
