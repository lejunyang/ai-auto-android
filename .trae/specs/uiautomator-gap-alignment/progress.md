# UIAutomator 缺口对齐进度

本文件 append-only，记录基线、RED/GREEN、各波次实际改动与验证结果。

## Round 1：基线与规格

- 工作区 `E:/Projects/ai-auto-android`，开工时 `git status --short --branch` 为
  clean，`git worktree list --porcelain` 只有主工作区一个条目，分支 `main`
  （ahead origin/main 128），HEAD `4207ab0`。
- 开工前基线 `make test` 通过：协议 14 schema / 13 合法 fixture / 35 非法 fixture /
  4 项兼容检查（共 52），Skills 3 组与 Trae 镜像通过，Go 全部包 ok。
- 依据来源为 `artifacts/uiautomator-gap/uiautomator-capability-gap.md` 第 4、5 节。
  只实施分类为 `可补充` 的条目；`需权衡` T1–T8 与 `有意不做` N1–N10 不实施。
- 键枚举收敛方向先查 `protocol/version.json`：`compatibility.requiresNewMajor`
  含 `narrow-valid-range`，因此禁止把协议 21 键收窄到 17 键。改为以协议为单一
  权威来源，直连与 MCP 向上补齐 MOVE_HOME / MOVE_END / PAGE_UP / PAGE_DOWN。
  该四键均为光标与翻页语义，不触及电源、通话或系统安全设置，不构成范围扩张。
- `app.stop` 矛盾选择收窄 MCP schema 而非放开风险门：风险门是安全权威，放开等于
  扩大模型动作面。CLI 侧 `action stop` 不受影响。
- `file.push` / `file.pull` 明确不接线，归入待决项 T4。

## Round 2：Wave A 协议与文档一致性

- 分支 `wave-a-protocol-alignment`，在主工作区上串行执行（它改共享协议面与文档，
  按 AGENTS.md 必须单一 owner）。
- RED 先行：新增 `internal/mcpserver/presskey_consistency_test.go` 后
  `go test` 以 `undefined: adb.SupportedKeys` 编译失败，确认测试确实先红。
- 实现：`internal/adb/direct.go` 的 `allowedKeys` 从 17 键补到 21 键，并新增
  `SupportedKeys()` 返回副本（白名单是安全边界，不暴露可写 map）。
  `internal/mcpserver/schemas.go` 的 `ui.pressKey` 同步到 21 键、`ui.scroll`
  补 forward/backward、`ui.setText` 增加指向 `android_bridge_set_text` 的
  description、移除 `app.stop` 分支。
- `bridge-request.schema.json` 补 `visual.action.execute`。首次用相对 `$ref`
  引用 `visual-observation-proposal.schema.json` 的 observation/candidate 失败：
  该 schema 的 `$id` 基址是 `https://aiauto.dev/schema/v1/`，与其余 schema 的
  `https://ai-auto-android.dev/protocol/v1/` 不同，相对引用解析不到。改用绝对
  URL 引用，未改动已发布的 `$id`。
- fixture 用一次性 Node 脚本原地插入，避免 PowerShell 的 JSON 往返重排 19KB
  清单：`git diff --numstat` 为 322 插入 0 删除，既有内容零改动。脚本用后删除。
  协议检查从 52 项升到 56 项（1 合法 + 3 非法 fixture）。
- 回归发现 `server_test.go` 原先断言 `app.stop` 返回 `ACTION_NOT_ALLOWED`。
  移除 schema 分支后拒绝点前移到 schema 层，错误码不再是风险门的那个。只更新该
  用例的期望（仍断言被拒、仍断言零次抵达 service），未放宽风险门本身——
  `risk_test.go` 对 `ActionStop` 的拒绝断言保持不变，两层防御都在。
- README 从「六个类型化工具」改为实际的 11 个，并新增
  `readme_toolcount_test.go` 把文档数字绑定到 `ToolNames` 长度。
- 验证：协议 56 项、mcpserver/adb/cli/service 测试、`make comments`（551 文件）、
  gofmt、`go vet`、`git diff --check` 全部通过。
- 提交 `385d353`（spec）、`6d13f38`（协议）、`8fa88b0`（README）。

## Round 3：Wave B CLI 表面补齐

