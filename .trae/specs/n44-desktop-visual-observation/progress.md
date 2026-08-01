# N44 桌面可信视觉 Observation 进度

本文件 append-only，记录采集可信边界、RED/GREEN、设备证据、清理与集成。

## Round 1

- 已确认 `n44-desktop-visual-observation` 位于独立干净 worktree，基线为 N33 收口
  后 `9acb54f`；N37/N38 QR 在互斥 worktree 并行，不修改共享路线图。
- N44 visual registry、proposal service 与独立 MCP adapter 已完成，但共享 server
  仍只接收 `service.Automation`；生产没有把同轮 PNG、hierarchy、前台包、屏幕和
  rotation 绑定成可信 capture 的端口，因此不能直接注册旧 adapter。
- 现有 ADB `Screenshot` 与 `Hierarchy` 都会独立检查设备在线；新端口仍需额外执行
  前后设备与 hierarchy attestation，不能用 PNG self-hash 冒充可信上下文。
- 生产候选范围固定为 hierarchy-derived template，只读匹配明确 target；本 change
  不引入 OCR/模型网络、视觉动作或任意坐标执行。

## Round 2

- RED 测试先因 `DesktopProposalService`、共享 MCP runtime 和 transport cleanup
  不存在而编译失败；随后实现固定
  `device -> hierarchy -> screenshot -> hierarchy -> device` 采集顺序。前后设备
  metadata、hierarchy XML/size/SHA-256、foreground package 或 rotation 漂移统一
  返回稳定 visual 错误且不产生候选。
- UIAutomator XML 仅映射 role、content description/text、归一化 bounds、
  sensitive 和 children；password 节点及后代清空 label。PNG 必须通过既有完整结构
  校验、SHA-256 复核、尺寸/像素预算和非全黑门，screen 使用自然方向尺寸，crop
  固定为完整 capture plane。
- 可信 registry verifier 同时绑定 observation ID、稳定设备、双 hierarchy 摘要、
  PNG hash 与 size，不接受 self-hash。生产 provider 只从同 observation 的唯一
  非敏感 role/label 节点生成 `template` 候选；无候选和多候选失败关闭，
  `actionCommitCount` 始终为 0。
- 共享 MCP 现公开第六个严格只读工具 `android_visual_target_propose`。MCP SDK
  在 handler 返回后才序列化图片，因此生产使用 transport wrapper：按 response 中
  observation ID 精确关联 cleanup，底层 write 成功或失败后立即清零服务端图片；
  connection/server 关闭也清理全部 pending，不使用定时器。
- visual 工具使用低层 MCP handler，在采集前再次拒绝重复 key、未知字段、尾随值和
  超限 JSON；结构化 output 先完成编码，再取得并登记图片所有权。因此 handler
  返回后没有 schema/marshal 失败的持图窗口，唯一剩余阶段由 transport write/close
  cleanup 覆盖。
- N44 core、MCP 与 CLI 定向及 race 通过；Go 全量、Node 238/238、协议 52 项、
  build、verify、Skills、comments 和 diff-check 通过。一次串联命令的
  `make skills-check` 因未 source 外置 Go PATH 失败，加载
  `android-tools.zsh` 后单独复验通过，不属于代码失败。

## Round 3

- N31 API 34 clean emulator `emulator-5584`，fingerprint
  `1ac57e68…ea8f`，安装并启动无敏感数据 Native Fixture。生产
  `adb.Client + DesktopProposalService + shared MCP server` 只读调用一次，匹配
  `button + Fixture click target`。
- 设备 smoke 返回同 observation 的 PNG、`dev.aiauto.fixture`、rotation 0、
  脱敏 hierarchy 与唯一 `template` 候选，`actionCommitCount=0`；客户端收到图片后
  server pending cleanup 为 0，未执行任何动作。
- runner 随后恢复 clean、停止 `emulator-5584` 并执行 Gradle clean。最终 doctor
  健康，设备、runtime、AVD/port lease 和构建 APK 均为零。
