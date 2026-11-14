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
