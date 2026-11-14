# 过期文档审计进度

本文件 append-only。核查以基线 `984ee31` 的本地代码、测试与 git 历史为准；日期
2026-11-14。后续行号若因文档插入变化，以此基线定位原断言。

## D1：开工与规格

- `git status --short --branch`：`## docs-stale-audit`，无未提交改动。
- `git worktree list --porcelain`：识别主工作区及 docs、vdisplay MCP、virtual display
  三个独立 worktree；本任务只在 `E:/Projects/wt-docs-audit` 写入。
- 已读 `AGENTS.md`、`docs/README.md`、根 README 与 `ime-show-focus` 的四份规格
  文件，按其目标、范围、任务、验收和 append-only 进度格式建立本规格。
- 目标清单共 27 份 `docs/*.md`；既有规格共 140 份 `spec.md` / `tasks.md`，均只读。
- 外部并行所有权排除：根 README、`docs/{virtual-display,protocol,architecture}.md`、
  `skills/`。本轮不运行任何设备操作。

## D4：历史文首更正的逐条证据

以下文档按实测/选型记录处理（早期摄像头记录未标统一日期，以 `70013a7` 收录正文定位）。除 `virtual-camera-selection.md` 的选型状态表直接更新外，只插入文首更正，不改历史正文。相同断言在同文反复出现只计一次；每个 H 编号为一组明确列出的结论，原子断言数量另见汇总。

| 编号 | 文档 | 更正结论 | 证据索引 |
| --- | --- | --- | --- |
| H01 | emulator-ui-automation.md | “自动预置零命中、test-control 只有 README”已不适用：N32 core/debug 实现、`scripts/emulator/provision-bridge.mjs` 和 N47 授权 provider 均已存在。N47 endpoint 设备闭环仍阻塞，不能据此声称整套矩阵通过。 | E02 |
| H02 | emulator-ui-automation.md | 录屏、前台包与应用清单均已有 CLI/MCP 入口，见 `internal/adb/observe_extra.go` 的 `RecordScreen` / `Foreground` / `InstalledApps` 和[后续观察记录](../../../docs/observe-capability-extension.md)。 | E03 |
| H03 | emulator-ui-automation.md | 等待/断言已有 `check wait\|assert` 与 `android_wait` / `android_assert`；长按/滚动已有直连出口，元素级动作还可走 Bridge，见[判据记录](../../../docs/interface-predicates-and-bridge.md)及[手势记录](../../../docs/gestures-lint-and-camera.md)。 | E04 |
| H04 | emulator-ui-automation.md | Unicode 输入已有 CLI `bridge set-text` 与 MCP `android_bridge_set_text`；直连 ASCII 约束仍保留。多指手势已由动作面规格提供 schema、router/backend 和设备派发证据，见[动作面追踪](../../../docs/action-surface-tasks.md)。 | E05、E06 |
| H05 | emulator-ui-automation.md | “直连 UI 树无预算”不成立：`internal/adb/direct.go:31,170-174` 有 8 MiB 输出上限、超时与截断拒绝。它不等于节点分区下钻能力。 | E07 |
| H06 | emulator-ui-automation.md | 相机后续实测已区分固定文件/预录序列与运行时新 payload：覆盖文件不能热换码，Windows OBS/Unity DirectShow 路线被否定；Linux/MF 仍待验，见[当前摄像头结论](../../../docs/camera-virtual-stacks.md)。 | E08 |
| H07 | observe-capability-extension.md | 文中的“工具数 8”是当轮计数；本轮基线 `ToolNames` 已为 16。等待/断言与长按/滚动均有 MCP 出口，见 `internal/mcpserver/server.go`、`risk.go`。 | E01、E04 |
| H08 | observe-capability-extension.md | Unicode 输入已由 `bridge set-text` / `android_bridge_set_text` 交付；“写 secure settings 后是否绑定”和桥侧 foreground 未实测由[后续判据记录](../../../docs/interface-predicates-and-bridge.md)闭合，测试预置代码也已存在。 | E02、E05、E09 |
| H09 | observe-capability-extension.md | 本记录中的 watch flaky 已由后续工作调整并保留 `TestWatchDevicesReportsInitialAndChangedMDNSEvents`；原失败是当时结果，不表示当前仍失败。 | E09 |
| H10 | observe-capability-extension.md | videofile/webcam 的后续取证见[通道盘点](../../../docs/camera-channels-and-text-input.md)、[webcam 实测](../../../docs/mcp-text-input-and-webcam.md)及[摄像头结论](../../../docs/camera-virtual-stacks.md)；不得再将 OBS 安装视为待确认的可行解。 | E08 |
| H11 | interface-predicates-and-bridge.md | 判据选择器的 text/description 已由 `0976f13` 统一为 normalize 后精确匹配；本记录 §1.3 的默认“包含”解释已被替换。显式 `operator=contains` 仍存在。桥侧 `match` 扩展不能套用到 Go `check` 的扁平选择器。 | E10 |
| H12 | interface-predicates-and-bridge.md | 当轮 MCP 10 工具已扩为 16；长按/滚动及 Unicode 输入已有出口，不再是 §5.1 的未实现项。 | E01、E04、E05 |
| H13 | interface-predicates-and-bridge.md | 测试预置已有 N32/N47 实现，早期 UI 预置脚本仍在，但新 UI 改版后其旧文案路径未重测；设备权限与 test-only 边界仍按 AGENTS.md。Instantiatable 的后续处理见[手势与 lint 记录](../../../docs/gestures-lint-and-camera.md)。 | E02、E09、E11 |
| H14 | interface-predicates-and-bridge.md | 运行时换码候选已继续实测；Windows DirectShow 路线已否定，Linux/MF 未验，见[摄像头结论](../../../docs/camera-virtual-stacks.md)。 | E08 |
| H15 | gestures-lint-and-camera.md | 中文输入、元素级长按/滚动和多指手势已有实现，分别见[输入记录](../../../docs/mcp-text-input-and-webcam.md)、`internal/mcpserver/bridge_action.go` 与[动作面追踪](../../../docs/action-surface-tasks.md)。直连 ASCII 限制和多指需 Bridge 的边界仍保留。 | E04、E05、E06 |
| H16 | gestures-lint-and-camera.md | §4 的 videofile “换码成立”仅适用于启动前确定的码序列；[后续覆盖实验](../../../docs/camera-channels-and-text-input.md)已证明不能换成运行时才产生的 payload。Windows DirectShow 虚拟相机也被后续实验否定，见[摄像头结论](../../../docs/camera-virtual-stacks.md)。 | E08 |
| H17 | gestures-lint-and-camera.md | `provision-bridge.mjs` 保留早期“桌面桥/无障碍执行”等 UI 查找文案，当前页面已改为“连接电脑/允许操作的应用”。本轮未运行设备，不把此记录的成功外推为脚本当前仍可用。 | E11 |
| H18 | camera-channels-and-text-input.md | §4-6 的“中文输入无替代/建议先补桥”已由 CLI `bridge set-text` 与 MCP `android_bridge_set_text` 交付，错误信息亦已指向 Bridge。直连字符白名单仍保留。 | E05 |
| H19 | camera-channels-and-text-input.md | “多指协议也没有”和“桥侧长按/滚动无元素级版本”已过期；见[动作面追踪](../../../docs/action-surface-tasks.md)及 MCP `android_bridge_action`。 | E04、E06 |
| H20 | camera-channels-and-text-input.md | 实体 webcam 接入后已有实测，OBS/Unity DirectShow 则被后续取证否定；Linux/MF 仍待验。摄像头结论以[当前状态入口](../../../docs/camera-virtual-stacks.md)为准，debug 帧注入建议未据此变为已实现。 | E08 |
| H21 | text-input-and-virtual-camera.md | “MCP 未暴露 setText、只有 CLI”已由 `android_bridge_set_text` 改变；当前可写非敏感 Unicode，见[后续 MCP 实测](../../../docs/mcp-text-input-and-webcam.md)及 `internal/mcpserver/server.go`。`android_action_execute` 的直连 ASCII 限制仍成立。 | E05 |
| H22 | text-input-and-virtual-camera.md | 宿主 OBS/Unity 路线已实测否定为 Windows DirectShow 枚举不兼容；不能沿用本记录“装 OBS 待确认”的推荐，见[摄像头结论](../../../docs/camera-virtual-stacks.md)。 | E08 |
| H23 | mcp-text-input-and-webcam.md | tools/list 的 11 是当轮数量；当前基线 `ToolNames` 为 16，不能照抄为现有工具清单。 | E01 |
| H24 | mcp-text-input-and-webcam.md | “本机没装软件/缺一个 OBS 或 Unity Capture 就能换码”的后续实验已完成且失败：两者无 PnP 实例，emulator 不枚举。未验的是 Linux v4l2loopback 和 Windows MF，见[后续取证](../../../docs/host-virtual-camera-verdict.md)与[当前结论](../../../docs/camera-virtual-stacks.md)。历史安装状态不是本轮宿主清点结果。 | E08 |
| H25 | virtual-camera-selection.md | 本文件是早期选型记录，Windows 推荐 OBS/Unity 的结论已被[DirectShow 实测](../../../docs/host-virtual-camera-verdict.md)推翻；统一 pyvirtualcam API 不能证明 emulator 可枚举后端。当前路线见[两套 Windows API 与待验入口](../../../docs/camera-virtual-stacks.md)。 | E08 |
| H26 | virtual-camera-selection.md | §7 的“OBS 未安装/未注册”是当时环境：后续记录已有安装与枚举失败证据；当前宿主是否仍保留这些软件未核实，不能按旧表重复安装或清理。 | E08 |
| H27 | virtual-camera-selection.md | “软件装完后换码 <1秒”的成本表是未通过端到端验证的方案估计，不是交付性能；Linux/MF 的枚举、Android 相机数与两次新 payload 仍需实际验收，见路线图 N55。 | E08 |
| H28 | unity-capture-verdict.md | 标题与正文“根因是像素格式”已被后续 OBS 对照实验否定：OBS 提供 NV12 仍不被枚举，决定性差异是 DirectShow 无 PnP 实例；Unity 的 bgr24 观察保留，不能当作已证实根因。 | E08 |
| H29 | unity-capture-verdict.md | “OBS 未验、最有希望/下一步装 OBS”已过期；完整实测见[宿主 verdict](../../../docs/host-virtual-camera-verdict.md)，当前未验路线见[摄像头结论](../../../docs/camera-virtual-stacks.md)。历史宿主安装与 Python 版本未在本轮清点。 | E08 |
| H30 | review-round-10.md | 本记录中的“API30-33 待完成、Task21/23 未完成”已由[Round 11](../../../docs/review-round-11.md)的 API33 真实 Engine 停止证据收口（`d64cca1`、`dbc7384`、`835f931`）；不表示 API30/31/32 每个版本都有同类设备证据。 | E12 |

