# Debug Harness Planned Action 兼容规格

## 目标

修复 N45 将 `SessionPlanner` 返回值升级为 `SessionPlannedAction` 后，debug-only
`SessionStopVerificationHarness` 仍使用旧 `ProviderAction` gate 导致的编译失败。

## 允许修改

- `.trae/specs/debug-harness-planned-action/`
- `android/app/src/debug/java/dev/aiauto/android/accessibility/SessionStopVerificationHarness.kt`
- `android/app/src/test/java/dev/aiauto/android/accessibility/SessionStopVerificationHarnessTest.kt`

## 禁止修改

- 生产 `AutomationSessionEngine`、Provider、风险策略和视觉 context 生命周期
- release 源集、Manifest、权限、设备授权与公共协议
- 让 debug 自检执行动作、创建视觉 context 或绕过用户触摸停止门

## 验收

- debug harness 与当前 `SessionPlanner` 类型契约一致。
- 用户触摸停止测试仍返回 `executorCalls=0`，runtime 清理完成。
- App debug/release、单测、lint 与 AndroidTest 编译通过。
