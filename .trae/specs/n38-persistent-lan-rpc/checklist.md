# N38 持久 LAN Bridge RPC 验收清单

- [x] LAN token 与 Go N37 固定公式和 base64url 编码一致
- [x] LAN token 仅在加密 plaintext 内验证，不进入 loopback `BridgeSessionManager`
- [x] 入站 frame 严格验证方向、序号、nonce、AAD、tag、type 和大小
- [x] 畸形、重放、乱序或篡改 frame 失败关闭并销毁秘密
- [x] `bridge.request` 复用严格 Bridge request schema、业务 handler 和风险门
- [x] `rpc.hello` 与 `session.open` 在 LAN transport 上明确拒绝
- [x] 同一 session 支持多次 request/response 和 `requestId` 防重放
- [x] 同一 LAN socket 使用单线程同步 deadline 调度，请求/响应不并发乱序
- [x] `AUTH_REQUIRED`、`AUTH_INVALID`、`AUTH_EXPIRED` 加密回包后关闭 transport
- [x] deadline 返回稳定错误且不阻塞后续安全关闭
- [x] `session.close` 返回响应后关闭，后续请求不再处理
- [x] 显式关闭、切网、过期和对端断开可中断阻塞读取
- [x] 无 token、key、nonce、ciphertext 或 plaintext 进入日志、状态或持久化
- [x] N38 强制定向 64/64 与 App JVM 全量测试通过
- [x] `lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、
  `make comments` 与 `git diff --check` 通过
- [x] 只修改允许目录，提交身份、信息和 trailer 符合约束
- [x] 提交后 worktree 干净
- [ ] Go N37 与 Android N38 的真实跨实现加密 socket 多轮 RPC 尚未完成
