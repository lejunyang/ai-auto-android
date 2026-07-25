# N37 LAN CLI 生产接线规格

## 目标

为 `aactl bridge lan` 增加类型化网卡发现和一次性 listener 命令，把现有
`DiscoverInterfaces`、`StartListener`、`Invitation`、`Accept` 与 `Session.Close`
接到真实 CLI 生命周期，同时保持 stdout 可机器读取且不泄露私钥或 LAN token。

## 命令契约

- `aactl bridge lan interfaces` 列出当前合格网卡和地址候选；该命令不解析 ADB。
- `aactl bridge lan listen --interface ID --address IP` 要求同时明确选择网卡 ID 和
  地址候选，不提供隐式默认。
- listener 建立后立即输出 `lan.invitation` JSON 事件，再阻塞等待一次 accept；事件
  包含 invitation JSON、手工码、fingerprint、endpoint 和 `expiresAt`。
- accept 成功后只公开协商 endpoint、capability 和过期时间，随后走幂等
  `Session.Close`；不输出 LAN token、临时私钥、derived key 或 confirmation tag。
- 可选二维码只能通过注入 `QRProvider` 生成；生产 CLI 不启动外部命令，也不隐式选择
  二维码实现。

## 安全与生命周期

- 复用 LAN 核心的私网/链路本地校验、VPN 拒绝、多网卡歧义、切网复核、重放保护和
  capability 协商，不在 CLI 重写或降低这些判断。
- `0.0.0.0`、`::`、公网、未知候选和 tunnel/VPN 均在绑定前失败。
- accept 超时、context 取消、中断信号、输出失败和握手失败均关闭 listener，并清零
  invitation 缓冲和临时私钥。
- 首次 accept 已消费 listener；重复 accept 保持 `LAN_SESSION_CLOSED`，不得重开端口
  或恢复 invitation。
- 本波次不新增任意 shell、DNS 解析、公网监听、长期 token 存储或 CLI 风险绕过。
- CLI 当前只建立并安全关闭已认证 session；在 Android 双向 wire adapter 接入前不
  对外声称已完成 LAN RPC 调度。

## 允许修改

- `.trae/specs/n37-cli-lan-integration/`
- `internal/cli/` 及对应 Go 测试
- 必要的 `internal/bridge/lan/` 窄 adapter 或可观测清理状态
- `cmd/aactl/`

## 禁止修改

- `android/`、`protocol/`、共享路线图和其他 change spec
- 任意公网或 wildcard 绑定、任意 shell、外部二维码命令
- loopback Bridge token、配对码、会话存储和既有风险策略

## 验收

- RED 测试先覆盖固定 invitation 输出、显式候选、超时、切网、取消、重复 accept 和
  secret redaction。
- `go test -race ./internal/bridge/lan ./internal/cli ./cmd/aactl` 通过。
- `make test`、`make verify`、`make comments`、`git diff --check` 通过。
- localhost 仅记录为 integration smoke；macOS/Windows 同 LAN 和 Windows 防火墙
  保持未验收。
