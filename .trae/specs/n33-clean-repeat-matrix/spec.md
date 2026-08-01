# N33 Clean Snapshot 重复矩阵规格

## 目标

修复原生 Fixture 系统导航返回状态的生命周期竞态，并在 N31 固定 API 30、33、34
模拟器上完成 N33 完整场景的 clean snapshot 重复矩阵。

## 范围

- 系统导航标记后，只有 Activity 确实离开前台并再次恢复时才提交
  `SYSTEM_RETURN`；同一前台周期内的额外 `onResume` 不得提前消费 pending 状态。
- JVM 测试覆盖导航标记、前台离开、返回提交和 reset 边界。
- 先在 API 30 clean emulator 运行一次 `fixtureScenario=system`；失败立即停止并清理，
  不以 sleep、重复动作或放宽后置断言规避失败。
- 单轮通过后，API 30、33、34 各运行 20 个完整场景；每个 profile 成功率不低于
  95%，并记录明确 serial、fingerprint、轮次结果和清理状态。

## 允许修改

- `.trae/specs/n33-clean-repeat-matrix/`
- `android/device-fixture/`
- `.trae/specs/n33-native-device-fixture/`
- `docs/next-phase-tasks.md`
- N33 专用矩阵脚本及其测试
- `.github/workflows/verify.yml` 与 workflow portability 契约测试

## 禁止修改

- 生产 App、Bridge、协议、第三方 APK、其他任务的规格状态
- raw ADB、隐式设备选择、共享 ADB server 重启或手工删除 runner 锁
- 直接写入 UI 后置状态、在同一失败页面重放动作或把命令成功当作 UI 成功

## 验收

- 生命周期 RED/GREEN 测试证明 pending 只能在真实前台离开后消费。
- API 30 系统导航单轮覆盖 Home、Recents、Settings 与 Launcher 重入。
- API 30、33、34 各 20 个完整场景成功率不低于 95%。
- 定向单测、instrumentation 编译、debug/test APK、lint、`make comments` 与
  `git diff --check` 通过。
- 最终设备、runtime、AVD/port lease、owned emulator 和矩阵临时目录均为零。

## 失败与清理

- 设备数量不为一、serial/fingerprint 漂移或基础设施错误时立即停止 profile。
- 产品场景失败记录固定分类并恢复 clean，不在未知状态上盲目重试。
- runner stop 失败时遵循 N31 固定恢复契约，不执行 `adb kill-server` 或强制删锁。
