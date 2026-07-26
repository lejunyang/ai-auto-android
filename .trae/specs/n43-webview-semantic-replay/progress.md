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
- API 30 单轮使用明确 serial `emulator-5568` 与固定 N31 fingerprint。点击语义
  动作通过后，WebView 虚拟输入节点未暴露 `ACTION_SET_TEXT`，测试在输入步骤明确
  返回失败；未尝试坐标、剪贴板、DOM/JavaScript 或 shell 降级。
- runner 已停止 `emulator-5568`，最终设备、emulator、runtime 和 lease 均为零。
  API 33/34 与 20 轮矩阵未启动；N43 保持未完成，后续需先定义不违反安全边界且由
  该 WebView 版本真实支持的类型化输入 route。

## Round 5

- 独立 capability probe 更正了 Round 4 的输入结论：API 30/33/34 上唯一 WebView
  `EditText` 均为 editable，并同时暴露 `ACTION_CLICK` 与 `ACTION_SET_TEXT`。
  先前按 `aria-label` 查找失败是 selector 错误，不是 API 30/WebView 91 缺少输入
  action；设备场景改为唯一 editable + `ACTION_SET_TEXT` 后三版本单轮均输入成功。
- fixture 增加 `LOAD_GENERATION`，reset、跨页导航和 Back 均等待可信本地页面完成，
  排除了旧 ready 状态与陈旧虚拟节点竞态。测试宿主通过 AndroidX
  `OnBackPressedDispatcher` 接入 WebView 历史，typed global Back 在 API 30/33/34
  均返回主页面，并满足 target API 36 的预测性返回 lint 契约。
- 三版本单轮均完成 click、input、dynamic DOM、scroll、详情导航与 Back，但仍不是
  full semantic。long-click 目标不含 `ACTION_LONG_CLICK`；iframe 与详情 click
  只提交一次且返回成功，更新后的后置文本却不进入语义树。场景稳定报告 long-click、
  iframe postcondition 和 detail postcondition 三项 hybrid-required。
- 降级不使用坐标猜测、ADB text、剪贴板、IME shell 或 DOM/JavaScript：long-click
  只能交给 N45 已授权且可验证的 typed visual action；iframe/详情仅允许授权视觉
  后置验证，不能重复点击。无可信 observation 或结果不明确时失败关闭。
- 三版本场景和只读 probe 均各 1/1 通过，最终设备、owned emulator、runtime 和
  AVD/port lease 为零。尚未执行每版本 20 轮以及 N45 真实视觉混合闭环，因此 N43
  checklist 与公共路线图保持未勾选。

## Round 6

- fixture Back 接线因 target API 36 的 `GestureBackNavigation` lint 错误迁移到
  AndroidX `OnBackPressedDispatcher`；未使用 suppression/baseline。模块完整
  `test + lint + build` 88 tasks 通过，覆盖 debug、androidTest 和 release。
- 迁移后 API 30 与 API 33 单轮各 1/1 通过。API 34 首轮的基础 click 返回成功但
  新语义树仍为 ready，按结果未知规则停止并清理；新的 clean snapshot 复跑通过，
  因此 API 34 本轮实际为 1/2，不可描述为确定性兼容。
- 该失败与 long-click/iframe 的稳定能力边界不同：它是 WebView 113 基础 click
  后置的偶发波动，目前只能依赖每步新观察失败关闭，不能自动重复可能已提交的 click。
  后续必须完成每版本 20 轮并统计 flaky，达不到 95% 时应将对应 profile 降级而非
  勾选 N43。最终设备、runtime、lease 和 owned emulator 再次为零。
