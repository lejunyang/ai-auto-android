# API 34 真机内置 LAN 扫码验收清单

- [x] 唯一设备为 OPPO Android 14 / API 34，USB state 为 `device`
- [x] `adb.direct` capability 可用
- [x] debug App 仅安装到明确 serial
- [x] 相机权限由用户人工批准
- [x] `PreviewView` 在真机可见且扫码会话持续
- [x] 扫码页可跨相机触发的配置变化保持
- [x] 二维码先经 ZXing Core 3.5.4 离线完整解码
- [x] 内置扫码后不要求输入短指纹或长确认码
- [x] 用户仅点击一次“我已核对短指纹并连接”
- [x] 同一 socket 三次 `device.info` 成功
- [x] `session.close` 返回 `closed=1`
- [x] 手机显式停止后不再显示活动会话状态
- [x] 二维码、临时 CLI、解码工具和摘要已删除
- [x] Make、Android 全量门禁和文档差异检查通过
- [x] Git 作者、提交者和 trailer 通过
