# N46 五包被动启动观察验收清单

- [x] 只接受固定 profile，不接受 manifest/package/serial/activity/动作参数
- [x] 每个 App 恰好一次 device info、launch 和 hierarchy
- [x] 不调用 tap、text、key、swipe、stop、Bridge、截图或任意 shell
- [x] hierarchy 只在内存解析，报告无 XML、文本、描述和坐标
- [x] 页面信号只输出固定布尔值，不驱动自动动作
- [x] 五个 verified APK 均完成安装、拉回同字节与签名复核
- [x] 每个 App 清数据并恢复 clean snapshot
- [x] 最终设备/runtime/lease/temp 为零，报告权限为 0600
- [x] 定向、N46 全量、扫描、comments 与 diff-check 通过
- [ ] 提交身份、trailer、集成和 worktree 清理正确
