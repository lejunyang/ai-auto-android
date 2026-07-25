# N33 Tasks

- [x] Task N33.1：定义稳定节点、进程内状态和三页面返回栈
- [x] Task N33.2：实现输入、点击、长按、纵向滚动、横向 swipe 和 dialog
- [x] Task N33.3：实现 Home、Recents、应用切换和 Launcher 重入场景
- [x] Task N33.4：增加状态单测与 UI Automator 前置/动作/后置断言
- [x] Task N33.5：提供场景选择和参数化重复入口
- [x] Task N33.6：完成模块构建、lint、注释和格式验证
- [ ] Task N33.7：由集成线程完成 API 30、33、34 各 20 轮设备矩阵
- [x] Task N33.8：以 `test(android): expand native automation fixture` 提交

## 依赖

N33 依赖 N31 的确定性 Emulator Runner；实现基线已由提交 `b7e9eda` 和
`6604f10` 提供。设备矩阵由集成线程在本提交完成后使用明确 serial 串行执行。
