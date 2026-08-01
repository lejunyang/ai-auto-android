# N49 抖音安全兼容场景契约验收清单

- [x] 独立分支、worktree 与 change spec 符合允许范围
- [x] 固定绑定 `douyin-39.8.0-arm64.json` 的完整身份摘要
- [x] 真实报告仅接受恰好 10 轮，fixture 不计真实轮次
- [x] 点赞、评论、关注、发送、分享、收藏和下载节点零提交
- [x] video Surface、visual-only、登录墙、广告、模拟器检测与 API 33/34 hierarchy
  failure 分别分类
- [x] hierarchy/video failure 不盲目升级 visual action
- [x] 纵向 swipe 绑定 fresh observation 并验证内容 fingerprint 变化
- [x] 长按默认 unsupported 且零 attempt/retry/commit
- [x] 无账号报告保留遥测与推荐动态限制
- [x] unknown commit 使用稳定码且不重试
- [x] 每轮清 App 数据、session、artifacts 并恢复 snapshot
- [x] 任意 package/APK/action、坐标、secret、路径和 raw content 均被拒绝
- [x] RED 与 GREEN 证据已 append 到 `progress.md`
- [x] N49 Node/schema、N47 回归、comments 与 diff-check 通过
- [x] 提交 author/committer 与唯一 trailer 正确
- [ ] 未操作设备，真实 10 轮保持未验收
- [ ] N49 路线图保持未勾选
