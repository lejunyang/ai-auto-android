# 虚拟显示的 MCP 出口与焦点隔离任务

M1 → M2 → M3 → M3b → M3c → M4 → M5 串行，同一 worktree、同一分支，单一执行者。规格状态文件只由
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
- [ ] 原验收脚本在新标志下完整通过（Round9首轮息屏后层级读取失败，未到65秒/PIN）
- [ ] M3阶段验收通过
- [x] 提交：`test(emulator): measure primary-display focus under virtual display input`
      （若有缓解实现，另加 `feat(displayd): ...`）

当前停在M3b首轮回归的息屏后层级读取失败（progress.md Round9）；BACK因果链、空屏错误及重新启动恢复已验证，完整回归0/3。M3/M3b未验收，M4/M5未开始。

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
- 5ac8c79：M3两组实测完成，原脚本回归失败，整体未验收。
- 7676beb：M3b空屏错误、CLI/MCP映射、RED/GREEN与BACK因果链记录。
- M3b脚本保存提交：`test(emulator): adapt virtual display acceptance to IME-less displays`；首轮息屏后层级读取失败，整体未验收。
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

## M3b：空虚拟屏语义与新BACK行为（依赖已完成的M3两组焦点测量）

目标：独立核实两种标志构建的BACK因果链，区分空屏/不存在，并修订旧验收脚本而不削弱电源/锁屏检查。
目录所有权：internal/adb、service、cli、mcpserver（仅错误映射）、apperr（仅新增错误码）、
原verify-virtual-display.mjs及其测试、docs/virtual-display.md、本规格状态。android/displayd不修改。
执行波次：M3b → M4 → M5 单人串行；不并行派发Agent。两份对照helper仅在本worktree忽略构建目录生成。

- [x] 两种构建独立复现点按输入框→BACK，记录IME、Activity与窗口分组
- [x] RED/GREEN：DISPLAY_EMPTY与DISPLAY_NOT_FOUND分离，CLI/MCP映射与无回退测试
- [x] 修订按键到达检查，明确BACK退出根Activity、空屏错误与重新启动恢复
- [ ] 其余隔离/息屏65秒/PIN往返与重建判据保持，冷启动连续3次完整通过
- [x] 提交：`fix(adb): report an empty display distinctly from a missing one`
- [x] 提交：`test(emulator): adapt virtual display acceptance to IME-less displays`（部分成果保存，非阶段验收通过）

验收证据：两种BACK逐步表、定向/全量测试、三个独立冷启动报告与设备指纹。
失败清理：停止helper、清转发和设备临时文件、清测试PIN并恢复超时、关闭模拟器；仅清理本轮忽略产物。
不改上一规格状态，不将M3b的脚本语义变化回写为其历史验收结论。

## M3c：显式焦点策略（修订二取代M3b单一策略回归要求）

目标：独立复现四种标志/电源组合；默认保护主屏输入，显式steal保留息屏挂机能力。
目录所有权：android/displayd、internal/adb/vdisplay/service/cli/mcpserver、apperr仅新增码、
scripts/emulator、docs/virtual-display.md与本规格状态。三道既有风险门不改。
执行波次：M3c → M4 → M5单人串行；只使用本地工具链与唯一固定模拟器，不修改fixture。

- [x] 原始dump矩阵、取帧及注入独立复现，记录字节数/文件存在性/显示组
- [x] displayd焦点枚举解析/范围/固定标志先RED后GREEN，返回策略及API33真实隔离能力
- [ ] Go客户端、service、CLI、MCP逐层透传及拒绝非法值测试
- [ ] 无窗口输出专用错误与CLI/MCP映射，畸形/截断仍拒绝
- [ ] steal冷启动连续3次完整息屏/65秒/PIN往返/重建通过
- [ ] isolated冷启动连续3次通过，复用完整焦点判据、BACK/空屏/恢复与息屏输入/取帧/错误
- [ ] make comments、make test、make verify、make build、make android-verify
- [ ] 按职责提交displayd、CLI/MCP、ADB错误及设备验收脚本

提交建议：feat(displayd): add an explicit focus policy for virtual displays；
feat(cli): expose the virtual display focus policy；fix(adb): explain missing window dumps instead of reporting invalid XML；
test(emulator): verify both focus policies end to end。前置层次定向验证通过再提交，整体验收未过不进入M4。
失败清理：恢复本轮超时、清除已设置的测试PIN、停止helper、清转发/设备临时文件、关闭模拟器，
只删除本轮已核对归属的忽略产物；失败记录追加progress，不覆盖历史结论。
