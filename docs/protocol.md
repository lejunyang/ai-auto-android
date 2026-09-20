# 协议 v1

`protocol/` 是 CLI、Go 服务、Android Bridge、MCP 和 Skills 的公共语义源。
当前协议版本为 `1.0`，Schema 使用
[JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12)。

## 协议面

系统有三个不同但共用类型的协议面，不应混为一谈：

| 协议面 | 传输 | 用途 |
| --- | --- | --- |
| CLI | stdout 上的单个 JSON 信封 | 人工调用和 Skills；日志、提示写 stderr |
| App Bridge | TCP loopback 上 UTF-8 NDJSON + JSON-RPC 2.0 | 电脑到 Android App 的短期会话 |
| MCP | stdio | 六个类型化工具；五个共用 `service.Automation`，visual proposal 使用独立只读 capture 端口 |

Schema 位于 [`../protocol/schema/v1/`](../protocol/schema/v1/)，运行限制位于
[`../protocol/limits.json`](../protocol/limits.json)，版本规则位于
[`../protocol/version.json`](../protocol/version.json)。

## CLI 信封

带 `--json` 时输出紧凑 JSON；不带时仍输出相同信封，只是缩进展示。成功和失败
都包含 `schemaVersion`、`requestId`、`ok`、`data`、`error` 和 `meta`：

```json
{
  "schemaVersion": "1.0",
  "requestId": "7d8af089-f5f8-4522-8090-bf01f4503af4",
  "ok": true,
  "data": {},
  "error": null,
  "meta": {
    "durationMs": 42
  }
}
```

失败时 `data` 为 `null`，`error` 至少包含稳定 `code`、可读 `message` 和
`retryable`。自动化应按 `code` 分支，不应匹配可能调整的英文消息。

## Bridge 帧

每行是一条完整 JSON-RPC 2.0 消息，使用 UTF-8 并以 `\n` 结束。请求的公共字段：

```json
{
  "jsonrpc": "2.0",
  "id": "e2df8595-edc7-440a-8c19-3b63c993fa8f",
  "requestId": "43b819e9-d22e-4549-a066-cd3bb696b953",
  "protocolVersion": "1.0",
  "method": "device.info",
  "params": {},
  "deadlineMs": 30000,
  "token": "<short-lived-token>"
}
```

`id` 关联 JSON-RPC 响应；`requestId` 用于 300 秒窗口内的防重放。除
`rpc.hello`、`session.open` 外都必须带当前 token。方法参数属于执行指令，
缺少字段或出现未知字段时会被拒绝。

### 限制

| 限制 | 当前值 |
| --- | ---: |
| 每条消息，包含换行 | 1,048,576 字节 |
| 默认 deadline | 30,000 ms |
| 最大 deadline | 300,000 ms |
| 并发请求 | 4 |
| Android 端并发连接 | 8 |
| 一次性码有效期 | 120 秒 |
| 会话 token 有效期 | 900 秒 |
| requestId 防重放窗口 | 300 秒 |
| 防重放缓存上限 | 4,096 条 |

超限不会执行对应动作。截图等大制品不走 Bridge，而是走直接 ADB 截图通道。

## 版本协商

每个 TCP 连接的第一条消息必须是 `rpc.hello`。客户端提交自身版本、支持的协议
版本和能力，服务端返回 `selectedProtocolVersion` 与当前能力。

兼容规则：

1. 选择相同 major 下双方共同支持的最高版本；当前只有 `1.0`。
2. major 不兼容返回 `VERSION_INCOMPATIBLE`，不得猜测字段语义。
3. 同一 major 可新增可选字段、能力，以及受 capability 约束的枚举值。
4. 删除字段、改变语义、将可选字段改为必填、收紧范围或改变默认行为必须提升
   major。
5. 可扩展信封中的未知可选字段可忽略；动作和方法参数中的未知字段必须拒绝。
6. 未知动作、方法和稳定错误码必须拒绝。

不要根据 App 或 CLI 的程序版本推断能力，必须读取 `capabilities`。

## 会话

建立会话的顺序：

```text
App 用户开启桌面桥并生成 6 位一次性码
  -> aactl 为明确指定的 serial 创建 ADB forward
  -> rpc.hello
  -> session.open(pairingCode, hostName)
  <- token、expiresAt、protocolVersion
```

推荐让 CLI 从交互式 stdin 读取一次性码，避免进入进程参数和 shell 历史：

```bash
aactl bridge open --device SERIAL --json
```

CLI 不在输出中返回 token。token 连同设备、临时本机端口和过期时间保存到当前
用户配置目录的 `aactl/bridge` 子目录；macOS/Unix 文件权限为 `0600`、目录为
`0700`。Windows 依赖当前用户配置目录 ACL，当前实现没有额外收紧 ACL。

