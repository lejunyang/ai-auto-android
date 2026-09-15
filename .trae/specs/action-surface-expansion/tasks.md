# 动作面扩展任务

分支：`feat/action-surface-expansion`
状态源：[`docs/action-surface-tasks.md`](../../../docs/action-surface-tasks.md)

任务勾选规则：必须同时具备三层实现、定向测试通过与模拟器实测证据后才可勾选。
设计完成或编译通过不构成勾选依据。

## 准备

- [x] 检查主工作区与并行 worktree，创建独立分支与 worktree
- [x] 审计动作面三层现状，确认 17 项缺口的落点
- [x] 创建独立 change spec，固定能力清单、优先级与实施顺序
- [x] 创建 `docs/action-surface-tasks.md` 作为本批唯一状态源

## P0

- [x] A1 多窗口模型（`service.windows` 遍历）
- [x] A7-核心 `dispatchGesture` 多 stroke 与 `willContinue` 续接改造
- [x] A2 `drag`（按住—停顿—移动分段时序）
- [x] A3 `scrollIntoView` / `scrollUntil`
- [ ] A4 Bridge 侧 `pressKey`（仅 MOVE_HOME/MOVE_END 可走无障碍，缺设备证据）

## P1

- [x] A5 文本选择（`ACTION_SET_SELECTION`）
- [x] A6 复制 / 剪切 / 粘贴
- [x] A8 双击 / N 连击
- [ ] A9 节点动作补齐（CLEAR_FOCUS 与 SCROLL_TO_POSITION 被平台拒绝，见 B3/B4）
- [x] A7-手势 pinch / zoom / 旋转 / 双指滚动
- [ ] A10 全局动作补齐（5 项）（缺设备证据；快捷键面板确认无法实现）
- [x] A11 设备状态动作（旋转 / 唤醒 / 解锁 / 亮屏检测）
- [ ] A12 IME 控制（枚举/切换/收起已验证；显示键盘确认无法实现）

## P2

- [x] A13 剪贴板读写（桥侧）
- [x] A14 deeplink 打开
- [x] A15 文件 push / pull
- [x] A16 Toast 捕获
- [x] A17 路径手势

## 收口

- [x] `make comments`
- [x] `make test`
- [x] `make verify`
- [x] `make build`
- [x] Android `testDebugUnitTest`（408 项通过） / `lintDebug` / `assembleDebug`
- [x] 模拟器动作面回归（4 个设备测试类共 15 项全通过）并回写证据
- [ ] API 30 / 33 / 34 多版本矩阵验收（本机仅有 API 35 镜像，见状态源 D3）

## 建议提交边界

每项能力一个提交，单一职责：

| 能力 | 提交 |
| --- | --- |
| A1 | `feat(accessibility): add multi-window node resolution` |
| A7-核心 | `refactor(accessibility): support multi-stroke continued gestures` |
| A2 | `feat(action): add drag with hold-and-move timing` |
| A3 | `feat(action): add scroll into view and scroll until` |
| A4 | `feat(bridge): add key press over the app bridge` |
| A5 | `feat(action): add text selection` |
| A6 | `feat(action): add copy, cut and paste` |
| A8 | `feat(action): add double and multi tap` |
| A9 | `feat(action): complete the node action set` |
| A7-手势 | `feat(action): add pinch, zoom, rotate and two-finger scroll` |
| A10 | `feat(action): complete the global action set` |
| A11 | `feat(action): add device state actions` |
| A12 | `feat(action): add ime control` |
| A13 | `feat(action): add clipboard read and write` |
| A14 | `feat(action): add deeplink open` |
| A15 | `feat(action): add file push and pull` |
| A16 | `feat(observe): add toast capture` |
| A17 | `feat(action): add path gesture` |
| 文档 | `docs(action-surface): record capability status and evidence` |

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`

## 失败清理

- 任一能力在模拟器验证失败：保留分支与提交，在 `docs/action-surface-tasks.md`
  写明未完成 + 原因 + 影响，不勾选对应任务。
- 模拟器进入不确定状态：`adb -s <serial> emu kill` 后从 clean 快照重启，
  不使用 `adb kill-server`（会影响其它并行会话）。
- worktree 清理：合入并确认干净后用非强制 `git worktree remove`，
  再 `git worktree prune`；删除分支前确认提交已合入。
