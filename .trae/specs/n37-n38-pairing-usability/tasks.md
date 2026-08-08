# N37/N38 配对可用性与内置扫码任务

- [x] 审计现有 120 秒协议、外部 ZXing 信任门和真机失败路径
- [x] RED：新增 600 秒 TTL、300 秒默认值与威胁 fixture
- [x] GREEN：同步 Go、Schema、Kotlin TTL 边界
- [x] RED：新增唯一地址/网卡自动选择和一次确认连接测试
- [x] GREEN：移除短指纹重复输入并保留明确确认门
- [x] RED：新增本地 QR luminance 解码和权限拒绝测试
- [x] GREEN：接入 CameraX 预览、ImageAnalysis 和 ZXing Core
- [x] 移除外部扫码 Activity 查询和 signer 运行依赖
- [x] 增加无需网页或 GUI 的 CLI 终端二维码直显模式
- [x] 运行协议、Go、Android 与 Make 全量验证
- [ ] 在 API 34 真机完成内置扫码、连接、RPC 和清理
- [ ] 更新路线图并提交单一职责 Conventional Commit

## 汇合顺序

1. 先修改共享 protocol TTL，再同步 Go/Kotlin parser。
2. 再修改纯状态机和 ViewModel，最后接 Compose/CameraX。
3. 真机证据在实现提交集成后执行，不与生产代码并行写同一文件。

## 失败清理

- 构建失败不操作设备。
- 扫码失败关闭 analyzer/camera，清零帧缓冲，保留手工码。
- 连接失败销毁 invitation、socket、临时密钥和确认状态。
- 真机验收结束删除临时 helper、ZXing APK 和原始观察产物。

## 建议提交

`feat(android): streamline lan qr pairing`