后续每次调用都会新建本机 TCP 连接、先执行 `rpc.hello`，再携带已保存 token
调用目标方法。`bridge close` 请求 App 撤销会话，然后移除 forward 和本地记录。
token 过期、错误或被 App 撤销时，本地记录会在相应错误后清理。

`action.execute` 和 `recording.replay` 要求 UUID `idempotencyKey`。当前 Android
实现只校验该字段格式；真正阻止重复请求的是 `requestId` 缓存，不能把
`idempotencyKey` 当作可查询的结果缓存。

## 方法与当前能力

| 方法 | 认证 | 当前行为 |
| --- | --- | --- |
| `rpc.hello` | 否 | 协商 `1.0` 和 capabilities |
| `session.open` | 一次性码 | 签发一个短期 token，并使码失效 |
| `device.info` | token | 返回 Android、App 版本及 Bridge 能力 |
| `ui.snapshot` | token | 返回脱敏 Accessibility 树；深度 1..100，默认 64 |
| `action.execute` | token | 执行当前 Bridge 已实现的动作 |
| `recording.list` | token | 返回脱敏脚本摘要，不返回步骤、变量或 secret |
| `recording.replay` | token | 回放 App 私有存储中的脚本 UUID |
| `session.close` | token | 撤销当前 App 会话 |

当前 hello 能力名为：

- `bridge.rpc`
- `device.info`
- `recording.list`
- `ui.snapshot`
- `action.execute`
- `recording.replay`

`recording.list` 直接读取 App 私有 `RecordingScriptStore`，桥启动后可用；它只返回
`id`、`name`、`targetPackages`、`stepCount`、`createdAt` 和 `requirements`。
后三项是否可用取决于无障碍服务是否正在运行。直接 ADB 设备则报告 `adb.direct`，
并根据 `device`、`unauthorized`、`offline` 等状态设置 `available`、`permission`
和 `reason`。

## 动作

Schema v1 共声明 45 种动作类型，但不同入口只实现其子集。下表列出常用动作的入口分布，未列出的动作类型以各入口的实际实现为准：

| 动作 | 直接 CLI/MCP | Bridge `action.execute` | App AI / 录制回放 |
| --- | --- | --- | --- |
| `app.launch` | 是 | 否 | App AI 可启动已授权包；录制不生成 |
| `app.stop` | 是 | 否 | App AI 风险策略阻断；录制不生成 |
| `ui.click`、`ui.longClick` | 否 | 是 | 是 |
| `ui.tap`、`ui.swipe` | 是 | 是 | App AI 可执行；录制不自动生成 |
| `ui.setText` | 受限 ASCII、无选择器 | 仅非敏感字面量 | App 回放可临时解析 `secretRef` |
| `ui.scroll` | 否 | 是 | 是 |
| `ui.back`、`ui.home`、`ui.recents` | 通过白名单 key | 是 | App AI 中 home/recents 被风险策略阻断 |
| `ui.wait`、`ui.assert` | 否 | 否 | 回放引擎可作为条件步骤处理 |
| `ime.show` | MCP 是；直连否 | 是（聚焦语义） | 未接入 |
| `ime.hide` | 是 | 否 | 未接入 |
| `file.push`、`file.pull` | 是（只新增，CLI 与 MCP） | 否 | 未接入 |
| `task.finish` | 否 | 否 | 仅 App AI 会话终止信号 |

`ime.show` 的实际语义是**聚焦当前输入控件**，不是确定的键盘可见性开关。
`ime.hide` 能走直连 ADB 是因为 `KEYCODE_BACK` 可收起键盘，而 Android 没有对称的
「打开键盘」keycode，无障碍通道也无法查询键盘当前是否可见。因此 `ime.show`
只走桥侧无障碍，对输入焦点节点执行 `ACTION_FOCUS`：键盘通常随焦点出现，但这取决于
输入法实现与当前焦点状态。成功结果表示**已聚焦**，不表示键盘已显示；当前没有可聚焦的
可编辑控件时返回 `ACTION_NOT_SUPPORTED`，而不是报告假成功。

`file.push` 与 `file.pull` 的语义严格限定为**只新增**：目标路径已存在时直接失败
（`ACTION_NOT_ALLOWED`），不覆盖、不追加、不删除，也没有 `--force` 之类的覆盖开关。
保护实现在 `internal/adb` 而非 CLI 参数层，因此任何经过 `PushFile` / `PullFile`
的调用方都自动受保护。远端存在性通过一次固定结构的 shell 探测判断，路径以单引号
包裹传递——`validateRemotePath` 的字符集允许空格却排除引号，不包裹会让含空格的
路径被拆词而误判为不存在，从而放行覆盖。探测失败时同样拒绝传输，不把「测不出来」
当作「不存在」。

