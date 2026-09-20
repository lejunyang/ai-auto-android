# UIAutomator 缺口对齐验收清单

## 范围与流程

- [x] 独立 change spec 与允许范围符合 AGENTS.md 约束
- [x] 未回写 `.trae/specs/build-ai-android-automation-mvp/` 的已验收结论
- [x] Wave A 在 B / C 之前串行完成；共享文件只由单一 owner 修改
- [x] 提交按波次单一职责，使用 Conventional Commit
- [x] 未 push 到远端（由用户自行决定）

## Wave A

- [x] `bridge-request.schema.json` 的 method 枚举含 `visual.action.execute`
- [x] 该方法有合法 fixture 与三条非法 fixture（缺 token、原始坐标载荷、未列动作类型）
- [x] 键枚举以协议为单一权威来源，直连与 MCP 向上对齐至 21 键
- [x] 新增跨实现一致性测试，三处漂移会失败
- [x] 未引入任意 keycode 或组合键能力（另有测试钉死已评审集合）
- [x] MCP `ui.scroll` 支持协议已声明的全部六个方向
- [x] MCP schema 不再声明风险门必拒的 `app.stop`
- [x] `risk.go` 白名单未被放宽（`risk_test.go` 仍断言 ActionStop 被拒）
- [x] README 工具数与 `ToolNames` 长度一致，并由测试守护

## Wave B

- [x] 九个动作子命令均可用且参数校验完整
- [x] `observe screen-state` / `observe input-methods` 可用
- [x] 每个新子命令有定向测试断言其 `service.ActionRequest` 映射
- [x] usage 串与错误提示同步更新（已用构建产物实跑核对）
- [x] 未接线 `file.push` / `file.pull`（属待决项 T4，并由测试锁定）

## Wave C

- [x] 新工具有独立白名单，不是 `requireModelActionAllowed` 的旁路
- [x] 白名单外动作被稳定拒绝（覆盖 10 个动作）
- [x] 白名单内动作抵达 `Automation.ExecuteBridgeAction`，并断言协议动作对象内容
- [x] 无 Bridge 会话时失败明确，不降级为坐标动作
- [x] 新工具已登记 `ToolNames`

## 验证

- [x] `make comments` 通过（554 个手写文件，自测 16 个正反例）
- [x] `make test` 通过（协议 56 项检查，Go 全部包 ok）
- [x] `make verify` 通过（fake ADB、协议、Skills smoke、元数据）
- [x] `make build` 通过
- [x] Android Gradle 回归通过：`testDebugUnitTest assembleDebug lintDebug`
      BUILD SUCCESSFUL，152 actionable tasks。本轮 `android/` 改动为 0 文件，
      该运行只用于确认无回归。
- [ ] 真机 / 模拟器验证保持未勾选（本轮无设备证据，待设备验证）

## 待设备验证的项

以下改动只经单测与构建验证，**未在真机或模拟器上执行**：

- 直连 ADB 新增的四个键（MOVE_HOME / MOVE_END / PAGE_UP / PAGE_DOWN）实际投递效果。
- Wave B 九个新 CLI 动作子命令在真实设备上的行为。
- Wave C 的 `android_bridge_action` 经真实 Bridge 会话命中元素的效果。
