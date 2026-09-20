# 多窗口、瞬时消息、事件型判据与剪贴板写入

## 范围

本批实施 C4（多窗口）、C5（Toast / 瞬时消息）、C11（事件型判据）、C6 的**写入部分**。
C10 / C12 本批**不实施**，只做调研（见第二部分产出）。

四项都动协议，因此单一 owner 串行完成，不拆并行。

## C6 为什么只做写入（平台事实，不是保守）

Android **不存在** `READ_CLIPBOARD` 权限。API 29+ 对剪贴板读取的限制不是靠权限
弹窗实施的，而是要求「应用必须持有输入焦点，或是当前默认输入法」。

这对自动化是死结：要读的是**别的 App** 的剪贴板，而读的那一刻本 App 必须在前台
持有焦点——两个条件互斥。因此读取不是「申请个权限就行」，只有两条路：

1. 走 IME 路线：要用户把本 App 设为默认输入法。该权限档位**高于**无障碍，
   意味着本 App 能看到用户在任何应用里的每一次按键。
2. 前台夺焦：会打断正在被自动化的界面，破坏自动化本身。

两条都需要独立产品决策，故本轮只做写入——写入不受该限制约束
（`ClipboardGateway.kt:80` 的注释已记录这一点）。

`ClipboardGateway` 的 `read()` 与 `ClipboardReadResult` 的 `Empty` / `Unavailable`
区分**保留不动**：那个区分是正确设计（`Empty` 是有效结果，`Unavailable` 要换通道）。
本轮只是不给 `read` 出口。同时加锁定测试钉住「`clipboard.get` 无任何通道出口」，
并在注释写明上述平台原因，防止日后有人以为「漏了」而顺手接上。

### 剪贴板写入的 MCP 风险门

`clipboard.set` 属**写数据**类动作，沿用上一批为文件传输建立的独立门模式，
不塞进 `requireModelActionAllowed`——`risk.go` 那条「只产生指针事件、不写入任何
数据」的标准继续零改动。`bridge_action.go:40` 的注释本就把 `clipboard.*` 与
`file.*` 并列为「需要独立安全决策」，本项正好兑现另一半。

## C4 / C5：平台前提已就绪，是纯过线

已核实，两项的平台前提都在 `accessibility_service_config.xml` 声明好了：

- `flagRetrieveInteractiveWindows`（第 16 行）——注释写明不声明它时 windows 恒为
  空列表（已在 API 35 模拟器实测）
- `typeNotificationStateChanged`（第 14 行）——Toast 捕获的唯一来源

Kotlin 实现也齐备：
- `WindowSnapshotter.kt` 已把 windows 枚举为**有界**快照（上限 12，按 layer 降序），
  且 `AndroidAccessibilityBackend.kt:124` 已在 `openNodeSession` 里用于扩大候选集合
- `TransientMessageCapture.kt` 已在事件到达即记录，**有界**（上限 64）**带时间戳**，
  并已挂在 `AiAutomationAccessibilityService.kt:45`

缺的只有：协议字段（`windows` / `transientMessages` 在 `protocol/schema/v1` grep 0
命中）、桥侧响应序列化、通道出口。

### 必须保住的两处既有设计

1. **窗口快照的有界性**：上限 12 的理由写在 `WindowSnapshotter.kt:128-135`。
   序列化时不得反手把全部窗口无限制展开。
2. **Toast 缓冲的有界与时间戳**：理由写在 `TransientMessageCapture.kt:14-15`——
   无时间戳就无法区分「这次操作触发的」还是「十分钟前遗留的」。
   **序列化必须把 `eventTimeMs` 一并暴露**，否则调用方失去这个判断能力，
   等于把该设计作废。

## C11：严格只加 2 个条件

判据 kind 现为 `["node","text","package","window","uiStable"]`
（`action.schema.json:216`，已实测确认）。UIAutomator 的 `EventCondition` 一共
**只有两个**：`newWindow()` 与 `scrollFinished(Direction)`。只加这两个，不扩别的，
也不把 C10 的 `scrollUntil` 顺手做进来。

### 语义落差必须如实表达

UIAutomator 的 `EventCondition` 是**事件驱动**的：它在 `performActionAndWait` 里
监听真实的 AccessibilityEvent。而本项目的 `AwaitPredicate`
（`internal/adb/predicate.go:124`）是**轮询**结构，每轮拉一次 hierarchy 比对。

这意味着直连通道只能做到「轮询观察到窗口已变化 / 滚动已停止」，而不是「捕获到
了那个事件」。二者在快速连续变化时结果可能不同（两次轮询之间发生又结束的变化会
被漏掉）。这一点要写进协议描述与代码注释，不能让调用方以为拿到了事件级保证。

## 落点

- `protocol/schema/v1/bridge-response.schema.json` 或 snapshot 响应：`windows` 数组
- `protocol/schema/v1/bridge-request.schema.json`：`ui.transientMessages` 方法
- `protocol/schema/v1/action.schema.json`：判据 kind 扩 2 项、`clipboard.set` 已声明需核对
- `android/.../AndroidBridgeMethods.kt`：windows 序列化、transientMessages 方法、
  clipboard.set 分派
- `android/.../AccessibilityCommandJsonParser.kt`：`clipboard.set` 分支
- `internal/adb/predicate.go`：2 个新 kind 的轮询实现
- `internal/service`、`internal/cli`、`internal/mcpserver`：对应出口

## 不做的

- `clipboard.get` 任何通道出口（见上述平台原因）
- C10 `scrollUntil`、C12 选择器维度
- 不删除 `ClipboardGateway.read()` 与 `ClipboardReadResult` 的三态

## 验收

- 四道门禁 + `make android-verify`（本批必定触及 Android 侧）
- android-verify 不停在聚合 BUILD SUCCESSFUL，核对 JUnit XML 确认单测真实执行
- 锁定测试的否定断言做变异验证
- 无设备证据项保持未勾选并标注「待设备验证」
