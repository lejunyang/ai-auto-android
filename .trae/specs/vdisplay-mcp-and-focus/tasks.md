# 虚拟显示的 MCP 出口与焦点隔离任务

M1 → M2 → M3 → M4 → M5 串行，同一 worktree、同一分支，单一执行者。规格状态文件只由
当前任务的执行者修改。

## M1：明确指向主屏（显示编号 0）

目录所有权：`internal/adb/`、`internal/vdisplay/`、`internal/service/`、`internal/cli/`。

- [x] 先写失败测试
- [x] 层级、截图、点按、滑动、按键、文本、启动接受显示编号 0
- [x] 显示编号 0 不要求辅助进程运行；正整数仍须归属辅助进程
- [x] 不带目标显示时命令行逐字不变（既有测试保持通过）
- [x] `make test`
- [x] 提交：`feat(cli): accept display 0 as the explicit primary display`

## M2：MCP 出口

目录所有权：`internal/mcpserver/`、`protocol/`。

- [x] 先写失败测试：schema、风险门、错误映射、未接入工具拒绝该参数
- [x] `android_observe` 的 `screenshot` / `hierarchy` 接受 `displayId`
- [x] `android_action_execute` 中已允许的动作接受 `displayId`，逐项列表记录
- [x] 虚拟屏管理工具：列出与状态、创建、关闭，独立风险门
- [x] `device.sleep` 的开放与否按规格判定并记录理由
- [x] `make test`、`make verify`、`make build`
- [x] 提交：`feat(mcp): target virtual displays from MCP tools`

## M3：焦点与输入法实测及缓解

目录所有权：`scripts/emulator/`（新增）、`android/displayd/`（仅缓解需要时）。

- [x] 实测脚本：构造主屏输入状态，逐步在虚拟屏操作并记录主屏状态
- [x] 记录未缓解时的逐步结果
- [x] 尝试缓解并重新测量；有效则实现并补测试，无效则如实记录
- [x] API34+固定标志RED/GREEN及两组独立测量通过（Round4/5）
- [ ] 原验收脚本在新标志下完整通过（Round6失败，未到息屏/PIN）
- [ ] M3阶段验收通过
- [x] 提交：`test(emulator): measure primary-display focus under virtual display input`
      （若有缓解实现，另加 `feat(displayd): ...`）

当前停在原验收脚本ASCII/BACK段回归失败（progress.md Round6）；基线和两组测量已完成。M3未验收，M4/M5未开始。

## M4：MCP 端到端验收与文档

目录所有权：`scripts/emulator/`（新增）、`docs/virtual-display.md`、`docs/protocol.md`、
`docs/architecture.md`、`README.md`、`skills/`。

- [ ] 通过真实 MCP stdio 会话的端到端脚本，冷启动连续 3 次
- [ ] 文档：MCP 工具与参数、显示编号 0、焦点实测结果与限制
- [ ] 全量：`make comments`、`make test`、`make verify`、`make build`、
      `make android-verify`（若改到 `android/`）
- [ ] 提交：`test(emulator): verify virtual display through MCP` 与
      `docs: document MCP virtual display tools and focus behaviour`

## 实际提交

- 6361df3：规格初始提交。
- 2862aa3：M1 实现与验证通过。
- b9d9e2b：M2 实现与验证通过。
- 0acf58b：M3首次基线准备失败记录，历史状态见Round3。
- 41c4e7c：M3固定标志实现与API/SECURE边界测试。
- 本轮M3测量提交：`test(emulator): measure primary-display focus under virtual display input`；两组实测完成，原脚本回归失败，整体未验收。
- M4、M5未开始。

## M5：接收文档清理移交（依赖 M4 验收通过）

目标：逐条核实并处理 docs-stale-audit 移交的 P01–P15，所有能力描述以本分支当前代码为准。
目录所有权：README.md、docs/architecture.md、docs/protocol.md、skills/ 下两份 references/commands.md
及项目同步工具生成的 .trae/skills/ 对应镜像；不修改移交 worktree。
执行波次：M3 → M4 → M5 单人串行；无并行子任务，导航与规格状态仍由本执行者统一汇合。

- [ ] 只读移交 progress.md，对 P01–P15 各自核对本分支代码与现有测试；核实不了的标注未核实
- [ ] 修正权限、截图授权例外、MCP工具/观察/动作与Bridge方法能力清单，保留未验收边界
- [ ] 修正两份commands.md并用项目既有命令同步镜像，运行make skills-check
- [ ] progress.md逐条记录处理结果、代码证据与未覆盖项
- [ ] make comments、make test、make verify、make build、make android-verify
- [ ] 提交：`docs: reconcile README, architecture, protocol and skills with current capabilities`

验收证据：逐条P01–P15表、实际工具/Schema清单、一致性检查与全量命令结果。
提交边界：仅上述文档/镜像与本规格状态，不混入M3/M4实现。
失败回滚/清理：保留未验项与核实记录，停止M5；只撤销明确属于本阶段且未验收的编辑，
不覆盖用户或其它任务改动。无设备操作；不修改路线图文件，后续项只登记本规格progress.md。