## D2：证据索引（当前实现、测试与提交）

以下行号指代码基线 `984ee31`，测试名表示已读取断言，不表示本轮执行了该测试。
所有引用的功能提交均已用 `git merge-base --is-ancestor <hash> 984ee31` 核实。
历史运行次数、截图 hash 和设备状态只沿用仓库已记录证据，不重新声称当轮实测。

| 索引 | 具体证据与边界 |
| --- | --- |
| E01 | `internal/mcpserver/server.go:55-71` 的 `ToolNames` 为 16 项；`TestReadmeToolCountMatchesRegisteredTools` 所在 `readme_toolcount_test.go` 将 README 与实际集合绑定，`server_test.go:36` 核对注册名单。脚本直接读取列表计数，未启动 MCP 或设备。 |
| E02 | N32 `spec.md` / `tasks.md`（`26865fe`）、`android/test-control/core/` 的 Java 安全内核与 debug Kotlin adapter、`scripts/emulator/provision-bridge.mjs:317`、N47 `n47-test-authorization-provider`（`5815b42`）。N47 后续 `fe52a8f` 与 `n47-production-fixture-device-fixes/progress.md` Round 3/4 明确保留 API30 endpoint 阻塞、API33/34 未启动及 N47/N52 未完成。 |
| E03 | `internal/adb/observe_extra.go:131,181,315` 分别实现 Foreground、InstalledApps、RecordScreen；`internal/cli/direct.go:114` 起分流观察扩展；MCP `server.go` 注册 `android_screen_record` 和 foreground/apps；`TestForegroundParsesResumedActivity`、`TestRecordScreenRejectsInvalidOptions`。 |
| E04 | `internal/cli/check.go:23-41` wait/assert/scroll-until；`internal/mcpserver/server.go:537,557` 注册 wait/assert；`risk.go:35-42` 允许 longClick/scroll；`internal/cli/direct.go:369` scroll；`bridge_action.go:43-48,159` 为元素动作白名单。`7b13abe` 与 `93ce93b` 是相应交付提交；`TestBridgeActionRoutesAllowlistedSelectorActionsToService` 与 `TestBridgeActionRejectsActionsOutsideItsAllowlist` 锁定范围。 |
| E05 | `9990d86` 同时修改 CLI、MCP、Bridge text 与服务层；`internal/service/bridge_text.go:38`、`internal/bridge/text.go`、`server.go:33-36` 的 `android_bridge_set_text` 接非敏感 Unicode。`docs/mcp-text-input-and-webcam.md` §1.5 保存中文回读证据；直连 `directText` 正则和错误指路仍存在。 |
| E06 | `action-surface-expansion/spec.md` / `tasks.md` 与 `docs/action-surface-tasks.md` E4/E18/E25；当前 `AccessibilityCommandJsonParser.kt`、`AccessibilityModels.kt`、`AccessibilityActionRouter.kt`、`AndroidAccessibilityBackend.kt` 有 pinch/rotate/twoFingerScroll、多 stroke 路径。设备证据仅对应 API30/33/34/35 与记录内的派发/后置范围，不意味着 MCP 白名单包含这些动作。 |
| E07 | `internal/adb/direct.go:31` 的 `maxHierarchyOutput=8*1024*1024`；`:170-174` 使用 10 秒/8 MiB 预算并拒绝 OutputTruncated/超额。不是 XML 分区下钻实现。 |
| E08 | 历史摄像头正文收录提交 `70013a7`；`camera-channels-and-text-input.md` §1 的覆盖视频 40 秒仍旧码；`mcp-text-input-and-webcam.md` §2 的实体 webcam；`host-virtual-camera-verdict.md` §1/2 的 OBS NV12 对照和 PnP 枚举；`camera-virtual-stacks.md` §3/5/6 汇合结论。仅能证明记录中的 Windows/版本实验；Linux/MF 与当前宿主安装状态未核实，未检查仓库外探针。 |
| E09 | `interface-predicates-and-bridge.md` §2/3 保存 watch 修复及真实 Bridge foreground 证据；当前 `internal/adb/watch_test.go:34,67-75` 为 `TestWatchDevicesReportsInitialAndChangedMDNSEvents`，上限为 10。`gestures-lint-and-camera.md` §1 保存后续 lint 修复；没有把历史构建次数替换为本轮结果。 |
| E10 | `0976f13`、`internal/adb/predicate_test.go:219` 的 `TestSelectorMatchesUsesNormalizedEqualityForAllDimensions`、`:446` 的跨侧一致性测试；`predicate.go` 的 normalize 后精确比较与独立 OperatorContains。`selector-consistency-c12-c10/tasks.md` A/B/C/D 全勾选。 |
| E11 | `provision-bridge.mjs:150-261` 仍按旧页面文案查找；`consumer_ui_strings.xml:100,104` 的现页面为“允许操作的应用/连接电脑”，`AccessibilityScreen.kt:116-135` 是应用开关直接保存披露，已无旧“包名输入+勾选”流程。脚本是否仍可运行未核实，只登记兼容性风险，不改脚本。 |
| E12 | `review-round-11.md` 与 `validation.md` 末尾 Round 11；`d64cca1`、`dbc7384`、`835f931` 的真实 Engine 自检、超时拆分与同步点；既有 MVP tasks 已关闭 Task21/23。本轮不重写这些规格，也不扩展为 API30/31/32 逐版本设备通过。 |
| E13 | `RecordingModels.kt:12-13,36-50,111-123` 为 schema1.1、revision、provenance、visualTarget/waitBefore 等；`RecordingScriptStore.kt:141-157,206-257,804-835` 为 CAS、导入导出与迁移，包含 pre-versioned/0.9/1.0 到1.1。`RecordingScriptStoreTest` 的 `version 1_0 is migrated without losing existing fields`、`stale revision returns both versions without overwriting current data`、`default export removes secrets screenshots and device local paths`；提交 `6290c36`。 |
| E14 | 文件 CLI `internal/cli/cli.go:161,290`；`file_transfer.go:32,35,52,94` 的 MCP 名称、独立门与注册；`device_state.go:339-357,383-406,428-439` 的存在性检查与 TOCTOU；`4da5182`、`008cf40`。测试 `TestFileTransferRefusesToOverwriteOrDelete`、`TestMCPFilePushIsBlockedByTheServiceLayerOverwriteGuard`、`TestMCPFilePullIsBlockedByTheLocalOverwriteGuard`。旧测试名仅在接替说明注释中出现，无旧测试定义。 |
| E15 | `6843cf5`；`internal/bridge/service.go:228,244`、`AndroidBridgeMethods.kt:245-246,265,306` 为 ui.windows/ui.transientMessages；`server.go:50-51` 的 kind=windows/transientMessages；`AccessibilityCommandJsonParser.kt:192-194` 只接 clipboard.set、刻意不接 get；`TestBridgeMessagesPreserveEventTimestamps`、parser 测试 `clipboard get has no bridge entry point`。MCP `bridge_action.go:159,204-210` 的 enum/description 仍排除 clipboard，没有独立写入门；不能按 spec 设想宣布已实现。 |
| E16 | `841b7a9`；`internal/adb/gesture.go:199-296` 的 ScrollUntil 先取基线，每轮滚动后求值，耗尽返回 satisfied=false；`predicate_test.go:527,562,591` 命中、上限与越界测试；`predicate.go:37-38` 事件 kind 与首轮/变化保护。`36e4d11`；Schema `action.schema.json:151,167-169` 为8策略/5种match，Kotlin `SelectorMatcher.kt:235,250,362` 为 regex 执行与超时；Go NodeSelector 没有 match 字段。ReplayEngine 的 evaluateNode/package 未实现事件型判据。 |
| E17 | `ime-show-focus` 与 `mcp-file-and-ime-show` spec/tasks；`AccessibilityActionRouter.kt:941` 聚焦，测试 `ime show focuses the input control through a node action`、`ime show fails when no input control can be focused`、`ime show result does not claim keyboard visibility`；CLI `bridge.go` 的 ime-show、`TestBridgeImeShowRoutesThroughTheBridgeChannel`，MCP `server.go:610`；`008cf40` 补出口，未补设备证据。 |
| E18 | `d33eca8`、`74be592` 与 `virtual-display-screen-off/tasks.md` / progress：CLI/helper 与 API35 连续三次冷启动已记录；`internal/cli/display.go`、`internal/adb/display.go` 对应实现。当前基线不含并行 vdisplay MCP 分支；N60 仍未验收。Schema 遍历 oneOf/const 得46种（device.sleep 是直连实现，未把它强算进 Schema）。 |
| E19 | N40/N41 spec/tasks（`8180ab6`、`d6f85e2`、`19ee765`）；`RecordingHost.kt` 默认 observation provider=null，`RecordingEditorScreen.kt` 的步骤/Undo/Redo/dry-run 与领域命令；`ReplayEngine.kt:266-318,383-461` 的 fresh visual run、单次执行与授权失败关闭（`18e50a6`）。当前 UI 无外部文件导入导出按钮，领域 import/export 已有。`internal/mcpserver/risk.go` 的 requireReplayConfirmation 总是返回 CONFIRMATION_REQUIRED。 |
| E20 | `AppTokens.kt:24` 为0xFFFAFBFC，`Theme.kt:31` 消费 Background；UI追踪的2026-09-18 P0背景修正、运行时推进、verify-round12 与2026-09-19 演练场证据。`SessionHistoryStore.kt:69,191-193` 有界持久化，`SessionHistoryRecord.kt:5-15,47` 固定摘要/不存 audit全文；`840c3e3` 和 SessionCoordinator 对应回归锁定异步时间快照。 |
| E21 | `AndroidSessionAdapters.kt:61-92` 显式截图授权后传 screenshotPng/visualObservation；`OpenAICompatibleProvider.kt:189-193` 构造 image data URL；`AutomationSessionEngine.kt:405-406,450` 内存 audit 上限100。N44 `1682806` 与 N45 `383c60a`，不把 Provider 语义摘要误写成唯一数据来源。 |
| E22 | `AutomationSessionEngine.kt:202,354`、`AutomationRiskPolicy.kt:138`、`LocalDemoProvider.kt:42` 的 task.finish 实际职责；不再依赖容易漂移的引用总数或旧文档行号。 |
| E23 | `internal/cli/lan.go:523-525` 只接受 device.info/recording.list；N36/N38、`n37-n38-cross-runtime-rpc` 与 `real-device-acceptance` spec/tasks，`f5772cc`/`667c7b5` 保存 API34 扫码/RPC 记录。`n52-production-matrix-ports/tasks.md` 真实300轮未勾选，N48-N51 各 spec/tasks 真实轮次/样例未验收；没有用旧核心规格的全勾选覆盖后续设备缺口。 |
| E24 | `rg --files docs -g '*.md'` 得27份，导航原仅12个 Markdown 目标；当前导航除自身外覆盖26份。本轮只读扫描相对链接与本地路径；没有获取外部网站、远端 Actions 或仓库外历史截图。 |

