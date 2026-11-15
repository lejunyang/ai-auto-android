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
| MCP | stdio | 20个类型化工具；共享service与独立视觉端口，管理风险门另立 |

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

### 虚拟显示 CLI 结果

`display start/stop` 仅由操作员 CLI 提供；MCP 的 android_display_list/status/create/close
提供独立风险门下的屏幕管理。观察的 screenshot/hierarchy 与六类既有动作接受可选
displayId（CLI为--display）；0明确主屏且不查helper，正整数先核对归属。其它工具拒绝该参数。
create 的 focus 枚举 isolated/steal，默认 isolated；list/status返回focus、focusIsolated。
API33如实false；API34+ isolated保护主屏输入但息屏无层级/虚拟屏无IME，steal可息屏读层级
但会打断主屏输入。动作面及参数详见[虚拟显示操作](virtual-display.md)。
这些焦点/IME/息屏结论仅适用于普通 DisplayManager 屏。可选 `camera:"back"` 创建
VirtualDevice 屏，返回实际 `displayFlags`、`focusIsolated` 与 `cameraInfo`，不得直接外推
普通屏结论。摄像头需要操作员 `display start --virtual-camera`；图片设置仅 CLI，
MCP 无主机图片路径或启用入口，详见[虚拟摄像头](virtual-camera.md)。
`action sleep` 为 CLI 的 device.sleep，KEYCODE_SLEEP 只使主屏息屏；MCP不开放sleep/wake。

层级结果分两种 `format`：

| format | XML 形态与含义 |
| --- | --- |
| `uiautomator-xml` | 普通活动窗口的单个 `hierarchy` 根；未指定显示时的既有行为 |
| `uiautomator-windows-xml` | `displays` 根下只含目标 `display id="N"`，其下有窗口及各自层级 |

按显示读取发 `dump --windows`，解析全部输入后只返回目标组；无该编号、重复编号、
畸形、截断或超过8MiB时失败，绝不返回其它显示。`sizeBytes` 与 `sha256` 对应筛选后
的 XML。普通层级在虚拟屏持有焦点时可能返回虚拟屏内容；不能当作主屏身份判据。

独立 displayd v2 线协议使用16384字节头、最多16MiB定长PNG体，详见
[设备端契约](../android/displayd/README.md)，不与 Bridge NDJSON 上限混用。
辅助进程及虚拟屏错误包括 DISPLAYD_HELPER_MISSING、DISPLAYD_NOT_RUNNING、
DISPLAYD_IDENTITY_MISMATCH、UNSUPPORTED_API、DISPLAY_NOT_FOUND、DEVICE_LOCKED、
DISPLAY_ASLEEP、DISPLAY_EMPTY、WINDOWS_UNAVAILABLE；这些显示错误目前走通用退出码10，须按 `error.code` 区分。
层级命令的 API 能力拒绝仍使用 CAPABILITY_UNAVAILABLE。DISPLAY_EMPTY表示有效XML中的归属空屏，
提示先启动应用；WINDOWS_UNAVAILABLE表示系统无文件/空输出（含本次随机路径的完整ENOENT），
提示唤醒主屏或用steal重建；其它诊断、坏XML/截断/超限仍拒绝。
helper v2拒绝v1握手，升级须先保留并用旧CLI停止v1 helper，再更新CLI/helper启动；
新CLI不会向版本不匹配的helper发送shutdown，socket名称保持aactl-displayd-v1。

API33 的 groupAwake 为 null；API34+ 可按屏查询。凭据锁定时拒帧，解锁后组仍休眠
返回 DISPLAY_ASLEEP，调用方需 close/create 后重新启动应用，原屏应用内容会销毁。
完整参数及限制见[虚拟显示操作](virtual-display.md)。

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

超限不会执行对应动作。普通Bridge观察不回传PNG，截图走直接ADB/helper/MCP图像内容。
例外是授权debug disposable emulator的visual.action.execute请求，可携带有界pngBase64及
attested observation；校验包、图像摘要、有效期后执行并验证，finally清零，仍受Bridge总帧预算。

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
| rpc.hello | 否 | 协商1.0及实际capabilities |
| session.open | 一次性码 | 短期token，使码失效 |
| session.close | token | 撤销当前会话 |
| device.info | token | Android/App版本与能力 |
| ui.snapshot | token | 脱敏Accessibility树，maxDepth 1..100、默认64 |
| ui.foreground | token | 已授权包的当前Activity/包名 |
| ui.windows | token | 可见窗口及节点树，目标包与深度限制 |
| ui.transientMessages | token | 已捕获Toast/通知，含eventTimeMs，支持toastsOnly |
| action.execute | token | parser与无障碍路由实际支持的动作，非全部Schema类型 |
| visual.action.execute | token+有效test-only授权 | 仅debug disposable emulator的attested原子视觉执行 |
| recording.list | token | 脱敏摘要，不返回步骤/变量/secret |
| recording.replay | token | 回放App私有脚本UUID，仍受本地策略 |

当前hello能力按AndroidBridgeMethods.capabilities()逐项为：bridge.rpc、device.info、
recording.list、ui.snapshot、ui.foreground、action.execute、action.gesture.composite、
action.node.extended、action.ime.show、action.text.editing、action.pressKey、action.global.extended、
visual.action.execute、recording.replay。ui.windows与ui.transientMessages现有方法，但没有
各自同名的独立hello能力项；不可按方法名猜capability。无障碍/视觉/回放按实际可用性上报，
visual.action.execute还要求授权的debug一次性模拟器；action.pressKey仅可靠发送MOVE_HOME/MOVE_END。

