# N45 视觉动作设备矩阵进度

本文件 append-only，记录 harness 安全边界、设备矩阵、失败、清理与集成。

## Round 1

- worktree `n45-visual-device-matrix` 基于 N44 收口 `a2325c7`，创建时干净；N41 与
  N37/N38 QR 在互斥 worktree 并行，本任务不修改其目录或共享路线图状态。
- N45 production planner/executor 已完成单元与 App 授权 session 接线，但当前没有
  可无人值守执行 production visual action 的 instrumentation。N32 test-control 只
  自动启用 debug screenshot service 与只读 Bridge，不提供 N45 动作测试入口。
- 设备矩阵必须新增 debug/androidTest-only harness，绑定 N31 serial/fingerprint、
  双签名和 marker，并复用 production planner/typed executor；不能直接调用纯 mapper
  或将 JVM 三分辨率测试冒充设备命中。
- 矩阵固定为 3 API × 3 自然分辨率 × 2 rotation；动作后必须重新观察独立状态，禁止
  因返回 true 自动判成功或在未知结果上重放。

## Round 2

- 先增加 host fake 与 instrumentation RED：host 因 N45 runner 缺失失败，Android
  instrumentation 因 debug harness 缺失无法编译；随后实现固定 profile runner、
  debug fixture 和 production executor 到类型化 Accessibility router 的完整接线。
- debug/androidTest harness 复用 `InstrumentationTestIdentityProvider`、
  `SecureAccessibilitySettings`、`ExplicitVisualReplayExecutor` 与生产 command
  映射，绑定 N31 serial/fingerprint、双签名、固定 marker 和六种矩阵几何；真机、
  release、serial/fingerprint/marker 漂移均失败关闭。
- tap、long-click、swipe 使用独立本地后置状态并重新读取 hierarchy；低置信度、
  多候选、过期、包/屏幕/secure 漂移保持零提交，post-fail 固定一次提交且不重放。
- host runner 只接受 `api-30`、`api-33`、`api-34` profile，内置三分辨率与两种
  rotation，拒绝外部 serial/坐标/任意命令；fake runner 7/7 通过，覆盖 JUnit
  新鲜度、多 emulator、serial/fingerprint 漂移、stop 和零残留。
- App compile/debug AndroidTest/release 验证共 104 tasks 通过；`make comments`
  覆盖 414 个手写文件且 14 个检查器正反例通过，`git diff --check` 通过。
- 本 worker 未启动或操作 emulator；API 30/33/34 的真实 18 环境矩阵仍待主线程运行，
  对应 tasks/checklist 保持未勾选，不能把 fake 或编译结果描述为设备命中证据。

## Round 3

- 主线程首次 API 30 矩阵在 release 扫描前失败：runner 固定路径误写为
  `app-release.apk`，而 AGP 9 产物是 `app-release-unsigned.apk`。修正固定路径并对
  N32 Java inspector 与 N45 ZIP scanner 做双重零入口验证；既有 JavaExec 关闭
  configuration cache 后正常通过。
- 修复后 API 30 启动设备，但首个产品轮在 instrumentation 进程以
  `keyDispatchingTimedOut` 终止。限定日志显示 MotionEvent 在 debug Activity
  处理约 4.6 秒；N45 异步 gesture 被平台接受后，verifier 立即递归 snapshot，与仍
  在分发的长按/滑动争用同一窗口。
- debug harness 现仅在动作被接受后等待该动作 duration 加 250ms，再开始只读后置
  观察；不修改 production executor、不重放动作、不放宽后置条件。两次失败均由
  runner finally 恢复/停止，设备、runtime、lease 与临时目录为零。

## Round 4

