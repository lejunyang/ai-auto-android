# N38 Android LAN 出站连接规格

## 目标

Android App 消费 N36 invitation，通过扫码结果端口或手工输入完成无网络副作用预检，
在用户明确选择地址候选、本地网卡并核对桌面短指纹后，主动建立短期加密 LAN Bridge
会话。

## 范围

- 直接消费 N36 的合法 invitation、18 个威胁 fixture 和密码向量，不复制或放宽协议。
- 提供纯 Kotlin 严格解析、TTL/重放/地址/网卡/指纹预检和无 secret 的 UI 状态机。
- 以 provider-neutral 扫码结果端口隔离具体相机库；无相机或权限拒绝时保留手工输入。
- 使用平台 X25519、HKDF-SHA-256、transcript 双向确认和 AES-256-GCM framed transport。
- 通过注入 socket、网络身份、时钟和重放存储验证失败关闭及秘密生命周期。

## 安全边界

- 所有 Schema、时效、重放、地址、网卡、密钥、指纹、版本和套件校验必须先于 socket。
- 扫码或手工输入后不得自动连接；用户必须选择 invitation 候选和本地网卡，并明确确认
  当前短指纹。release 不提供绕过路径。
- 二维码图像、原始 payload、nonce、公私钥、派生 key、confirmation tag 和 token
  不进入 UI state、日志或持久化。
- 切网、过期、进程恢复、重放、地址不可达、transcript/tag 错误必须关闭连接、停止新
  动作并销毁会话秘密，禁止明文、旧版本或 loopback 配对降级。
- 本任务不修改现有 loopback Bridge、Accessibility 授权、公共导航、Manifest、Gradle
  或协议文件；相机 provider 和公共页面接线由串行集成任务完成。

## 允许修改

- `.trae/specs/n38-android-lan-outbound/`
- `android/app/src/main/java/dev/aiauto/android/bridge/lan/` 及对应单元测试
- `android/app/src/main/java/dev/aiauto/android/ui/bridge/lan/` 及对应纯 UI 单元测试
- 必要时独立的 `android/app/src/debug/` N38 test-only adapter

## 验收

- Kotlin 对 N36 合法 fixture、18 个威胁 fixture 和密码向量结果完全一致。
- 未选择候选/网卡或未确认指纹时 socket 尝试数为零；预检失败同样为零。
- 扫码结果和手工输入走同一严格解析入口；拒绝权限和无相机状态仍可手工输入。
- 注入式连接测试覆盖地址不可达、切网、过期、进程恢复、重放、transcript/tag 篡改和
  AES-GCM tag 篡改，失败后动作提交数为零且无明文降级。
- 定向 App 单测、`make comments` 和 `git diff --check` 通过。
- 与 N37 的真实同 LAN 集成、相机权限声明、扫码库接入和公共导航留给串行集成，不在
  没有设备证据时勾选。
