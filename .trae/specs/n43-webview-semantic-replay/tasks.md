# N43 Tasks

- [x] Task N43.1：定义 API/WebView/页面能力矩阵和四级兼容结果
- [x] Task N43.2：实现只生成现有类型化动作的 WebView semantic adapter
- [x] Task N43.3：实现流程保存、reset、跨页面 replay 与逐步验证编排
- [x] Task N43.4：覆盖动态 DOM、iframe、歧义、时效和上下文漂移的失败关闭测试
- [x] Task N43.5：提供 `ReplayGateway`、页面身份、存储和 reset 的设备可注入端口
- [ ] Task N43.6：在 API 30、33、34 固定 AVD 上各连续运行 20 轮完整语义流程
- [x] Task N43.7：运行定向/完整 app 单测、`make comments` 和 `git diff --check`
- [x] Task N43.8：以 `test(android): validate webview semantic replay` 单提交收口

## 依赖

代码依赖 N42 的离线 Web fixture 和现有 recording/accessibility 模型。设备矩阵依赖
主线程独占 N31 emulator runner、显式 serial 和固定 clean snapshot；在 60 轮证据
完成前，N43 保持“实现完成、设备验收待完成”，不得更新路线图为完成。