- 上述 settle 修复编译通过后，API 30 首环境仍以同一
  `keyDispatchingTimedOut` 失败。新鲜 JUnit 为 1 test/1 failure/0 skip，限定日志
  显示 instrumentation `UiAutomation` 持有 accessibility cache，debug App 主线程与
  production `UserTouchMonitor` 的事件归因路径分别等待该 cache 约 5 秒，MotionEvent
  最终耗时 4659ms；问题发生在 verifier 之前，因此继续增加 settle 无法修复根因。
- API 30 不支持 API 31 才加入的 `UiAutomation.FLAG_DONT_USE_ACCESSIBILITY`。
  runner 改为在每次 clean restore 后，仅对显式 owned emulator 安装固定 debug APK、
  写入唯一固定 test accessibility component，并回读 component 与 enabled 状态；
  instrumentation 不再创建 `UiAutomation` 或持有 shell identity。
- host 预置不接受调用方 serial、APK、package、component 或任意 shell 参数，回读漂移
  失败关闭。production planner、typed Accessibility router/backend、
  `UserTouchMonitor` 和动作后 hierarchy 观察保持不变；失败轮仍由 finally 完成
  restore/stop，确认设备、runtime、lease、lock 与 N45 临时目录为零。

## Round 5

- host 固定 service 预置与回读均成功，但去掉 test-control `UiAutomation` 后，
  Android instrumentation 默认抑制无障碍服务；API 30 首环境在动作前等待连接 15 秒
  后以明确 `Enable Accessibility device test manually` 失败，并未再次出现
  accessibility cache contention。该轮同样恢复、停止且确认零残留。
