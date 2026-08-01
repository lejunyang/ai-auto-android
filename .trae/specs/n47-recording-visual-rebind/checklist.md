# N47 录制视觉 Rebind 验收清单

- [x] 独立分支、worktree 与 change spec 符合约束
- [x] 历史 observation ID/hash 无法单独授权 production replay
- [x] provider 缺失和所有 metadata 漂移均零动作
- [x] 当前 run 显式 rebind 写入 fresh ID/hash/geometry 后才能 replay
- [x] 图片 bytes、lease 与 post observation 不持久化
- [x] lease 只能消费一次，全部路径撤销
- [x] 动作成功后重新观察，后置失败不重放
- [x] unknown commit 显式失败且不重放
- [x] 不存在裸坐标或 semantic fallback
- [x] 定向 Android JVM 与 `:app:testDebugUnitTest` 通过
- [x] `:app:lintDebug`、`:app:assembleDebug`、comments 和 diff-check 通过
- [ ] Bridge/N47 desktop port 与设备证据保持未完成
- [ ] N47 路线图保持未勾选
- [ ] 提交身份、唯一 trailer 与 worktree 状态正确
