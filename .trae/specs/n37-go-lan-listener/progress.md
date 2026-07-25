# N37 Progress

本文件 append-only，记录测试先行证据、实现轮次、验证结果和未覆盖平台边界。

## Round 1

- 已确认独立 worktree `phase2-n37-lan-listener` 干净，HEAD 为 N36 依赖提交
  `9d5017e`；N32/N33/N34/N38/N40/N43 位于互斥 worktree。
- 已阅读 MVP spec、备选方案、N37 路线图、N36 change spec、LAN 协议文档、
  invitation/handshake Schema、18 个 threat fixture 和 crypto vector。
- 当前检查点仅创建 N37 change spec 与直接消费 N36 fixture/vector 的 RED Go 测试；
  listener、crypto 生产实现和共享 CLI 注册尚未开始。
- 测试环境允许固定 fake interfaces 和真实 localhost socket，但不会把 loopback
  结果描述为真实 LAN、Windows 防火墙或 Windows 设备验收。

## Round 2

- 测试先行文件包含 3 个顶层 Go 测试，直接读取 N36 合法 invitation、18 个 threat
  fixture 和 handshake crypto vector，不在 Go 源中复制 expected bytes。
- 首次 `go test` 因非登录 shell 未包含 Go SDK 而未进入编译；改用显式
  `/Volumes/aigo S7 Media/Projects/SDK/go-1.26.5/bin/go`，并将 `GOCACHE` 隔离到
  `/private/tmp/ai-auto-n37-gocache`，避免把环境失败误记为 RED。
- 有效 RED 结果：包编译因 `Code`、`ParseInvitationJSON`、`ValidateInvitation`、
  `BuildTranscriptHash` 等 N37 生产符号尚不存在而失败。该结果发生在任何 listener
  或 crypto 生产实现落盘之前，符合测试先行检查点。

## Round 3

- N36 纯协议切片转绿：合法 invitation、18/18 threat fixture、fingerprint、
  transcript、四个 HKDF key、桌面/客户端 confirmation 和 X25519 低阶点拒绝均与
  N36 fixture/vector 字节一致。
- 第二批测试先行覆盖明确网卡选择、VPN/多网卡歧义、wildcard 拒绝、切网、
  invitation/manual/provider 同 payload、临时私钥清零、AES-GCM frame 乱序/重放/
  篡改和 localhost listener 生命周期。
- 第二批有效 RED 首先因 `NetworkInterface`、`QRRepresentation`、`Framer` 和
  listener 符号尚不存在而编译失败。独立 race 检查随后还发现过渡实现曾引用标准库
  不存在的 `cipher.ErrOpen`，且 listener 类型尚未完成；生产实现已改为将任意 AEAD
  `Open` 失败包装为本包稳定 `LAN_FRAME_INVALID`，不依赖具体标准库错误类型。

## Round 4

- 已实现明确网卡枚举与选择、VPN/tunnel 拒绝、单一私网或链路本地地址绑定、切网
  复核、临时 listener、确定 invitation/手工码和二维码 provider 端口；生产 binder
  从不绑定 `0.0.0.0` 或 `::`。
- listener 按 N36 顺序完成 client hello、原子重放消费、X25519、HKDF、transcript
  与双方 confirmation；双方确认前不签发 LAN token。LAN token 由独立 domain 与
  token-binding key 派生，不复用 loopback Bridge 配对码或 token。
- framed RPC 使用独立方向 key、方向分域 nonce、严格单调序号和绑定 transcript、
  direction、sequence、type 的 AAD；重放、乱序、篡改、超限、断开、取消和超时均
  失败关闭并清零可控密钥与缓冲。
- 初次并发复验真实检测到 `Seal` 重复 nonce 和 `Destroy/Seal` data race；修复后
  Framer 与 Session 分别串行化 send/receive/state，并增加并发 nonce 唯一和 close
  回归。最终 `go test -race ./internal/bridge/lan/...` 通过。
- `make comments` 覆盖 223 个手写文件与 14 个检查器自测，`gofmt` 和
  `git diff --check` 通过；未修改共享 CLI root、现有 loopback Bridge 或协议文件。
- localhost socket 仅验证生命周期与协议语义，不能替代 macOS/Windows 同 LAN 和
  Windows 防火墙验收；对应 checklist 保持未勾选，N37 路线图任务暂不标为完成。
