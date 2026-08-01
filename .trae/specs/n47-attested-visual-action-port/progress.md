# N47 可信桌面视觉动作进度

本文件 append-only，记录 RED/GREEN、安全拒绝、清理、验证与未完成设备证据。

## Round 1

- 工作位于独立分支 `n47-attested-visual-action-port` 和 worktree
  `/private/tmp/ai-auto-n47-attested-port`，基线为 `e10aae4`；并行 N45 matrix、
  recording visual rebind 与 N51 worktree 均未修改。
- 已审计 N44 desktop proposal lease、共享 MCP、Go Bridge、Android Bridge 和 N45
  authorized visual production port。N44 lease 在只读 MCP response write 后关闭，
  不能跨调用复用；现有 `android_action_execute` 是裸坐标直接 ADB 动作，不能作为
  N47 visual route。
- 采用原子窄端口：Go 在单次调用栈内 fresh capture、唯一 propose、pre-commit
  re-attestation、Bridge execute 和 post-observe；Android Bridge 在同一 RPC 内调用
  N45 planner、package-bound Accessibility executor 和 fresh post verification。
- 设备 fingerprint 定义为本次调用规范化 `protocol.Device` evidence 的 SHA-256，
  必须在 pre/post attestation 中一致；不扩展共享 Device schema、不猜 build
  fingerprint，也不开放任意 shell/getprop。

## Round 2

- Go RED 首次因 `DesktopAttestedActionService`、完整 evidence request/result 和
  commit status 类型不存在而编译失败。Android RED 随后因
  `AndroidAttestedVisualActionGateway`、screen reader 和 image decoder 不存在而
  编译失败，证明旧 N44 proposal lease 与裸 `android_action_execute` 均不能满足
  原子端口。
- Go 服务实现三轮稳定采集：source、pre-commit 和 post-action 均固定
  `device -> hierarchy -> screenshot -> hierarchy -> device`。source 与 pre-commit
  必须绑定同一 device fingerprint、package、screen、rotation、crop、hierarchy
  hash、PNG hash、唯一 candidate 和 expiry；post-action 必须保持设备、包和 screen
  一致且 hierarchy 或 PNG 发生 fresh 变化。
- MCP 新增 destructive `android_visual_action_execute`，输入只有明确 serial、
  package、role/label 和 tap/long-click/方向型 swipe；schema 与严格 JSON decoder
  拒绝坐标、candidate/observation/hash/handle、重复 key、未知字段和尾随值。输出只
  返回 audit observation ID、device/screen fingerprint、route、verification 和
  `not_committed|committed|unknown`，不返回候选 geometry、图片或 lease。
- 原子服务独立于 N44 `DesktopProposalLease`，不注册或返回可复用 observation。
  backend、service、Bridge request JSON、NDJSON wire buffer 和 Android decoded PNG
  副本在成功、拒绝、transport 异常和 verification 异常路径清零。

## Round 3

- Android Bridge 新增 `visual.action.execute` 窄方法。严格 evidence 解析绑定
  observation/candidate UUID、target package、PNG size/hash、crop、screen、
  confidence 和 expiry；图片 hash 不一致、过期、secure、包漂移或 screen drift
  在动作前返回 `not_committed`。
- Gateway 把 candidate 和方向型动作转换为内存 `RecordedStep`，调用既有 N45
  `ExplicitVisualReplayPlanner`，再通过 `AndroidVisualReplayActionExecutor` 执行
  package-bound Accessibility tap/long-click/swipe。Bridge 没有裸坐标入口，方向型
  swipe 的临时点仅在 candidate bounds 内由 planner 输入生成。
- 动作明确拒绝返回零提交；typed executor 抛异常、Bridge transport/protocol 异常、
  post snapshot/screen 缺失或状态未变化均返回 `unknown` 或等价
  `ACTION_COMMIT_UNKNOWN`/`POST_ACTION_VERIFICATION_FAILED`，单次端口不重放。
- production factory 只有 `BuildConfig.DEBUG` 且 emulator identity 命中时才声明
  capability；release、真机、未授权 Accessibility 和未建立 Bridge session 均在动作
  前失败关闭。普通测试构造默认注入 unavailable gateway，避免 capability 查询触发
  window/device 副作用。

## Round 4

- 定向验证通过：Go `internal/visual`、`internal/bridge`、`internal/mcpserver`、
  `internal/cli` 的普通测试、`go test -race` 和 `go vet`；Android
  `AttestedVisualActionGatewayTest`、`AndroidBridgeMethodsTest` 与
  `BridgeDispatcherTest` 通过。
- App `testDebugUnitTest` 共 396 项通过；`lintDebug` 为 0 error、40 warning，warning
  为既有项目告警；`assembleDebug` 55 个 task 通过，`assembleRelease` 49 个 task
  通过，证明 release 失败关闭代码路径可编译。
- `make test`、`make verify`、`make build`、`make comments` 和
  `git diff --check` 通过。协议仍为 14 schemas、13 valid fixtures、35 invalid
  fixtures、4 compatibility checks；本 change 不需要新增公共 protocol schema。
- 范围审查确认未修改 `docs/next-phase-tasks.md`、N45、recording/replay/editor、
  test-lab、SDK/cache 或 GitHub workflows。N47 路线图保持 `[ ]`。
- 本轮没有接入外部 OCR/model production provider，也没有启动 emulator 或真机运行
  生产 MCP -> Bridge -> Accessibility 闭环；production provider/device evidence
  明确保持未验收，不能据此勾选 N47。
