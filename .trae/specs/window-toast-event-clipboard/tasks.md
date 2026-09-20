# 多窗口、瞬时消息、事件型判据与剪贴板写入任务

## C6 剪贴板写入

- [x] `AccessibilityCommand.ClipboardSet` 变体（无 target）
- [x] 后端接口 `writeClipboard`，`AndroidAccessibilityBackend` 用 `ClipboardGateway.write`
- [x] 路由 `executeClipboardSet`，route=GLOBAL_ACTION
- [x] 文本上限与协议 `maxLength` 对齐（10000），超限在写入前拒绝
- [x] parser `"clipboard.set"` 分支
- [x] 锁定测试：`clipboard.get` 无桥侧出口，注释写明平台原因
- [x] 保留 `ClipboardGateway.read()` 与三态结果不动

## C4 多窗口

- [x] 服务层 `windowSnapshots()` 与 `AccessibilityRuntime.windowSnapshots()`
- [x] 网关接口 `windows()`，桥方法 `ui.windows`
- [x] 序列化含 windowId / kind / layer / active / focused / bounds / root
- [x] 有界性保住（上限 12 未放开），节点树按 maxDepth 截断
- [x] CLI `aactl bridge windows`
- [x] MCP `observe kind=windows`

## C5 瞬时消息

- [x] 网关接口 `transientMessages()`，桥方法 `ui.transientMessages`
- [x] 序列化含 text / package / toast / **eventTimeMs**
- [x] 只读不隐式清空
- [x] CLI `aactl bridge messages --toasts-only`
- [x] MCP `observe kind=transientMessages`
- [x] 测试锁定时间戳不被丢弃（含变异验证）

## C11 事件型判据

- [x] 只加 `newWindow` 与 `scrollFinished` 两个 kind
- [x] 新增 `occurred` 运算符，且事件型判据只接受它、不接受 expected
- [x] 基线机制：第一轮只记录起点，不判定成立
- [x] `scrollFinished` 必须先观察到变化再等静止
- [x] 协议、Go、MCP schema 三处同步
- [x] 轮询与事件的语义落差写进协议 description 与代码注释

## 收尾

- [x] 四道门禁
- [x] `make android-verify`（删除 test-results 后强制重跑，核对 JUnit XML）
- [x] 变异验证
