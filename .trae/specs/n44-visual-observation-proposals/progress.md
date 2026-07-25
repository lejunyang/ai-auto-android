# N44 Progress

本文件 append-only，记录视觉 observation、候选、安全清理和验证证据。

## Round 1

- 已确认 `phase2-n44-visual-observation` 位于独立且干净的 worktree，基线为
  `7974815`；并行 N46 位于另一 worktree，本任务不会修改其目录。
- 已审计 N31 固定设备基线、N34 可信截图 verifier 原则、N36 独立协议所有权、现有
  Accessibility 截图裁剪/预算/清零和 MCP `ImageContent` 返回方式。
- 设计固定为短生命周期图片租约：SHA-256 仅校验完整性，必须叠加调用进程提供的
  独立可信 verifier；图片不写临时文件、脚本、日志或结构化 JSON。
- proposal 层只返回候选，不持有动作执行接口；过期、包变化、保护窗口、空图、
  低置信度和歧义统一失败关闭并保持动作提交数为零。
- 共享 MCP server 与公共 protocol manifest 不在允许范围，故仅提供独立 adapter
  和 schema 测试；正式注册保留给主线程串行集成。
- 下一步先写 Android、Go、schema 与 MCP adapter 失败测试；生产实现尚未开始。

## Round 2

- 已先观察 Android、Go 和 MCP adapter 测试因生产类型缺失而失败，再实现两端
  observation registry、可信 verifier、图片租约、proposal 服务和严格 JSON。
- Android 与 Go 均把 `screen.width/height` 定义为自然方向尺寸，crop 位于采集时
  旋转后的 capture plane；0/90/180/270 四种逆旋转映射、越界和反向 crop 均有
  回归测试。
- registry 对同 ID 元数据漂移失败关闭，结构化 observation 使用深拷贝防止调用方
  篡改；调用方、verifier、provider、registry 与 MCP 图片副本在使用后清零。
  verifier/provider 拒绝、抛错或 panic 均不泄露底层细节。
- sensitive 节点及其后代统一移除 label 并标记为 sensitive；独立 schema 与
  Android/Go canonical example 均不包含原图、Base64、路径或执行字段。
- 独立 MCP adapter 返回同 observation 的 `ImageContent` 与脱敏结构化候选，
  `Close` 后清零图片；未修改共享 server、`ToolNames`、公共协议 manifest 或
  recording 模块。
- Android visual 定向单测 15/15 通过；Go `internal/visual` 与
  `internal/mcpserver` race 测试通过；`make comments`、`git diff --check` 和
  允许目录审计通过。测试未写临时 PNG，未启动设备、ADB 或动作执行。
- 本轮完成服务层与独立 adapter。共享 MCP 正式注册、用户授权截图采集入口和 App
  AI 会话接入不在本 change spec 的允许范围，后续必须单独集成和验收，不能据此声称
  N44 的完整用户路径已经公开可用。
