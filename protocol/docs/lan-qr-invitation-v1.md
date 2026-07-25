# LAN QR Invitation v1

本文档定义桌面临时 listener 与 Android App 主动出站连接之间的 LAN Bridge 建连契约。
它与 ADB forward 访问的 loopback App Bridge 属于不同信任域，不复用配对码、token、
密钥或认证降级路径。

## QR 内容与生命周期

QR payload 必须完整符合 `lan-invitation.schema.json`。invitation 包含：

- 固定 `kind` 和版本 `1.0`、随机 UUID `invitationId`；
- UTC `issuedAt`、`expiresAt` 与 15–120 秒 `ttlSeconds`；
- 32-byte 随机 `nonce`，使用无 padding base64url；
- 单个明确选择的网络接口、1024–65535 临时端口，以及绑定该接口的私网或链路本地
  IP literal 候选；
- 桌面 X25519 临时公钥、16 hex digit 分组短指纹；
- 桌面愿意协商的 capabilities 和固定密码套件。

QR 严禁包含最终 session token、loopback 配对码、API Key、录制 secret、临时私钥、
长期设备密钥、明文回退地址或持久认证材料。Schema 对 invitation 及嵌套对象全部使用
`additionalProperties: false`，任何未知字段在网络操作前拒绝。

invitation 只能消费一次。`invitationId` 与 `nonce` 任一已消费都视为重放；实现应在
成功接收首个合法 `lan.clientHello` 时原子标记二者已消费，连接失败也不得恢复为可用。
达到 `expiresAt` 的瞬间即过期，接收时间早于 `issuedAt` 也拒绝。重放状态的持久范围
至少覆盖 invitation TTL，并应在过期后有界清理。

## 地址与网卡

地址只接受 IP literal，不进行 DNS 解析。每个候选的 `interfaceId` 必须等于
`selectedInterface.id`；IPv6 link-local 候选还必须携带与接口名相同的 `zoneId`。
实现必须重新按地址字节分类，不能信任 QR 中声明的 `scope`：

- IPv4 只允许 RFC 1918 或 `169.254.0.0/16`；
- IPv6 只允许 ULA `fc00::/7` 或 link-local `fe80::/10`；
- 拒绝 loopback、unspecified、multicast、公网地址、family/scope 不一致和错误网卡；
- listener 只能绑定 invitation 中明确选择的接口和端口，不能隐式改为所有网卡；
- 所有候选不可达时可要求用户输入同一 invitation 的一次性信息，但不得回退到明文、
  无认证 listener 或旧版协议。

`validateLanInvitationPreflight` 是无 I/O 参考实现。N37/N38 的 Go/Kotlin 消费者必须在
创建或连接 socket、签发 token、写入信任状态前执行等价的 Schema 与 preflight 校验。
N37 在生成 invitation 和接受 `clientHello` 前还必须把操作员实际选择的桌面接口作为
`expectedInterface` 传入并逐项匹配 `id`、`name`、`kind`；N38 无法独立观察桌面网卡，
但必须保证所选 endpoint、candidate、zone 和 transcript 都绑定同一个 QR 接口。

## 短指纹

短指纹用于用户核对和快速发现 QR 字段篡改，不替代 X25519 transcript confirmation。
计算步骤：

1. 从 invitation 对象移除 `fingerprint` 字段。
2. 按本协议固定的 canonical JSON 生成字节：对象 key 按 UTF-8 字节排序，无空白；
   字符串按 JSON 转义；只允许 null、boolean、string、safe integer、array 和
   object。本规则不委托给平台默认 map 顺序。
3. 计算：

```text
SHA-256(
  UTF8("AIAUTO-LAN-INVITATION-FINGERPRINT-V1") || 0x00 ||
  UTF8(canonicalInvitationWithoutFingerprint)
)
```

取 digest 前 8 bytes，按大写 hex 编为四组 `XXXX-XXXX-XXXX-XXXX`。任何 invitation
字段变化都会改变指纹。`lan-handshake-v1-vector.json` 固定跨实现结果。

## Handshake 与 Transcript

双方只支持 `X25519-HKDF-SHA256-AES-256-GCM`，不协商空密码套件。消息顺序为：

1. App 完成 invitation preflight 后连接选定 endpoint，发送严格
   `lan.clientHello`，包含 App 临时公钥、原 invitation ID/nonce、实际 endpoint、
   支持版本和请求 capabilities。
2. 桌面校验 invitation 未过期、未重放且 endpoint 属于邀请，选择双方明确支持的
   `1.0` 和 capability 交集。`lan.bridge.rpc.v1` 与
   `lan.bridge.mutual-confirmation.v1` 是必需能力，缺失即
   `LAN_CAPABILITY_MISMATCH`。
3. 双方计算相同 transcript hash。桌面发送 `lan.desktopSelection`，App 必须重新
   计算并以常量时间比较 `transcriptHash`。
4. 双方完成 X25519，拒绝非法公钥及 RFC 7748 要求拒绝的全零 shared secret。
5. 双方分别发送并验证角色分域的 `lan.confirmation`。两侧确认均通过前不得发送
   JSON-RPC、签发 session token 或持久化信任。
6. 双方确认后，桌面才能签发绑定 `tokenBindingKey`、transcript hash、能力集合、
   endpoint 和短 TTL 的新 LAN session token。该 token 不能用于 loopback Bridge。

