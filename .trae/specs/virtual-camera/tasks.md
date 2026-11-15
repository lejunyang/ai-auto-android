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

VC8–VC11审查修复按下表顺序实施，当轮不执行VC5。集成方后续回归结果见VC17；本轮Agent仅记录文档，不操作设备。

| 编号 / 波次 | 目标与步骤 | 依赖 / 修改所有权 | 验收 / 提交 / 清理 |
| --- | --- | --- | --- |
| VC8 / 8 | 两套标志具名；CameraPolicy纯函数与销毁/安全/焦点差异测试；status读取removeMode；真机比较保留/去掉OWN_DISPLAY_GROUP | 当前审查；android/displayd、internal/vdisplay与MCP显示schema、本规格 | JVM单测、四个真机组合实际flags/group/awake/removeMode；fix(displayd)；实验仅临时本地产物，不增加产品选项，逐次stop精确清理 |
| VC9 / 9 | Stop独立尝试普通文件/相机关联/图片清理，分类错误可见；回归缺关联服务/格式未知/无关联/普通helper；正则包级 | VC8；internal/vdisplay、internal/adb、相关CLI测试 | 定向Go测试，错误details与成功兼容；fix(display)；不删未知forward/关联 |
| VC10 / 10 | 只读记录缓存目录基线与普通/摄像头启动、创建、停止后的存在性/mtime；查明来源后决定源头避免或受所有权约束的rmdir | VC9；helper部署代码/固定设备验收脚本及本规格，当前已存在目录保持不动 | 逐步stat与相关框架调用证据；fix(camera)；仅启动前不存在且工具可证明归属的两个精确空目录可删，不能查明则如实登记未决 |
| VC11 / 11 汇合 | 重跑真机helper脚本、五项make；修正文档与append-only历史更正，审查范围、提交、停止Gradle daemon | VC8–VC10；文档/脚本/本规格单一owner | ok/cleaned、新数值、测试与退出码、干净worktree；docs(camera)；模拟器继续待验，无push/无二进制 |

- [x] VC8 标志与removeMode修复/取证（410769a；两方案四组合及最终脚本均removeMode=1）
- [x] VC9 独立清理与正则修复（551b1fe、f818f8e；API33/34真实CLI链路与普通运行helper回归通过）
- [x] VC10 缓存来源与约束清理（系统uiautomator包装脚本证据；普通/摄像头各阶段stat未变，原有目录保留给负责人）
- [x] VC11 验证、文档与本地提交（五项make、最终脚本ok/cleaned、Gradle停止；当轮模拟器待验，现见VC17）

