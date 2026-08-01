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
