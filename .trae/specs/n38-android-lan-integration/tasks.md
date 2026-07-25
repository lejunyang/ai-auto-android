# N38 Android LAN Integration Tasks

- [x] Task N38I.1：创建独立 change spec 并固定 wire、导航和设备验收边界
- [x] Task N38I.2：先增加真实 NDJSON socket 与完整加密 frame RED 测试
- [x] Task N38I.3：实现选中 Android Network 绑定、接口枚举和实时身份校验
- [x] Task N38I.4：实现生命周期 ViewModel、手工 invitation 和显式停止
- [x] Task N38I.5：接入桌面 Bridge 页面与 App 导航
- [x] Task N38I.6：完成定向测试、App 单测、注释和差异验证

## 依赖与汇合

N38I 基于已合入的 N38 `c4bff51`。N37 CLI 在独立 worktree 并行修改 Go CLI 目录；
两者提交合入主线后才可进行真实 wire 互操作和路线图更新。

## 建议提交

单一 `feat(android): integrate lan pairing flow` 提交包含生产接线、对应测试和本 change
spec 证据；不得混入 N37 CLI 或路线图共享文件。
