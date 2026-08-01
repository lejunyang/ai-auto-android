# N47 可信桌面视觉动作窄端口规格

## 目标

新增原子 `propose-and-execute` 桌面视觉动作端口。单次调用内完成 fresh
device/hierarchy/PNG 采集、唯一候选生成、N45 production
preflight/planner/package-bound Accessibility 执行与动作后重新观察，不向调用方返回
候选坐标、图片、租约或可长期复用 handle。

## 原子契约

- 输入只接受明确 `device`、`expectedPackage`、role/label 目标和
  tap/long-click/swipe 类型化动作意图；不接受屏幕坐标、candidate ID、observation
  ID、PNG hash、路径、shell 或任意动作 JSON。
- Go 服务在同一调用内按固定顺序采集 fresh device、hierarchy、PNG、hierarchy、
  device，生成唯一 template candidate，并绑定 serial、设备 evidence fingerprint、
  target package、observation ID、PNG hash、crop、自然屏幕、rotation、confidence
  和 expiry。
- 动作提交前再次读取 device 与 hierarchy；serial/fingerprint、package、screen、
  hierarchy、PNG hash 或 expiry 任一漂移都必须零动作失败关闭。
- Bridge 原子请求携带完整短期 evidence，Android 端读取 production
  density/window/insets/rotation/secure 状态，调用 N45
  `ExplicitVisualReplayPlanner` 和 package-bound Accessibility executor。
- Android 端动作后重新读取 hierarchy 与 screen evidence。动作明确拒绝返回零提交；
  动作已提交后的 transport、解析或 verification 异常返回 unknown commit，不得自动
  重放。
- Go 服务在 Bridge 返回后再次读取 hierarchy 与 device，验证目标包、设备 fingerprint
  和 fresh 状态；成功只返回 observation ID、设备 fingerprint、route、verification
  和 commit 状态，不返回 candidate geometry。
- PNG 只在服务调用栈内存活，Bridge write 成功、失败、取消及任意返回路径均清零；
  不注册 N44 可返回租约，不复用返回后已关闭的 proposal lease。

## 授权与发布边界

- production 端口要求既有 Bridge 会话和 Accessibility 授权，不建立或绕过人工授权。
- release、真机或未授权路径默认失败关闭；自动设备入口只允许 debug/test disposable
  emulator，由现有测试控制面显式建立。
- `android_action_execute` 裸坐标工具不参与实现，既有工具兼容行为不扩权。

## 允许修改

- `.trae/specs/n47-attested-visual-action-port/`
- `internal/visual/`、`internal/service/`、`internal/bridge/`、`internal/cli/`、
  `internal/mcpserver/` 中直接相关文件与测试
- `android/app/src/main/java/dev/aiauto/android/bridge/` 和对应 JVM tests
- 仅在严格 wire schema/fixture 无法内联表达时修改 `protocol/`

## 禁止事项

- 不修改 `docs/next-phase-tasks.md`，不勾选 N47。
- 不修改 N45、recording/replay/editor、test-lab runner、SDK/cache 或 GitHub
  workflows。
- 不返回或持久化坐标、图片、候选、observation lease、token、路径或设备运行产物。
- 不把 fake/JVM 证据描述为 production provider、真机或 API 设备命中。

## 验收

- RED 先证明原子服务、Bridge 方法和无坐标 MCP 工具尚不存在。
- Go race/vet 覆盖成功、低置信度、多候选、secure、package/screen/device/hash/expiry
  漂移、write 清零、明确拒绝和 unknown commit。
- Android JVM 覆盖严格解析、N45 planner、production typed executor、pre/post
  evidence、一次性消费和提交后异常不重放。
- 运行受影响 Android `testDebugUnitTest`、`lintDebug`、`assembleDebug`，
  `make comments`、`make test`、`make verify`、`make build` 与
  `git diff --check`。
- production provider 与真实 device evidence 保持明确未验收，N47 路线图保持未勾选。
