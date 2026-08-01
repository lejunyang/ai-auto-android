# N47 录制视觉 Rebind 进度

本文件 append-only，记录 RED/GREEN、运行期授权边界、验证证据和未接入项。

## Round 1

- worktree `/private/tmp/ai-auto-n47-recording-visual-rebind` 位于分支
  `n47-recording-visual-rebind`，基线 `d752bc0`，创建后干净；并行 N45/N51
  worktree 未修改。
- 已审计 N41 `EditStepAuthorizedVisualTarget`/UI observation holder、N44
  `VisualObservationStore`/proposal、N45 planner/executor、session production
  screenshot authorization 与 recording production factory。
- 当前 `ExplicitVisualReplayExecutor` 直接用持久 `VisualTarget.observationId` 调用
  `VisualReplayObservationPort.acquire`；这会把历史 ID 当成未来授权入口。普通
  recording factory 又未注入 visual port/provider，因此 production 当前虽默认
  失败关闭，但没有可安全启用的 fresh rebind 组合。
- 设计改为 current-run authorization：provider 显式产生绑定 run/script/revision/
  step/serial/package 的一次性 lease，lease 内携带 fresh observation、唯一候选、
  当前 screen 与 post-observation verifier。executor 不再按历史 ID 查找
  observation，而是消费 lease 后仅在内存请求中写入 fresh ID/hash/geometry。
- 已新增 `VisualReplayRebindTest`。首次定向
  `:app:testDebugUnitTest --tests *VisualReplayRebindTest` 在测试编译期按预期失败，
  明确缺少 run authorization、fresh step lease、`VISUAL_REBIND_REQUIRED`、
  `DEVICE_SERIAL_CHANGED`、`ACTION_COMMIT_UNKNOWN` 与 nullable commit count。
- RED 还锁定 provider 缺失、旧 ID、serial/package/screen/hash/candidate/expiry
  漂移、单次消费、后置重新观察和 unknown commit 不重放；下一步实现最小生产代码。

## Round 2

- 新增 current-run `VisualReplayAuthorizationProvider`、严格 run binding、一次性
  `AuthorizedVisualReplayStepLease` 与进程内 registry。run/script/revision/step、
  serial、package、screen、hash、candidate geometry/confidence 和双层 expiry
  任一漂移均在类型化 action 前失败。
- `ReplayEngine` 为每次脚本运行生成独立 run ID，只有 provider 在该调用期间返回
  匹配 authorization 后才开启 visual route；provider 缺失、抛错或端口缺失均不调用
  visual executor。run 结束在 `finally` 撤销 authorization。
- N45 planner 只接收 lease 内重写后的 fresh ID/hash/geometry；持久 target 仅作为
  历史提示，并明确拒绝 fresh ID 与历史 ID 相同。production executor 在 step 获取、
  动作提交前和动作后验证三个阶段复核授权时窗与 serial。
- typed Accessibility action 仍显式绑定目标包，不新增裸坐标或 semantic fallback。
  明确拒绝记零提交；dispatch 抛错记 `ACTION_COMMIT_UNKNOWN` 与保守一次可能提交，
  step lease 已消费且 `ReplayEngine` 不进入 retry。
- 成功动作必须通过同一 run authorization 的 `verifyAfter` 重新观察；post evidence
  必须绑定 fresh observation、相同 serial/package/screen，时间更新且状态变化。
  provider 缺失、抛错、旧 evidence 或漂移均记一次提交并停止。
- `RecordingHost` 将编辑 observation provider 与 replay authorization provider
  分成两个独立、默认 `null` 的参数，避免编辑截图授权隐式提升为执行授权。
  `RecordingViewModel` production factory 注入 fresh-registry N45 executor；普通 App
  入口没有 replay provider，因此历史视觉脚本默认失败关闭。

## Round 3

- RED 后 core/UI 定向测试最终通过，覆盖旧 ID、provider 缺失、run/script/revision/
  step、serial/package/screen/hash/candidate/confidence/expiry 漂移、单次消费、run
  开始后过期、unknown commit、后置重新观察和无 fallback。
- 最终 `:app:testDebugUnitTest` 为 390/390；`:app:lintDebug` 为 0 error/40
  warning，`:app:assembleDebug` 与 `compileDebugAndroidTestKotlin` 通过，但本轮
  未在 emulator 或真机执行 instrumentation。
- 仓库级 `make test`、`make verify`、`make build` 通过；`make comments` 覆盖
  428 个手写文件及 14 个检查器正反例，`git diff --check` 与允许范围检查通过。
- 本 change 未修改 Bridge、Go、protocol、N45/N51 spec、test-lab runner 或
  `docs/next-phase-tasks.md`。Bridge/N47 desktop visual port、production provider
  的外部授权调用方和 API 30/33/34 设备证据仍未接入；N47 主任务保持未勾选。

## Round 4

- 实现提交为 `04e3e34`（`feat(recording): require fresh visual replay rebind`）。
  Author/Committer 均为 `lejunyang <lejunyang@qq.com>`，提交末尾恰好一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`。
- 提交包含 13 个文件，全部位于本 change spec、允许的 recording/replay/visual、
  `RecordingHost.kt`、`RecordingViewModel.kt` 及对应 JVM 测试范围。提交后 worktree
  干净；未修改其他并行 worktree。
- 规格证据只确认本 change 的 fresh rebind 前置完成，不代表 Bridge/N47 desktop
  port、production provider 外部调用方或任何设备命中已交付；N47 保持未勾选。
