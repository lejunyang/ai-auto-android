# N37/N38 二维码生产闭环规格

## 目标

在不改变 N36 invitation、N37 listener 或 N38 加密连接协议的前提下，补齐桌面端真实
二维码 representation 与 Android 受测扫码 provider，使扫码和手工邀请码都进入同一
严格输入端口，并继续要求用户选择网卡、地址和核对短指纹后才允许连接。

## N37 输出契约

- 生产 `aactl bridge lan listen` 默认使用纯 Go、无 CGO 的固定有界 QR Model 2
  provider；不调用外部命令、不打开 GUI、不创建二维码临时文件。
- stdout 的 invitation 事件增加 `qrText` 字段，`qrFormat` 固定为
  `terminal-utf8-v1`；内容使用 UTF-8 半块字符表示带静区的黑白模块，调用方可直接
  在等宽终端显示。
- 二维码编码的内容必须与 `invitationJson` 完全一致；表示必须有固定最大 payload、
  module、行、列和输出字节数。超出二维码容量时只降级为 `payload-only` 并保留
  `manualCode`；context 取消或 provider 内部错误仍失败关闭并清理临时私钥、payload
  和 representation。
- `manualCode` 继续输出并与二维码表示同源；二维码数据只写本次显式 stdout，不进入
  stderr、日志、仓库或持久化文件。

## N38 扫码契约

- App 使用类型化外部 QR scanner Activity contract；只接受明确 QR 格式、非空且不
  超过 64 KiB 的结果，取消、畸形结果或 provider 异常均返回稳定状态。
- provider 必须由系统解析为显式组件后才能启动；不隐式选择任意 handler。生产适配
  当前只信任已知 ZXing scanner package，后续 provider 必须独立评审后加入。
- App 在扫码页明确展示相机硬件、provider 和权限边界。相机权限由外部 scanner 自身
  请求和持有，本 App 不声明无用途的 `CAMERA`，也不绕过授权；拒绝或取消后手工路径
  始终可用。
- scanner 原始结果只在 Activity result 回调栈内存在，立即交给
  `StrictLanInvitationInputPort`；状态、ViewModel、日志和进程恢复数据不保留原始
  payload。
- 手工输入接受 N37 的 `AIAUTO1-` base32 code，并解码到同一个严格 JSON
  parser/preflight。扫码 JSON 和手工码都只产生安全摘要，且都必须经过候选地址、本地
  网卡和短指纹确认；release 不自动连接。

## 允许修改

- `.trae/specs/n37-n38-qr-integration/`
- `internal/bridge/lan/`、`internal/cli/`、必要的 `cmd/aactl/` 与对应 Go tests
- `android/app/src/main/java/dev/aiauto/android/bridge/lan/`
- `android/app/src/main/java/dev/aiauto/android/ui/bridge/lan/`
- 必要的 App 导航、UI、资源、Manifest 与对应 unit/androidTest
- 现有 N37/N38 `progress.md` 仅可 append

## 禁止修改

- `docs/next-phase-tasks.md`、N36 schema/fixture、动作白名单和无障碍授权流程
- SDK、模拟器、设备、APK 下载、外部扫码 App 安装或任何权限自动确认
- 二维码/邀请持久化、原始扫描日志、明文或静默连接、任意外部命令

## 验收

- RED 先覆盖二维码确定性/精确内容/边界，以及扫码 provider、手工码解码、取消与零
  自动连接。
- Go LAN/CLI 定向与 race、Android LAN/UI 定向通过。
- App unit、lint、debug、androidTest、release 按改动范围通过。
- `make comments`、`make test`、`make verify`、`make build`、`git diff --check`
  通过。
- 真机仍需人工安装/选择受信 scanner、处理其相机授权，并验证实际终端扫码；自动化
  测试不得伪装成真机相机结论。
