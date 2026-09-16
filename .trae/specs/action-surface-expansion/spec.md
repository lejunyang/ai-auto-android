# 动作面扩展规格（Action Surface Expansion）

## 背景

当前动作面在 protocol / Android / Go 三条通道上封闭且一致，但底层只有两类原语：

- `NodeAction`：CLICK、LONG_CLICK、SET_TEXT、六向 SCROLL；
- `Gesture`：Tap、Swipe。

`protocol/schema/v1/action.schema.json` 的 `oneOf` 仅 14 项，
`AndroidAccessibilityBackend.dispatchGesture` 只 `addStroke` 一笔（单指、单段、
不可续接），且窗口来源全部取 `service.rootInActiveWindow`，没有 `service.windows`
遍历。这三条事实共同导致真实 App 场景（弹窗、输入法、拖拽排序、缩放、长列表定位、
文本编辑）无法表达。

## 目标

在不破坏既有 14 项动作语义的前提下，把 17 项缺失能力补齐为三层贯通的产品能力。
每项必须同时打通：

1. `protocol/schema/v1/action.schema.json`（+ `ProviderActionParser` 校验）；
2. Android：`AccessibilityModels` → `AccessibilityActionRouter` →
   `AndroidAccessibilityBackend` → `AccessibilityCommandJsonParser` → capability；
3. Go：`internal/service` 常量与接口 → `internal/adb` 或 `internal/bridge` 实现 →
   `internal/mcpserver` schema 与工具面 → `internal/cli`。

只改一层不算完成。

## 能力清单与优先级

### P0（真实 App 会直接卡死）

| 编号 | 能力 | 关键约束 |
| --- | --- | --- |
| A1 | 多窗口模型 | 补 `service.windows` 遍历，覆盖对话框、悬浮窗、IME、分屏、画中画、Toast；`rootInActiveWindow` 降为回退 |
| A2 | `drag` | 长按后拖动，需「按住—停顿—移动」分段时序；`swipe` 不能替代 |
| A3 | `scrollIntoView` / `scrollUntil` | 滚到目标可见；带最大轮次、无进展检测与超时预算 |
| A4 | Bridge 侧 `pressKey` | 按键当前只在 ADB direct 通道；LAN Bridge / 真机路径连 ENTER 都发不出 |

### P1

| 编号 | 能力 | 关键约束 |
| --- | --- | --- |
| A5 | 文本选择 | `ACTION_SET_SELECTION` + 光标移动键（白名单需补 MOVE_HOME/MOVE_END 等） |
| A6 | 复制 / 剪切 / 粘贴 | `ACTION_COPY` / `ACTION_CUT` / `ACTION_PASTE` |
| A7 | 多指与复合手势 | pinch、zoom、旋转、双指滚动；**前置**为 `dispatchGesture` 多 stroke + `willContinue` 续接改造 |
| A8 | 双击 / N 连击 | 同一坐标的多段续接 stroke，间隔需落在系统多击窗口内 |
| A9 | 节点动作补齐 | FOCUS、CLEAR_FOCUS、SELECT、EXPAND、COLLAPSE、DISMISS、SET_PROGRESS、SCROLL_TO_POSITION |
| A10 | 全局动作补齐 | 通知栏、快捷设置、锁屏、截屏、电源菜单 |
| A11 | 设备状态动作 | 旋转屏幕、唤醒、解锁、亮屏检测（N45 的旋转是测试 runner 的 `wm` 命令，**不是**产品能力） |
| A12 | IME 控制 | 键盘显示 / 隐藏、切换测试输入法；缓解非 ASCII 输入对唯一可编辑节点的依赖 |

### P2

