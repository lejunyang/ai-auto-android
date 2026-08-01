# N33 Clean Snapshot 重复矩阵验收清单

- [x] 独立 change spec、分支和 worktree
- [x] pending 状态不会被未离开前台的 `onResume` 消费
- [x] `onPause` 或窗口失焦后的首次返回恰好提交一次系统返回
- [x] reset 清空全部系统导航握手状态并释放输入焦点
- [x] API 30 系统导航 clean 单轮通过
- [x] API 30、33、34 各 20 轮完整场景成功率不低于 95%
- [x] 每轮显式 serial、固定 fingerprint 且先恢复 clean
- [x] 最终设备、runtime、lease、owned emulator 和临时目录为零
- [x] 定向测试、构建、lint、comments 与 diff-check 通过
- [ ] 提交身份、trailer、集成和 worktree 清理正确
