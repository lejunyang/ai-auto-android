# 虚拟显示的 MCP 出口与焦点隔离进度

本文件 append-only，记录前提核实、RED/GREEN 与验证结果。

## Round 0：立项（2026-11-14）

- 基于分支 `virtual-display-screen-off`（PR lejunyang/ai-auto-android#2，尚未合入
  `main`）叠加。worktree `E:/Projects/wt-vdisplay-mcp`，分支 `vdisplay-mcp-focus`。
- 上一规格的集成方验收里再次复现了普通层级的焦点歧义：虚拟屏上启动应用后，不带
  目标显示的层级读取返回的是虚拟屏应用的内容，且坐标被裁到主屏尺寸。
- 上一轮实测中曾观察到在虚拟屏启动应用后 `mTopFocusedDisplayId` 变为虚拟屏编号，
  尽管虚拟屏设置了独立焦点标志。这说明「独立焦点」未必等于「不影响主屏输入」，
  本规格 M3 专门测量。该观察没有对应的受控实验，不能当作结论。
- 并行说明：另有一个文档清理任务拥有 `docs/next-phase-tasks.md` 等文件；本规格只
  修改 `spec.md` 列出的文档。后续项的路线图登记由集成方在汇合时处理。
- 本轮没有任何实现，`tasks.md` 与 `checklist.md` 全部未勾选。
