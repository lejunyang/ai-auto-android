# N47 固定 Fixture Production Runner 进度

本文件 append-only，记录 RED/GREEN、验证和未完成设备证据。

## Round 1

- 基线为 `main` 的 `e804c87`，独立分支 `n47-production-fixture-runner`，worktree
  `/private/tmp/ai-auto-n47-production-runner`。
- 已审阅 N31 fixed profiles/lifecycle、N47 59 项 tests 与 production aactl adapter、
  N34 artifact、N45 attested visual MCP port 和 N52 八类 residue。
- 现有 N32 Bridge 授权与 N45 Accessibility/visual 授权都封装在各自 instrumentation
  harness 内；host production CLI 没有绑定 N31 serial/fingerprint 且具备 teardown
  的类型化授权入口。
- 因禁止修改 Android/Go production 与 N45，本 change 不扩权、不复制 secure
  settings 逻辑。默认 production CLI 必须在 N31 start 前以
  `PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE` 失败关闭。
- 接下来先新增会失败的 fake/negative tests，再实现固定 orchestrator、CLI、无坐标
  MCP adapter 与三个 production fixture scenarios；本轮不操作设备。

## Round 2

- RED 先因固定 production runner、ports、attested visual adapter 和三个 fixture
  scenario 文件不存在而失败；随后实现只接受 `--profile api-30|33|34` 的 CLI，
  调用方不能传 serial、package、APK、task、scene 或 action。
- production ports 固定 N31 lifecycle、App/native/Web fixture build/install、
  test-only authorization provider、aactl semantic/input、无坐标
  `android_visual_action_execute`、四步 scenario cleanup 和八类 residue。
- attested visual adapter 绑定仓库外 `aactl` identity、固定 `mcp serve` argv 与
  NDJSON stdin；旧 observation/token、fingerprint drift、明确拒绝和 unknown commit
  均保持单次调用且不重试。
- native/WebView/Canvas 三个场景只引用本地无敏感 fixture 资源并通过严格 Schema；
  新增定向测试 13/13，完整 N47 smoke 73/73，原有 59 项全部保留，N34 49/49 通过。

## Round 3

- 真实外置环境执行 CLI 时，默认授权 provider 返回稳定
  `PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE`；发生在 build、emulator start、
  APK install 和动作之前。随后只读 `aactl devices list` 为 0，未留下设备状态。
- 使用外置 Go/Android toolchain 运行 `make test`、`make verify`、`make build` 和
  comments 全部通过；Android `testDebugUnitTest`、`lintDebug`、`assembleDebug`、
  `assembleRelease`、`compileDebugAndroidTestKotlin` 共 111 tasks 通过，仅有既有
  deprecated/native strip 告警。
- `git diff --check`、机器路径、禁用参数和允许范围审查通过。由于当前没有可安全由
  host 建立并在 finally 撤销的 test-only Bridge/Accessibility 授权 provider，
  API 30/33/34 设备闭环保持未验收，不能勾选 N47。
