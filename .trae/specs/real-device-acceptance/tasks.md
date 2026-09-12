# API 34 真机内置 LAN 扫码验收任务

- [x] 重新发现并绑定唯一 USB 真机
- [x] 安装并启动包含内置扫码修复的 debug App
- [x] 用户人工授予 App 相机权限
- [x] 修复并验证预览白屏、横向尺寸和暂态帧问题
- [x] 修复并验证扫码会话跨配置变化保持
- [x] 使用内置扫码读取同版 ZXing 离线验证的 invitation
- [x] 用户执行一次短指纹确认
- [x] 完成同一 socket 三次 `device.info` 与 `session.close`
- [x] 用户显式停止手机 LAN 会话并验证 UI 清理
- [x] 删除二维码、临时 CLI、解码工具和摘要
- [x] 更新 N37/N38 路线图与清单
- [x] 运行最终门禁并提交证据

## 失败清理

- 设备状态变化或结果未知时停止，不重复扫码或确认。
- listener 失败时取消唯一 invitation，不轮换或保留旧二维码。
- 原始 hierarchy 只在命令响应中短暂使用，不落盘、不提交。

## 建议提交

`docs(android): record api34 embedded qr acceptance`
