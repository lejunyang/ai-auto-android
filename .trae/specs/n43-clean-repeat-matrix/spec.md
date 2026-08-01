# N43 Clean Snapshot 重复矩阵规格

## 目标

在 N31 API 30、33、34 固定 emulator 上，将现有
`N43SemanticReplayDeviceTest` 按每轮独立 clean snapshot 连续运行 20 次，获得
成功率、耗时和失败分类证据，而不是用同一 App 进程内的 `n43Repeat=20` 代替设备
隔离。

## 范围

- 每次 runner 调用只接受固定 `api-30|33|34` profile，不接受 serial、测试类、
  Gradle task、round 数、APK 路径或任意命令。
- 固定构建 `:web-fixture:assembleDebug` 与
  `:web-fixture:assembleDebugAndroidTest`。
- 每轮先通过 N31 runner 恢复 `clean` snapshot，验证 serial、profile fingerprint
  和唯一在线 emulator，再运行固定 `N43SemanticReplayDeviceTest` 一次。
- 每轮解析本次新生成的 JUnit XML，严格要求一个指定 test case；不以 Gradle 退出码
  单独判定通过。
- instrumentation 失败记录 `product-failure`，缺失或歧义结果记录
  `infrastructure-failure`；下一轮必须先恢复 clean snapshot，不在已提交动作的页面
  重试。
- 报告只保留 profile、serial、fingerprint、round、耗时、状态和固定分类，不保存
  Gradle stdout/stderr、stack、页面文本、hierarchy、截图或动作参数。
- 最终恢复 clean snapshot、停止 owned emulator，并确认设备、runtime、lease 与
  runner 临时目录为零；报告位于外置 emulator state 且权限 `0600`。

## 非目标

- 不修改 N43 生产 fixture、语义动作类型、WebView DOM、JavaScript 或视觉降级。
- 设备 instrumentation 只允许增加动作提交前的只读稳定观察门；不得重放已提交动作，
  不得修改生产 `MainActivity`、HTML/JavaScript 或动作后置等待语义。
- 不把当前 long-click、iframe/detail postcondition 的 hybrid-required 分类描述为
  已完成 N45 混合闭环。
- 不把单 profile 或少于 20 轮的结果描述为 N43 完成。
- 不自动重放结果未知的语义 click，不使用坐标、ADB text、剪贴板或 shell 降级。

## 允许修改

- `.trae/specs/n43-clean-repeat-matrix/`
- `scripts/webview-semantic-matrix/`
- `android/web-fixture/src/androidTest/java/dev/aiauto/webfixture/N43SemanticReplayDeviceTest.kt`
- `android/web-fixture/src/test/java/dev/aiauto/webfixture/ResourceContractTest.kt`
- `.trae/specs/n43-webview-semantic-replay/`
- `.trae/specs/n43-webview-capability-probe/`
- `docs/next-phase-tasks.md`

## 验收

- fake 端口覆盖固定参数、每轮 restore、结果 XML 新鲜度、19/20 阈值、产品/基础设施
  失败分类、stop 锁保留单次重试和最终零设备门。
- API 30、33、34 各运行恰好 20 轮，每轮从 clean snapshot 开始。
- 每 profile 成功率不低于 95% 才可勾选对应 N43 重复矩阵项；低于门槛必须保留失败
  统计并继续保持 N43 未完成。
- 运行 Web fixture 定向/构建、runner tests、comments、diff-check 和仓库相关验证。

## 失败与清理

- restore、serial/fingerprint 漂移或多设备立即停止，不继续剩余轮次。
- 单轮测试失败只记录固定分类；下一轮恢复 clean 后继续，不盲目重放同一动作。
- stop 未确认时只对 `STOP_FAILED_LOCK_RETAINED` 做一次 N31 固定重试；再次失败保留
  runtime/lease，不手工删锁或执行 `adb kill-server`。
