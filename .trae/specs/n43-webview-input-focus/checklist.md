# N43 WebView 聚焦输入验收清单

- [x] 独立 worktree/change spec 与允许范围符合约束
- [ ] click/focus 和 setText 作为两个显式动作计数
- [ ] setText 前重新获取语义树和同名唯一节点
- [x] 节点能力不足时零提交停止且不使用回退
- [x] 不包含坐标、剪贴板、IME shell、DOM/JavaScript 或 raw ADB
- [ ] 固定非敏感 ASCII 输入值和稳定失败摘要
- [x] Web fixture JVM 与 instrumentation 编译通过
- [ ] API 30 clean AVD 单轮通过
- [ ] API 30 二十轮成功率不低于 95%
- [ ] API 33/34 设备验收状态不被错误更新
- [x] API 30 失败后设备、runtime、lock 和 lease 清理完成
- [ ] comments、diff-check、提交身份和 trailer 正确