桌面必须在处理 X25519、发送 selection 或签发 token 前调用等价于
`validateLanClientHello` 的纯校验，确认 `invitationId`、`nonce`、App 临时公钥和
实际 endpoint 全部绑定原 invitation。任何一项篡改都关闭连接，不能换地址重试或改走
loopback 认证。

transcript 输入是以下对象的 canonical JSON，保持数组顺序，不能在哈希时静默排序：

```json
{
  "invitation": "<完整 invitation，包含 fingerprint>",
  "clientHello": "<完整 lan.clientHello>",
  "selection": {
    "selectedBridgeVersion": "1.0",
    "selectedCapabilities": ["<按 UTF-8 字节升序>"]
  }
}
```

计算：

```text
transcriptHash = SHA-256(
  UTF8("AIAUTO-LAN-BRIDGE-TRANSCRIPT-V1") || 0x00 ||
  UTF8(canonicalTranscriptObject)
)
```

`selectedCapabilities` 必须是请求集合中同时被 invitation 和桌面策略允许的项，并按
UTF-8 字节升序编码。客户端不得请求 invitation 未发布的能力，桌面不得静默增加能力。
版本或必需能力不匹配必须失败，不得尝试 `0.x`、loopback `session.open` 或明文路径。

## HKDF 与双方确认

X25519 shared secret 必须是非零 32-byte 值。HKDF-SHA-256 参数为：

```text
salt = SHA-256(
  UTF8("AIAUTO-LAN-BRIDGE-HKDF-SALT-V1") || 0x00 || transcriptHash
)
IKM  = x25519SharedSecret
info = UTF8("AIAUTO-LAN-BRIDGE-KEYS-V1") || 0x00
L    = 128
```

128-byte 输出按顺序切分为四个独立 32-byte key：

1. `clientToDesktopKey`
2. `desktopToClientKey`
3. `confirmationKey`
4. `tokenBindingKey`

方向 key 只能用于各自方向的 AES-256-GCM framed transport；每帧 nonce 必须唯一，
并将 transcript hash、方向、单调序号和 frame 类型作为 AAD。具体 framed transport
由 N37/N38 实现，但不得改变上述 key 顺序或跨方向复用 nonce。

双方确认标签：

```text
HMAC-SHA256(
  confirmationKey,
  UTF8("AIAUTO-LAN-BRIDGE-CONFIRMATION-V1") || 0x00 ||
  UTF8(role) || 0x00 ||
  transcriptHash
)
```

`role` 只能是 `desktop` 或 `client`，接收方用常量时间比较。桌面 tag 不能作为客户端
tag 重放。transcript 或 tag 不匹配分别返回 `LAN_TRANSCRIPT_MISMATCH`、
`LAN_CONFIRMATION_INVALID`，关闭连接且不签发 token。

## 秘密清理

临时密钥只活到握手结束：

- invitation 撤销、过期、握手成功或失败后立即销毁 X25519 ephemeral private key；
- X25519 shared secret 传入 HKDF 后立即清零；
- 128-byte HKDF material 切片复制到四个独立 key 后立即清零；
- confirmation expected tag 在常量时间比较后立即清零；
- session 关闭、过期、切网或认证失败后清零方向 key、confirmation key、
  token-binding key、未发送 token 和加密帧缓冲；
- 不能保证内存清零的运行时必须缩短作用域、禁止日志/序列化，并使用平台支持的
  可销毁密钥容器；不能把垃圾回收描述为已验证清零。

参考 Node 模块执行上述可控 Buffer 的 `fill(0)`，但调用方仍负责清零传入的
`sharedSecret`、返回的派生 keys、ephemeral private key 和 transport 缓冲。

## 会话分域与稳定错误

LAN Bridge 复用的是确认完成后的版本化 JSON-RPC 方法、capability、风险门、目标包、
deadline 和 `session.close` 语义，不复用 loopback 建连认证：

| 属性 | Loopback App Bridge | LAN QR Bridge |
| --- | --- | --- |
| transport | ADB forward 到设备 loopback | App 主动出站到明确 LAN endpoint |
| 外层信任 | ADB RSA | invitation + 用户短指纹核对 |
| 建连认证 | UI 一次性配对码 | X25519 + transcript 双方确认 |
| token | loopback session token | transcript/tokenBindingKey 绑定的新 LAN token |
| 降级 | 不适用 | 禁止回退到 loopback code、明文或旧版本 |

LAN 稳定错误码由 `lan-handshake.schema.json` 枚举，并与纯验证器导出列表自动核对。
错误日志只能记录 code、invitation ID 的不可逆摘要和短指纹；不得记录 QR 原文、
nonce、完整临时公钥、shared secret、派生 key、confirmation tag 或 token。

## 跨实现集成

本任务不修改 Go/Android 生产实现。N37/N38 必须共同消费：

- `lan-invitation.schema.json` 与 `lan-handshake.schema.json`；
- `lan-invitation-v1-valid.json` 和全部威胁 fixture；
- `lan-handshake-v1-vector.json` 的 fingerprint、transcript、HKDF、confirmation
  和非零低阶点拒绝向量。

Go/Kotlin 消费者只有在合法 fixture、18 个威胁 fixture 和密码向量全部一致后，才能把
验证器接到 socket 或 token 签发路径。