需要说明的是检查与写入之间存在竞态：探测返回「不存在」之后、`adb push` 落盘之前，
设备上仍可能出现同名文件。adb 协议层没有 O_EXCL 语义，该窗口无法消除，因此上述
保证的准确表述是「已检查该路径当时不存在」，而不是「保证不覆盖」。本地侧同理——
`adb pull` 由外部进程写文件，Go 无法用 `O_EXCL`。

MCP 侧提供 `android_file_push` / `android_file_pull`，但走**独立的风险门**
（`internal/mcpserver/file_transfer.go` 的 `requireFileTransferAllowed`），不并入
`risk.go` 的直连白名单。原因是那份白名单的准入标准写着「只产生指针事件，不改变
应用生命周期也不写入任何数据」，而文件传输恰恰写数据；并进去等于废掉该标准，
日后审计无从判断某项为何在列。三道门（直连白名单、语义桥白名单、文件传输门）
各守自己的动作面，任一层都不能成为绕过另一层的旁路。

`android_file_pull` 的落盘位置需要特别注意：文件写在**运行 MCP 服务的那台机器**上，
不是调用方或模型所在的环境。该事实已写进工具 description 与入参 schema 的字段说明。

CLI 侧 `ime.show` 的出口是 `aactl bridge ime-show`，不在 `action` 子命令下——
`action` 走直连通道，而 `ime.show` 没有直连实现，挂在那里会永远返回 unavailable。

一个可执行的 Bridge 返回动作示例：

```bash
aactl bridge action --device SERIAL \
  --action '{"type":"ui.back","params":{}}' --json
```

Schema 合法不等于当前后端可执行。Bridge 对未实现但合法的动作返回
`CAPABILITY_UNAVAILABLE`，对本地策略禁止的动作返回 `ACTION_NOT_ALLOWED`。

## 稳定错误

| 类别 | 错误码 |
| --- | --- |
| 参数/协议 | `INVALID_ARGUMENT`、`VERSION_INCOMPATIBLE`、`PROTOCOL_ERROR` |
| ADB/设备 | `ADB_NOT_FOUND`、`ADB_UNAUTHORIZED`、`DEVICE_OFFLINE`、`DEVICE_NOT_FOUND`、`MULTIPLE_DEVICES`、`DEVICE_UNREACHABLE` |
| 权限/能力 | `PERMISSION_DENIED`、`CAPABILITY_UNAVAILABLE` |
| 会话 | `AUTH_REQUIRED`、`AUTH_INVALID`、`AUTH_EXPIRED`、`REQUEST_REPLAYED` |
| 资源限制 | `DEADLINE_EXCEEDED`、`MESSAGE_TOO_LARGE`、`RATE_LIMITED` |
| 动作/选择器 | `ACTION_NOT_ALLOWED`、`ACTION_FAILED`、`SELECTOR_NOT_FOUND`、`SELECTOR_AMBIGUOUS` |
| 录制/内部 | `SCRIPT_NOT_FOUND`、`INTERNAL_ERROR` |

回放报告中的 `SECRET_REQUIRED`、`CONDITION_TIMEOUT`、
`SELECTOR_LOW_CONFIDENCE` 等是单步 `errorCode`，不是顶层稳定错误枚举。

CLI 退出码：

| 退出码 | 含义 |
| ---: | --- |
| `0` | 成功 |
| `2` | 参数、消息大小或重放请求错误 |
| `3` | ADB/Bridge 认证或权限错误 |
| `4` | ADB/设备、动作、选择器或脚本错误 |
| `5` | 超时 |
| `6` | 能力、版本或策略不支持 |
| `10` | 协议、限流或内部错误 |

## MCP 边界

`aactl mcp serve` 当前只暴露：

- `android_devices_list`
- `android_device_get`
- `android_observe`
- `android_visual_target_propose`
- `android_action_execute`
- `android_recording_replay`

MCP 不暴露 doctor、Wi-Fi 配对/连接、Bridge open/info/action/close、录制列表或
任意 shell。`android_observe` 支持 `screenshot`、`hierarchy` 和已建立 Bridge
会话后的 `semantic`；`android_visual_target_propose` 仅从一次稳定且明确包的
PNG/UIAutomator observation 提出 template 候选，返回的 `actionCommitCount` 固定
为 0；动作工具只走直接 ADB 类型化动作。

## 验证

```bash
make protocol-test
```

验证器检查 Schema dialect、唯一 `$id`、跨文件 `$ref`、合法/非法夹具，以及
deadline 限制一致性。

参考：

- [JSON-RPC 2.0](https://www.jsonrpc.org/specification)
- [NDJSON 规范](https://github.com/ndjson/ndjson-spec)
- [MCP 规范](https://modelcontextprotocol.io/specification/2025-11-25)
