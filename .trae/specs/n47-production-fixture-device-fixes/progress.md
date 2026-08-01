# N47 Production Fixture 设备前置修复进度

本文件 append-only，记录真实设备前置失败、根因修复、清理和复验证据。

## Round 1

- API 30 第一次 production fixture 在 emulator 启动前返回 `ERR_INVALID_THIS`。
  直接 probe 可用，进一步定位到真实场景 run ID 生成；默认依赖把
  `crypto.randomUUID` 作为未绑定方法保存，Node 24 调用时拒绝。失败后规范化设备
  列表为空，runtime、AVD/port lease 和 `n47-production-*` 临时目录均为空。
- 修复为绑定包装函数，并新增不注入 fake UUID 的 production port 回归测试。
- API 30 第二次进入场景 setup 后返回 `SCENARIO_CLEANUP_FAILED`。审计确认单轮只安装
  App 与当前 fixture，但 cleanup 对未安装的第三个固定包执行 `pm clear`，使补偿被
  误报为失败。外层 finally 后设备、runtime、lease 和临时目录再次全零。
- 清理范围改为固定 App 与 `context.targetPackages` 中唯一当前 fixture；未知、重复或
  非固定包失败关闭，测试断言每轮只执行两次 `pm clear`。
- API 30 第三次在动作前返回
  `PRODUCTION_FIXTURE_AUTHORIZATION_SIGNER_REJECTED`。仓库外固定 build-tools
  `36.0.0` 离线复核 App 与 androidTest APK，二者均为单 signer 且 SHA-256 都是
  `a1bb4a7cdfacab4e6cfd050225ae2d6dfd8b8194d172f4f0326eeb585e1aae36`。
  根因是 parser 只接受冒号分隔摘要，而实际工具输出连续 64 位 hex。
- Parser 现只接受连续 64 位 hex 或严格 32 字节冒号分隔 hex；重复摘要仍因结果数不为
  1 而拒绝。Provider 定向 7/7、N47 runner 74/74、`make comments` 和
  `git diff --check` 通过。
- 三次失败均没有被改写为成功，也没有在未知提交后重放动作；每轮结束后的规范化设备、
  runtime、lease 与 production 临时目录均为零。

## Round 2

- API 30 在 signer 修复后启动 owned `emulator-5570`，N31 aggregate fingerprint、
  build fingerprint、Android 11/API 30 与 `clean` marker 均匹配。该轮最终在固定
  instrumentation 上限后返回 `PRODUCTION_FIXTURE_RESIDUE_DETECTED`。
- 外层退出后规范化设备列表、runtime、AVD/port lease、production 临时目录和本轮
  emulator 日志均为空，emulator PID 已退出；因此宿主物理残留为零。Provider 因
  instrumentation/control 未完成而保守记录 test service 与 Bridge session 非零，
  没有把未知清理状态伪报为零。
- 静态时序确认 ADB forward 的本地 socket 可在设备 endpoint 尚未 bind 时接受连接；
  旧 host 直接发送一次 setup，失败后 instrumentation 仍在设备端等待控制连接，host
  又等待其 10 分钟总超时，形成确定的 readiness 竞态。
- 控制协议新增只读、幂等 `probe`：host 对 probe 可在 60 秒内有界重试，收到严格
  readiness envelope 后才单发 setup。Setup 一旦写出仍不得重放；probe 前失败则固定
  force-stop instrumentation/App、移除 control forward，不等待长生命周期。
- Android endpoint 严格验证 probe 的固定 token 和 run ID，只返回 readiness，不启用
  Accessibility、不启动 Bridge、不修改 App 设置。Node 定向 12/12、core JVM 与
  `compileDebugAndroidTestKotlin`、comments 和 diff-check 通过。

## Round 3

- Readiness probe、细分阶段错误码和改用 Gradle connected instrumentation 的实验均
  未能让 API 30 endpoint 在有界窗口内监听 `38484`。仓库外诊断仅得到被安全终止的
  instrumentation 进程，stdout/stderr 为空；诊断脚本和临时报告已立即删除。
- 根据“未验收能力不进入最终实现”的规则，上述未闭环 probe/Gradle 启动实验已完整
  撤销。最终代码只保留三项有明确真实证据且离线回归通过的修复：Node 24 默认 UUID
  绑定、按本轮已安装包清理、兼容固定 `apksigner 36.0.0` 连续 hex signer 输出。
- 最后一次 API 30 仍在 authorization endpoint readiness 阶段失败，API 33/34 未
  启动；N47 与 N52 保持未完成。所有设备轮结束后规范化设备列表、runtime、
  AVD/port lease 和 `n47-production-*` 临时目录均为空，没有执行 `adb kill-server`
  或对用户真机做任何动作。
- 收敛后 N47 runner 74/74、`make comments` 和 `git diff --check` 通过。endpoint
  readiness 是下一轮唯一设备 blocker，不能通过继续增加超时、重复 setup 或伪报
  residue 为零来绕过。

## Round 4

- 最终 signer parser 会先统计全部 `Signer #N certificate SHA-256 digest` 行，要求
  恰好一条且必须为 `Signer #1`；连续 hex 与冒号 hex 均支持，真实双 signer 输出在
  instrumentation 启动前拒绝。授权 provider 定向 8/8、N47 runner 74/74。
- N52 全量 61/61、`make test`、`make verify`、`make build` 和 Android
  `testDebugUnitTest`、`lintDebug`、debug/androidTest/release 136 tasks 全部通过；
  release APK test-control scanner 为 0 findings。
- SDK、JDK、Gradle、Go、AVD、Go cache、module cache、Gradle cache、emulator state
  与 APK cache 的实际环境变量全部位于
  `/Volumes/aigo S7 Media/SDK/android-tools`；zsh/bash 导入文件均保持一行 source。
  旧 `/Volumes/aigo S7 Media/Projects/SDK` 已不存在。
- 已删除外置工具根的 `.DS_Store`、顶层 Gradle worker/report 临时文件和临时
  discovery 二进制；保留 Gradle native `.tmp/.cache` 必要展开内容。清理后
  `gradle --version` 与 wrapper 均可启动，最后一轮全量验证再次通过。
- N47/N52 因设备 endpoint 和真实矩阵缺口仍保持未完成；N53/N54 条件未满足，未开始。
