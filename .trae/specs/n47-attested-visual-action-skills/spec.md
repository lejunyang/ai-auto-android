# N47 原子视觉动作 Skills 接入规格

## 目标

把 `android_visual_action_execute` 的 fresh evidence、人工确认、提交状态和恢复边界写入
设备观察与自动化 Skills，使只读 proposal 不会被误作后续动作授权。

## 契约

- `android_visual_target_propose` 只读响应写出后 lease 已关闭，不得跨调用复用。
- 原子动作输入只描述 serial、目标包、role/label 和类型化动作意图，不接收坐标、
  candidate、observation、hash 或 handle。
- 只有 committed、verified 和一次提交同时成立时报告成功。
- not-committed 必须重新观察后再决策；unknown commit 和 null 提交数禁止重试。
- destructive 或外部状态变更仍需针对具体动作取得新的人工确认。
- source `skills/` 与 `.trae/skills/` mirror 必须字节一致。

## 允许修改

- `.trae/specs/n47-attested-visual-action-skills/`
- `skills/android-device-observation/`
- `skills/android-device-automation/`
- 对应 `.trae/skills/` mirror

## 禁止事项

- 不修改视觉动作生产代码、协议、路线图或设备状态。
- 不把 debug disposable emulator 能力描述为真机或 release 能力。
- 不提供坐标、图片、长期 lease、配对码或 Bridge secret 示例。

## 验收

- `make skills-smoke`
- `make comments`
- `git diff --check`
- source 与 mirror 的三份修改文件逐字一致