## D3：活文档逐条改动登记

计数口径：一行是同一能力或状态的过期断言组，同文重复位置不重复计数；导航补漏和
诚实标注未核实也单独说明。下表 R 编号与上表 H 编号覆盖全部语义改动。

| 编号 | 文档/原位置 | 原断言与处理 | 证据 |
| --- | --- | --- | --- |
| R01 | docs/README.md 导航/状态说明 | 范围仍为N31-N54，且已有文档未收录；改为N31-N60并补14个入口、标历史性质与MVP后独立规格 | E24、路线图任务标题 |
| R02 | next-phase-tasks.md:3-16 | “唯一源N31-N54/当前2026-08-01”不再反映后续交付；保留旧阶段记录，新增基线汇总，不改历史验收 | E13-E23与下方逐任务表 |
| R03 | next-phase-tasks.md:33-39 | “最新run/远端仍停留5dcc70f”只有当时证据；明确为2026-08-01历史远端快照，当前远端未核实 | E24；未访问远端 |
| R04 | next-phase-tasks.md N57.1 | “窗口集合不过线”改为独立ui.windows及CLI/MCP；保留ui.snapshot单root，设备待验 | E15 |
| R05 | 同上 N57.2 | “没有瞬时消息方法”改为ui.transientMessages与真实kind、eventTimeMs；设备待验 | E15 |
| R06 | 同上 N57.3/验收 | “ClipboardGateway零生产调用”改为桥侧写入已接、读取无出口；实际MCP写入门也未落地，验收不依赖未开放get | E15 |
| R07 | 同上 N58.1 | “无法滚动直到判据成立”改为ScrollUntil及CLI/MCP，设备验收待补 | E16 |
| R08 | 同上 N58.2 | “待加事件条件”改为Go轮询已支持，区别于真实事件流与App replay | E16 |
| R09 | 同上 N58.3 | “只有6策略/无StartsWith等”改为8策略、桥侧5种match；hint/结构/深度余项保留 | E16 |
| R10 | 同上 N59.1 | “文件传输无任何出口/旧测试锁定”改为CLI/MCP对应规格与提交、现存测试、TOCTOU及主机落盘边界 | E14 |
| R11 | 同上 N59.8 | 去掉旧引用数和漂移行号，保留task.finish归属与真实调用符号 | E22 |
| R12 | 同上 N59括注 | ime.show补CLI/MCP后续指向，仍不保证键盘可见性 | E17 |
| R13 | 同上 收口清单T3 | “七项全未实施”改为六项，T3 CLI已交付，MCP/兼容性仍见N60 | E18 |
| R14 | 同上 收口清单能力覆盖 | “29个桥侧分支”不能代表MCP元素动作范围，改为独立白名单 | E04、E15 |
| R15 | 同上 N58验收 | “maxScrolls耗尽零提交失败”与循环相悖，改为已滚动后satisfied=false、到上限停止 | E16 |
| R16 | action-surface-tasks.md:11-14 | 14项/两类原语是立项背景，明确当前46种Schema动作与各入口子集 | E06、E18 |
| R17 | 同上 A3 | E10只验scrollIntoView，不能算后来判据型ScrollUntil设备验收；分开范围 | E16 |
| R18 | 同上 A12/§11 | “ime.show未实现/无法实现”区分为确定性显示不保证、聚焦已实现有出口但设备待验；E12原始实测不动 | E17 |
| R19 | 同上 A13 | 平台ClipboardGateway读写证据不等于Bridge读取出口；写入已接、get/MCP仍不可用 | E15 |
| R20 | 同上 A15 | 底层往返与后续存在性保护/出口分别说明，保护真机证据不冒充已通过 | E14 |
| R21 | 同上 状态定义之后 | “三层交付”不能推断全部动作都可MCP调用；补当前白名单子集边界 | E04、E06、E15 |
| R22 | alternatives.md 状态表 | UIAutomator/农场一起“未实现”改为本地控制面已交付、N52待验、农场未实施 | E02、E23 |
| R23 | 同上 状态表 | “视觉层未实现”拆出既有Accessibility/ADB/N44/N45与未实现MediaProjection/OCR | E06、E19、E21 |
| R24 | 同上 LAN/P1/演进7 | “条件满足再实现LAN”改为邀请/加密/扫码已实现、跨平台及持续控制待补 | E23 |
| R25 | 同上 下一阶段/P0/演进顺序 | 整节“尚未实现/现在做”将已交付编辑器/fixture/视觉继续当缺口；补各阶段已交付与剩余工作 | E13、E19、E23 |
| R26 | 同上 视觉接口接入点 | “不新增视觉点击”的旧方案不能遮蔽现有原子视觉方法；写明fresh attestation/确认边界及OCR未实现 | E19；`21c2a78` |
| R27 | recording.md 前提/使用 | “仅编辑名称/填写目标包”改为选应用与真实步骤编辑能力；截图默认不展示 | E11、E19 |
| R28 | 同上 模型 | schema1.0改为1.1并列实际新增字段 | E13 |
| R29 | 同上 持久化/迁移 | 仅临时替换/迁移到1.0改为文件锁、CAS、备份/原子替换及到1.1 | E13 |
| R30 | 同上 路由/适用范围 | 增加显式视觉步骤的fresh rebind，不能把语义预检失败回退与视觉授权路径混同 | E19 |
| R31 | 同上 Secret | “CLI/MCP都是单步SECRET_REQUIRED”改为CLI逐步失败、MCP在执行前CONFIRMATION_REQUIRED | E19 |
| R32 | 同上 导入限制 | “只查版本和局部约束”改为严格领域import/export已有，Compose文件入口仍无 | E13、E19 |
| R33 | consumer-agent-ui-tasks.md 顶部/主题 | 活汇总旧背景EEF1F5改为paper FAFBFC；日志/截图按各自日期基线保留 | E20 |
| R34 | 同上 模块A | 不再只写单测：LocalDemo历史设备证据与后续异步时间修复均已存在；不扩展到真实Provider | E20 |
| R35 | device-usb-acceptance.md §5 | 包名输入/checkbox旧流程改为已安装应用开关保存披露，系统授权仍人工 | E11 |
| R36 | 同上 §6 | 桌面桥旧入口名改为“我的→连接电脑” | E11 |
| R37 | 同上 §7 | 操作录制/填写包名改为技能加号、录制新流程与应用选择 | E11、consumer_ui_strings.xml:300起、RecordingSessionScreen.kt |
| R38 | troubleshooting.md Bridge/文本 | 修正旧桌面桥入口；补Unicode的CLI/MCP指路，原直连ASCII限制未被放宽 | E05、E11 |
| R39 | security-distribution.md UI数据/Provider | “Provider只接收语义摘要”改为显式授权时也传最小图像与观察元数据 | E21 |
| R40 | 同上 会话audit | 保留内存100条audit事实，补持久化固定摘要历史，区别于合规审计 | E20、E21 |

