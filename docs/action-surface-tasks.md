# 动作面扩展任务跟踪

本文件是「动作面扩展」这批工作的**唯一状态源**。规格见
[`.trae/specs/action-surface-expansion/spec.md`](../.trae/specs/action-surface-expansion/spec.md)，
任务勾选见同目录 `tasks.md`，逐轮记录见同目录 `progress.md`。

本批工作**不**写入 `docs/next-phase-tasks.md`。

## 1. 目标

当前动作面底层只有两类原语：`NodeAction`（CLICK / LONG_CLICK / SET_TEXT / 六向
SCROLL）与 `Gesture`（Tap / Swipe）。`protocol/schema/v1/action.schema.json` 的
`oneOf` 仅 14 项。三条通道封闭且一致，但真实 App 场景（弹窗、输入法、拖拽排序、
缩放、长列表定位、文本编辑）无法表达。

本批把 17 项缺失能力补齐为**三层贯通**的产品能力：

```
protocol schema  →  Android（models / router / backend / bridge parser / capability）  →  Go（service / adb 或 bridge / mcp / cli）
```

只改一层不算完成。

## 2. 能力清单与当前状态

状态取值：`未开始` / `进行中` / `已实现未验证` / `已交付` / `未完成`。
只有三层实现 + 定向测试 + 模拟器实测证据齐备才能记 `已交付`。

### P0（真实 App 会直接卡死）

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A1 | 多窗口模型（`service.windows` 遍历） | 未开始 | — | — |
| A2 | `drag`（按住—停顿—移动） | 未开始 | — | — |
| A3 | `scrollIntoView` / `scrollUntil` | 未开始 | — | — |
| A4 | Bridge 侧 `pressKey` | 未开始 | — | — |

### P1

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A5 | 文本选择（`ACTION_SET_SELECTION`） | 未开始 | — | — |
| A6 | 复制 / 剪切 / 粘贴 | 未开始 | — | — |
| A7 | 多指与复合手势（pinch / zoom / 旋转 / 双指滚动） | 未开始 | — | — |
| A8 | 双击 / N 连击 | 未开始 | — | — |
| A9 | 节点动作补齐（FOCUS / CLEAR_FOCUS / SELECT / EXPAND / COLLAPSE / DISMISS / SET_PROGRESS / SCROLL_TO_POSITION） | 未开始 | — | — |
| A10 | 全局动作补齐（通知栏 / 快捷设置 / 锁屏 / 截屏 / 电源菜单） | 未开始 | — | — |
| A11 | 设备状态动作（旋转 / 唤醒 / 解锁 / 亮屏检测） | 未开始 | — | — |
| A12 | IME 控制（键盘显隐 / 切换测试输入法） | 未开始 | — | — |

### P2

| 编号 | 能力 | 状态 | 验证 API | 证据 |
| --- | --- | --- | --- | --- |
| A13 | 剪贴板读写 | 未开始 | — | — |
| A14 | deeplink 打开 | 未开始 | — | — |
| A15 | 文件 push / pull | 未开始 | — | — |
| A16 | Toast 捕获 | 未开始 | — | — |
| A17 | 路径手势（图案解锁） | 未开始 | — | — |

## 3. 实施步骤与依赖

```
A1（多窗口，地基）
  └─ 所有后续能力的节点定位都依赖它

A7-核心（dispatchGesture 多 stroke + willContinue 续接）
  ├─ A2  drag
  ├─ A8  双击 / N 连击
  ├─ A7-手势 pinch / zoom / 旋转 / 双指滚动
  └─ A17 路径手势

A3  scrollIntoView（依赖 A1 的多窗口快照）
A5 → A6（文本选择先落地，复制/粘贴复用其选区）
A4（按键白名单扩展同时服务 A5 的光标移动键）
A9 / A10 / A11 / A12（相互独立）
A13 / A14 / A15 / A16（相互独立）
```

**串行顺序**：A1 → A7-核心 → A2 → A3 → A5 → A6 → A8 → A9 → A4 → A7-手势 → A10 →
A11 → A12 → A13 → A14 → A15 → A16 → A17。

