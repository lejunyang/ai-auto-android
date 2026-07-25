# N43 Progress

本文件 append-only，记录 WebView 语义兼容实现、测试和设备矩阵证据。

## Round 1

- 已从 `75b0321` 创建独立 change spec 和
  `phase2-n43-webview-replay` worktree。
- 已确认 N42 `3f5c002` 与共享模块集成 `e7a5716` 位于基线祖先链；N42 的 API
  30/33/34 instrumentation 各 3 项通过，但不是 N43 的 20 轮回放证据。
- 修改范围限定为本 change spec、app `recording/webview/` 生产目录和对应 JVM
  测试目录；未修改 recording core、N40 editor、Gradle 或 N42 现有 fixture 文件。

## Round 2

- 测试先行新增 20 个 JVM 场景，首轮按预期因 semantic adapter/domain 尚不存在而
  编译失败；JDK 和 Android SDK 均通过 `/Volumes/aigo S7 Media/Projects/SDK`
  下的显式路径使用。
- 已实现四级能力矩阵，记录 API、WebView 版本、页面、已提供能力、缺失能力和降级
  原因；full、partial、canvas、受限页面分别覆盖完整语义、混合、仅视觉和不支持。
- 已实现只输出 `ui.click`、`ui.setText`、`ui.longClick`、`ui.scroll` 和
  `ui.back` 的 semantic adapter；输出目标只保留 package、selector 和 fingerprint，
  不包含 tap、swipe、bounds 或归一化坐标。
- 动态 DOM、iframe、节点或动作能力缺失统一返回 `SEMANTIC_UNAVAILABLE`；歧义节点
  返回 `SELECTOR_AMBIGUOUS`，不会选择任一候选。

## Round 3

- observation 绑定 `capturedAtMs`、`expiresAtMs`、package、page、API 和 WebView
  版本；过期、未来时间、package/page/runtime 漂移均在动作提交前失败关闭。
- 跨页面 orchestrator 通过注入的 `ReplayGateway`、`WebViewPageIdentity`、
  `WebViewFlowStore` 和 `WebViewResetPort` 完成保存、reset、逐步前置观察、类型化动作
  提交和后置验证。默认在提交前二次观察；内容、页面或 runtime 变化返回
  `OBSERVATION_STALE`。
- 定向命令
  `:app:testDebugUnitTest --tests 'dev.aiauto.android.automation.recording.webview.*'`
  最终 20/20 通过，0 failure/error/skip，其中 adapter 9 项、orchestrator 11 项。
- 完整 `:app:testDebugUnitTest` 219/219 通过，0 failure/error/skip；随后默认二次观察
  安全边界的定向复验再次通过。
- `make comments` 覆盖 208 个手写文件并通过 14 个检查器自测；`git diff --check`
  和禁止 DOM/坐标输出的静态检查通过。
- 未新增或修改 N42 instrumentation。主线程设备矩阵可用 `UiAutomation` 获取的新鲜
  Accessibility 快照接入 `ReplayGateway`，用 `WebViewPageIdentity` 提供当前页面、
  API 和 WebView 版本，并注入 test-only flow store/reset port；每轮必须使用明确
  serial、N31 fingerprint 和 clean snapshot。
- API 30、33、34 各 20 轮尚未执行，设备 checklist 保持未勾选；当前不能宣称 N43
  完成或达到不低于 95% 的设备成功率。

## Round 4

- 新增独立 `N43SemanticReplayDeviceTest` 设备入口，参数 `n43Repeat=1..100`。测试
  复用系统 `UiAutomation` 激活 WebView 虚拟节点，不修改 N42 现有测试或生产
  fixture，也不使用 DOM、JavaScript 注入、shell 或坐标动作。
- 每轮从原生 Reset 开始，逐步通过 Accessibility action 验证点击、输入、长按、
  动态 DOM、iframe、语义滚动、详情跳转、详情动作与全局 Back；每步重新抓取语义树
  并验证页面/状态，始终断言 `NETWORK_REJECTED:0`。
- 测试固定 API 30/33/34 对应 WebView 版本，不匹配时失败关闭；编译
  `:web-fixture:assembleDebugAndroidTest` 通过。三 API 设备执行尚未开始，checklist
  设备项保持未勾选。
