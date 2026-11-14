# 过期文档审计进度

本文件 append-only。核查以基线 `984ee31` 的本地代码、测试与 git 历史为准；日期
2026-11-14。后续行号若因文档插入变化，以此基线定位原断言。

## D1：开工与规格

- `git status --short --branch`：`## docs-stale-audit`，无未提交改动。
- `git worktree list --porcelain`：识别主工作区及 docs、vdisplay MCP、virtual display
  三个独立 worktree；本任务只在 `E:/Projects/wt-docs-audit` 写入。
- 已读 `AGENTS.md`、`docs/README.md`、根 README 与 `ime-show-focus` 的四份规格
  文件，按其目标、范围、任务、验收和 append-only 进度格式建立本规格。
- 目标清单共 27 份 `docs/*.md`；既有规格共 140 份 `spec.md` / `tasks.md`，均只读。
- 外部并行所有权排除：根 README、`docs/{virtual-display,protocol,architecture}.md`、
  `skills/`。本轮不运行任何设备操作。
