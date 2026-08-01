# N33 Clean Snapshot 重复矩阵进度

本文件 append-only，记录生命周期根因、RED/GREEN、设备统计、清理和集成证据。

## Round 1

- ListView 纵向场景已在 API 30 clean emulator 单轮通过，但完整 `all` 场景首次运行在
  Home 后 Launcher 重入失败：Fixture 已恢复前台，`home_return_state` 未出现
  `SYSTEM_RETURN:HOME`。
- 当前状态机在标记导航后，仅凭任意一次 `onResume` 消费 pending；它不能证明
  Activity 已真实离开前台。修复必须增加 `onPause` 离开握手，禁止通过增加等待、
  重放 Launcher intent 或放宽 UI 后置断言掩盖竞态。
- 本轮先用 JVM 测试固定“标记后额外 resume 不消费、pause 后 resume 恰好消费一次”
  的契约，再运行唯一 API 30 clean 系统导航单轮。

## Round 2

- JVM RED 先因缺少 `recordForegroundExit()` 编译失败；两阶段状态机实现后，只有
  pending 已标记且确实发生前台离开时，首次返回才会提交 `SYSTEM_RETURN`，重复返回
  保持幂等。模块 test、debug/AndroidTest APK 与 lint 共 78 tasks 通过。
- API 30 `emulator-5582` 的 system-only 单轮 1/1 通过，但首次完整矩阵稳定 0/20，
  失败均为 Home 重入后下方 system 节点不可观察。增强有界断言后确认
  `PAGE:MAIN` 仍存在，而 system 节点与 pending 节点均不在可见树。
- 新的 API 30 core RED 证明 Reset 后 `text_input` 仍聚焦。根布局改为可在触摸模式
  接管焦点，Reset 显式请求根焦点后，完整 `all` 单轮通过；修复前 0/20 报告以
  `n33-native-matrix-api-30-before-focus-fix.json` 保留为 `0600` 外置证据。

## Round 3

- 焦点修复后 API 30 首次完整矩阵为 20/20；API 33 首轮却稳定 0/20，均停在
  Recents。`UiDevice.pressRecentApps()` 与 `UiAutomation.performGlobalAction`
  都返回成功，但 `currentPackageName` 仍报告 Fixture，证明该字段不能单独验证
  API 33 overview。
- 对明确 API 33 `emulator-5566` 只读采集一次 UIAutomator hierarchy，根包为
  `com.google.android.apps.nexuslauncher`，并存在 `overview_panel`；原始 XML 未
  持久化。随后窗口探针确认 application window 包集合恰好只有 launcher，但该
  overview window 没有 `isActive`，因此最终使用去重后的唯一 application window
  包作为 Recents 后置证据。
- API 33 回到 Fixture 后 pending 仍为 `RECENTS`，进一步证明 overview overlay
  不触发 Activity `onPause`。`onWindowFocusChanged(false/true)` 被接入同一幂等
  两阶段握手，覆盖失焦 overlay；Home 与 Settings 继续由 `onPause/onResume`
  覆盖。API 33 system 单轮随后通过，API 30 system 单轮防回归也通过。
- API 33 修复前 0/20 报告保留为
  `n33-native-matrix-api-33-before-root-observation.json`；API 30 中间 20/20
  报告保留为 `n33-native-matrix-api-30-before-recents-fix.json`。这些历史报告不
  覆盖最终代码证据。

## Round 4

- 最终代码在 N31 clean snapshot 上串行运行 API 30 `emulator-5578`、
  API 33 `emulator-5580`、API 34 `emulator-5582`；fingerprint 分别为
  `51ec5c4a…2707`、`082695b5…f86f`、`1ac57e68…ea8f`。
- 三个 profile 各 20/20，总计 60/60，成功率均为 100%。每轮固定
  `fixtureScenario=all` 与 `fixtureRepeat=1`，覆盖输入、点击、长按、真实纵横
  offset、dialog、三页 Back 栈、Home、Recents、Settings 与 Launcher 重入。
- 三份最终报告 `n33-native-matrix-api-{30,33,34}.json` 均为 `0600`，不含
  hierarchy、截图、stdout/stderr 或 stack。最终 doctor 健康，设备、runtime、
  AVD/port lease、owned emulator 和 `n33-matrix-*` 临时目录均为零。
- 提交前验证通过：Node 238/238，`make test`、`make verify`、`make build`，
  Android 全模块 test/lint/debug/androidTest/release 共 374 tasks，comments 与
  diff-check。首次 Node 与 Android 并行时，repository scan 仅因 Gradle 正在生成
  两个 ignored debug APK 而失败；Android 完成后执行 `gradlew clean`，串行重跑
  Node 238/238 通过，Git 历史和干净工作区 APK 路径均为零。
- N33 runner fake 测试已加入 `verify.yml` 的 Ubuntu、Windows、macOS portability
  job，仓库实现只依赖显式 `AACTL_TOOLCHAIN_ROOT`，不硬编码本机卷名或用户目录。

## Round 5

- 实现提交 `db2c268` 的 Author 与 Committer 均为
  `lejunyang <lejunyang@qq.com>`，要求的 co-author trailer 恰好一次；`main` 在
  干净状态下以 fast-forward 等价集成该提交。
- 临时 worktree `/private/tmp/ai-auto-n33-matrix` 在确认干净且提交已是 `main`
  祖先后，使用非强制 `git worktree remove` 清理；随后完成 `worktree prune` 并
  删除已合入分支 `n33-clean-repeat-matrix`。最终只保留 `main` worktree。
