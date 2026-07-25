# N47 通用场景 Runner 进度

本文件 append-only，记录 RED/GREEN、fixture 证据、安全拒绝和未完成设备边界。

## Round 1

- worktree `/private/tmp/ai-auto-n47-runner` 位于 `phase2-n47-scenario-runner`，
  基线 `de032b2`，开工时干净；Git 身份为
  `lejunyang <lejunyang@qq.com>`。
- 已审计 N34 `scenario-result`/collector、N33 native fixture、N42 Web/Canvas
  fixture、N43 语义回放、N45 显式视觉回放和 N46 verified lifecycle。
- 当前缺少统一场景 DSL、页面分类、route 选择、执行 timeline、失败产物端口和兼容
  聚合。先新增严格 Schema 与 RED 测试，不修改 Android fixture 或既有安全内核。
- N47 采用 fixture-first：本轮不下载、安装或提交第三方 APK，不运行账号、发送、
  购买、登录或系统安全设置动作。

## Round 2

- 先增加 Schema、runner、聚合和 fixture 测试；首次 `npm test` 三个测试文件分别因
  `schema-validator.mjs`、`runner.mjs` 和 `aggregation.mjs` 不存在而有效失败。
- 定义三份严格 Schema：场景 DSL、运行报告和兼容汇总。场景固定 `none` 副作用策略、
  包 allowlist、10 类动作、pre/post condition、超时、route/score、页面分类和归一化
  动态区域；未知字段、动作、包和越界/零面积区域均拒绝。
- runner 只通过 observer/conditions/router/executor/values/artifacts/lifecycle 窄
  端口运行。每步绑定最新 observation；包、时效、页面、route、score 或 observation
  漂移在 executor 前失败。
- 页面分类对 advertisement、update、emulator、network、login-wall 和 unknown
  默认零提交，A-B variant 只有步骤显式接受时继续；不实现自动关闭弹窗路径。

## Round 3

- native/Web/Canvas 两个 fixture 场景动态执行 18 步，联合覆盖 launch、tap、input、
  long-click、scroll、swipe、Back、Home、Recents、switch-app 和 semantic/hybrid/
  visual route。资源契约验证所有目标存在于既有 strings/离线 HTML。
- input 只以 `valueRef` 存于场景，明文仅在 executor 调用栈内出现；报告和 N34 run
  不包含 value、selector、坐标、route token、截图、hierarchy 或自由异常。
- executor 明确 `committed:false` 才记录零提交；成功为一次；异常或畸形返回记为
  `ACTION_COMMIT_UNKNOWN` 与 `actionCommits:null`，禁止伪报零或重放。
- 后置失败、post 页面异常和 deadline 超时保留一次提交且不重放；取消和动作前失败
  保持零提交。成功、失败、取消都尝试 stop/close/clear/restore 四步。
- 失败 run 在 cleanup 前通过 N34 `scenario-result` Schema 后交给 artifact 端口；
  collector 异常只返回稳定码并继续清理。业务失败后 cleanup 再失败时，collector
  保留原业务错误，最终 report 标记 `SCENARIO_CLEANUP_FAILED`。
- 兼容聚合输出 run/step 成功率、route/page/error/failure category 计数，拒绝混合
  scenario、重复 iteration 和内部矛盾 report，输入顺序不影响结果。

## Round 4

- N47 smoke 最终通过 3 份 Schema、2 个 fixture 场景、N34 结果映射和 25/25 测试；
  N34 artifacts smoke 49/49 同时通过，证明未放宽既有产物契约。
- `make comments` 覆盖 350 个手写文件并通过 14 个检查器自测，`git diff --check`
  通过；目录中没有 APK、截图、日志、账号或第三方包数据。
- 当前只有 runner 安全内核、Schema、fake fixture 和聚合器。production
  observer/router/executor adapter 与 emulator 设备运行尚未实现，因此路线图 N47
  必须保持未完成，不能用 fake fixture 证据冒充真实 App 或设备验收。