## D2/D3：逐文档断言清单与汇总

范围为全部27份 `docs/*.md`（包含3份只读），根README另列移交。数量按上述证据组计，
不是字面句子出现次数。0表示未发现需更正的当前仓库断言，不表示重新执行了历史实测。
选型状态表是活文档，直接修改；verdict/评审/实测正文只加文首说明。

| 文档 | 主要现状断言（同能力的重复表述合并） | 过期断言组数 | 处理/证据 |
| --- | --- | ---: | --- |
| README.md | 导航覆盖、N31-N54状态范围、MVP规格是唯一后续约束 | 1 | 改正/补导航，R01/E24 |
| action-surface-tasks.md | 14项原语背景、A3滚动、A12 IME、A13剪贴板、A15文件、三层交付范围 | 6 | 改正，R16-R21；A4、CLEAR_FOCUS、原始E1-E25与矩阵结论保留 |
| alternatives.md | 本地UIAutomator未实现、视觉未实现、LAN待立项、整节均未实现、视觉执行接口 | 5 | 改正，R22-R26；Node CLI/Shizuku/DPC/getevent/互联网/农场/scrcpy仍无交付，见对应源码目录与N53/54状态 |
| architecture.md | 六工具、无watch、无截图Provider/仅语义、三种观察等 | 6 | 只读移交P03-P08；其余信任边界对照service/Bridge/Session源码保留 |
| camera-channels-and-text-input.md | 中文最大缺口、多指三层皆空/元素动作无、webcam待验 | 3 | 文首更正H18-H20；video覆盖失败/私有帧注入未实现保留 |
| camera-virtual-stacks.md | DirectShow失败、Linux/MF未验、无产品喂帧出口；宿主安装状态 | 0 | 历史相机结论与E08一致；当前宿主状态未核实，增加日期/范围说明U01 |
| consumer-agent-ui-tasks.md | 当前主题颜色、历史持久化仅单测；语音/通知/锁屏/编辑/Provider设备缺口 | 2 | 改当前汇总R33-R34；所有带日期日志与未闭合流程保留，E20 |
| control-assessment.md | LAN仅探针、未接service/MCP持续控制；连接代次、按serial会话、Windows ACL未验 | 0 | 2026-10-17基线评估正文保留；`lan.go:523`、bridge store/client及E23仍支持这些边界 |
| device-usb-acceptance.md | 授权页包名输入/checkbox、桌面桥与录制入口名称 | 3 | 改正R35-R37；RSA/系统权限人工、显式serial、Secret与清理要求仍成立 |
| emulator-ui-automation.md | 自动预置零实现、观察/判据/输入/多指缺口、无UI预算、相机候选 | 6 | 文首更正H01-H06；模拟器transport/root镜像条件为原环境事实，未外推 |
| gestures-lint-and-camera.md | 中文/桥元素动作/多指缺口、video运行时换码、预置旧UI兼容 | 3 | 文首更正H15-H17；旧构建计数/设备坐标不改，当前脚本可用性未核实 |
| host-virtual-camera-verdict.md | OBS/Unity DirectShow不枚举、格式非根因、Linux/MF未验 | 0 | 记录已经收窄结论，与E08一致，保留正文；宿主软件状态只作历史 |
| interface-predicates-and-bridge.md | 默认text包含、10工具/长按滚动/Unicode缺口、预置/lint状态、相机候选 | 4 | 文首更正H11-H14；单判据、轮询和App Replay条件边界保留 |
| mcp-text-input-and-webcam.md | 11工具；OBS缺安装即可换码的推荐 | 2 | 文首更正H23-H24；中文回读与实体webcam数据保留 |
| next-phase-tasks.md | N31-N60状态、N57/58缺口、N59文件/IME/finish、T3、分支数量和maxScrolls语义 | 14 | 改正R02-R15；逐任务表见下，所有任务勾选均未擅改 |
| observe-capability-extension.md | 8工具/判据与动作无出口、Unicode/绑定/foreground未验、watch失败、相机候选 | 4 | 文首更正H07-H10；固定录屏1..180秒/无提前停止/无通用证书入口仍成立 |
| protocol.md | 45动作、方法/能力清单、动作入口、六工具/观察子集 | 5 | 只读移交P09-P13；Bridge预算/TTL/握手与源码一致，版本规则保留 |
| recording.md | 仅改名、schema1.0、迁移/保存、无视觉路径、MCP secret错误、导入约束 | 6 | 改正R27-R32；6类录制事件/物理触摸限制、32包/10000步、内存secret边界对照Models/Store/Replay保留 |
| review-round-10.md | API30-33缺设备、Task21/23未完成 | 1 | 文首更正H30指向Round11；历史审查/安全/构建结果不改 |
| review-round-11.md | API33真实Engine停止、Task21/23已关闭、无事件触摸仍不可观察 | 0 | `d64cca1`/`dbc7384`/`835f931`与MVP tasks一致，保留 |
| security-distribution.md | Provider仅语义、audit仅内存而未区分历史 | 2 | 改正R39-R40；明文token/Windows ACL、debug发布/无Play裁剪变体仍与代码一致；政策现时效力未核实U03 |
| text-input-and-virtual-camera.md | MCP仅ASCII/只有CLI、宿主OBS路线待验 | 2 | 文首更正H21-H22；普通App虚拟相机权限门为原实测，未重跑 |
| troubleshooting.md | Bridge旧UI入口、Unicode输入指路缺失 | 1 | 改正/补充R38；ADB解析、5037诊断、会话错误与源码一致，未操作实际ADB |
| unity-capture-verdict.md | 像素格式为根因、OBS未验最有希望 | 2 | 文首更正H28-H29；原枚举/格式/崩溃数据不改 |
| validation.md | 最初设备数0、后续API34与Round11 API33验收；旧测试计数/制品hash | 0 | 文件内部已按时序追加收口，无需再改正文；历史计数不是当前数量 |
| virtual-camera-selection.md | Windows OBS/Unity推荐、未安装/注册、<1秒与后续顺序 | 3 | 活选型直接改正H25-H27，保留macOS研究并标未重新核实；当前安装状态未核实 |
| virtual-display.md | CLI-only/API35已验，API33/34/OEM待验、窗口列表/焦点/休眠恢复边界 | 0 | 基线内与 `virtual-display-screen-off` 规格和E18一致；不把并行分支的后续实现混入 |

