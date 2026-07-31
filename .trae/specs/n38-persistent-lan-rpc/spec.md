# N38 持久 LAN Bridge RPC 规格

## 目标

在 N38 双方确认完成后，由 Android 持续接收桌面发送的加密
`bridge.request` frame，并通过 LAN 专用 adapter 复用现有 `BridgeDispatcher` 的严格
JSON-RPC envelope、`requestId` 防重放、deadline 和 `BridgeMethodHandler` 业务风险门。
响应以加密 `bridge.response` frame 返回，同一短期 LAN session 支持多次请求。

## 线契约

- 请求 frame 的 `type` 必须为 `bridge.request`，plaintext 必须是现有 Bridge Request
  JSON，且 `token` 必须是本次 LAN handshake 派生的 token。
- 响应 frame 的 `type` 必须为 `bridge.response`，plaintext 必须是现有 Bridge
  JSON-RPC Response。
- LAN token 固定为
  `HMAC-SHA256(tokenBindingKey, domain || 0 || transcriptHash || 0 || invitationId || 0 || expiresAt)`，
  其中 domain 为 `AIAUTO-LAN-BRIDGE-SESSION-TOKEN-V1`，`expiresAt` 使用 UTC
  RFC3339Nano，wire 编码使用无 padding base64url。
- Android 必须以常量时间验证 LAN token；token 不进入日志、UI、持久化或
  `BridgeSessionManager`。

## 调度语义

- LAN handshake 已完成连接版本协商和认证；`rpc.hello` 与 `session.open` 必须返回
  `PROTOCOL_ERROR`，不得生成或接受 loopback pairing code/token。
- 普通业务方法继续由现有 `BridgeMethodHandler` 处理，不复制或绕过 capability、
  权限、目标包和动作风险门。
- `requestId` 继续使用现有 `RequestReplayCache`；`deadlineMs` 继续使用现有
  `BridgeDispatcher` 解析和稳定 `DEADLINE_EXCEEDED` 响应。
- 合法 `session.close` 先返回加密成功响应，再关闭 LAN session；后续 frame 不得处理。

## 安全与生命周期

- 入站 frame 必须严格校验固定字段集合、版本、desktop-to-client 方向、单调序号、
  nonce、AAD、GCM tag、frame type 和有界 ciphertext。
- 方向错误、重放、乱序、nonce/AAD/tag 篡改、错误 frame type 或畸形 JSON 均失败
  关闭，并清零可控 plaintext、token、key 和 transcript 缓冲。
- 显式关闭、对端断开、切网或 session 过期必须关闭 socket，使阻塞读取立即返回；
  不得降级到明文、loopback 认证或旧协议。
- 不记录 token、key、nonce、ciphertext、plaintext、原始 invitation 或请求内容。

## 修改范围

- `.trae/specs/n38-persistent-lan-rpc/`
- `android/app/src/main/java/dev/aiauto/android/bridge/lan/`
- 必要的 `android/app/src/main/java/dev/aiauto/android/bridge/` 独立 LAN adapter 文件
- `android/app/src/test/java/dev/aiauto/android/bridge/lan/` 与对应 adapter 测试

禁止修改协议 Schema/fixture、Go、公共路线图、Manifest/权限、相机、无障碍授权或
release 安全边界；禁止 shell/raw ADB。

## 验收

- 固定 token 向量与 Go N37 公式一致，错误或缺失 token 不进入业务 handler。
- 入站 frame 对方向、序号、nonce、AAD/tag 和严格 NDJSON 的负向测试全部失败关闭。
- 同一 session 完成至少两次 request/response，重复 `requestId` 被拒绝，deadline
  返回稳定错误，`session.close` 响应后关闭。
- 显式关闭、切网和过期均自动中断阻塞读取；后续动作提交数为零。
- N38 定向测试、App JVM 全量、`lintDebug`、`assembleDebug`、
  `assembleDebugAndroidTest`、`assembleRelease`、`make comments` 和
  `git diff --check` 全部通过。
