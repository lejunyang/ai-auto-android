# N45 授权视觉接线验收清单

- [x] 独立 worktree/change spec 与允许范围符合约束
- [x] 视觉 evidence 只存在 session 内存且不进入 ProviderAction 持久模型
- [x] visual lease 存活到执行和后置验证完成
- [x] 成功、拒绝、失败、停止、超时和取消均关闭 lease
- [x] 无授权/无 evidence 的 tap、long-click、swipe 动作提交数为零
- [x] observation/package/hash/candidate/confidence/point/bounds 全部绑定
- [x] 当前 screen、rotation、density、window/inset、secure 在动作前复核
- [x] typed tap/long-click/swipe 明确绑定目标包且不开放任意坐标接口
- [x] 后置验证失败记录一次提交且不自动重放
- [x] Provider 图片副本、source/pre/post observation 和候选全部清零
- [x] 语义 action 与非截图会话行为无回归
- [x] visual swipe 端点按候选 bounds 内相对坐标接入 production typed executor
- [x] 定向、全量 JVM、lint、AndroidTest、comments、verify、diff-check 通过
- [ ] API 30/33/34 设备命中保持未验收
- [ ] 提交身份、trailer 与 worktree 状态正确