自有文档更正共70组，9份历史文档仅添加文首更正；另有1份摄像头状态文档仅标明
当前宿主未核实。只读文档11组列入移交，不修改；根README/skills移交另计。

## D3：路线图逐任务勾选核查

每个已勾选任务对照其原规格、后续收口与实现/提交；“保持未勾选”即明确尚缺该任务的
验收，不表示基础代码不存在。未修改任何任务的勾选，不回写任何既有规格。

| 任务 | 当前勾选/核查结果 | 实现、规格与提交证据 |
| --- | --- | --- |
| N31 | [x] 保留 | `n31-deterministic-emulator-runner` spec/tasks；`b7e9eda`/`6604f10`与scripts/emulator runner；历史30轮生命周期证据保留 |
| N32 | [x] 保留 | `n32-debug-test-control-plane` spec/tasks；`26865fe`/`4287344`；core/debug隔离与三API snapshot记录 |
| N33 | [x] 保留 | `n33-native-device-fixture`→`n33-native-scroll-redesign`→`n33-clean-repeat-matrix`；`0e3f8a9`/`db2c268`/`9acb54f`；60/60。旧ScrollView否定与阶段未完成记录不改 |
| N34 | [x] 保留 | `n34-bounded-failure-artifacts`；`dc677b7`及collector/Schema，49个fake-input不是设备矩阵 |
| N35 | [ ] 保留 | `6872b17`/`dc3e10e`与mdns/watch/trusted源码；缺macOS/Windows各20次恢复证据，没有据单测勾选 |
| N36 | [x] 保留 | `n36-lan-qr-invitation`；`a0b66b6`/`492fdad`/`c4bff51`，跨语言fixture/密码向量 |
| N37 | [ ] 保留 | Go listener/CLI/persistent/QR/cross-runtime各spec；`ad59f7f`/`cb7ef3d`/`4acf923`；真实跨平台网络/防火墙矩阵仍缺 |
| N38 | [x] 保留 | outbound/integration/persistent/QR/usability及real-device-acceptance各spec；`667c7b5`/`f5772cc`；API34内置扫码/RPC，不扩大为全部OEM通过 |
| N39 | [x] 保留 | `n39-automation-script-v1.1`；`6290c36`；Models/Store migration/CAS与定向测试 |
| N40 | [x] 保留 | `n40-recording-editor-domain`；`8180ab6`；ScriptEditorSession/ScriptDryRunEngine与领域测试 |
| N41 | [x] 保留 | compose/integration/device/authorized-editor各spec；`d6f85e2`/`19ee765`；API34授权provider合成截图4/4，不冒充普通入口自动截屏 |
| N42 | [x] 保留 | `n42-offline-webview-canvas-fixture`；`3209164`/`581f01c`；离线三模式三API 9/9 |
| N43 | [x] 保留 | semantic/probe/clean-repeat spec；`9cc8db6`/`fae0014`/`6371f02`；60/60。旧input-focus否定实验未被抹掉 |
| N44 | [x] 保留 | proposal/session/desktop各spec；`1682806`/`3157f83`/`a2325c7`；生产只读MCP API34 smoke |
| N45 | [x] 保留 | explicit-replay/authorized-ports/visual-device-matrix；progress Round14与`e804c87`记录18/18；早期tasks未勾收尾不回写 |
| N46 | [x] 保留 | external-cache/inspector/badging/install-runner各spec；`725d628`/`9414b9c`；五固定包×三API安装身份验收，不算真实动作场景 |
| N47 | [ ] 保留 | runner/adapters/stdin/rebind/attested/fixture/authorization/device-fixes各spec；`fe52a8f`最终endpoint仍阻塞，未转绿 |
| N48 | [ ] 保留 | bilibili contracts；`b3527ae`；10轮真实场景未验，fixture不计真实轮次 |
| N49 | [ ] 保留 | douyin contracts；`9efc0ee`；真实10轮未验，unknown commit不重试 |
| N50 | [ ] 保留 | xiaoheihe contracts；`51e1cf3`；真实10轮未验，consent阻塞不能计成功 |
| N51 | [ ] 保留 | miniapp contracts；`821f501`；分类契约已有，真实小程序样例为零 |
| N52 | [ ] 保留 | local-matrix/ports/residue各spec；`502a5a1`/`d4d5083`/`f9068d6`；完整300轮仍未验，不用61个离线测试替代 |
| N53 | [ ] 保留 | 条件任务，N52未闭合；`test-lab/providers/device-farm/`无实现，无供应商验收 |
| N54 | [ ] 保留 | 条件任务，`internal/operator/`无scrcpy sidecar实现，无接管矩阵 |
| N55 | [ ] 保留 | Linux需实际环境，仓库无v4l2loopback验收记录；E08/N55判据不代表成功 |
| N56 | [ ] 保留 | UI implementation/wave2 spec，`dd949f5`/`f1ef91b`与UI追踪；通知/锁屏/录制完整流程等仍待设备验证 |
| N57 | [ ] 保留，文字修正 | E15：窗口/Toast/写入已有；get/MCP写入与设备验收仍有余项，不能整项勾选 |
| N58 | [ ] 保留，文字修正 | E10/E16：scrollUntil/事件/策略/match已有；hint/结构/深度与设备证据仍缺 |
| N59 | [ ] 保留，区分已决 | E14/E17/E18/E22：文件、聚焦、CLI多屏与finish定位已有；任意键/组合键/OCR等未决，不整体勾选 |
| N60 | [ ] 保留 | E18：仅基线CLI交付；MCP/发布/API33/34/OEM/焦点语义/相机决策均无本分支验收；其他worktree不纳入证据 |

