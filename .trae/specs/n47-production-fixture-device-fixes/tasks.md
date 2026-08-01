# N47 Production Fixture 设备前置修复任务

- [x] 记录首次 API 30 `ERR_INVALID_THIS` 与零残留证据
- [x] 修复默认 UUID provider 并增加非 fake 回归测试
- [x] 记录四步补偿误报与零残留证据
- [x] 将 App 数据清理绑定到本轮已安装固定包
- [x] 记录真实 APK signer 输出并修复严格 parser
- [x] 运行 N47 runner、comments 和 diff 定向验证
- [x] 串行尝试 API 30 并在 endpoint blocker 后停止 API 33/34
- [x] 运行全量验证并提交单一职责修复

## 依赖与汇合点

- N31 独占 emulator 生命周期和 serial 分配；本 change 不自行发现或选择设备。
- N47 provider 独占 Bridge、Accessibility 和 control forward 生命周期。
- 设备轮次串行运行；一个 profile 未安全清理前不得启动下一个。

## 失败清理

- 依赖 production fixture runner 的四步场景清理和 N31 外层 restore/stop。
- 失败后检查规范化设备列表、runtime、AVD/port lease 与 `n47-production-*` 临时目录。
- unknown commit 不重放动作；授权、签名或身份失败保持零动作。

## 建议提交

`fix(test-lab): close production fixture device blockers`
