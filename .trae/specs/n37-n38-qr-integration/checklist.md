# N37/N38 二维码生产闭环验收清单

## 规格与范围

- [x] 独立 change spec 已创建。
- [x] 未修改共享路线图或 N36 wire schema。
- [x] 不新增 SDK、设备、模拟器或 APK 操作。

## N37

- [x] 生产 CLI 默认生成真实二维码 representation。
- [x] representation 内容可验证为精确 invitation payload。
- [x] 输出固定有界、确定性、无 CGO、无 GUI、无临时文件。
- [x] 取消、provider 失败和输出失败均清理 listener 与临时秘密；超容量仅降级。
- [x] 手工码继续可用且不泄露最终 token 或私钥。

## N38

- [x] 相机硬件与扫码 provider 能力明示。
- [x] provider 只能通过受信显式组件启动。
- [x] 拒绝、取消、无相机或无 provider 时手工路径可用。
- [x] 扫码 JSON 与 `AIAUTO1-` 手工码进入同一严格 parser/preflight。
- [x] 原始扫码/手工 payload 不进入状态、日志或恢复数据。
- [x] 扫码成功后仍要求地址、网卡和短指纹确认，不自动连接。
- [x] release 不声明无用途 `CAMERA` 权限且不静默连接。

## 验证

- [x] RED/GREEN 证据已记录。
- [x] Go 定向与 race 通过。
- [x] Android LAN/UI 定向通过。
- [x] App unit/lint/debug/androidTest/release 通过。
- [x] `make comments/test/verify/build` 与 `git diff --check` 通过。
- [x] 真机相机/provider/权限待人工清单已明确，不夸大自动化证据。
- [ ] Git 身份、trailer、提交边界和干净 worktree 已审计。
