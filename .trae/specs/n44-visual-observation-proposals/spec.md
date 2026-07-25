# N44 视觉 Observation 与 AI 候选规格

## 目标

把用户授权的最小 PNG、脱敏 hierarchy、前台包、屏幕与裁剪元数据绑定为同一份
短生命周期 observation，并让 Android、Go 与独立 MCP adapter 只提出视觉候选，
不包含任何动作执行能力。

## 数据契约

- observation 固定 UUID、前台包、屏幕宽高、旋转、裁剪区域、采集/过期时间、
  PNG SHA-256 和脱敏 hierarchy；同一 UUID 不得绑定不同元数据。
- PNG 只通过进程内短生命周期租约传递，结构化 schema 和日志不得包含 Base64、路径
  或原始像素；sensitive hierarchy 节点不得保留 label。
- OCR、template、model、manual 候选统一为裁剪图中的归一化点与边界，再按旋转和
  crop 映射为全屏归一化坐标。
- Android 与 Go 读取独立 visual schema 内同一份 canonical example，编码结果必须
  一致；本任务不修改公共协议 manifest。

## 安全边界

- 图像 SHA-256 只证明字节完整性，不证明图像已脱敏。创建 observation 必须同时由
  调用进程注入的可信 verifier 结合独立上下文返回 true；缺失、拒绝或抛错均失败
  关闭。
- `FLAG_SECURE`、空白/无效 PNG、图片超预算、过期、前台包变化、低置信度和多个
  接近候选均撤销 observation，清零图片与候选，并保持动作提交数为零。
- proposal API 只暴露 `propose`，不依赖动作路由、ADB、Accessibility action 或
  recording 执行器，类型上不存在 `execute`。
- 原图不得进入脚本、结构化输出、日志或持久文件；实现不创建临时 PNG。调用方传入
  的 ByteArray/byte slice、内部副本和 MCP 图片副本在全部成功与失败路径清零。

## 允许修改

- `.trae/specs/n44-visual-observation-proposals/`
- `android/app/src/main/java/dev/aiauto/android/observe/visual/` 及对应单元测试
- `internal/visual/`
- 独立 visual JSON Schema
- 独立 MCP visual adapter 新文件及测试

## 禁止事项

- 不修改 recording core/editor/webview、导航、Gradle、公共协议清单、共享 MCP
  server/root 注册、路线图或 N46。
- 不执行真实动作，不新增截图授权入口，不绕过 `FLAG_SECURE`，不把 self-hash 当成
  脱敏或可信证明。
- 不提交 PNG、日志、测试运行产物、APK、缓存或仓库外 SDK 内容。

## 验收

- 先观察生产类型缺失时 Android、Go 与 MCP 定向测试失败，再完成最小实现。
- observation 关联、旋转/crop 映射、元数据防漂移和跨实现 canonical example 一致。
- 所有拒绝场景返回稳定错误码、零候选和 `actionCommitCount=0`。
- Android 定向测试、Go `test -race`、独立 schema 测试、`make comments` 与
  `git diff --check` 通过。
- 共享 MCP 注册保留为主线程串行集成点，不能把未注册 adapter 描述为已公开工具。
