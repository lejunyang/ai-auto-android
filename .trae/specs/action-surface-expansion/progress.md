# 动作面扩展进度

本文件 append-only，记录每轮的审计结论、RED / GREEN、模拟器证据与未完成边界。

## Round 1 — 审计与规格

- 主工作区 `main` ahead `origin/main` 14 个提交，worktree 干净，无并行 worktree。
- 审计确认动作面三层现状与 MainAgent 结论一致：
  - `protocol/schema/v1/action.schema.json` 的 `oneOf` 为 14 项；
    `ProviderActionParser.validate` 的 `when(type)` 分支与之逐项对应。
  - `AccessibilityModels.kt`：`NodeAction` 9 项、`Gesture` 2 项、
    `GlobalAction` 3 项、`AccessibilityCommand` 7 个子类。
  - `AndroidAccessibilityBackend.kt`：`dispatchGesture` 只
    `addStroke(StrokeDescription(path, 0, durationMs))` 一笔；
    `validateTarget` / `openNodeSession` / `findNodeInActiveWindow` /
    `findUniqueNodeAt` 四处全部取 `service.rootInActiveWindow`，
    确认无 `service.windows` 调用。
  - `AccessibilitySnapshotter.toNodeAction()` 的映射表同样只认 9 个动作 id，
    因此即使节点支持 COPY / PASTE / SET_SELECTION，快照也不会上报——
    这是 A5 / A6 / A9 除路由外的第二个改动点，原缺口清单未点出。
  - `internal/adb/direct.go` `allowedKeys` 为 17 项（不是 18；
    实际计数：BACK / HOME / RECENTS / ENTER / TAB / ESCAPE / SPACE / DELETE /
    FORWARD_DEL / DPAD_UP / DPAD_DOWN / DPAD_LEFT / DPAD_RIGHT / DPAD_CENTER /
    VOLUME_UP / VOLUME_DOWN / VOLUME_MUTE），无光标移动键。
  - `internal/mcpserver/schemas.go` `actionSchema()` 的 `oneOf` 为 8 项，
    `ToolNames` 为 11 项。
  - `AndroidBridgeMethods.capabilities()` 为 8 项，其中 UI 相关仅
    `ui.snapshot`、`ui.foreground`、`action.execute`。
- 建立独立 change spec 与 `docs/action-surface-tasks.md`，未改
  `docs/next-phase-tasks.md`。

### 与原缺口清单的差异（需记录，不影响范围）

1. `allowedKeys` 实为 17 项而非 18 项。
2. `AccessibilitySnapshotter.toNodeAction()` 是节点动作补齐的必改点，
   原清单只提到 router / models / backend。
3. `internal/adb/gesture.go` 的 `ScrollDirection` 只有 up/down/left/right 四向，
   缺 forward/backward（直连侧无元素语义，属既有设计取舍，非本批缺口）。

### 环境

- serial `emulator-5554`，AVD `osdk-test-35`，API 35（Android 15），
  320x640，x86_64。
- `scripts/emulator/profiles.json` 声明的 API 30 / 33 / 34 arm64 镜像在本机
  **未安装**（`$ANDROID_HOME/system-images` 下只有 `android-35`），
  故本批只能给出 API 35 证据，30 / 33 / 34 矩阵验收保持未完成。