## 4. 并行波次与目录所有权

本批 17 项**不适合并行**：全部集中修改同一组核心文件——
`AccessibilityModels.kt`、`AccessibilityActionRouter.kt`、
`AndroidAccessibilityBackend.kt`、`action.schema.json`、`service.go`、
`schemas.go`。并行 worktree 会产生必然冲突。

因此采用**单分支串行**（`feat/action-surface-expansion`），以 Conventional Commit
的单一职责保证可回溯：每项能力一个提交。

若后续确需并行，唯一可切分的波次是：

| 波次 | 内容 | 独占目录 |
| --- | --- | --- |
| W1 | A1 + A7-核心 | `accessibility/`（必须先单独完成，是所有人的地基） |
| W2 | A14 + A15 | `internal/adb/`（纯 Go 侧，不碰 Android 动作模型） |
| W3 | A16 | `accessibility/observe`（新增文件，不改路由） |

`protocol/` 清单、根 Gradle 配置与本文件由单一集成负责人串行修改。

## 5. 允许修改范围

- `.trae/specs/action-surface-expansion/`
- 本文件 `docs/action-surface-tasks.md`
- `protocol/schema/v1/action.schema.json`、`protocol/fixtures/`
- `android/app/src/main/java/dev/aiauto/android/accessibility/**`
- `android/app/src/main/java/dev/aiauto/android/bridge/**`
- `android/app/src/main/java/dev/aiauto/android/provider/ProviderActionParser.kt`
- `android/app/src/test/java/dev/aiauto/android/**`
- `internal/service/`、`internal/adb/`、`internal/bridge/`、`internal/mcpserver/`、
  `internal/cli/`
- `scripts/emulator/`
- `docs/README.md`（仅新增一行导航）

**禁止修改**：`docs/next-phase-tasks.md`、已完成 MVP 与其它 spec 的
`tasks.md` / `checklist.md` / `progress.md`、`.github/workflows/`、`osdk.toml`。
release 变体不得引入任何 test-only 入口。

## 6. 验收证据要求

每项能力必须记录：

1. **三层实现位置**：protocol / Android / Go 三侧的具体文件与符号；
2. **定向测试**：测试名与通过结果；
3. **模拟器实测**：显式 serial、API 版本、前置观察、执行的动作、后置验证、
   清理结果；
4. **未验证范围**：如实标注哪些 API 版本未验证，不以「应该可用」代替。

### 当前验证环境

| 项 | 值 |
| --- | --- |
| serial | `emulator-5554` |
| AVD | `osdk-test-35` |
| API | 35（Android 15） |
| 分辨率 | 320x640 |
| ABI | x86_64 |

`scripts/emulator/profiles.json` 声明的 API 30 / 33 / 34 镜像在本机**未安装**，
故本批默认只能给出 API 35 证据；30 / 33 / 34 的矩阵验收保持未完成。

## 7. 提交边界

每项能力一个提交，格式见
[`.trae/specs/action-surface-expansion/tasks.md`](../.trae/specs/action-surface-expansion/tasks.md)
的「建议提交边界」表。提交末尾保留且仅保留一次
`Co-authored-by: TRAE CLI <noreply@bytedance.com>`。

不提交 APK、密钥、token、截图、缓存或构建产物。

## 8. 失败与回滚清理

| 情形 | 处理 |
| --- | --- |
| 某能力模拟器验证失败 | 保留提交，在第 2 节写 `未完成` + 原因 + 影响，不勾选 `tasks.md` |
| 模拟器进入不确定状态 | `adb -s <serial> emu kill` 后从 clean 快照重启；**不用** `adb kill-server` |
| schema 改动破坏既有 fixture | 回滚该提交单独修，不与能力实现混在一个提交里 |
| worktree 清理 | 确认干净后非强制 `git worktree remove` + `git worktree prune`；删分支前确认已合入 |

## 9. 需用户决定的事项

（本批执行过程中累积，最终统一呈现。）

| 编号 | 事项 | 备选 | 当前处理 |
| --- | --- | --- | --- |
| — | 暂无 | — | — |
