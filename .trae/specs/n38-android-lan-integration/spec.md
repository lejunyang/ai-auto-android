# N38 Android LAN 生产接线规格

## 目标

将既有 N38 严格 invitation、加密握手和 UI 状态机接入真实 Android 网络与 App 导航，
通过与 N37 一致的有界 NDJSON wire 主动连接用户明确选择的本地网卡和桌面地址。

## 范围

- 为握手和加密 frame 实现严格、有界的 NDJSON socket port。
- 加密 frame 完整携带 N37 所需的版本、方向、序号、类型、nonce 和 ciphertext。
- 枚举 Android 当前可用 Wi-Fi/以太网私网接口，并在连接前通过选中 `Network` 绑定
  socket；不使用 DNS、默认网络或 all-interface 猜测。
- 以生命周期感知 ViewModel 编排严格输入、显式候选/网卡/指纹确认、后台握手和停止。
- 在既有桌面 Bridge 页面增加 LAN 配对入口，并提供手工 invitation 主路径。
- 保留类型化扫码 provider 边界；没有受测 provider 时不得请求无用途的相机权限或
  宣称扫码已实现。

## 安全边界

- 单条握手消息最大 64 KiB，加密 NDJSON 最大 1 MiB + 64 KiB；超限、EOF、尾随数据、
  非对象或重复字段均失败关闭。
- socket 必须先绑定用户选择的 Android `Network`，再连接 invitation 中的 IP
  literal；禁止 DNS、公网、loopback、unspecified 和透明明文降级。
- ViewModel 不保存原始 invitation、nonce、密钥、tag 或 ciphertext；后台错误只进入
  稳定错误码。
- 未完成候选、本地网卡和短指纹确认前，连接线程、socket 和重放 claim 均不得启动。
- 页面退出、ViewModel 清理、显式停止和连接失败必须关闭 session、input port 与
  executor。
- 本任务不引入第三方扫码库，不绕过 Android 相机授权，也不把 localhost/JVM 测试
  描述为真实同 LAN、N37 互操作或 API 设备验收。

## 允许修改

- `.trae/specs/n38-android-lan-integration/`
- `android/app/src/main/java/dev/aiauto/android/bridge/lan/` 及对应测试
- `android/app/src/main/java/dev/aiauto/android/ui/bridge/` 及对应测试
- `android/app/src/main/java/dev/aiauto/android/ui/AiAutoAndroidApp.kt`
- `android/app/src/main/AndroidManifest.xml`，仅在真实扫码 provider 已落地时修改

## 禁止修改

- N36 协议 Schema、fixture 和密码向量
- Go N37 并行 worktree、现有 loopback Bridge 认证与 Accessibility 授权
- 发布变体测试入口、动作白名单、风险策略和路线图状态

## 验收

- socket 单测覆盖分片读取、严格 NDJSON、握手上限、完整 frame wire 和失败关闭。
- ViewModel 单测覆盖手工输入、确认门、单次后台连接、稳定错误码、显式停止和清理。
- Android 网络 adapter 仅暴露合格接口，并在 `Network.bindSocket` 后连接 IP literal。
- App 可从桌面 Bridge 页面进入 LAN 配对页，返回或销毁页面会停止短期会话。
- N38 定向测试、App 全量 JVM 单测、`make comments` 和 `git diff --check` 通过。
- 真实扫码、真实 N37 同 LAN、切网/防火墙/OEM 和 API 设备矩阵继续保持未验收。
