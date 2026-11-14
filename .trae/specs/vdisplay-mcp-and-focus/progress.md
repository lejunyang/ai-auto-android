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

## Round 1：M1 显式主屏 RED/GREEN（2026-11-14）

- 已完整阅读前置资料并核对 status/worktree；只有本规格四份新文件，先提交
  `6361df3 docs(spec): add MCP virtual display and focus change spec`。未改其它 worktree。
- RED：新增显式主屏的 CLI、service、fake-adb 测试，分别复现参数零值拒绝、
  helper 前置拒绝与截图拒绝。Go shim 对含管道的正则转义失败一次，随后用简单测试名执行。
- GREEN：CLI 的 observe 与五类 action 接受 0；display close 仍拒绝 0。
  service 显式零值绕过 helper，正整数继续 RequireDisplay；真实 ADB 的 New 也可在
  没有 helper 时构造主屏视图。截图用既有 screencap；输入带 -d 0，启动带 --display 0，
  层级 dump --windows 完整校验后只保留 display0，格式 uiautomator-windows-xml。
- fake-adb 逐字核对 tap/swipe/key/text、带/不带 Activity 的 launch、层级与截图；
  未指定显示的 argv 保持原样。此前断言 0 非法的测试迁移为 -1，仍验证无设备调用。
- 验证：make comments 退出0（589文件、16自测）；make test 退出0（协议56项、
  Skills3份及镜像、所有Go包、linux/darwin/windows交叉vet）。首次定向测试暴露旧的
  零值非法断言，修正后全量通过；git diff --check通过。
- 本阶段未启动模拟器、helper，未设PIN、修改设置或创建转发/设备文件。
  收尾 adb devices -l 为空，无 emulator/qemu 进程；因此无需设备清理动作。
- M1 仅有单元/命令证据，真实主屏隔离留待 M4，不提前勾选设备验收。

## Round 2：M2 MCP 出口 RED/GREEN（2026-11-14）

- 前置 M1 `2862aa3` 已通过 make test 并提交，才开始本阶段。串行、单一执行者。
- RED：新增 MCP 会话测试，目标显示参数被旧 schema 拒绝，四个管理工具不存在；
  完成后同一测试 GREEN。过程中未选定版本的 python shim 拒绝启动，改用 Node/补丁，
  没有安装软件或改 osdk 配置。
- 新工具为 android_display_list/status/create/close；采用独立
  requireDisplayManagementAllowed，只收四种操作。helper start/stop/shutdown 无出口。
  输入 additionalProperties:false，尺寸128..2048、dpi72..640、关闭编号至少1；
  数量上限2继续由 helper 强制。关闭工具描述明确销毁其上应用内容。
- 新显示路径将 apperr 映射为带 code/message/retryable 的工具错误；未启动返回
  DISPLAYD_NOT_RUNNING 并要求操作员先 aactl display start。已有错误码全部复用，
  未新增错误码，未改变省略显示时的错误/输出。observe 显式目标返回 displayId，
  层级 format 增加 uiautomator-windows-xml。
- 三道既有门 risk.go、bridge_action.go、file_transfer.go 的完整文件 diff 为空，
  准入标准、注释及名单均未改；文件传输语义不变。service/CLI 仅做接线：MCP 使用
  带显示后端且保留 Bridge 的服务，长按复用已有固定 input swipe 合成。

| android_action_execute 动作 | 可带 displayId | 依据与路由 |
| --- | --- | --- |
| ui.tap | 是 | risk.go 既有白名单；input -d N tap |
| ui.swipe | 是 | 既有白名单；input -d N swipe |
| ui.longClick | 是 | 既有白名单；固定同点 swipe，经相同 input -d N；不新增动作 |
| ui.pressKey | 是 | 既有白名单和原键枚举不变；input -d N keyevent |
| ui.setText | 是 | 既有白名单及原ASCII字符约束不变；input -d N text；不开放Unicode |
| app.launch | 是 | 已在直连白名单；am start --display N；可能迁移既有任务的限制仍适用 |
| ui.scroll | 否 | 既有实现先读不带显示的 wm size，再合成坐标；不能将主屏尺寸当目标屏尺寸。schema 明确拒绝，省略目标仍维持既有行为，接入留后续 |
| device.wake / device.sleep | 否 | wake 不在既有 MCP 白名单；sleep 按规格不开放；没有修改风险门 |
| 其它原来被拒动作 | 否 | 显示字段不改变原风险门，schema与服务双重拒绝 |

- 视觉候选/动作、wait/assert/scroll_until、Bridge工具与其它observe种类均未接入。
  有会话负测试，传 displayId（含0）明确拒绝、服务调用数为0。显示负数、小数、
  字符串、越界及管理未知字段/尺寸均拒绝；helper错误原码透传，无主屏输入回退。
  真实 service + vdisplay 客户端驱动 fake-adb 验证未启动时不发 input。
- README 工具数按一致性测试从16同步为20；未修改其它文档/协议，协议动作本身未变。
- 验证：定向 mcpserver/service/cli 全通过；make comments（591文件、16自测）、
  make test（Go313个顶层Test定义，含1个默认跳过的设备冒烟；协议56项、Skills3份、
  三系统交叉vet）、make verify、make build 全退出0。git diff --check通过。
- 未启动模拟器、helper，未设置PIN或改设置；阶段末 adb devices 为空，无
  emulator/qemu、设备文件、转发或进程残留。
- 汇合待办（只登记本规格）：ui.scroll 的目标尺寸路由；视觉/等待/断言/滚动直到与
  Bridge 的多显示支持。不得将这些未交付项记入历史MVP。
