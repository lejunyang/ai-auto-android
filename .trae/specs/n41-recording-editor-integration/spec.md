# N41 录制编辑器入口集成规格

## 目标

把已验证的 N41 Compose 编辑器组件接入现有录制脚本详情页，使用真实 N39 store
保存、复制和 revision CAS，并提供只读 Accessibility dry-run；不新增截图授权。

## 边界

- 详情页内使用 `RecordingDestination.EDITOR`，不修改 App 根导航和协议。
- 编辑 ViewModel 以 script ID 作为 lifecycle key，旋转后保留表单、Undo/Redo 和
  dirty；离开仍走明确 discard 确认。
- 保存和复制使用 `RecordingScriptStoreSavePort`；保存成功后重新读取脚本，冲突
  保留 attempted/current。
- dry-run adapter 只读取当前脱敏 snapshot、selector 和坐标，不执行任何动作。
- observation surface 只有调用方显式提供 `AuthorizedObservationView` 才显示；普通
  详情页不创建截图授权、不缓存图片。

## 允许修改

- `.trae/specs/n41-recording-editor-integration/`
- `android/app/src/main/java/dev/aiauto/android/ui/recording/`
- 对应 JVM 与 AndroidTest

## 禁止事项

- 不修改 N40 domain、RecordingModels/Store 实现、ReplayEngine、协议、根导航或
  screenshot 权限。
- 不把 instrumentation 编译、无 observation 的编辑页描述为真实截图点选验收。

## 验收

- 详情页可进入编辑、保存/复制后刷新详情，dirty 离开确认有效。
- dry-run 只读 adapter 的 selector/coordinate/package 条件有单测。
- App 全量、AndroidTest APK、lint、comments 和 diff-check 通过。
