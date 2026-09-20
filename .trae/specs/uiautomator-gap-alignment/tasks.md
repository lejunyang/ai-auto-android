# UIAutomator 缺口对齐任务

## Wave A：协议与文档一致性（单一 owner 串行先行）

- [x] 先增加 RED 测试：键枚举跨实现一致性、`visual.action.execute` fixture
- [x] `bridge-request.schema.json` 补 `visual.action.execute` 方法与 `$defs` 分支
- [x] `protocol/fixtures/manifest.json` 补该方法的合法与非法 fixture
- [x] `internal/adb/direct.go` 的 `allowedKeys` 补齐 MOVE_HOME / MOVE_END / PAGE_UP / PAGE_DOWN
- [x] `internal/mcpserver/schemas.go` 的 `ui.pressKey` 枚举同步补齐同样四键
- [x] `internal/mcpserver/schemas.go` 的 `ui.scroll` 补 forward / backward
- [x] 从 `actionSchema()` 移除 `app.stop` 分支（风险门保持不变）
- [x] `README.md` 的 MCP 工具数改为实际注册数
- [x] 运行协议 smoke、Go 测试、comments

## Wave B：CLI 表面补齐（目录：仅 internal/cli/）

- [x] 先增加 RED 测试：新子命令映射到既有 `service.ActionRequest`
- [x] `action drag` / `multi-tap` / `path-gesture`
- [x] `action rotate` / `wake` / `unlock`
- [x] `action ime-hide` / `ime-select` / `deeplink`
- [x] `observe screen-state` / `observe input-methods`
- [x] 更新两处 usage 串与未知子命令错误提示
- [x] 运行 CLI 定向测试与 comments

## Wave C：MCP 语义动作（目录：仅 internal/mcpserver/）

- [x] 先增加 RED 测试：白名单外动作被拒、白名单内抵达 service、无桥稳定失败
- [x] 新增 `android_bridge_action` 工具与独立语义动作白名单
- [x] 登记到 `ToolNames`，让既有一致性测试自动覆盖
- [x] `ui.setText` 的 ASCII 限制改为可自解释的引导
- [x] 运行 MCP 测试与 comments

## 收尾

- [x] 未纳入缺口写入 `docs/next-phase-tasks.md`（新增 N57 / N58 / N59）
- [x] `make comments` / `make test` / `make verify` / `make build`
- [x] Android Gradle 回归（本轮未改 Android 代码，仍跑一次确认无回归）
- [x] 审查范围并分波次提交

## 建议提交

- Wave A：`fix(protocol): align typed action and bridge method contracts`
- Wave A 文档：`docs: correct mcp tool count in readme`
- Wave B：`feat(cli): expose implemented typed actions and device observations`
- Wave C：`feat(mcp): add allowlisted semantic bridge action tool`
