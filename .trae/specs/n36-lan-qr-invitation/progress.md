# N36 Progress

本文件 append-only，记录契约、测试证据、失败结果和后续平台集成边界。

## Round 1

- 已创建独立 change spec。
- 当前状态：正在先增加合法/威胁 fixture 与预期失败测试，Schema 和验证器尚未实现。
- 本任务仅提供跨实现协议契约；Go listener 与 Android 扫码消费者分别留给 N37、N38。

## Round 2

- 测试先行证据：合法/威胁 fixture 和定向测试落盘后，首次运行因尚不存在
  `lan-invitation.mjs` 明确返回 `ERR_MODULE_NOT_FOUND`；随后才实现 Schema 和验证器。
- 新增严格 invitation/handshake Schema、1 个合法 invitation、18 个威胁 fixture
  与 1 组跨实现密码向量。威胁中 6 个由 Schema 拒绝，12 个由纯 preflight 返回稳定
  错误码。
- 威胁覆盖过期、invitation ID/nonce 重放、过短/错误指纹、公网/unspecified/
  multicast 地址、候选与生产者网卡不匹配、字段篡改、未知 token 字段、全零或非法
  X25519 公钥、TTL 越界/时间不一致、旧版本和明文套件降级。
- 无 I/O 参考模块在 socket 或 token 签发前验证时间、重放集合、地址分类、网卡、
  指纹、能力和 `clientHello` 的 invitation ID、nonce、App 临时公钥与 endpoint 绑定。
- 固定 canonical JSON、X25519、HKDF-SHA-256、AES-256-GCM 方向 key、transcript、
  capability selection 和角色分域 HMAC confirmation。测试覆盖非零低阶点导致的
  X25519 全零 shared secret 拒绝，以及 transcript/capability 篡改。
- HKDF 128-byte material 在四个 32-byte key 独立复制后清零，confirmation expected
  tag 比较后清零；文档明确调用方负责清零 ephemeral private key、shared secret、
  派生 keys 和 transport 缓冲。
- LAN 定向测试 10/10 通过；协议全量验证通过 13 个 Schema、13 个合法 fixture、
  35 个非法 fixture 和 4 个兼容检查，共 52 项。
- `make comments` 覆盖 205 个手写文件并通过 14 个检查器自测；`git diff --check`
  通过。未修改 Android/Go 生产实现、动作白名单、现有 Bridge 认证或路线图。
- N37/N38 后续必须分别实现 Go/Kotlin 消费器，并共同运行本任务的 Schema、18 个威胁
  fixture、密码向量和秘密生命周期契约；本任务不把参考 Node 验证描述为平台已集成。
