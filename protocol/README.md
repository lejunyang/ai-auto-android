# Automation Protocol v1

`protocol/schema/v1/` 是桌面 CLI、Android App、MCP 和 Agent Skills 共用的协议契约源。所有 Schema 使用 JSON Schema Draft 2020-12，并通过稳定的 HTTPS `$id` 相互引用。

## 协议组成

| Schema | 用途 |
| --- | --- |
| `common.schema.json` | UUID、版本、时间、deadline、坐标等基础类型 |
| `capability.schema.json` | 可协商设备/App 能力 |
| `error.schema.json` | 稳定机器错误码 |
| `device.schema.json` | ADB 设备和连接状态 |
| `action.schema.json` | Agent 可执行的类型化动作白名单 |
| `automation-script.schema.json` | 语义录制与确定性回放脚本 |
| `cli-envelope.schema.json` | CLI `--json` 响应信封 |
| `bridge-request.schema.json` | App Bridge JSON-RPC 2.0 请求 |
| `bridge-response.schema.json` | App Bridge JSON-RPC 2.0 响应 |
| `lan-invitation.schema.json` | 不含长期秘密的 LAN QR invitation |
| `lan-handshake.schema.json` | X25519/HKDF LAN 握手、确认与稳定错误 |

协议运行限制记录在 [`limits.json`](limits.json)，稳定错误码由 `error.schema.json` 的 `code` 枚举定义。

## 兼容规则

1. 协议版本使用 `MAJOR.MINOR`。双方选择最高的共同 major 下、双方均支持的最高 minor。
2. major 不同表示不兼容，必须返回 `VERSION_INCOMPATIBLE`，不得猜测字段语义。
3. 同一 major 中只能新增可选字段、能力或动作；删除字段、改变既有字段语义、收紧有效范围或改变默认行为必须提升 major。
4. 可扩展资源和响应信封中的未知可选字段必须忽略；动作及方法参数属于执行指令，未知字段必须拒绝，避免版本间产生不同解释。
5. 能力是否可用以 `capabilities` 协商结果为准，不得只根据程序版本推断。
6. 每个 Bridge 请求都必须有 `requestId` 和 `deadlineMs`。除 `rpc.hello`、`session.open` 外必须携带当前短期 token。
7. NDJSON 每行只能包含一条消息，UTF-8 编码，含换行后的字节数不得超过 `maxMessageBytes`。
8. 未知动作、方法和错误码必须拒绝。超过 deadline、消息大小、并发或重放窗口的请求分别返回稳定错误码，不执行对应动作。
9. LAN QR Bridge 使用独立 invitation、X25519 transcript 双方确认和新 LAN token；
   不得复用 loopback 配对码/token，也不得降级到明文或旧版本。字节级规范和威胁
   契约见 [`docs/lan-qr-invitation-v1.md`](docs/lan-qr-invitation-v1.md)。

## 运行验证

```bash
make protocol-test
```

验证器零运行时依赖，使用 Node.js 24，检查：

- Schema dialect、唯一 `$id` 和所有跨文件 `$ref`；
- Draft 2020-12 核心约束，包括 `oneOf`、`anyOf`、`allOf`、类型、格式、枚举、范围及额外属性；
- `fixtures/manifest.json` 中所有合法夹具通过；
- 所有非法夹具失败，且命中声明的错误路径或错误片段；
- LAN invitation 合法、威胁和密码向量经过 Schema 与无 I/O preflight 双阶段验证；
- 限制配置与 Schema 中 deadline 上限保持一致。
