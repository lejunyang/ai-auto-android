# 发布包任务

单一执行者、当前分支串行推进 R0 → R1 → R2 → R3 → R4，不并行派发 Agent；规格状态、清单、Makefile 与导航由同一执行者汇合。每项只有实现和证据齐备才勾选。

| 任务 | 步骤、依赖与目录所有权 | 验收证据 | 建议提交边界 | 失败回滚/清理 |
| --- | --- | --- | --- | --- |
| R0 规格 | 核对 Git/worktree、现有构建及 osdk pin；本规格目录 | 干净分支 d565282、范围/授权/验收定义 | docs(spec): define release bundles | 保留原始事实，不改历史规格 |
| R1 定位诊断 | 依赖 R0；internal/adb locator 及测试、internal/cli 诊断及测试；先 RED 再实现 | 优先级、损坏、执行位、符号链接、CWD、doctor 来源；定向 Go 测试 | feat(adb): prefer bundled platform tools and report source | 仅撤回本项修改，保留测试失败证据 |
| R2 打包 | 依赖 R1；scripts/release.sh、新辅助脚本/测试、固定清单、Makefile、release.yml；先离线 RED，获取官方元数据后下载核对，白名单和可复现归档 | 离线成功/拒绝矩阵、原子输出、官方原始校验、全量检查 | build(release): ship full and slim bundles with matching helper | 清专属 staging/坏缓存；保留旧成功输出；不提交二进制 |
| R3 实测 | 依赖 R2；仅新增发布验证脚本/测试及本规格证据；真实构建与 Windows 包隔离、emulator-5554 冒烟 | 九资产摘要、八包内容/大小、doctor 两结果、设备指纹、helper v2、清理与退出 | test(release): verify packaged tools and emulator smoke | 先保留限额脱敏证据，再清设备临时目录及已启动 emulator；身份不符停止动作 |
| R4 文档验收 | 依赖 R3；README、security-distribution、virtual-display、troubleshooting、路线图仅 N60.2、本规格 | 实测大小、安装/许可/冲突/升级、平台限制、全量结果、干净 worktree | docs(release): document bundled installation and acceptance | 不把未验收写成完成，不回写其它规格 |

- [ ] R0 规格已提交
- [ ] R1 RED/GREEN 与定向验证
- [ ] R2 离线 RED/GREEN、固定清单、打包/CI
- [ ] R3 真实构建、隔离运行、模拟器与清理
- [ ] R4 文档、路线图 N60.2 与全量验收

手动边界：真机 RSA/权限、安全设置必须用户确认，本任务无需操作；disposable emulator 的既有调试通道可自动使用，不安装 App、不启用无障碍/MediaProjection、不自动确认其它操作。
