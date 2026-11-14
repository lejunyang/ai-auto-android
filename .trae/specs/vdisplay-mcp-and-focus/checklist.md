# 虚拟显示的 MCP 出口与焦点隔离验收清单

已完成项必须有实现、测试或设备证据后才能勾选。

## 范围与流程

- [x] 独立 change spec、独立分支与 worktree，单一职责 Conventional Commit
- [x] 未回写任何既有规格的验收结论
- [x] 未修改 `android/app/`、`internal/bridge/` 与规格未列出的 `docs/` 文件
- [x] 未 push 到远端

## 安全边界

- [x] MCP 没有辅助进程启停出口
- [x] 虚拟屏管理走独立风险门，既有风险门的准入标准与注释未改
- [x] 带目标显示不放宽动作面（有测试）
- [x] 未接入的工具收到目标显示参数时明确拒绝（有测试）
- [x] 目标显示无效或辅助进程不可用时不回退到主屏
- [x] 新增工具的 schema 为 `additionalProperties:false`，不暴露标志位、套接字与路径

## 功能

- [x] 显示编号 0 在 CLI 上明确指向主屏，层级不受焦点歧义影响
- [ ] MCP 可列出、创建、关闭虚拟屏
- [ ] MCP 可在虚拟屏上读层级、截图、点按、滑动、按键、ASCII 文本、启动应用
- [x] 不带目标显示时既有命令与工具行为逐字不变（有测试）

## 模拟器证据（API 35）

- [x] 记录 AVD、system image、分辨率与设备指纹
- [ ] MCP stdio 端到端冷启动连续 3 次的结果已记录
- [x] 焦点与输入法逐步实测结果已记录（未缓解）
- [x] 缓解手段的重新测量结果已记录，或记录了无法缓解的依据
- [x] 停止后无残留进程、虚拟屏、转发与临时文件

- [ ] 原verify-virtual-display.mjs在新标志下完整通过（Round9首轮息屏后层级读取失败）
- [ ] M3整体验收通过；M4/M5才可开始

## M3b证据

- [x] 旧标志第一次BACK收IME且保留Activity，第二次BACK退出；新标志第一次BACK即退出
- [x] 已归属空屏与不归属显示分别返回DISPLAY_EMPTY与DISPLAY_NOT_FOUND，CLI/MCP路径有测试
- [x] DELETE到达输入框，BACK后归属仍在、无前台Activity、专用错误，重新启动恢复正常
- [x] 息屏65秒与PIN检查段逐字保持，未放宽XML校验
- [ ] 修订后回归冷启动连续3次完整通过（当前首轮失败，0/3）
- [ ] M3b阶段验收通过

## M3c证据（修订二替代旧单一策略要求）

- [x] 两构建在主屏唤醒/息屏下的原始文件存在性、字节数、显示组、帧与输入独立复现
- [x] focus默认isolated、显式steal，固定标志映射且不接受原始flags
- [x] v2协议、API33明确focusIsolated=false，Go/CLI/MCP逐层测试通过
- [x] 无窗口输出本地错误映射测试、XML校验和三道旧风险门保持
- [ ] 无窗口输出设备端返回WINDOWS_UNAVAILABLE（F2实测ACTION_FAILED，未达契约）
- [x] steal场景冷启动连续3次完整通过（Round13）
- [ ] isolated场景冷启动连续3次完整通过（Round19已进入产品检查，错误契约阻塞，完整0/3）
- [x] 原始层级/截图/系统分段/时间线/实际CLI响应在重试及清理前保留，缺失来源明确标注
- [ ] M3c整体通过；M4/M5才可开始

## 验证

- [x] `make comments`
- [x] `make test`
- [x] `make verify`
- [x] `make build`
- [x] `make android-verify`（若改到 `android/`）

## 待设备验证（保持未勾选）

- [ ] 真机与 OEM ROM 上的焦点与输入法行为
- [ ] API 33 / API 34 上的行为差异
- [ ] 硬件键盘输入在虚拟屏持有焦点时的去向
