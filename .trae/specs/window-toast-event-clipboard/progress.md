# 进度

本文件 append-only。

## Round 1：核实前提

基线 `b0e482f`，worktree `E:/Projects/wt-c4c5`，分支 `window-toast-event-clipboard`。

三项平台前提逐条核实成立：

- `accessibility_service_config.xml:16` 已声明 `flagRetrieveInteractiveWindows`，
  `:14` 已含 `typeNotificationStateChanged`。
- `WindowSnapshotter.kt` 已把 windows 枚举为有界快照（上限 12，按 layer 降序），
  且 `AndroidAccessibilityBackend.kt:124` 已用于扩大候选集合。
- `TransientMessageCapture.kt` 已在事件到达即记录，有界（64）带时间戳，
  并已挂在 `AiAutomationAccessibilityService.kt:45`。
- `AccessibilityRuntime.transientMessages` 早已存在——C5 缺的只有桥方法与协议。
- 判据 kind 实测为 `["node","text","package","window","uiStable"]`。

## Round 2：实施中的三次自造错误与修正

记录下来是因为三次都是同一类错误，值得留痕。

1. **`.Replace()` 把锚点重新插回，后续替换又匹配到自己插入的文本**，导致
   `AiAutomationAccessibilityService.kt` 被写成 468 行、同一个方法体重复 5 次。
   该文件当时只有我自己的改动，用 `git checkout --` 单文件恢复后，改用**唯一锚点**
   （`internal suspend fun captureScreenshot(` 与 `suspend fun captureScreenshot(`
   区分服务类与 Runtime）重做，得到 30 插入 0 删除的干净结果。

2. 同样的问题在 `DebugTestControlPlane.kt` 重演（3 个 `override fun windows`）。
   改用**按行索引插入**而非字符串替换，得到 20 插入 0 删除。

3. Go 侧 `predicate.go` 的锚点用了空格缩进，而文件是 Tab，匹配失败。
   改用正则按行匹配 `^\t\tcase PredicateUIStable:$` 定位。

教训：多行锚点 + `.Replace()` 在同一文件上连续使用是危险组合。后续一律用
行索引插入，并用 `git diff --numstat` 核对插入/删除数是否与预期一致。

## Round 3：一处真实回归

加 `ClipboardGateway` 作为 `AndroidAccessibilityBackend` 的构造参数默认值后，
`AndroidAccessibilityBackendTest` 有一条用例失败：MockK 报
`no answer found for AccessibilityService.getApplicationContext()`。

原因是构造期就调用了 `service.applicationContext`，而大量只关心手势与节点动作的
测试并未桩接该方法。改为 `by lazy` 后通过——这既修了测试，也是更好的设计：
不碰剪贴板的路径不必为剪贴板付出代价。

## Round 4：变异验证

四条否定断言逐个变异确认非空洞：

| 变异 | 预期失败的测试 | 结果 |
| --- | --- | --- |
| 接线 `clipboard.get` | `clipboard get has no bridge entry point` | 如期失败（549 tests 1 failed） |
| `newWindow` 去基线保护 | `TestAwaitNewWindowDoesNotFireWithoutAChange` | 如期失败 |
| `scrollFinished` 去「先动过」前提 | `TestAwaitScrollFinishedRequiresMotionFirst` | 如期失败 |
| 丢弃 `eventTimeMs` | `TestBridgeMessagesPreserveEventTimestamps` | 如期失败 |

全部恢复后复跑通过，且 `git diff --numstat` 确认无残留。

## Round 5：android-verify 的 UP-TO-DATE 陷阱

首次 `make android-verify` 报 `BUILD SUCCESSFUL in 11s`——11 秒说明 testDebugUnitTest
是 UP-TO-DATE，根本没重跑。删除 `android/app/build/test-results/testDebugUnitTest`
后重跑得到 26s 与 74 个全新 XML（549 tests / 0 failures），再逐条核对新增的 7 条
clipboard+ime 与 4 条 parser 用例确实 PASSED。

## 门禁结果

`make comments` 559 文件通过 · `make test` 通过 · `make verify` 协议 56 项通过 ·
`make build` 通过 · `make android-verify` 强制重跑后 549 tests 0 failures。
