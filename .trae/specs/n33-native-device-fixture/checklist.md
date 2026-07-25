# N33 验收清单

- [x] 文本输入、点击、长按、纵向滚动和横向 swipe 均有稳定语义目标
- [x] 每个动作均有固定 `contentDescription` 和独立后置状态节点
- [x] 至少三个页面具备平台 Back 栈和逐步断言
- [x] dialog 可关闭且关闭结果可独立断言
- [x] 进程内状态可一键复位，不持久化用户或设备数据
- [ ] Back、Home、Recents、应用切换和 Launcher 重入设备断言通过
- [x] instrumentation 支持单场景选择和参数化重复
- [x] Fixture 无网络权限、无敏感数据、无外部副作用
- [x] 模块单测、构建、lint、`make comments` 和 `git diff --check` 通过
- [ ] API 30、33、34 各连续 20 轮成功率不低于 95%
