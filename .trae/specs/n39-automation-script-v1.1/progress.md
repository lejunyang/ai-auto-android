# N39 Progress

本文件 append-only，记录实现、测试、契约证据与失败结果。

## Round 1

- 已创建独立 change spec。
- 当前状态：实现尚未开始。

## Round 2

- 已增加 AutomationScript 1.1 严格 Schema、1.0/1.1 fixture 和合法/非法契约，
  字段覆盖 revision、updatedAt、provenance、enabled、visualTarget、notes、
  waitBefore 及兼容 retry 参数。
- Kotlin 模型和 store 已迁移到 1.1；1.0 脚本保留现有字段并补齐来源、revision、
  updatedAt 和步骤默认值，未知 major、损坏 JSON 和严格导入未知字段均失败关闭。
- 保存事务在 JVM 互斥外使用 OS `FileChannel` lock，锁覆盖 revision 读取、冲突判断、
  `.bak` 备份、原子替换和回滚；外部锁竞争超时不读取或覆盖正式脚本。
- revision 冲突显式返回 attempted/current 双方；两个独立 store 同 revision 更新只允许
  一个 Saved，另一个返回 Conflict。成功更新保留上一 revision 的 `.bak` 供恢复。
- 默认导出递归移除敏感键、敏感变量默认值、截图字节和设备绝对路径，同时保留
  `secretRef`；严格导出再导入通过。
- 协议验证通过 11 个 Schema、12 个合法 fixture、17 个非法 fixture 和 4 个兼容
  检查，共 33 项；1.1 fixture 经 Kotlin JSON round-trip 等价。
- Android recording 定向测试 43/43 通过：Store 18、EventMapper 6、Runtime 1、
  StateMachine 8、ReplayEngine 10，0 failure/error/skip。
- 中文注释覆盖检查覆盖 181 个受管文件通过，检查器 14 个正反例通过，
  `git diff --check` 通过；未提交 APK、截图、secret、缓存或构建产物。