## 只读文件移交（不落笔修改）

位置为 `984ee31` 的文件行号。每条均包含原断言、证据与建议；并行实现若已处理，
由集成方在汇合时去重，不由本任务修改这些文件。

| 编号 | 文件/位置 | 过期断言 | 证据 | 建议改法 |
| --- | --- | --- | --- | --- |
| P01 | 根README.md:150 Android权限 | “清单只声明网络权限” | main AndroidManifest.xml:4-18声明CAMERA/前台服务/通知/RECORD_AUDIO/悬浮窗/查询应用等；E11/E20 | 按真实权限和用户触发路径列出，不沿用旧网络-only说法 |
| P02 | 根README.md:164-165 | “Bridge不传截图”没有区分普通观察与attested执行；CLI不能编辑本身正确 | E13/E19；AttestedVisualActionGateway.kt:112,255 | 补attested请求有界PNG例外；如补App编辑器说明则保留CLI限制与N56待验 |
| P03 | docs/architecture.md:47 | 六个MCP tools | E01，当前16 | 用ToolNames当前集合或无固定数的能力描述 |
| P04 | 同上:182 | CLI没有devices watch | `internal/adb/watch.go`、CLI discovery分流、`6872b17`/`dc3e10e` | 补有界watch/可信重连，N35跨平台矩阵仍未验收 |
| P05 | 同上:160 | 截图不会进入App AI数据流 | E21 | 说明默认语义、显式授权才最小图像/observation、清零与后置验证 |
| P06 | 同上:175-176 | App到Provider仅语义摘要 | E21 | 与P05同步图片授权边界 |
| P07 | 同上:183-184 | android_observe统一“三种观察” | `schemas.go:383`当前8个kind；E03/E15 | 列当前kind并区分原始ADB与脱敏Bridge观察 |
| P08 | 同上:62-69/202-203 | 直接能力表只有早期动作，末尾笼统“视觉自愈未实现” | E03/E04/E14/E16/E19/E21 | 补应用/前台/录屏/判据/文件等；区分已交付视觉端口与尚无完整真实App自愈产品 |
| P09 | docs/protocol.md:184 | Schema共45动作 | E18，当前oneOf/const为46，841b7a9新增scrollUntil | 更新计数或改为引用Schema，避免旧计数延续 |
| P10 | 同上:154-180 方法与hello能力 | 只列旧8方法/6能力，漏foreground/windows/transientMessages/visual | AndroidBridgeMethods.kt:243-246及其capabilities，E15/E19 | 从当前dispatcher/capabilities整理完整列表，保留认证与test-only限制 |
| P11 | 同上:190-195 动作入口表 | longClick/scroll/wait/assert直连标“否” | E04，CLI check与MCP专用工具都已存在 | 区分按坐标/选择器/判据工具，不把协议type与路由混为一项 |
| P12 | 同上:272-286 MCP边界 | 六工具、仅三种observe、动作只走ADB、无recordings | E01/E04/E15/E19，recordings经android_observe读取 | 改完整工具/观察kind与独立风险门；MCP replay仍拒绝 |
| P13 | 同上:105及196 App/Bridge截图边界 | “截图等大制品不走Bridge”过于笼统；ime.show“App未接入”需细分 | Bridge `visual.action.execute` 为attested截图payload例外；E17/E19；ProviderActionParser接受ime.show与通用executor不同于专门CLI出口 | 说明普通快照不回传PNG，但attested执行请求含有界图像；按App parser/router实际路径核实ime.show表述，不能一概视为未实现 |
| P14 | skills/android-device-observation/references/commands.md:106 | CLI仅支持screenshot/hierarchy | E03/E15/E16；direct.go:30有完整observe列表 | 补foreground/apps/recording/screen-state/input-methods与Bridge观察；由集成方同步镜像 |
| P15 | skills/android-device-automation/references/commands.md:115 | Bridge只能执行旧9动作，其他都CAPABILITY_UNAVAILABLE | E06/E15/E17、AccessibilityCommandJsonParser.kt现有分支 | 改为实际支持集合及MCP子集，保留get/不支持动作失败关闭，镜像由集成方处理 |