`recording.list` 直接读取 App 私有 `RecordingScriptStore`，桥启动后可用；它只返回
`id`、`name`、`targetPackages`、`stepCount`、`createdAt` 和 `requirements`。
无障碍相关方法取决于服务是否运行和目标包约束；recording.list本身不要求无障碍。直接 ADB 设备则报告 `adb.direct`，
并根据 `device`、`unauthorized`、`offline` 等状态设置 `available`、`permission`
和 `reason`。

## 动作

Schema v1 当前oneOf声明46种动作类型（以action.schema.json为准），但不同入口只实现其子集。下表列出常用动作的入口分布，未列出的动作类型以各入口的实际实现为准：

| 动作 | 直接CLI / MCP | Bridge action.execute | App AI / 回放边界 |
| --- | --- | --- | --- |
| app.launch | 两者均可 | 否 | AI可启动授权包，录制不自动生成 |
| app.stop | CLI可；MCP拒绝 | 否 | AI风险策略阻断 |
| ui.click | 直连无选择器；MCP用android_bridge_action | 是 | 语义执行 |
| ui.longClick | 两者可按坐标；MCP语义长按另走Bridge工具 | 是，按选择器 | AI/回放可按对应受控路径执行 |
| ui.tap / ui.swipe | 两者可按坐标 | 是 | AI视觉路径要求有效授权 |
| ui.setText | 两者受限ASCII；Unicode用Bridge专用工具 | 非敏感字面量 | 回放可临时解析secretRef |
| ui.scroll | 两者可；无displayId | 是 | 可用，不代表scrollUntil事件回放已全部接线 |
| ui.back / home / recents | 直连白名单key；MCP同样 | 是 | AI的home/recents被策略阻断 |
| ui.wait / ui.assert | CLI check wait/assert、MCP专用工具 | 否 | AI/回放条件路径，须按各自判据子集使用 |
| ui.scrollUntil | CLI check scroll-until、MCP android_scroll_until | 否 | 不能据公共Schema推定AI/回放支持 |
| ime.show | CLI bridge ime-show、MCP android_ime_show；无ADB打开键盘键 | 是，仅聚焦 | parser/通用executor能映射；AI风险白名单不含，故不作为AI已开放能力 |
| ime.hide | CLI可；MCP不开放 | 否 | 不据parser接受宣称用户流程已交付 |
| file.push / file.pull | CLI/MCP只新增检查，无删除/覆盖开关 | 否 | 未开放到AI用户流程 |
| task.finish | 电脑无此会话 | 否 | 仅AI会话终止信号 |

App的ProviderActionParser接受ime.show，RuntimeSessionExecutor与ReplayEngine的通用路由
可交给AccessibilityCommandJsonParser，再执行ImeShow；但AutomationRiskPolicy白名单没有
ime.show，AI会话会先阻断。回放的通用路由存在不等于编辑器可配置该步骤或完成了设备验收，
这些用户流程本轮未核实；不能仅凭parser声称已交付。

`ime.show` 的实际语义是**聚焦当前输入控件**，不是确定的键盘可见性开关。
`ime.hide`的直连实现是注入KEYCODE_BACK，不保证只收键盘；IME未显示时会直接作用于应用，
isolated无IME的根页面会退出。Android没有对称的
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
| 虚拟显示 | DISPLAYD_NOT_RUNNING、DISPLAYD_IDENTITY_MISMATCH、DISPLAY_NOT_FOUND、DISPLAY_EMPTY、WINDOWS_UNAVAILABLE、DEVICE_LOCKED、DISPLAY_ASLEEP |
| 虚拟摄像头 | `VIRTUAL_CAMERA_NOT_ENABLED`（未启用/旧helper，CLI退出6）、`VIRTUAL_CAMERA_UNAVAILABLE`（API/服务/创建失败，退出6）、`CAMERA_IMAGE_INVALID`（图片预算/格式/路径，退出2） |
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

`aactl mcp serve`当前完整20工具与internal/mcpserver.ToolNames一致：

- `android_devices_list`
- `android_device_get`
- `android_observe`
- `android_visual_target_propose`
- `android_visual_action_execute`
- `android_action_execute`
- `android_bridge_action`
- `android_recording_replay`
- `android_screen_record`
- `android_wait`
- `android_assert`
- `android_bridge_set_text`
- `android_ime_show`
- `android_file_push`
- `android_file_pull`
- `android_scroll_until`
- `android_display_list`
- `android_display_status`
- `android_display_create`
- `android_display_close`

android_observe有8种kind：screenshot、hierarchy、foreground、apps、semantic、windows、
transientMessages、recordings。后四种要求已有Bridge会话；录制摘要可读，
android_recording_replay仍始终CONFIRMATION_REQUIRED、不执行回放。录屏MP4是独立
android_screen_record，不是脚本列表或回放。

直连动作、四种语义Bridge动作（ui.click/longClick/scrollIntoView/nodeAction）、文件传输
和虚拟屏管理各守独立风险门；原子视觉动作另外校验有效授权/attestation。ui.setText的
Unicode入口是android_bridge_set_text，ime.show只承诺聚焦。虚拟屏目标仅接已列六类
直连动作和两类观察，不能通过displayId扩张任何门的准入标准。

MCP无doctor、Wi-Fi配对/连接、Bridge open/info/close、helper启停、权限授予或任意shell。
CLI bridge action并未整体透传给模型；MCP仅给经过独立门批准的语义子集。只读视觉候选
actionCommitCount=0，不构成后续授权；原子视觉执行在同一次调用重新采集、绑定与验证，
unknown提交状态必须停止，不重试动作。

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
