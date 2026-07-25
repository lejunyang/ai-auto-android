# N32 实现任务

## Wave 1：安全内核

- [x] 定义独立 change spec、目录所有权和 release 安全边界。
- [x] 先写一次性 token、scope 与设备身份拒绝测试并记录 RED 结果。
- [x] 实现安全随机签发、摘要存储、短期过期、单次消费和常量时间比较。
- [x] 覆盖真机 serial、N31 fingerprint、双签名、marker、release 和越权拒绝。

## Wave 2：Debug 适配

- [x] 实现不导出的进程内控制面和受控命令模型。
- [x] 接入状态读取、测试无障碍意图、Bridge 配对状态、无敏感脚本和自检。
- [x] 保证命令、状态、异常和 `toString()` 不包含 token。
- [x] 实现 release APK 的静态零入口检查。

## Wave 3：设备编排与验收

- [x] androidTest 使用显式 serial 与 N31 fingerprint 构造可信上下文。
- [x] 在 API 30、33、34 启停测试服务、建立 Bridge 并运行只读 snapshot。
- [x] 每轮撤销 token、关闭 Bridge、恢复设置并清理测试脚本。
- [x] 运行定向测试、`git diff --check`、`make comments` 和允许范围内构建。

## 依赖与汇合

- N31 提交 `b7e9eda`、`6604f10` 和共享基线 `581f01c` 已满足前置依赖。
- 本分支不修改共享 Gradle；模块注册和 App debug/androidTest 依赖由主线程串行集成。
- N33/N34 目录互斥，不读取、吸收或回退并行 Agent 的未提交工作。

## 建议提交边界

- 单一提交：`test(android): add emulator-only control plane`
- 提交仅包含本规格允许范围，并保留规定的 `Co-authored-by` trailer。