P02/P08含边界歧义表述；P13中App表格部分作为待集成复核项，不将
“parser接受”等同于App用户流程已交付。根README的16工具计数正确，virtual-display.md
的CLI-only结论在本基线正确；本任务未因并行分支正在开发就修改它们。

## 未核实项与原因

- U01：当前宿主OBS/Unity/Python安装/注册状态、外置E:\\tmp探针与原始截图不在仓库；
  本任务不运行宿主安装工具/设备，无法复核。live摄像头状态文档已标明当前未核实，
  不据旧表自动安装/卸载。新增的日期/范围说明同样以E08为证据，不声称软件已移除。
- U02：Linux v4l2loopback、Windows MF、macOS换码与跨设备/ABI/ROM、N35/37等矩阵，
  没有本分支后续实测证据；用户禁止设备操作，保持未验。预置脚本旧UI文案是否实际
  兼容新UI也未重跑，不将“代码还在”视为可执行验证通过。
- U03：第三方软件版本/macOS扩展要求、Google Play/开发者验证政策现时效力未核实；
  本轮限本地仓库事实，不作在线政策/软件选型重新研究。security文档保留2026-07-18
  日期口径，选型文件已明示第三方研究未重新核实。
- U04：2026-08-01远端Actions状态与当时外置产物hash不能代表当前；未访问远端，
  未push，旧远端段已明确标历史。`artifacts/uiautomator-gap/`与UI历史截图等引用是
  本地取证来源而非本轮可复现附件，不据仓库外内容增加交付结论。
- U05：新vdisplay MCP分支的实现/验收不在`984ee31`；只读移交以汇合前基线为准，
  不能替并行任务更新N60或写其文件。

## D2：既有规格读取索引

逐目录读取现有 spec.md/tasks.md 全文，以目标、范围、验收与勾选项建立索引；下列数量只是历史文件中的标记，不是本轮重新验收结果。没有tasks的设计规格单列，所有文件保持只读。

