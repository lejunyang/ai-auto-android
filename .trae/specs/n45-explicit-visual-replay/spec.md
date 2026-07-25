# N45 显式坐标与视觉步骤回放规格

## 目标

为 `visual`、`coordinate`、`manual` 来源的 tap、long-click 和 swipe 提供显式、
可验证的坐标回放路径。该路径不依赖语义 selector，不使用录制时绝对像素直接猜测
当前位置，并保持 `semantic` 来源的既有回放行为不变。

## 坐标契约

- N44 observation 的 `screen.width/height` 表示自然方向尺寸，`crop` 位于采集时旋转
  后的 capture plane；候选点和边界是经 N44 逆 crop/rotation 映射后的自然方向
  归一化坐标。
- 回放先把候选从自然方向映射回原 observation crop 的相对位置，再映射到当前窗口
  去除系统栏 inset 后的可交互区域；当前窗口偏移、分辨率、rotation 和 density
  变化不得退化为原始像素复用。
- tap 与 long-click 使用唯一候选的归一化点；swipe 在存在候选边界时把录制端点解释
  为候选区域内的相对参数，否则解释为 observation crop 内的相对参数。
- 坐标、边界、crop、window、inset、自然屏幕尺寸、rotation 或 density 元数据缺失、
  不一致或越界时失败关闭，不做 clamp 猜测或隐式降级。

## 执行安全边界

- 每次执行前绑定 step 的 observation ID、唯一候选、前台包、允许包、录制屏幕元数据、
  当前屏幕元数据、过期时间及安全窗口状态。
- 低置信度、多候选、过期 observation、前台包变化、`FLAG_SECURE`、不支持动作或
  observation 漂移均在 typed action executor 前失败，动作提交数为零。
- 只允许类型化 Accessibility tap/press/swipe；禁止 raw `sendevent`、shell、
  剪贴板、DOM/JS 注入和隐式坐标猜测。
- typed executor 返回成功后必须调用注入的只读 observation/snapshot verifier。
  verifier 缺失、拒绝、抛错、返回旧 evidence、包变化、安全窗口或屏幕元数据漂移
  均明确失败；由于动作已经提交，该失败不得按 retry policy 自动重放。
- observation 在全部成功和失败路径撤销。前置失败及动作执行失败保持成功提交数为零；
  后置验证失败明确记录已有一次成功提交，避免把已发生副作用描述为零。

## ReplayEngine 集成

- `visual`、`coordinate`、`manual` 来源跳过语义 selector 预检，只进入 N45 显式端口。
- 未注入 N45 端口时返回稳定 `VISUAL_REPLAY_UNAVAILABLE`，不得回落到旧绝对坐标。
- `waitBefore` 在动作前只读检查，`waitAfter` 在 N45 verifier 成功后检查；任一失败都
  停止当前脚本。显式动作单次提交，不因后置失败重复执行。
- `semantic` 和 `imported` 来源继续使用现有 `ReplayGateway`、selector 与 retry
  行为，本任务不改变其动作语义。

## 允许修改

- `.trae/specs/n45-explicit-visual-replay/`
- `android/app/src/main/java/dev/aiauto/android/automation/recording/ReplayEngine.kt`
- `android/app/src/main/java/dev/aiauto/android/automation/recording/replay/visual/`
- 必要的 accessibility typed coordinate action/transformer
- 上述范围对应的单元测试

## 禁止事项

- 不修改 recording editor UI/domain、N44 registry/proposal、协议 schema/fixture、
  Provider/MCP、共享路线图或 Gradle。
- 不注册新的截图入口，不伪造 observation、后置验证、设备测试或 `FLAG_SECURE`
  可见性。
- 不修改或吸收其他并行 worktree，不提交构建产物、截图、缓存或设备状态。

## 验收

- 先观察 N45 生产类型缺失导致定向测试失败，再实现最小生产代码。
- 单元测试覆盖至少三种自然分辨率、0/90/180/270、density 变化、系统栏/window
  offset、crop 逆映射、相对 swipe、过期、包变化、低置信度、多候选、安全窗口及
  后置验证失败。
- N45 定向测试、`:app:testDebugUnitTest`、`:app:lintDebug`、`make comments` 与
  `git diff --check` 通过。
- API 30/33/34 fixture 命中只有实际执行后才能勾选；本 change spec 不以 JVM
  映射测试冒充设备准确命中。
