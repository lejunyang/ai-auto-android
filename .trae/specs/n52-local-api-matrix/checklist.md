# N52 本地矩阵验收清单

- [x] 独立 worktree/change spec 与允许范围符合约束
- [x] 固定 3 profile × 5 scenario × 20 iteration，无 skip/缺轮/重复
- [x] capability 缺失在设备启动前失败
- [x] 每轮显式 serial、API、fingerprint 和 clean snapshot
- [x] 场景报告逐字绑定 profile/scenario/iteration
- [x] 成功/失败/取消均 stop，stop 失败立即停止矩阵
- [x] App/Bridge/service/screenshot/log/process/runtime/lease 残留全部为零
- [x] residue 失败立即停止且不继续污染后续统计
- [x] 每 API/场景生成 N34 20 轮与 N47 兼容汇总
- [x] 通过门要求每场景 >=95%、cleanup 100%、未知提交为零
- [x] 报告无设备内容、自由异常、selector、坐标、secret 或运行产物
- [x] fake 300 轮与全部失败路径通过
- [x] N52、N47、N34、comments 和 diff-check 通过
- [ ] 真实 API 30/33/34 设备矩阵保持未验收
- [x] 提交身份、trailer 与 worktree 状态正确
