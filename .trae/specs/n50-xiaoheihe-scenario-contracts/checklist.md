# N50 小黑盒安全兼容场景契约验收清单

- [x] 独立分支、worktree 与允许范围符合约束
- [x] 固定绑定 `xiaoheihe-1.3.392-arm64.json` 完整身份
- [x] 报告总计固定 10 轮且 API 仅允许 30、33、34
- [x] consent/permission 阻塞零提交且不计兼容成功
- [x] native/WebView route 分离且无 DOM/JavaScript 注入
- [x] 公开搜索、详情、Back/Home/Recents/跨 App 返回有 post observation
- [x] 长按与全局动作均禁止关注、收藏、评论、发帖、下载和分享
- [x] dynamic content、advertisement 和 network 独立分类与统计
- [x] unknown commit 不重试且停止后续步骤
- [x] 搜索历史、App 数据、Bridge、session、artifacts、snapshot 全清理
- [x] validator 从内容重算 decision 与全部统计
- [x] arbitrary package/APK/action/坐标/secret/path/raw content 被拒绝
- [x] fixture 不计入真实十轮、真实兼容数或真实兼容率
- [x] RED 与 GREEN 证据 append 到 progress
- [x] 定向 Node/schema、N47 smoke、comments 与 diff-check 通过
- [ ] 实现提交身份与唯一 trailer 正确
- [ ] 真实 10 轮设备证据保持未验收
- [ ] N50 路线图保持未勾选