| 编号 | 能力 | 关键约束 |
| --- | --- | --- |
| A13 | 剪贴板读写 | `ClipboardManager`；读取受前台限制约束（API 29+） |
| A14 | deeplink 打开 | `am start -a VIEW -d <uri>`，URI 需严格校验且限定目标包 |
| A15 | 文件 push / pull | 路径白名单，拒绝任意宿主路径逃逸 |
| A16 | Toast 捕获 | `TYPE_NOTIFICATION_STATE_CHANGED` + `TYPE_WINDOWS_CHANGED`，有界缓冲 |
| A17 | 路径手势 | 多点连续路径（图案解锁），依赖 A7 的 stroke 内核 |

## 实施顺序

A1（地基）→ A7 的 stroke 内核 → A2 → A3 → A5 + A6 → A8 → A9 → A4 → A10 → A11 →
A12 → A13 → A14 → A15 → A16 → A17。

A1 必须最先落地：它不做，后续任何新动作在弹窗 / IME 场景都定位不到节点。
A7 的 stroke 内核必须在 A2、A8、A17 之前落地，三者共用同一条续接路径。

## 分支与 worktree 策略（对 AGENTS.md 的具体化）

AGENTS.md 要求「相互独立的实现任务使用独立分支和独立 worktree」。本批 17 项**不**
相互独立：全部集中修改同一组核心文件（`AccessibilityModels.kt`、
`AccessibilityActionRouter.kt`、`AndroidAccessibilityBackend.kt`、
`action.schema.json`、`service.go`、`schemas.go`）。并行 worktree 会产生必然冲突，
因此本批采用**单分支串行**，以 Conventional Commit 保证单一职责：
每项能力一个提交，提交边界写在 `tasks.md`。

分支：`feat/action-surface-expansion`（从 `main` 派生，独立 worktree）。

## 允许修改

- `.trae/specs/action-surface-expansion/`
- `docs/action-surface-tasks.md`（本批唯一状态源）
- `protocol/schema/v1/action.schema.json`、`protocol/fixtures/`（新增 fixture）
- `android/app/src/main/java/dev/aiauto/android/accessibility/**`
- `android/app/src/main/java/dev/aiauto/android/bridge/**`
- `android/app/src/main/java/dev/aiauto/android/provider/ProviderActionParser.kt`
- `android/app/src/test/java/dev/aiauto/android/**`（对应测试）
- `internal/service/`、`internal/adb/`、`internal/bridge/`、`internal/mcpserver/`、
  `internal/cli/`
- `scripts/emulator/`（验证脚本）
- `docs/README.md`（仅新增一行导航）

## 禁止修改

- `docs/next-phase-tasks.md`（用户明确要求不写入）
- `.trae/specs/build-ai-android-automation-mvp/` 的既有勾选与验收结论
- 其余已完成 spec 的 `tasks.md` / `checklist.md` / `progress.md`
- `.github/workflows/`、`osdk.toml`、根 Gradle 配置（除非能力确实需要，需单独说明）
- release 变体：不得引入任何 test-only 入口

## 验收标准

- **三层贯通**：每项能力在 protocol、Android、Go 三侧都有实现与测试。
- **真实设备证据**：每项须有模拟器实测记录，含显式 serial、前置观察、动作、
  后置验证、清理结果，并写明实际验证到的 API 版本。未验证的 API 版本如实标注。
- **不得夸大**：设计完成、编译通过、计划中的能力一律不得记为已交付；
  做不到的项写未完成 + 原因 + 影响。
- 简体中文注释覆盖新增手写文件，`make comments` 通过。
- 按改动范围跑定向测试；最终跑 `make test`、`make verify`、`make build` 与
  Android `testDebugUnitTest`、`lintDebug`、`assembleDebug`。
- 不提交 APK、密钥、token、截图、缓存或构建产物。

## 本轮授权边界（用户显式授权，覆盖 AGENTS.md 默认人工授权边界）

用户已明确授权：允许 root 模拟器、自行开启无障碍服务、自行编写测试并用模拟器验证，
无需逐步人工确认。该授权**仅**适用于一次性模拟器；真机的 USB/Wi-Fi ADB RSA、
安装确认、敏感权限仍必须由用户本人完成，本规格不改变这一点。
