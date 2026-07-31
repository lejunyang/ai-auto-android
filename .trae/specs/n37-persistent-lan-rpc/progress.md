# N37 持续 LAN RPC 进度

本文件 append-only，记录测试、实现、互操作、清理和集成证据。

## Round 1

- 现有 `lan.Session` 已提供加密 `ReadFrame/WriteFrame`，但 CLI 在认证后立即关闭；
  Android 也只有单向发送，因此 N37/N38 的实际缺口是持续 request/response。
- 本轮固定复用 Bridge JSON-RPC plaintext 与 LAN 加密 frame，不新增动作协议，不把
  loopback pairing code/token 迁移到 LAN 信任域。

## Round 2

- RED 测试以真实 `net.Pipe` 和双方 Framer 先因 `Session.Call` 缺失而编译失败。
  实现后，同一 authenticated session 连续完成 `device.info` 与 `recording.list`，
  每轮使用新 UUID、LAN token、Bridge deadline、`bridge.request/response` 和严格
  response 标识校验。
- `rpcMu` 将 write+read 绑定为单一往返，避免并发调用串响应。错误 frame type、
  response ID/protocol/严格 JSON 违约和 `AUTH_REQUIRED/INVALID/EXPIRED` 均关闭
  session；普通 `CAPABILITY_UNAVAILABLE` 业务错误不销毁仍可信的 transport。
- `rpc.hello` 与 `session.open` 在写 frame 前拒绝，防止通过 LAN RPC 重做 loopback
  认证。`session.close` 成功响应后本地也清零并关闭。
- CLI 新增可选 `--rpc-method device.info|recording.list` 与 `--rpc-count 1..20`，
  只用于固定只读 probe；默认 listen 输出与关闭行为兼容。RPC event 只输出 result、
  method 和 iteration，不输出 token、key 或 request plaintext。
- LAN/CLI 定向 race、Go 全包、protocol/skills、`make verify`、`make build`、
  `make comments` 与 `git diff --check` 通过。跨 Kotlin 真实 socket 互操作等待 N38
  分支汇合后执行，因此 N37 仍未完成。
