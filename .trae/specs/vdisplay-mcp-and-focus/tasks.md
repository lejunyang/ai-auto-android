# 虚拟显示的 MCP 出口与焦点隔离任务

M1 → M2 → M3 → M4 串行，同一 worktree、同一分支，单一执行者。规格状态文件只由
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

- [ ] 实测脚本：构造主屏输入状态，逐步在虚拟屏操作并记录主屏状态
- [ ] 记录未缓解时的逐步结果
- [ ] 尝试缓解并重新测量；有效则实现并补测试，无效则如实记录
- [ ] 提交：`test(emulator): measure primary-display focus under virtual display input`
      （若有缓解实现，另加 `feat(displayd): ...`）

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

（完成后填写）