- 独立 worktree `E:/Projects/wt-wave-b-cli`，分支 `wave-b-cli-surface`，
  基于 Wave A 的 `8fa88b0`。目录所有权限定 `internal/cli/`。
- RED 先行：九个子命令全部以
  `Unknown action. Supported actions: tap, swipe, text, key, launch, stop.` 失败。
- 实现 `executeActionExtra` 承载九个动作，`executeObserveExtra` 增加
  `screen-state` / `input-methods`。`path-gesture` 用
  `--points "x,y x,y"` 传折线：`parseNamedOptions` 对同名选项会报重复，
  改造它会波及所有其它命令的既有约定。
- `rotate` 的 orientation 在 CLI 层就校验：错误方向名落到 ADB 会变成一条静默
  无效的 settings 写入，调用方看不出哪一步出错。
- 新增 `TestFileTransferHasNoCLIEntryPoint`，锁定 file.push / file.pull 不被
  顺手接线——它属于待决项 T4，需要先定义路径白名单。
- gofmt 首次失败（新测试文件对齐不符），`gofmt -w` 修复后复跑通过。
- 验证：CLI 全包测试通过（32.2s），构建产物实跑核对两条 usage 串已更新，
  comments（552 文件）、gofmt、vet、diff-check 通过。
- 提交 `726e09d`。

## Round 4：Wave C MCP 语义动作

- 独立 worktree `E:/Projects/wt-wave-c-mcp`，分支 `wave-c-mcp-semantic`，
  同样基于 `8fa88b0`。目录所有权限定 `internal/mcpserver/`，与 Wave B 互斥。
- RED 先行：`undefined: ToolBridgeAction` 等 8 处编译失败。其中
  `apperr.CodeCapabilityUnavailable` 实际名为 `CodeCapabilityMissing`，据此修正。
- 实现 `android_bridge_action`，独立白名单只含四个元素级动作：
  ui.click / ui.longClick / ui.scrollIntoView / ui.nodeAction。
  锁屏、电源对话框、剪贴板、文件与坐标手势均不在其中。
- 该白名单与 `requireModelActionAllowed` 互不覆盖，测试双向确认：语义白名单拒绝
  `ui.tap`，直连白名单拒绝 `ui.click`，任一层都不是另一层的旁路。
- schema 的 action 枚举与运行时白名单是同一集合，避免重演 app.stop 那种
  「schema 允许、运行时必拒」的矛盾。
- 上一轮新增的 README 计数测试在本轮如预期失败（工具数 11→12），据此把 README
  更新为 12。这条测试起到了设计时预期的防漂移作用。
- 验证：mcpserver 全包通过，comments（553 文件）、gofmt、vet、diff-check 通过。
- 提交 `93ce93b`。

## Round 5：集成与全量验证

- 三个分支以 `--no-ff` 依次并入 `main`，无冲突。
- 集成后全量门禁实测结果：
  - `make test`：通过。协议 14 schemas / 14 valid / 38 invalid / 4 compat（56 项，
    基线为 52）；Skills 3 组与 Trae 镜像通过；Go 全部包 ok。
  - `make verify`：通过。fake ADB 定向测试、协议与 Skills smoke、comments
    （554 文件 + 16 正反例自测）、repository metadata valid。
  - `make build`：通过，产出 `bin/aactl` 7773696 字节。
  - `make comments`：通过，554 个手写文件。
  - `make android-verify`：BUILD SUCCESSFUL in 1m50s，152 actionable tasks，
    覆盖 testDebugUnitTest / assembleDebug / lintDebug。本轮 `android/` 改动为
    0 文件（`git diff --stat 4207ab0..HEAD -- android/` 为空），该运行只用于
    确认无回归。
- 本轮总改动 17 文件、1884 插入、19 删除，全部落在允许范围内。
- 未纳入本轮的缺口写入 `docs/next-phase-tasks.md` 的 N57 / N58 / N59，
  插入为 94 行新增 0 行删除，未改动任何历史验收结论。
- **未 push 到远端**：`main` 已 ahead origin/main 128 + 本轮提交，推送会影响共享
  状态，留给用户决定。
- **无设备证据**：新增的四个键、九个 CLI 子命令与语义动作工具都只经单测与构建
  验证，未在真机或模拟器上执行，checklist 对应项保持未勾选。

