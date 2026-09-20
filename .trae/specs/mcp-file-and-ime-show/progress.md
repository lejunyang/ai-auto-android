# MCP 文件传输与 ime.show 出口进度

本文件 append-only。

## Round 1：核实与实现

基线 `463c5d0`，worktree `E:/Projects/wt-mcpfile`，分支 `mcp-file-and-ime-show`。

### 发现：`action ime-show` 行不通，必须挂 `bridge`

原计划按 `ime-hide` / `ime-select` 的命名挂到 `action` 子命令下。核实后发现那会
做出一个永远失败的命令：`action` 走**直连通道**（`cli.go` 用
`a.directAutomation(client)`，bridge 为 nil），`ExecuteAction` 的 switch 全部落到
`s.direct.*`。`ime.hide` 能在那里是因为它有直连实现（KEYCODE_BACK），而
`ime.show` 上轮已确认只能走无障碍。挂到 action 下会因 `s.bridge == nil` 返回
`unavailable`（`service.go:566`）。

因此 CLI 出口定为 `aactl bridge ime-show`，与同样只走桥的 `bridge set-text` 一致。
这不是命名偏好，是通道归属决定的。加了
`TestBridgeImeShowRoutesThroughTheBridgeChannel` 锁定，防止日后有人"为了命名统一"
把它挪回 action 下——那种失败在编译期看不出来。

### 发现：Go 侧此前没有 ime.show 的任何实现

上轮只做了 Kotlin 桥侧。Go 侧 `grep ActionImeShow` 零命中，`ime.show` 此前只能靠
`aactl bridge action` 透传原始 JSON 抵达。本轮补了 `service.BridgeImeShow` 与
`ActionImeShow` 常量，CLI 与 MCP 共用同一条服务层路径。

### 决策：文件传输走独立风险门（方案一）

`risk.go:28` 的白名单注释写着准入标准「只产生指针事件，不改变应用生命周期也不
写入任何数据」，现有 7 项全部符合。`file.push` 在设备落盘、`file.pull` 在**运行
MCP 服务的机器**落盘，都与该标准正面冲突。

选独立门而非并入，三条理由（详见 spec 与 `file_transfer.go` 头注释）：
保住原标准的判定力；`bridge_action.go:40` 早已写明 `file.*` 需要独立安全决策，
本项是兑现而非另起；两类动作准入条件本就不同，合并会让放宽意外互相波及。

`ime.show` 则经核对确实符合原标准——只改焦点归属，不写数据——因此走既有门，
不需要第四道。加了测试断言 `file.push` / `file.pull` **不在**直连白名单内。

### 关键验证：MCP 路径确实继承不覆盖保护

「保护在服务层所以 MCP 自动受保护」是设计意图，不是证据。因此用真实的
`service.New(adb.NewClient("adb", fake), nil)` 驱动 fake ADB，让远端探测返回
「已存在」，确认 MCP 工具调用被拒且 `adb push` 未发出。用 fakeAutomation 证明不了
这一点——它绕过了真实服务层。

再做变异确认该实证非空洞：把 `internal/adb` 的 `if exists` 改成 `if false && exists`，
MCP 测试如期失败（file_transfer_test.go:98「MCP push to an existing remote path
succeeded; the guard did not apply」），恢复后通过。

### README 计数

新增 3 个工具后防漂移测试报 12 → 15，列出的 15 个工具名与 `ToolNames` 一致
（`ToolVisualTargetPropose` 双注册但计数绑定去重后的切片，未受影响）。已更新。

## Round 2：收口清单

在 `docs/next-phase-tasks.md` 补「UIAutomator 能力对齐收口清单」，逐条回代码核对
C1–C16 的当前状态而非沿用旧结论。核对结果：C1/C2/C3/C7/C8/C9/C13/C14/C15/C16
已收口；C4/C5/C6/C10/C11/C12 仍开放（均以 grep 零命中或 schema 实际枚举为证）。

结论：从 UIAutomator 能力对齐角度**已无影响实际可用性的硬缺口**。核心工作流
（选择器定位 → 元素级动作 → 等待条件）三环节三通道均已走通，剩余条目都是表达力
增量而非「某常见场景做不了」。

### 门禁结果

见 checklist。未改 Android 侧，`make android-verify` 不适用。
