# N49 抖音安全兼容场景契约任务

- [x] 审计 N49 路线、N46 固定 manifest 与 N47 安全契约
- [x] 创建独立分支、worktree 和 change spec
- [x] 先增加身份、轮次、分类、动作和清理 RED 测试
- [x] 实现固定场景策略与兼容报告严格 Schema
- [x] 实现 fail-closed validator 与统计重算
- [x] 增加固定 fake fixture、专用 schema-check 和 README
- [x] 运行 N49 定向 Node/schema 与 N47 离线回归
- [x] 运行 `make comments` 与 `git diff --check`
- [ ] 审查允许范围、提交身份和 trailer 后提交
- [ ] 真实设备连续 10 轮保持未验收
- [ ] N49 路线图保持未勾选

## 依赖与边界

N49 契约依赖主线既有 N46 固定制品身份和 N47 安全内核，但本 change 不修改或调用
共享 runner，不操作设备。fixture 只证明契约，不计真实轮次。

## 建议提交

`test(lab): add douyin scenario contracts`

提交末尾保留且仅保留一次：

`Co-authored-by: TRAE CLI <noreply@bytedance.com>`