| 规格目录 | spec/tasks存在性 | tasks已勾/未勾 |
| --- | --- | --- |
| action-surface-expansion | spec.md / tasks.md | 25/4 |
| android-control-ci-hardening | spec.md | 无tasks，按设计规格读取 |
| build-ai-android-automation-mvp | spec.md / tasks.md | 139/0 |
| consumer-agent-ui-prototype | spec.md | 无tasks，按设计规格读取 |
| debug-harness-planned-action | spec.md / tasks.md | 5/0 |
| file-transfer-no-overwrite | spec.md / tasks.md | 17/0 |
| ime-show-focus | spec.md / tasks.md | 18/0 |
| mcp-file-and-ime-show | spec.md / tasks.md | 17/0 |
| n31-deterministic-emulator-runner | spec.md / tasks.md | 6/0 |
| n32-debug-test-control-plane | spec.md / tasks.md | 12/0 |
| n33-clean-repeat-matrix | spec.md / tasks.md | 10/0 |
| n33-native-device-fixture | spec.md / tasks.md | 8/0 |
| n33-native-scroll-redesign | spec.md / tasks.md | 9/0 |
| n33-platform-scroll-surface | spec.md / tasks.md | 9/1 |
| n34-bounded-failure-artifacts | spec.md / tasks.md | 9/0 |
| n36-lan-qr-invitation | spec.md / tasks.md | 6/0 |
| n37-cli-lan-integration | spec.md / tasks.md | 6/0 |
| n37-go-lan-listener | spec.md / tasks.md | 10/0 |
| n37-n38-cross-runtime-rpc | spec.md / tasks.md | 9/0 |
| n37-n38-pairing-usability | spec.md / tasks.md | 12/0 |
| n37-n38-qr-integration | spec.md / tasks.md | 14/0 |
| n37-persistent-lan-rpc | spec.md / tasks.md | 8/0 |
| n38-android-lan-integration | spec.md / tasks.md | 6/0 |
| n38-android-lan-outbound | spec.md / tasks.md | 7/0 |
| n38-persistent-lan-rpc | spec.md / tasks.md | 9/0 |
| n39-automation-script-v1.1 | spec.md / tasks.md | 6/0 |
| n40-recording-editor-domain | spec.md / tasks.md | 7/0 |
| n41-authorized-editor-production | spec.md / tasks.md | 9/0 |
| n41-compose-recording-editor | spec.md / tasks.md | 13/0 |
| n41-editor-device-instrumentation | spec.md / tasks.md | 12/0 |
| n41-recording-editor-integration | spec.md / tasks.md | 5/0 |
| n42-offline-webview-canvas-fixture | spec.md / tasks.md | 6/0 |
| n43-clean-repeat-matrix | spec.md / tasks.md | 9/0 |
| n43-webview-capability-probe | spec.md / tasks.md | 9/0 |
| n43-webview-input-focus | spec.md / tasks.md | 7/3 |
| n43-webview-semantic-replay | spec.md / tasks.md | 8/0 |
| n44-desktop-visual-observation | spec.md / tasks.md | 10/0 |
| n44-visual-observation-proposals | spec.md / tasks.md | 8/0 |
| n44-visual-session-integration | spec.md / tasks.md | 8/0 |
| n45-authorized-visual-production-ports | spec.md / tasks.md | 10/0 |
| n45-explicit-visual-replay | spec.md / tasks.md | 8/0 |
| n45-visual-device-matrix | spec.md / tasks.md | 8/2 |
| n46-android-apk-inspector | spec.md / tasks.md | 9/0 |
| n46-external-apk-cache | spec.md / tasks.md | 9/0 |
| n46-modern-apk-badging | spec.md / tasks.md | 7/0 |
| n46-n31-install-runner | spec.md / tasks.md | 8/0 |
| n46-passive-app-smoke | spec.md / tasks.md | 8/0 |
| n47-aactl-production-adapters | spec.md / tasks.md | 9/0 |
| n47-attested-visual-action-port | spec.md / tasks.md | 9/0 |
| n47-attested-visual-action-skills | spec.md / tasks.md | 7/0 |
| n47-cross-app-scenario-runner | spec.md / tasks.md | 9/0 |
| n47-production-fixture-device-fixes | spec.md / tasks.md | 8/0 |
| n47-production-fixture-runner | spec.md / tasks.md | 7/1 |
| n47-recording-visual-rebind | spec.md / tasks.md | 8/0 |
| n47-stdin-action-input | spec.md / tasks.md | 8/0 |
| n47-test-authorization-provider | spec.md / tasks.md | 8/0 |
| n48-bilibili-scenario-contracts | spec.md / tasks.md | 8/2 |
| n49-douyin-scenario-contracts | spec.md / tasks.md | 9/2 |
| n50-xiaoheihe-scenario-contracts | spec.md / tasks.md | 8/1 |
| n51-miniapp-classification-contracts | spec.md / tasks.md | 9/1 |
| n52-local-api-matrix | spec.md / tasks.md | 9/0 |
| n52-production-matrix-ports | spec.md / tasks.md | 9/1 |
| n52-production-residue-provider | spec.md / tasks.md | 7/0 |
| n56-consumer-ui-implementation | spec.md | 无tasks，按设计规格读取 |
| n56-consumer-ui-wave2 | spec.md | 无tasks，按设计规格读取 |
| portable-toolchain-windows-ci | spec.md / tasks.md | 10/0 |
| real-device-acceptance | spec.md / tasks.md | 12/0 |
| selector-consistency-c12-c10 | spec.md / tasks.md | 26/0 |
| selector-regex-match | spec.md / tasks.md | 12/0 |
| uiautomator-gap-alignment | spec.md / tasks.md | 25/0 |
| virtual-display-screen-off | spec.md / tasks.md | 46/0 |
| window-toast-event-clipboard | spec.md / tasks.md | 28/0 |

合计140份既有spec/tasks；未把其早期“零实现/无出口”背景当作当前状态改写。

## D5：指定验证与范围检查

| 验证 | 结果 |
| --- | --- |
| make comments | exit 0；589个手写文件覆盖，16个检查器正反例通过 |
| make verify | exit 0；fake ADB定向、协议14 schemas/14 valid/38 invalid/4 compat共56项、3组Skills及镜像、CLI命令示例smoke、comments、repository metadata均通过 |
| git diff --check | exit 0 |
| 本地链接/锚点 | docs全部Markdown与本规格共131个引用，0失效；导航26/26覆盖 |
| 历史正文 | 9份历史记录逐一与984ee31原文后缀比较，正文全部一致；选型活状态表单独直接更正 |
| 路线图状态 | N31-N60共30项checkbox与基线完全一致，逐任务核查结果见上 |
| 文件所有权 | 24个差异路径均属本任务（20份docs + 本规格4文件）；根README和3份只读docs逐字节一致，既有规格/skills/代码/测试/脚本/配置无diff |

本轮没有启动模拟器、发现/连接/操作设备，没有运行安装/下载命令，没有push。make verify仅使用仓库指定fake ADB/离线验证入口；未用历史设备证据冒充本轮重测。后续仅按主题提交与检查最终工作区，不重复已通过且未受纯文档变化影响的测试。
