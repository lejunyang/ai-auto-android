# N37/N38 真实跨运行时 LAN RPC 验收清单

- [x] Android 入口仅存在于 androidTest，release 零新增入口
- [x] 成功路径使用生产 CLI、listener、handshake、framer 与 Kotlin session
- [x] 同一真实 TCP socket 连续完成三次 `device.info`
- [x] `session.close` 响应后双方关闭且不再处理请求
- [x] 错误 LAN token 只存在于加密 plaintext 且不进入输出
- [x] Android 加密返回 `AUTH_INVALID` 后关闭真实 socket
- [x] API 30、33、34 分别通过两个独立 session 场景
- [x] Android 11 使用同一 RFC 7748 字节契约的 Tink X25519 安全回退
- [x] 每轮 clean restore，最终设备/runtime/lease/temp 为零
- [x] 定向、全量、构建、comments 与 diff-check 通过
- [x] 提交身份、trailer、集成和 worktree 清理正确
