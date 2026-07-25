# N38 验收清单

- [x] N36 合法 invitation 与 18 个威胁 fixture 在 Kotlin 结果一致
- [x] N36 fingerprint、transcript、HKDF、双方 confirmation 和低阶点向量一致
- [x] 未知字段、TTL、重放、私网地址/网卡、短指纹、弱密钥和降级均在 socket 前拒绝
- [x] 扫码结果和手工输入共用严格入口，拒绝权限或无相机仍可手工输入
- [x] 用户选择候选和本地网卡并确认短指纹前不会连接
- [x] UI 只展示网卡、桌面短指纹、session 过期时间和明确停止入口
- [x] 切网、过期、进程恢复、地址不可达和认证错误停止新动作且不降级
- [x] socket/crypto 使用注入端口完成单元测试，无 secret 进入状态、日志或持久化
- [x] 定向 App 单测、`make comments` 和 `git diff --check` 通过
- [x] 只修改允许目录且单一 Conventional Commit，worktree 干净
- [ ] 真实同 LAN、相机权限/库、公共导航和设备验收保持未勾选
