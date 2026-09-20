# UIAutomator 缺口对齐验收清单

## 范围与流程

- [ ] 独立 change spec 与允许范围符合 AGENTS.md 约束
- [ ] 未回写 `.trae/specs/build-ai-android-automation-mvp/` 的已验收结论
- [ ] Wave A 在 B / C 之前串行完成；共享文件只由单一 owner 修改
- [ ] 提交按波次单一职责，使用 Conventional Commit
- [ ] 未 push 到远端（由用户自行决定）

## Wave A

- [ ] `bridge-request.schema.json` 的 method 枚举含 `visual.action.execute`
- [ ] 该方法有合法 fixture 与至少一条非法 fixture
- [ ] 键枚举以协议为单一权威来源，直连与 MCP 向上对齐至 21 键
- [ ] 新增跨实现一致性测试，四处漂移会失败
- [ ] 未引入任意 keycode 或组合键能力
- [ ] MCP `ui.scroll` 支持协议已声明的全部六个方向
- [ ] MCP schema 不再声明风险门必拒的 `app.stop`
- [ ] `risk.go` 白名单未被放宽
- [ ] README 工具数与 `ToolNames` 长度一致

## Wave B

- [ ] 九个动作子命令均可用且参数校验完整
- [ ] `observe screen-state` / `observe input-methods` 可用
- [ ] 每个新子命令有定向测试断言其 `service.ActionRequest` 映射
- [ ] usage 串与错误提示同步更新
- [ ] 未接线 `file.push` / `file.pull`（属待决项 T4）

## Wave C

- [ ] 新工具有独立白名单，不是 `requireModelActionAllowed` 的旁路
- [ ] 白名单外动作被稳定拒绝
- [ ] 白名单内动作抵达 `Automation.ExecuteBridgeAction`
- [ ] 无 Bridge 会话时失败明确，不降级为坐标动作
- [ ] 新工具已登记 `ToolNames`

## 验证

- [ ] `make comments` 通过
- [ ] `make test` 通过
- [ ] `make verify` 通过
- [ ] `make build` 通过
- [ ] Android Gradle 回归通过，或明确标注未验证及原因
- [ ] 真机 / 模拟器验证保持未勾选（本轮无设备证据）
