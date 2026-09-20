# MCP 文件传输与 ime.show 出口任务

## ime.show

- [x] 服务层 `BridgeImeShow`（`internal/service/bridge_ime.go`）
- [x] `Automation` 接口新增方法，两个测试 fake 同步实现
- [x] CLI `aactl bridge ime-show`
- [x] MCP `android_ime_show`，走 `requireModelActionAllowed`
- [x] 输出无任何键盘可见性字段
- [x] CLI 测试锁定通道归属（挂到 action 下会永远 unavailable）

## 文件传输 MCP

- [x] 独立风险门 `requireFileTransferAllowed`
- [x] `android_file_push` / `android_file_pull`
- [x] schema 无 force/overwrite，`additionalProperties:false`
- [x] pull 的 description 写明落盘在 MCP 服务端机器
- [x] 用真实服务层 + fake ADB 实证不覆盖保护对 MCP 生效
- [x] 变异测试确认该实证非空洞

## 文档

- [x] README 工具数 12 → 15
- [x] `docs/protocol.md` 更新出口分布与三道门说明
- [x] `docs/next-phase-tasks.md` 补收口清单

## 收尾

- [x] 四道门禁
- [x] 提交
