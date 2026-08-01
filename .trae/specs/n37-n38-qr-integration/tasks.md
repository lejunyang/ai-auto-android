# N37/N38 二维码生产闭环任务

## Wave 1：规格与 RED

- [x] 审计 N37 `QRProvider`、CLI 流式 invitation 和 N38 严格输入/状态机。
- [x] 固定终端二维码、外部扫码 Activity、手工码解码和人工确认安全边界。
- [x] 增加 Go 二维码和 CLI 生产注入 RED 测试。
- [x] 增加 Android scanner contract、手工码和零自动连接 RED 测试。

## Wave 2：互斥实现

- [x] 在 `internal/bridge/lan/` 实现有界纯 Go 二维码与 UTF-8 terminal renderer。
- [x] 在 `internal/cli/` 默认注入生产 provider，并显式输出 representation。
- [x] 在 Android LAN UI 目录实现可信 scanner discovery/result contract。
- [x] 把扫码结果和 `AIAUTO1-` 手工码接到同一严格输入端口。
- [x] 接入 Compose launcher、能力提示和取消/失败状态，不声明本 App `CAMERA` 权限。

## Wave 3：验证与提交

- [x] 运行 Go LAN/CLI 定向及 race。
- [x] 运行 Android LAN/UI 定向、App unit/lint/debug/androidTest/release。
- [x] 运行 `make comments`、`make test`、`make verify`、`make build` 和差异检查。
- [x] 审计无 invitation/二维码/密钥/缓存/构建产物入库。
- [ ] 以单一职责 Conventional Commit 提交并确认 worktree 干净。

## 依赖与汇合

- Go 与 Android 文件所有权互斥，可在 RED 后独立实现；共享 change spec 和最终提交由
  本 worktree 单点维护。
- `docs/next-phase-tasks.md` 由主线程集成，本任务禁止修改。

## 失败清理

- 测试失败时只删除本 worktree 生成的构建产物；不清理共享 cache、不操作 SDK。
- 不启动设备或模拟器，因此不存在 ADB、AVD、权限或相机资源清理。
- 实现不创建二维码文件；若未来新增文件 representation，必须另立规格和生命周期。
