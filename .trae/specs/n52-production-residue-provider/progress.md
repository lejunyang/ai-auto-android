# N52 Production Residue Provider 进度

本文件 append-only，记录 concrete inspector 的 RED/GREEN、安全边界和验证证据。

## Round 1

- 基线为 `main` 的 `ec0c596`，分支 `n52-production-residue-provider`，独立 worktree
  `/private/tmp/ai-auto-n52-production-residue-provider`；开工时主工作区和新 worktree
  均干净。
- 审阅 N31 runtime/lease 文件格式、N47 production fixture residue fake、N34 artifact
  安全边界及既有 N52 production ports。N47 的 residue 依赖场景内授权状态，不能直接
  作为 N52 stop 后的 production provider。
- 当前没有可供 N52 host 使用并能证明 App 数据、Bridge session、测试服务与 owned
  process 身份的完整 typed provider。默认 factory 必须在 capability 阶段返回
  `PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE`，保持 emulator 启动数为零。
- 可安全交付的固定文件系统子集为 N31 runtime、双 lease、N31 log，以及 N52 固定
  state/report/staging roots 下绑定当前设备的 screenshot/log。所有目录与普通文件
  都需执行 lstat/realpath、预算和身份竞态检查。
- 基线 `npm test --prefix test-lab/matrix/local` 为 41/41。RED 新增 11 个主题测试，
  首次定向运行因 `src/production-residue.mjs` 不存在得到明确
  `ERR_MODULE_NOT_FOUND`；未调用 SDK、ADB、emulator 或设备。

## Round 2

- GREEN 新增 `production-residue.mjs`，只读取 N31 profiles、固定 state/report/
  staging roots 和固定 package 集。App 数据、Bridge session、测试服务与 owned
  process 由四个窄类型 provider 提供；默认 providers 为空，因此 production
  capability 返回 `PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE`。
- screenshot/log 遍历、runtime JSON、双 lease owner 和 N31 log 均从固定路径推导；
  不接受调用方路径，也不信任 runtime 内的 lock/log 自由路径。JSON 通过
  `lstat -> open(O_NOFOLLOW) -> fstat -> read -> fstat -> lstat/realpath` 复核。
- 所有目录执行逐层 `lstat`、`realpath` 和前后 dev/inode/mode/size/mtime/ctime
  identity 校验；artifact 固定为 4 层、256 entries、16 MiB，JSON 固定为 64 KiB。
  symlink、特殊文件、超预算、状态竞态和根目录替换全部失败关闭。
- runtime、lease、artifact binding 和 typed provider 返回都逐字绑定
  profile/API/serial/fingerprint/`clean` snapshot。owned process 检查前后重读
  runtime；stale runtime、双 lease、process、截图和日志均报告真实非零。
- 默认 N52 adapter factory 已接 concrete residue provider；由于 typed providers
  尚缺，capability 在 lifecycle `startClean` 为零时失败，未降级为 fake 或常量零。

## Round 3

- N52 最终 `npm run smoke --prefix test-lab/matrix/local` 通过 Schema 和 61/61
  测试；原有 41 项全部保留，新增 20 项覆盖默认零启动、八类零/非零、固定 package、
  symlink、预算、目录与 process identity 竞态、stale runtime/lease、截图日志及
  binding/fingerprint drift。
- N47 `npm run smoke --prefix test-lab/runner` 为 73/73，N34
  `npm run smoke --prefix test-lab/artifacts` 为 49/49。npm 仅输出既有用户级配置
  deprecation 告警。
- `make comments` 覆盖 467 个手写文件和 14 个自测，`git diff --check` 通过。
- 首次沙箱内 `make test/verify/build` 与 Android 验收因既有外置 Go/Gradle cache
  `operation not permitted` 失败；按相同命令在沙箱外重跑后全部通过。Go
  `make test`、`make verify`、`make build` 成功；Android `testDebugUnitTest`
  75 tasks、`lintDebug` 85 tasks、`assembleDebug` 107 tasks 成功，仅有既有 SDK
  XML、deprecated API 和 native library strip 告警。
- 未运行 connected/instrumentation、ADB、emulator 或设备操作，未修改路线图或勾选
  N52。真实 App/Bridge/service/process typed providers 与真实 300 轮矩阵仍未验收。
