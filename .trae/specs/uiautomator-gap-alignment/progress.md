# UIAutomator 缺口对齐进度

本文件 append-only，记录基线、RED/GREEN、各波次实际改动与验证结果。

## Round 1：基线与规格

- 工作区 `E:/Projects/ai-auto-android`，开工时 `git status --short --branch` 为
  clean，`git worktree list --porcelain` 只有主工作区一个条目，分支 `main`
  （ahead origin/main 128），HEAD `4207ab0`。
- 开工前基线 `make test` 通过：协议 14 schema / 13 合法 fixture / 35 非法 fixture /
  4 项兼容检查（共 52），Skills 3 组与 Trae 镜像通过，Go 全部包 ok。
- 依据来源为 `artifacts/uiautomator-gap/uiautomator-capability-gap.md` 第 4、5 节。
  只实施分类为 `可补充` 的条目；`需权衡` T1–T8 与 `有意不做` N1–N10 不实施。
- 键枚举收敛方向先查 `protocol/version.json`：`compatibility.requiresNewMajor`
  含 `narrow-valid-range`，因此禁止把协议 21 键收窄到 17 键。改为以协议为单一
  权威来源，直连与 MCP 向上补齐 MOVE_HOME / MOVE_END / PAGE_UP / PAGE_DOWN。
  该四键均为光标与翻页语义，不触及电源、通话或系统安全设置，不构成范围扩张。
- `app.stop` 矛盾选择收窄 MCP schema 而非放开风险门：风险门是安全权威，放开等于
  扩大模型动作面。CLI 侧 `action stop` 不受影响。
- `file.push` / `file.pull` 明确不接线，归入待决项 T4。