- API 30 没有 `FLAG_DONT_USE_ACCESSIBILITY`，因此 test-control 继续以
  `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 连接 automation，仅用于启用并确认
  固定 debug 服务；服务稳定后把 automation `eventTypes` 和 notification timeout
  清零、回读确认静默，并在进入动作体前释放 `WRITE_SECURE_SETTINGS` shell identity。
- finally 恢复阶段才短暂重新取得最小 shell permission；N45 production
  `UserTouchMonitor` 仍接收 fixture 的语义事件，instrumentation automation 不再消费
  accessibility events。host fake 9/9 与 AndroidTest 编译通过，设备效果待下一轮
  API 30 矩阵验证。

## Round 6

- host 预置与 test-control 同时存在时，API 30 的 enabled service 列表没有发生状态
  变化，系统未重新绑定刚安装的 debug service；第四轮在动作前等待 test service
  连接超时，限定日志没有出现 gesture 或 accessibility cache contention。finally
  仍完成 restore/stop，设备、runtime、lock 与临时目录为零。
- 该证据否定 host 预置方案与当前 clean snapshot/test-control 生命周期的组合，因此
  撤回 runner 中的 APK 安装、secure settings 写入和对应 fake；恢复由已验收
  `SecureAccessibilitySettings` 单点启用/恢复服务。
- 保留 Round 5 的 API 30 automation 静默与最小 shell permission 改动；下一轮只验证
  test-control 正常连接后，动作期 eventTypes 为零是否消除 cache 争用。

## Round 7

- 撤回 host 预置后的第五轮未进入 service 或动作路径：新鲜 JUnit 在 0.093 秒内因
  App 侧 `WindowManager.maximumWindowMetrics` 尚未收敛到 host 已回读的
  `720x1600@0` 而失败；日志没有 `UiAutomation`、gesture 或 cache contention。
- N45 identity provider 现仅对固定 N45 参数轮询最多 10 秒，等待 App 侧 width、
  height 与 420 dpi 全部匹配；超时错误包含期望与最后实际几何，且在身份构建、
  service 启用和动作提交前失败关闭。其他 N31/N32 流程仍单次观察，不引入等待。
- 失败轮由 runner 恢复/停止并再次确认设备、runtime、lock 与临时目录为零。

## Round 8

- 第六轮 `720x1600@0` 已完整通过 production planner、tap/long-click/swipe、后置
  hierarchy 和全部安全拒绝，证明 API 30 automation 静默消除了原 cache 争用。
- 第二环境 `720x1600@90` 在动作前超时，期望 `1600x720@420`、实际始终为
  `720x1600@420`。runner 原先只写并回读 `Settings.System.user_rotation=1`，该配置
  值在 API 30 未强制 WindowManager 实际旋转，因此此前 host 验证是假阳性。
- runner 改用 Android 11 AOSP `wm set-user-rotation lock <0|1>`，并从
  `dumpsys input` 严格解析唯一 `SurfaceOrientation` 作为实际方向真值；App 侧
  metrics 收敛门继续作为第二重验证。缺失、重复、非法 orientation 或 settings
  回退均失败关闭。

## Round 9

- 第七轮在 instrumentation 前以 `DEVICE_CONFIG_DRIFT` 停止；限定 owned API 30
  诊断显示 `set-user-rotation lock 1` 返回成功，WindowManager 为
  `mUserRotationMode=LOCKED`、`mUserRotation=ROTATION_90`，但
  `mFixedToUserRotation=false` 且实际 `mRotation=0`。
- 同次诊断显示 `dumpsys input` 含多个 inactive viewport 的
  `SurfaceOrientation`，不能作为唯一 display 真值。诊断 finally 已 restore/stop，
  设备、runtime 和 lock 为零。
- runner 现先执行 AOSP `wm set-fix-to-user-rotation enabled`，再锁定 0/90；
  严格解析 `dumpsys window displays` 的唯一 `mRotation=<0|1>`，随后仍由 App
  metrics 做第二重收敛验证。命令退出成功但实际 rotation 未变时继续失败关闭。

## Round 10

- 第八轮仍在 instrumentation 前以 `DEVICE_CONFIG_DRIFT` 停止。第二次限定诊断连续
  采样显示 fixed-to-user 已为 true，但 500ms 时 `mRotation=0`，
  1000ms 才稳定为 1；runner 单次等待 500ms 过早。
- host rotation 门改为最多 10 秒、每 250ms 读取一次唯一 `mRotation`；只有实际值
  匹配才继续，命令失败、输出歧义或超时均在安装/动作前失败关闭。fake 明确覆盖首读
  旧值、第二读收敛的 API 30 时序。
- 诊断 finally 和第八轮 runner finally 均完成 restore/stop，设备、runtime 与 lock
  为零。

## Round 11

- 第九轮前五个环境全部通过；最新 `1440x3200@0` JUnit 为 1/1、0 failure、
  0 skip。唯一剩余 `1440x3200@90` 在 instrumentation 前以
  `DEVICE_CONFIG_DRIFT` 停止，runner finally 再次确认零残留。
- 最后环境限定诊断显示 size/density 命令成功，500ms 时 rotation 仍为 0，1000ms
  后稳定为 1，且 `1440x3200` override 与 420 dpi 在整个 10 秒窗口保持稳定。
- host 配置门改为在同一个有界窗口内同时轮询 size、density 与唯一 `mRotation`；
  只有三者同轮一致才继续。瞬时旧值或 WindowManager dump 切换期歧义会继续等待，
  命令失败或最终超时仍失败关闭。

## Round 12

- 原子门后的完整矩阵仍在 App 启动前失败。直接复用 production
  `configureFixedVisualCase` 逐环境诊断确认前五个配置通过，唯一失败为
  `1440x3200@90`；诊断 finally 已 restore/stop 并确认零残留。
- 失败窗口内 22 次 size/density 读取始终为 `1440x3200` 和 420 dpi，
  `mUserRotation` 仅短暂出现一次 90，随后被仍在完成的 snapshot restore 回写为 0；
  所有命令退出码均为 0。
- 有界 host 配置门在实际 rotation 未匹配时重新声明 fixed-to-user 与目标 rotation，
  直至三项同轮一致或 10 秒超时。该重申只修复 snapshot 后的系统配置漂移，发生在
  instrumentation 和任何 App 动作之前，不构成动作重放。
