# N47 通用场景 Runner 验收清单

- [x] 独立 change spec、分支和 worktree 符合约束
- [x] DSL 严格拒绝未知字段、未知动作、越权包和副作用声明缺失
- [x] 全部 10 类动作均绑定 pre/post 条件、超时和目标包
- [x] semantic、hybrid、visual route 均记录 score、attempts 和 observation
- [x] route/score/包/observation 漂移在动作前失败且提交数为零
- [x] 广告、更新、模拟器检测、网络失败、登录墙和 unknown 不自动关闭或点击
- [x] A-B variant 仅在步骤显式接受时继续
- [x] 后置失败记录一次已提交动作且不隐式重放
- [x] executor 异常记录未知提交状态而不伪报零
- [x] 报告不包含输入文本、selector、坐标、截图、hierarchy 或自由异常
- [x] 失败结果符合 N34 scenario-result Schema 并调用 artifact 端口
- [x] 成功、失败和取消都执行 stop、close、clear 与 restore
- [x] 聚合输出包含兼容率、route/page/error 计数且输入顺序无关
- [x] native/Web/Canvas fake fixture 场景覆盖全部动作和三种 route
- [x] N47 smoke、N34 Schema、comments 和 diff-check 通过
- [x] 未下载、安装或提交第三方 APK/账号/凭据
- [ ] production adapter 与 emulator 设备验收保持未完成
- [ ] 提交身份、trailer 与 worktree 状态正确
