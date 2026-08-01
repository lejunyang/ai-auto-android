# N50 小黑盒安全兼容场景契约任务

- [x] 审计 N50 路线、N46 小黑盒 manifest、N47 与 N51 离线契约模式
- [x] 创建独立分支、worktree 和 change spec
- [x] 先增加固定矩阵、安全边界、统计重算与数据最小化 RED 测试
- [x] 实现严格场景和运行报告 JSON Schema
- [x] 实现 fail-closed validator、逐轮判定和统计重算
- [x] 增加专用 schema-check、README 和固定 fake fixture
- [x] 运行定向 Node/schema、N47 smoke、comments 与 diff-check
- [x] 审查允许范围、提交身份和 trailer 后提交
- [ ] 总计 10 轮真实设备场景保持未验收

## 依赖与边界

本 change spec 只消费 N46 固定 manifest 的文本身份，并复用 N47 离线 JSON Schema
validator。不得修改或运行 N47 Runner、共享 Schema、manifest、APK/cache、Android、
Go 或设备。fixture 只能证明契约，不能形成 N50 真实十轮证据。

## 建议提交

`test(lab): add xiaoheihe scenario contracts`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
