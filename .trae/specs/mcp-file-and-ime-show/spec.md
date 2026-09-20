# MCP 文件传输与 ime.show 出口

## 目标

给 `ime.show` 补 CLI + MCP 出口，给文件传输补 MCP 出口，与 CLI 保持一致。

## 决策：文件传输走**独立风险门**（方案一）

`risk.go:28` 的 `requireModelActionAllowed` 注释写着明确准入标准：长按与滚动能进
白名单，是因为「只产生指针事件，不改变应用生命周期也不写入任何数据」。现有 7 项
全部符合。

`file.push` 与这条标准正面冲突——它在设备上落盘，是白名单里第一个「写数据」的
动作。`file.pull` 更进一步：它写的是**运行 MCP 服务的那台机器**的磁盘。

选择独立门而非塞进现有白名单，理由：

1. **保住原标准的判定力。** 那条注释现在是可执行的判据：拿一个动作来，问它是否
   只产生指针事件。一旦塞进文件写入，判据就退化成「名单上有就行」，将来审计无从
   判断某项为何在列。
2. **代码结构已有先例且已预留。** `bridge_action.go:40` 的注释明确写着
   「`clipboard.* / file.*`：涉及隐私数据与文件系统，需要独立的安全决策」。
   本项正是兑现那句话，而不是另起一套。
3. **两类动作的准入条件本就不同。** 文件传输的安全性依赖「不覆盖、不删除、
   路径校验」三个前提，指针类动作不需要这些前提。混在一起意味着放宽任一侧会
   意外波及另一侧——`requireSemanticBridgeActionAllowed`（`bridge_action.go:54`）
   的注释已经点明过这个理由。

因此新增 `requireFileTransferAllowed`，与 `requireModelActionAllowed`、
`requireSemanticBridgeActionAllowed` 并列为第三道独立门，各守自己的动作面。
原注释一字不改，其标准继续成立。

## 三条硬约束

1. **不覆盖、不删除保护必须对 MCP 路径同样生效。** 保护在
   `internal/adb/device_state.go`，MCP 走同一 `service.Automation` 接口，
   理论上自动受保护——但这要**实测验证**，不能假定。验证方式：用一个真实的
   `service.New(...)` 驱动 fake ADB，让远端探测返回「已存在」，确认 MCP 工具
   调用被拒且未发出 push。只用 fakeAutomation 无法证明这一点，因为它绕过了
   真实服务层。
2. **不得引入 force / overwrite 等价字段。** input schema 用
   `additionalProperties:false`，并由锁定测试逐字段检查。
3. **pull 的本地落盘必须在 description 里写明**：文件会写到运行 MCP 服务的机器
   上，而不是模型所在的环境。这是模型容易误解的点。

## ime.show 出口

### CLI 落点：`bridge ime-show`，不是 `action ime-show`

你建议命名对齐 `ime-hide` / `ime-select`，但那两个在 `action` 子命令下，而
`action` 走的是**直连通道**：`cli.go` 用 `a.directAutomation(client)`
构造服务（bridge 为 nil），`ExecuteAction` 的 switch 全部落到 `s.direct.*`。
`ime.hide` 能在那里是因为它有直连实现（`KEYCODE_BACK`）。

`ime.show` 恰恰没有直连实现——这是上轮确认的前提，Android 没有对称的「打开键盘」
keycode，它只能走无障碍。若挂到 `action` 下，`ExecuteBridgeAction` 会因
`s.bridge == nil` 直接返回 `unavailable`（`service.go:566`），命令永远失败。

因此 CLI 出口放在 `bridge` 子命令下，与同样只走桥的 `bridge set-text` 一致：
`aactl bridge ime-show --device SERIAL`。这不是命名偏好，是通道归属决定的。
- MCP：纳入 `requireModelActionAllowed`。核对过它符合原注释标准——聚焦只改变焦点
  归属，不改变应用生命周期，也不写入任何数据。因此这一项**不需要**独立门。
- 诚实表述延续上轮：结果只表达「已聚焦输入控件」，不表达「键盘已显示」。
  input/output schema 与 description 均不得出现暗示确定性 IME 可见性的字段或措辞。

## 锁定测试换目标

`TestFileTransferRefusesToOverwriteOrDelete` 现锁「MCP 无出口」，该条即将不成立。
不删除，换锁：

- MCP 文件工具存在，且 input schema 无 force / overwrite 类字段
- MCP push 到已存在远端路径被拒（经真实服务层）
- 无任何删除类 MCP 工具
- CLI 侧原有的无删除、无覆盖开关断言保留

变异验证：故意让保护失效，确认测试真的变红。上轮踩过空洞断言的坑
（取值型选项让否定断言提前失败），本轮同样要验。

## README 计数

新增工具会让 `readme_toolcount_test.go` 失败，那是防漂移设计生效，同步更新数字。
注意 `ToolVisualTargetPropose` 在 `desktop_visual.go:174` 与 `visual_adapter.go:79`
双注册，计数测试绑定去重后的 `ToolNames` 长度，别被差额误导。

## 收口清单

本项完成后在 `docs/next-phase-tasks.md` 补一份收口清单，不新建平行文档。

## 不做的

- 不引入路径前缀白名单（沿用上轮决定）
- 不放宽 `requireModelActionAllowed` 的原有标准
- 不给 `clipboard.*` 开 MCP 出口（同属需独立决策，不在本轮）
