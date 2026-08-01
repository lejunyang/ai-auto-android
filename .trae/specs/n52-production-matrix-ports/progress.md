# N52 Production Matrix Ports 进度

本文件 append-only，记录 production 组装、固定 CLI、RED/GREEN 和未完成设备边界。

## Round 1

- 主工作区 `main` 基线 `e804c87` 干净；并行 N47-N50 使用互斥 worktree。本任务在
  `/private/tmp/ai-auto-n52-production-matrix-ports` 和
  `n52-production-matrix-ports` 独立开展。
- 既有 N52 已有严格 300 轮编排、报告 Schema 和 21 项 fake 测试，但没有固定
  production CLI 或 concrete ports 组装。
- 现有 N31 `EmulatorRunner` 可提供固定 profile、clean snapshot、明确 serial、
  fingerprint 和 stop；外置工具根固定为
  `/Volumes/aigo S7 Media/SDK/android-tools`。
- 主线 N47 有严格 runner 与 `aactl` semantic adapter，但没有覆盖五个 N52 场景的
  production fixture provider；recording 与 visual 设备入口也未完整接线。默认
  production factory 必须在 capability 阶段返回稳定 unavailable，设备启动数为零。
- 当前没有可验证八类 residue 的 concrete production provider，不能硬编码全零或用
  fake 冒充。真实 3 × 5 × 20 设备矩阵保持未验收。

## Round 2

- RED 先以 `node --test test/production.test.mjs` 运行，因
  `test-lab/matrix/local/src/production.mjs` 不存在得到明确
  `ERR_MODULE_NOT_FOUND`；未调用 SDK、ADB、emulator 或设备。
- GREEN 新增零参数 `production-cli.mjs`、固定外置配置、可注入 adapter factory 和
  严格 production session。argv 不接受 skip、serial、task、scenario、action、
  report 或 tool root；程序化 ports 同样拒绝额外字段、任意 serial 和计划漂移。
- capability 按 lifecycle、scenario、residue 三类 provider 探测，只有 scenario
  provider 可声明固定九项能力；production CLI 在调用 N52 内核前完成三 profile
  preflight，任一 unavailable 返回稳定错误且 lifecycle `startClean` 调用数保持零。
- session 将每轮 lifecycle 返回的 profile/API/serial/fingerprint/clean snapshot
  与固定 scenario/iteration 绑定；每轮只允许一次 scenario attempt，随后必须
  stop，再按固定顺序执行 App/Bridge/service/screenshot/log/process/runtime/lease
  八类 residue inspector。
- N31 adapter 已接 `verifyProfile`、`start`、`waitForBoot`、clean marker 和 `stop`；
  start 后 marker、fingerprint 或返回校验失败会补偿 stop，stop 失败优先报告清理
  风险。N47 五场景和 residue concrete provider 尚不存在，默认 factory 分别返回
  `PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE` 和
  `PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE`，不使用 fake 或硬编码零。
- 严格 N47 report 需逐字绑定 scenario/iteration 和设备身份；报告通过 N52 Schema
  后才以同目录 `0600` 临时文件、sync、原子 rename 发布，失败删除临时文件且保留
  旧报告。fake 只验证内存中的 300 轮调用，不执行真实设备矩阵。
- 当前定向验证为既有 N52 21/21、新 production 19/19、N47 59/59、N34 49/49；
  `make comments` 覆盖 439 个手写文件和 14 个自测。真实矩阵与 N52 完成项继续
  保持未勾选。

## Round 3

- 最终 `npm run smoke --prefix test-lab/matrix/local` 通过严格 Schema 和 40/40
  测试，其中既有 N52 保持 21/21，新增 production 为 19/19；fake 300 轮只在内存
  中验证固定计划和调用顺序，未启动 emulator。
- N47 `npm test --prefix test-lab/runner` 为 59/59，N34
  `npm test --prefix test-lab/artifacts` 为 49/49；npm 仅输出既有用户级
  `store-dir`、`global-bin-dir`、`global-dir` 弃用告警，命令均退出 0。
- 使用外置工具环境运行 `make test && make verify && make build` 全部通过；首次沙箱
  内运行因外置 `go-cache` 的 `operation not permitted` 失败，获批在沙箱外按相同
  命令重跑后成功，该失败不是代码或测试失败。
- Android `testDebugUnitTest lintDebug assembleDebug` 全部通过，共 152 个 task；
  仅有既有 SDK XML、deprecated API 和 native library strip 告警。未运行
  instrumentation、ADB、emulator 或任何设备操作。
- `make comments` 覆盖 439 个手写文件与 14 个自测，`git diff --check` 通过。
  N47 concrete 五场景 provider、recording/visual 设备入口及八类 production residue
  provider 仍缺失，因此真实 N52 矩阵和主任务不得勾选。
