# N45 授权视觉生产接线规格

## 目标

把 App AI 会话中用户授权的 N44 observation 接到 N45 显式视觉执行内核，封闭
Provider 直接返回无证据 `ui.tap`/`ui.swipe` 后进入通用坐标 executor 的路径。
视觉 evidence 只在单步 session 内存计划中存活，不写入协议、脚本、日志或持久存储。

## 计划与生命周期

- `SessionPlanner` 返回 action 与可选的 session-only execution context。
- 普通语义 action 没有 context，行为保持不变。
- 视觉 action 必须绑定仍存活的 observation lease、PNG SHA-256、唯一候选、置信度、
  前台包和屏幕元数据；缺一项即零提交拒绝。
- Engine 在风险检查、人工确认、执行前重新观察、typed action 和执行后验证期间持有
  context，并在成功、拒绝、失败、超时、停止和取消的 `finally` 中关闭及清零。
- context 只能消费一次；已关闭、过期或已消费的 context 不得重试。

## Provider 契约

- 授权截图 prompt 要求视觉 tap/long-click/swipe 返回严格 `visualTarget`：
  `packageName`、`observationId`、`imageSha256`、`candidateId`、`source`、
  `confidence`、归一化 `point` 和 `bounds`。
- `source` 首轮只接受 `model`；置信度不低于 N45 门槛，点必须位于 bounds。
- `ui.tap` 不再接受 App AI 会话中的裸绝对坐标；视觉 long-click 与 semantic target
  严格二选一。视觉 swipe 的 start/end 是候选 bounds 内 0..1 相对端点，不能作为
  屏幕像素解释。语义 click/setText/scroll 继续使用 selector。
- 未授权截图时 Provider 返回视觉 action 必须在动作提交前失败，不回落旧坐标。

## 生产端口

- screen reader 读取自然尺寸、rotation、density、窗口 bounds、系统栏 inset、
  前台包和 secure 状态；缺失或漂移失败关闭。
- typed executor 只执行目标包绑定的 Accessibility tap/long-click/swipe。
- verifier 在动作后重新观察，要求目标包和屏幕元数据稳定、证据时间更新且状态变化；
  无法验证时保留一次提交并明确失败，不自动重放。
- source observation、候选和图片在所有路径撤销；后置 observation 也只在验证期存活。

## 允许修改

- `.trae/specs/n45-authorized-visual-production-ports/`
- `android/app/src/main/java/dev/aiauto/android/automation/session/`
- `android/app/src/main/java/dev/aiauto/android/automation/recording/replay/visual/`
- `android/app/src/main/java/dev/aiauto/android/accessibility/` 的窄 production port
- `android/app/src/main/java/dev/aiauto/android/provider/` 的视觉 action 严格解析/提示
- 上述目录对应 JVM 测试

## 禁止事项

- 不修改 recording models/store、Bridge/公共协议、Go/N47/N52、编辑器、Gradle 或路线图
- 不持久化 observation、图片、候选或视觉 action context
- 不开放 raw ADB、shell、sendevent、剪贴板、DOM/JavaScript 或未绑定坐标
- 不把 JVM/fake/编译证据描述为 API 30/33/34 设备命中

## 验收

- RED 测试证明 visual lease 当前在 planning 后关闭且裸坐标可以进入 executor。
- 语义路径回归不变；视觉路径覆盖授权成功和全部零提交拒绝门。
- 后置验证失败明确一次提交，Engine 不重放且 finally 清理。
- Provider/Session/N45/Accessibility 定向、App 全量 JVM、lint、AndroidTest 编译、
  comments、verify 和 diff-check 通过。
- API 30/33/34 真实命中保持未验收。
