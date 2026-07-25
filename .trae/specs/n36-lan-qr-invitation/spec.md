# N36 LAN QR Invitation 协议规格

## 目标

定义不包含长期秘密、可在网络连接前完成严格校验的 LAN QR invitation 契约，并固定
X25519、HKDF transcript、双方确认、能力协商和失败关闭语义。

## 范围

- 定义版本化 invitation、显式网卡、私网或链路本地地址候选、端口、nonce、短 TTL、
  X25519 临时公钥、短指纹和 capabilities。
- 定义固定加密套件、规范 transcript、HKDF 分域派生、双方确认和稳定错误码。
- 提供 Schema、合法 fixture、威胁 fixture 和无网络副作用的纯 preflight 验证器。
- 明确 LAN 会话与 ADB forward 下的 loopback App Bridge 属于不同信任域。

## 安全边界

- QR 不得包含最终 token、API Key、录制 secret、临时私钥或长期设备密钥。
- invitation 必须在建立 socket 前校验结构、时效、重放状态、地址、网卡、临时公钥
  和指纹；失败时不得尝试明文或旧版本回退。
- session token 只能在双方验证 transcript confirmation 后签发，并绑定本次 LAN
  transcript；loopback token、配对码和 LAN token 不得互换。
- 本任务只定义跨实现契约，不越权增加 Android 或 Go 生产消费者。

## 允许修改

- `.trae/specs/n36-lan-qr-invitation/`
- `protocol/schema/` 下新增的 LAN Schema
- `protocol/fixtures/` 下新增的 LAN fixture 与独占清单
- `protocol/docs/` 下的 LAN 协议文档
- 协议自身验证和测试脚本

## 验收

- 合法 invitation 同时通过 JSON Schema 与纯 preflight 验证。
- 过期、重放、指纹、地址、网卡、篡改、未知字段、弱密钥、TTL 和降级威胁均在
  socket 建立前失败关闭。
- transcript、HKDF 分域、双方确认和 capability negotiation 有确定性测试向量。
- 运行协议全量验证、定向测试、`make comments` 和 `git diff --check`。
