# 任务拆分

所有状态由单一集成者维护；本次顺序实施，不并行修改共享协议或规格。当前 worktree 为 E:/Projects/wt-virtual-display，分支 virtual-display-screen-off；只本地提交，不 push，不动 main。

| 编号 / 波次 | 目标与具体步骤 | 依赖 / 目录所有权 | 验收证据 | 建议提交 / 回滚清理 |
|---|---|---|---|---|
| VC1 / 1 | 检查 AGENTS、Git、现有实现；建立四份独立规格并先提交 | 无；仅本规格目录 | 干净起点 aacd60d、规格提交 | docs(spec): define virtual camera change；仅撤销本次规格补丁 |
| VC2 / 2 | 先验证协议、图片预算/缩放/路径与启用策略失败测试，再实现 VirtualDevice 摄像头后端、流生命周期、位图绘制和退出释放 | VC1；android/displayd | JVM 测试、helper 编译；设备证据另记 | feat(displayd): add opt-in virtual camera backend；关闭 helper 清理资源 |
| VC3 / 3 | 固定 argv 创建/复用/精确清理关联；扩展客户端、service、CLI/MCP 枚举、能力兼容与错误表；测试遗留关联、失败清理、旧 helper、CLI/MCP 错误 | VC2；internal/{adb,vdisplay,service,cli,apperr,mcpserver} 中显示相关文件；禁止三道风险门 | 定向 Go 测试、协议测试 | feat(camera): add operator camera controls；停止 helper、移除精确关联与临时文件 |
| VC4 / 4 | fixture 增加正常权限申请、预览、颜色采样文本和相机编号/朝向/错误节点 | VC2；android/device-fixture | Android 单测/lint/build；真机读回由人工另验 | test(fixture): add virtual camera sampling page；不改生产 App/Bridge |
| VC5 / 5 | 三份旧脚本分别冷启动验收；新增模拟器不支持路径、关联清理、普通屏复用检查；定位脚手架抖动 | VC3/VC4；scripts/emulator | 固定环境、指纹、运行计数和结果，仓库外证据 | test(emulator): verify virtual camera failure cleanup；关闭 emulator、移除 forward |
| VC6 / 6 | 真机唯一身份/凭据锁守卫；记录相机基线；两种 focus 分别创建摄像头屏、只读焦点/层级/组状态/截图、设置图片、关闭与数量恢复 | VC5；仅授权 helper 操作，规格 progress 单一所有者 | 逐步文字记录与仓库外原始证据；无应用的能力不得宣称验证 | docs(camera): record helper device evidence；结束 stop、精确关联/临时文件/forward 清理 |
| VC7 / 7 汇合 | 编写负责人验收步骤与可自动部分，更新文档、错误表、skills 镜像和仅 N60.6；执行全量验证并审查最终 diff | VC2–VC6；任务允许的文档/脚本/规格/必要 helper 打包 | make comments/test/verify/build/android-verify、测试数量、人工待验项清单 | docs(camera): document acceptance and limits；无全量删除，产物不入 Git |

## 人工步骤与阻塞处理

fixture 在真机的安装确认、CAMERA 单独授权、主动打开测试页和主屏输入法/息屏切换由负责人操作：设备边界明确禁止自动执行。其它只读检查及 helper 操作提供固定 serial 的步骤，每步列目标、动作、预期、判据。无设备、离线、锁定或指纹变化即停；不重试、不唤醒。等待/脚手架失败自行修复并保留原因，安全或产品冲突先报告。

## 进度

- [x] VC1 规格提交（bdefd6c）
- [ ] VC2 helper
- [ ] VC3 Go/CLI/MCP
- [ ] VC4 fixture
- [ ] VC5 模拟器
- [ ] VC6 真机 helper（不包含端到端人工验收）
- [ ] VC7 文档、汇合与全量验证
