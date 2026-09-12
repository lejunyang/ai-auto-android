# API 34 真机内置 LAN 扫码验收进度

本文件 append-only，仅记录脱敏且可复查的真机证据。

## Round 1

- 重新发现唯一 USB 真机：OPPO `PEEM00`、Android 14 / API 34、state=`device`、
  `adb.direct` granted；原始 serial 未写入仓库。
- 首次相机授权后真机显示白屏。生产修复依次强制 CameraX `COMPATIBLE` 预览实现、
  给 `AndroidView` 增加全宽约束，并把相机暂态异常帧改为丢弃当前帧后继续扫描；
  每项均通过 LAN 定向单测、debug 构建和中文注释门禁。
- 真机随后暴露相机触发配置变化时普通 `remember` 丢失 `scanning` 状态，扫码页无
  错误返回空配对表单。改用 `rememberSaveable` 后，等待三秒及配置变化后仍保持扫码
  预览；LAN 单测、AndroidTest Kotlin 编译和 debug 构建通过。
- TRAE 会折叠 CLI 的超大 UTF-8 字符二维码，因此真机验收使用固定文件名的临时 PNG，
  不启动网页服务或随机 URL。PNG 为 1200×1200、1-bit，先由 ZXing Core 3.5.4
  离线完整还原 804 字节 N36 invitation，再由 App 内置扫描器识别。
- 用户人工扫码并只点击一次“我已核对短指纹并连接”。生产 Go listener 在同一认证
  加密 socket 上完成三次 `device.info` 和 `session.close`，脱敏摘要为
  `rpc=3`、`closed=1`。
- 用户随后点击“立即停止 LAN 会话”；新鲜 hierarchy 中停止按钮、当前手机网卡和
  会话过期时间均消失，证明手机端会话状态已清理。
- 二维码、临时 CLI、Java 解码/生成工具、listener 脚本、摘要和预览目录均已删除；
  主工作区只保留任务开始前已存在的未知 `android/test-control/core/bin/`，未触碰。
- 最终 `make test`、`make verify`、`make build` 通过；Android unit、AndroidTest
  Kotlin 编译、lint、debug 和 release 共 111 个任务通过。