- [x] VC1 规格提交（bdefd6c）
- [x] VC2 helper（当前48项JVM单测；旧验收产物的isolated/640×480 GPU出流已由集成方与负责人验证，其它档位/策略仍待验）
- [x] VC3 Go/CLI/MCP（定向/全量Go测试通过）
- [x] VC4 fixture（14项单测、lint/build；负责人已安装新版1dc42ad…，集成方验证横/竖图读回、文本可见和4:3预览；自动重连真机仍待验）
- [x] VC5 模拟器四项回归已执行（集成方2026-11-15，每项前冷启动；三项各1/1通过，MCP为2/3通过，[一次超时失败及未证归因](progress.md#mcp-cold-start-failure-20261115)保留，多轮flaky仍待验）
- [x] VC6 真机 helper（空屏注册、图片接收、实际标志与清理通过；不包含端到端人工验收）
- [x] VC7 文档、汇合与全量验证（既有五项make及9f68242上的集成方独立复跑通过；VC5结果已汇合，含MCP 2/3与失败记录，不代表稳定性全部通过）

## 端到端验收续修（A–E，顺序实施）

| 编号 / 波次 | 目标与步骤 | 依赖 / 允许修改与所有权 | 验收 / 提交 / 回滚清理 |
| --- | --- | --- | --- |
| VC12 / 12 | 写入集成方/负责人十项端到端证据及全部未验项，冻结执行者/摘要和推断强度 | 本次输入；仅本规格状态单一owner | docs(spec)先提交；progress只追加，不改既有记录 |
| VC13 / 13 A | 定向按包名启动固定resolve-activity argv；严格单行同包组件校验后显式am；普通路径不变 | VC12；internal/adb及定向测试，禁止风险门改动 | 解析错误/跨包/多行/截断/命令失败均不发am，普通argv回归；fix(adb)；无主屏回退 |
| VC14 / 14 C | 审查所有脚本media.camera调用；完整落主机文件后过滤；helper保守服务死亡/渲染失败诊断，Go/MCP同步 | VC12；android/displayd、internal/vdisplay、MCP显示schema、scripts验证脚本；协议单一owner | JVM状态机、Go线协议/MCP schema及脚本审查；feat(camera)；不诱发真实崩溃、不重建/重试 |
| VC15 / 15 D | fixture状态复位与最多3次退避重连、最终错误/次数；去标题遮挡、固定4:3预览；提纯逻辑测试 | VC12；仅android/device-fixture，不动生产App/Bridge | 预算/代次/退避/几何纯单测及Android lint/build；fix(fixture)；取消旧回调和待执行重试；真机重装待负责人 |
| VC16 / 16 汇合 B/E | 双授权人工前提、系统权限复查仅作推断；完整dump警示/关闭重建、最近任务行为/N60.6登记；真机A与helper脚本、五项make、提交和停daemon | VC13–VC15；docs、规格、仅N60.6，脚本固定fixture包且显式N | PAGE:MAIN、ok/cleaned、无工具资源/相机5；docs(camera)；权限框/离线/锁定即停；不安装、不改权限、不清最近任务，模拟器不启动 |

- [x] VC12 采纳集成方与负责人既有端到端证据（仅已给定范围；原始证据仓库外）
- [x] VC13 A 显示定向launcher解析与真机PAGE:MAIN（c9572a7、9e21f2e；显示65，仅包名启动并读到同包PAGE:MAIN，无权限框）
- [x] VC14 C 可用性诊断与完整dump（c580cda、9e21f2e；4项JVM状态测试、Go/MCP兼容测试，全部脚本审查；未主动制造服务崩溃）
- [x] VC15 D 新fixture代码修复（6a4c1bf；4项新增纯逻辑测试及lint/build通过）
- [x] VC15 新fixture真机横/竖图读回、文本可见性与4:3几何复验（负责人安装1dc42ad…，集成方复验；[仓库外证据摘要](progress.md#integrator-recheck-20261115)）
- [ ] VC15 新fixture断流后自动重连真机验收（当前仅单测覆盖，未制造强制断流）
- [x] VC16 B/E 文档、验证与清理（两处授权/推断/最近任务已记录；五项make通过，脚本ok/cleaned，相机5、Gradle无daemon；本轮提交见progress）

## 集成方复验记录（VC17，仅文档）

| 编号 / 波次 | 目标与步骤 | 依赖 / 允许修改与所有权 | 验收 / 提交 / 回滚 |
| --- | --- | --- | --- |
| VC17 / 17 | 记录22:35–23:06 JST集成方真机复验与模拟器逐轮结果；分开已验/未验，登记授权持久性决策、强制断流诊断与冷启动稳定规则/统计 | 9f68242及用户提供的仓库外证据；单一owner，仅本规格tasks/checklist/progress/device-acceptance、docs的virtual-camera/troubleshooting/next-phase-tasks和displayd README；progress仅追加 | make comments及文档范围审查；一个docs(camera)提交，不push；不操作设备、不启动模拟器，不改代码/测试/脚本/构建；失败只修正文档，不用新设备操作补结论 |

- [x] VC17 采纳集成方与负责人复验证据，模拟器四项已执行且MCP 2/3如实记录；权限不跨helper重启及N60.6后续已登记（[完整记录](progress.md#integrator-recheck-20261115)）。
- [x] 正常关闭/重开两轮availability无误报（available、reason=null，每次重开正确读回）；不外推为所有断流路径通过。
- [ ] restricted强制断流下availability是否误报（两处授权在时无法复现）。
- [ ] 冷启动稳定等待规则与MCP多轮flaky统计（现仅3个样本，不确定因果，不放宽判据）。
- [ ] 1280×720/1920×1080实际出流、steal带应用读回、IME、主屏息屏、第三方应用及其它多轮统计。

## 可选保留关联（VC18–VC21，顺序实施）

| 编号 / 波次 | 目标与步骤 | 依赖 / 目录所有权 | 验收 / 提交 / 回滚清理 |
| --- | --- | --- | --- |
| VC18 / 18 | 先写失败测试；只读关联查询、Stop保留可选结果与独立清理、service和CLI stop专用布尔参数；普通模式忽略 | 本轮规格；internal/adb、vdisplay、service、cli，仅显示相关文件；共享契约单一owner | 定向Go单测、默认stop命令/JSON兼容；feat(camera)；无全局清理，测试只fake |
| VC19 / 19 | Created标明关联归属；启动失败保留既有，仅删除新建；错误明确归属与清理结果，审查Java退出 | VC18；internal/adb、vdisplay及测试；Java只读审查，无必要不改 | 新建/复用及失败清理、取消/分类错误测试；fix(display)；只清本次可证明归属资源 |
| VC20 / 20 | 扩展摄像头模拟器脚本：保留编号→无helper/文件→重启同号→默认stop移除；指定AVD唯一serial冷启45秒后运行一次 | VC18/19；scripts/emulator/verify-virtual-camera.mjs，证据仓库外；不跑其它3脚本，不操作真机 | 每步编号/结果和最终清理归零、emulator退出、adb为空；test(camera)；发现其它设备即停报告 |
| VC21 / 21 | 同步命令参考/授权代价/精确清理、N60.6仅相关决策；五项make、范围审查、本地提交、Gradle退出 | VC18–20；任务允许docs/skills参考镜像/规格，progress只追加；禁止三份风险门/MCP入口/二进制 | 五项make及测试数量，docs(camera)；不push、不动main，清理仅本工具；真机授权保留由负责人另验 |

- [ ] VC18 stop可选保留、结果与CLI/service兼容测试。
- [ ] VC19 启动失败关联归属清理与Java退出审查。
- [ ] VC20 指定模拟器一次完整回归与清理。
- [ ] VC21 文档、五项make、本地提交与Gradle退出。
- [ ] 保留关联后helper重启的真机CAMERA授权仍有效（本轮无真机，不操作敏感权限）。