# N37 Go LAN QR Listener 规格

## 目标

在桌面端提供仅绑定操作员明确选择网卡的短期 LAN listener，生成与 N36 完全一致的
invitation JSON 和手工邀请码，并以 X25519、HKDF、双方确认及 AES-GCM framed RPC
建立一次性、失败关闭的 LAN Bridge 会话。

## 范围

- 枚举私网或链路本地候选地址，要求明确网卡选择，并拒绝 VPN、多网卡歧义和
  unspecified/all-interface 绑定。
- 创建有界 TTL 的临时 listener，显式管理端口、accept、取消、切网和客户端断开
  生命周期。
- 严格消费 N36 Schema 语义、18 个威胁 fixture 和密码向量，生成确定 invitation
  payload 与不依赖摄像头的手工邀请码文本。
- 以标准库 `crypto/ecdh` 完成 X25519，以等价 HKDF-SHA-256 派生四个分域密钥，
  完成 transcript 双方确认。
- 以 AES-256-GCM 传输有界 frame；AAD 绑定 transcript、方向、单调序号和 frame
  类型，拒绝乱序、重放和篡改。
- 通过窄 adapter 复用既有 capability、风险门、目标包、deadline 和
  `session.close` 语义，不复用或降低 loopback Bridge 认证。
- 为后续共享 CLI 注册提供 N37 专用服务入口；本任务不修改现有 CLI root/switch。

## 安全边界

- listener 不得默认绑定 `0.0.0.0`、`::` 或所有网卡；地址必须属于当前明确选择的
  接口，接口变化后立即关闭。
- invitation、二维码 provider 和手工邀请码不得包含最终 token、临时私钥、
  derived key、tag、loopback 配对码或长期秘密。
- 首个合法 `lan.clientHello` 原子消费 invitation ID 和 nonce；其后失败不得恢复
  invitation，也不得回退到明文、旧协议或 loopback 认证。
- 双方 confirmation 完成前不得接受 RPC、签发 LAN token 或持久化信任。
- 每个方向独立 key 和序列空间；nonce 唯一，乱序、重放、方向/type/AAD 篡改均关闭
  会话。
- 过期、错误接口、错误 fingerprint/transcript、accept 超时、连接中断、切网和取消
  均关闭 socket，并清零可控的临时私钥、shared secret、derived keys 与 frame
  plaintext。
- 日志和错误详情只允许稳定错误码及不可逆 invitation 摘要，不记录 QR 原文、nonce、
  完整公钥、key、tag 或 token。
- LAN token 仅属于本次 transcript 绑定会话，不能传给现有 loopback Bridge。

## 网络选择

- 生产枚举只接受 `Up`、非 loopback 且有合格私网/链路本地 IP literal 的接口。
- 接口稳定 ID、名称和类型必须由调用方明确选择；名称或地址归属变化视为切网。
- 同时存在多个可用接口且未指定时返回稳定歧义错误，VPN/tunnel 不作为隐式默认项。
- IPv6 link-local listener 与 endpoint 必须带选中接口 zone；不能用 DNS 或公网地址
  扩大绑定范围。
- 测试可用固定 fake interfaces 加真实 localhost socket 验证协议状态机，但该路径不
  等同真实 LAN、macOS/Windows 防火墙或 Windows 设备验收。

## Invitation 与二维码边界

- JSON payload 使用 N36 字段、canonical JSON、fingerprint、能力和密码套件，不增加
  私有字段。
- 手工邀请码是同一 invitation payload 的确定编码，解码后必须字节等价，不产生第二
  套弱认证协议。
- 本任务不增加二维码依赖；二维码通过接收 payload 的 provider 接口集成。未注入
  provider 时只输出 payload 和手工文本，明确报告未生成二维码图像。

## Framed RPC 与适配器

- frame 包含固定版本、方向、序号、类型和 ciphertext；单帧及解密后明文均有上限。
- nonce 由方向分域和单调序号确定，达到上限即关闭，禁止回绕。
- `RPCAdapter` 只接收已完成双方确认的解密 RPC，将协商 capability、目标包、
  deadline、取消和 close 传给既有服务边界。
- adapter 不导入或调用 loopback `session.open`、配对码或 token store；共享 CLI
  注册由主线程在其他并行工作结束后串行完成。

## 允许修改

- `.trae/specs/n37-go-lan-listener/`
- `internal/bridge/lan/`
- `cmd/aactl/` 下新增的 N37 专用文件
- `internal/cli/` 下新增的 N37 专用文件及对应测试

## 禁止修改

- `protocol/`、`android/`、`go.mod`、`go.sum`
- 现有 loopback Bridge 认证、会话存储和公共 CLI root/switch
- 路线图、README、N38 或其他并行任务目录
- 动作白名单、风险策略或发布安全边界

## 验收

- 测试先行证据明确显示 N36 fixture/vector Go 契约在实现前失败。
- 合法 fixture 通过，18 个 threat fixture 逐项返回 N36 声明的稳定错误码。
- fingerprint、transcript、四个 HKDF key、双方 confirmation 和低阶点拒绝与 N36
  vector 字节一致。
- fake interfaces 覆盖显式选择、VPN/多网卡歧义、地址归属和切网关闭。
- 真实 localhost socket 覆盖 accept、握手、加密 RPC、乱序、重放、篡改、超时、
  断开、取消和清理；不得将其描述为真实 LAN 验收。
- `go test -race ./internal/bridge/lan/...`、相关 CLI 测试、`make comments`、
  `gofmt` 和 `git diff --check` 通过。
- 记录 Windows 真实防火墙与 macOS/Windows 同 LAN 建连仍待实际平台验收。
