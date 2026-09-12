# N37/N38 配对可用性与内置扫码验收清单

- [x] invitation TTL 范围为 15 至 600 秒，默认 300 秒
- [x] 过期、重放和 TTL/timestamp 不一致继续失败关闭
- [x] 唯一地址和唯一手机 LAN 网卡自动选择
- [x] 多候选不自动选择
- [x] UI 不再要求抄写短指纹
- [x] 只有一次明确“核对并连接”动作可签发连接请求
- [x] 切换候选、失败、停止和恢复会撤销确认
- [x] release 仅在用户点击后请求 `CAMERA`
- [x] CameraX 帧不保存、不上传并始终关闭和清零
- [x] QR 解码只接受 QR_CODE 和 64 KiB 内 payload，并兼容深色终端反色
- [x] 相机拒绝/无相机时手工邀请码仍可用
- [x] 外部 ZXing package/signature 不再是运行依赖
- [x] CLI `--terminal` 直接渲染二维码且不输出协议内部字段
- [x] protocol、Go race、Android unit/lint/build 全部通过
- [x] API 34 真机内置扫码、RPC、停止和零临时产物通过
