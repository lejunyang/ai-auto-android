# N37 Tasks

- [x] Task N37.1：创建独立 change spec 并确认 N36 依赖提交
- [x] Task N37.2：先增加 N36 合法/18 威胁 fixture 与密码向量 RED Go 测试
- [x] Task N37.3：实现严格模型、canonical JSON、preflight 与重放消费
- [x] Task N37.4：实现明确网卡选择、候选枚举和临时 listener 生命周期
- [x] Task N37.5：实现 invitation、手工邀请码和无依赖二维码 provider 端口
- [x] Task N37.6：实现 X25519、HKDF、transcript 和双方 confirmation
- [x] Task N37.7：实现 AES-GCM framed RPC 的 nonce、AAD、序号和失败关闭
- [x] Task N37.8：增加 capability、风险门、目标包、deadline 和 close 窄 adapter
- [x] Task N37.9：覆盖超时、断开、取消、切网与秘密清理，完成定向验证
- [x] Task N37.10：审查允许范围并提交单一职责 commit

## 依赖

N37 依赖 N36 提交 `a0b66b6`。本 worktree 直接基于该提交，N36 Schema、18 个 threat
fixture 和 crypto vector 已存在。N38 在独立 worktree 并行，本任务不得修改其目录。

## 并行与汇合

N37 独占 `.trae/specs/n37-go-lan-listener/` 与 `internal/bridge/lan/`。若需要共享 CLI
root/switch 注册，保持专用入口未注册状态并交由主线程在 N37/N38 完成后串行集成。

## 建议提交

`feat(bridge): add desktop lan invitation listener`

提交正文末尾保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
