# API 34 真机内置 LAN 扫码验收规格

## 目标

在用户明确授权、唯一在线的 OPPO Android 14 / API 34 真机上，验证内置 CameraX
扫码、一次短指纹确认、三次只读 RPC、双方关闭和临时产物清理；同时记录真机暴露的
预览、布局、暂态帧与配置变化兼容问题。

本 change 只记录已取得的设备证据和路线图状态，不扩大为其他 OEM、Windows 防火墙、
热点、VPN 或切网矩阵结论。

## 安全与隐私边界

- 每条设备命令使用发现流程返回的唯一精确 serial；文档不记录原始 serial、IP、
  invitation、短指纹、相机画面或 session secret。
- 相机权限和短指纹确认由用户手动完成；不得自动批准系统权限或代替用户确认。
- 只执行 `device.info` 三次和 `session.close`，不执行动作、录制回放或第三方 App 操作。
- 二维码、临时 CLI、解码工具和脱敏摘要均位于仓库外或未跟踪固定文件，验收后删除。

## 验收范围

1. 唯一 USB 真机 discovery、设备信息和 App 前台页面确认。
2. CameraX 预览可见、权限不重复请求、配置变化后扫码会话仍保持。
3. 内置 ZXing Core 识别经同版离线解码验证的 N36 invitation。
4. 用户只点击一次“我已核对短指纹并连接”。
5. 同一加密 socket 完成三次 `device.info` 和 `session.close`。
6. 用户显式停止手机端 LAN 会话，页面不再显示活动网卡、过期时间或停止按钮。
7. 删除所有二维码、临时 helper 和原始设备观察产物。

## 允许修改

- `.trae/specs/real-device-acceptance/`
- `.trae/specs/n37-n38-pairing-usability/` 的 append-only 设备证据与清单状态
- `docs/next-phase-tasks.md` 的 N37/N38 append-only 实现记录和状态

## 禁止修改

- Android、Go、Node 生产实现和测试
- 无障碍、动作白名单、Bridge 协议、CI、SDK 或用户设备系统设置
- 提交 APK、二维码、invitation、短指纹、原始 serial、截图或 hierarchy

## 完成标准

- 每个设备动作有最新前置和新鲜后置；未知结果不重试。
- `rpc=3`、`closed=1`，手机显式停止后无活动 LAN 状态。
- `make test`、`make verify`、`make build`、Android unit、AndroidTest Kotlin 编译、
  lint、debug/release 构建和 `make comments` 通过。
- N38 只按现有验收证据更新；N37 的 Windows、防火墙和网络矩阵仍保持未完成。
