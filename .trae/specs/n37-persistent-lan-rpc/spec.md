# N37 持续 LAN RPC 规格

## 目标

在 N36/N37 双方确认完成后的同一加密 `Session` 上复用现有 Bridge JSON-RPC
请求/响应，实现有界、多次、可关闭的持续 RPC，而不是认证后立即关闭 socket。

## 线契约

- frame type 固定为 `bridge.request` 与 `bridge.response`。
- plaintext 复用现有 `bridge.Request`/`bridge.Response`，并调用
  `bridge.ValidateResponse`。
- 每个请求生成新的 `id`、`requestId`，携带 LAN token、当前协议版本和 deadline。
- LAN token 只存在于加密 plaintext，不进入 argv、stdout、错误或持久化状态。
- `rpc.hello`、`session.open` 不允许通过持续 RPC 重做；它们已由 LAN handshake
  和人工短指纹确认替代。

## 允许修改

- `.trae/specs/n37-persistent-lan-rpc/`
- `internal/bridge/lan/`
- `internal/cli/lan.go` 与 `internal/cli/lan_test.go`
- `.trae/specs/n37-*/progress.md` 的 append-only 证据

## 禁止修改

- Android、协议 Schema/fixture、公共路线图和 loopback Bridge 行为
- 任意 action CLI 参数、明文 token 输出、无限读取或无 deadline I/O
- listener 默认绑定范围、能力白名单和现有失败关闭边界

## 验收

- 同一真实 socket 可连续完成至少两个请求/响应，并保持严格方向与序号。
- response type、标识、协议、重复 JSON key、deadline 或 token 违约均关闭 session。
- CLI 只允许固定只读探针 `device.info`、`recording.list`，默认 listen 行为兼容。
- cancel、过期、输出失败和 `session.close` 均关闭 socket并清零可控秘密。
