# 负责人/集成方真机端到端验收

2026-11-15 20:45–21:51 JST的640×480/isolated端到端读回由集成方与负责人完成，证据和摘要见progress及docs/virtual-camera.md。负责人完成安装与两处授权；本轮Agent只验证已安装fixture的按包名MainActivity启动和helper，不安装新fixture、不修改权限。新相机页的重连/布局修复需负责人重新安装后再验；不把旧产物读回结果当成新fixture已验。

## 环境与停止条件

明确 serial `324462538298`，NX888J/API36/arm64；预期指纹见 docs/virtual-camera.md。ADB 每次操作前检查 `adb devices -l` 只有该在线 serial，并核对 transport、指纹和当前用户0。通过 `dumpsys trust` 的当前用户 `deviceLocked=0` 确认未锁定；离线、身份变化、凭据锁定或状态未知时立即停止，不重试、不唤醒、不解锁。所有命令显式带 serial 或 `--device`，所有虚拟屏动作显式带本次 N。

记录 host OS、CLI/helper/fixture SHA-256、APK package/version/ABI/source（仓库本地 debug 构建）、设备指纹、语言/密度/导航模式和开始时间。证据保存在仓库外，截图/层级须限额脱敏，完成验收后按项目策略清理，不提交二进制或原始 UI 数据。

**禁止设备端截断相机dump**（如`dumpsys media.camera | head`、提前关闭设备端管道）。本机曾因此使虚拟服务死亡，甚至物理0–4也移除重加。须完整写电脑文件，命令成功结束后才在电脑端过滤；不要为验证故意触发故障。脚本已采用完整stdout文件，无输出缓冲上限/设备端过滤或提前终止。若服务消失或stream error，保留证据，由操作员close受影响屏后create新屏、重新启动目标应用，不自动恢复或重启设备。

## 独立 helper 检查（可自动）

没有其它 helper 或工具标记关联时，运行：

```powershell
node scripts/verify-virtual-camera-device.mjs --device 324462538298 --adb PATH_TO_ADB_EXE --aactl PATH_TO_AACTL_EXE --helper PATH_TO_DISPLAYD_APK --evidence NEW_DIRECTORY_OUTSIDE_REPO
```

脚本先记录普通helper与缓存，再验证两策略注册、标志/removeMode、图片接收与清理。默认不启动应用；本轮明确加`--launch-fixture true`，仅在自建isolated屏按包名启动已装dev.aiauto.fixture并断言PAGE:MAIN，同时核对已装APK为给定05ade33a…摘要。权限框出现即停，绝不点按；不安装、不授予权限、不输入或切换主屏。`report.ok`仅代表这些判据通过；helper空屏FRAME_NOT_READY不是相机读回通过。须确认`cleaned:true`。

清理清单同时记录`/data/local/tmp/dalvik-cache`与`arm64`的存在性、inode、权限、mtime/ctime和子项。启动前已存在的共享目录保持原样；本机系统uiautomator包装脚本会重定向ANDROID_DATA并使用它，不归helper删除。最终`sharedRuntimeCache`必须与基线一致；变化则保留并报告负责人，不擅自递归删或rmdir。stop部分失败在`error.details.cleanup`公开每类结果，需分别检查helperFiles、cameraAssociation、cameraImages。

## 有应用的分步验收

表中“人工”不是自动化脚本中的确认开关。负责人亲自执行，Agent 不代点；集成方先观察再执行一个动作，再核对后置条件。

| 步骤 | 目标 / 动作 | 预期与判据 | 执行者与人工原因 |
| --- | --- | --- | --- |
| 1 安装 | 负责人安装本次本地 `android/device-fixture/build/outputs/apk/debug/device-fixture-debug.apk`，确认包 dev.aiauto.fixture 与摘要 | 安装完成且版本/签名匹配；无需打开任何负责人应用 | **人工**：真机安装确认不允许自动执行；不使用 -g，不预授权限 |
| 1a 默认设备CAMERA | 负责人在系统设置的应用权限页给fixture默认设备相机权限；已授予则保持，不为测试撤销重授 | 记录默认设备granted=true及CAMERA Uid mode foreground；这是本机33分钟稳定运行前提之一 | **人工**：系统设置及敏感权限只能负责人处理，Agent不打开设置/点权限/pm grant/appops |
| 2 基线 | 完整取回 `dumpsys media.camera` 到主机后过滤，另读关联、进程/forward | 总/normal/public/virtual provider有基线；不设备端head/提前关管道，其它关联保留 | 可自动完整读取；每步守卫身份/未锁定 |
| 3 启动 | `aactl display start --device 324462538298 --virtual-camera --helper PATH --json` | v2、virtualCamera:true；只有工具精确关联新增 | 集成方可执行 helper 操作 |
| 4 创建 | `aactl display create --device 324462538298 --width 720 --height 1280 --dpi 240 --focus isolated --camera back --json` | 记录 N/virtualDeviceId/cameraId/实际标志；注册数量+1，无 SECURE | 集成方可执行；N 从响应取，不填主屏0 |
| 5 打开相机页 | 集成方陪同负责人运行 `aactl action launch --device 324462538298 --display N --package dev.aiauto.fixture --activity .CameraActivity --json` | 出现fixture相机页或虚拟设备权限框；不向显示0重试。本轮Agent只复验旧MainActivity启动，新fixture重装后再做相机页 | 新fixture安装与接下来的授权由负责人处理；Agent若遇权限框立即停，不代点 |
| 6 虚拟设备CAMERA | 查看同屏最新截图/层级的系统权限框，由负责人自己决定并按可靠节点/坐标点按；已授权则不动 | 与默认设备授权一起记录；只授虚拟设备曾在21秒/约3分钟被restricted断流，默认授权后稳定33分钟。元数据应为id0/back/orientation0/visible0/display=N | **人工**：第二处单独授权；禁止Agent自动点击/pm grant/appops。推断后续复查可能按默认设备判定，不写成已证机制 |
| 7 占位读回 | 未设图片，观察 fixture 文本与 helper status | `CAMERA:TL=black,TR=black,BL=black,BR=black`；streams 非空、frames 递增且 error=null，预览黑色 | 集成方可只读采样；最多在10秒预算内轮询观察，不重发启动/授权动作 |
| 8 横图 | 用 helper 检查生成的 quadrants.png 执行 `aactl camera set-image --device 324462538298 --display N --image PATH --json` | 10秒内至少3次独立层级样本含 `CAMERA:TL=red,TR=green,BL=blue,BR=white`，预览对应方向无镜像；helper frames 持续增长 | 图片设置与只读采样可自动；imageSet:true 本身不算读回成功 |
| 9 运行中切换 | set-image 换本脚本生成的 portrait.png，再换横图；第二张顺序反转，避免把旧帧误当切换成功 | 竖图10秒内3次文本为 `CAMERA:TL=white,TR=blue,BL=green,BR=red`；保持比例、居中、左右黑边；切回横图恢复原顺序 | 可自动设置/采样，负责人核对预览留边；仅四中心文本不能证明全部边缘几何；早期helper探测生成的旧竖图不用于此判据 |
| 10 分辨率 | 用 fixture 的固定640×480请求验收；1280×720/1920×1080 当前只有输入配置声明，需后续扩展测试客户端请求不同输出 | 每种实际请求都要有流尺寸、帧数、ImageReader 颜色证据；未执行的尺寸写未验 | 后续测试扩展独立提交，不把配置声明当实际三档读回 |
| 11 两屏焦点与IME | 负责人在无敏感数据的输入场景建立基线；依次对本次虚拟屏测试页进行可见操作，记录顶层焦点/IME客户端显示编号和输入结果 | isolated 与 steal 分别记录主屏是否被打断、虚拟屏是否有IME；不预设旧普通屏结论 | **人工**：本次 Agent 禁止主屏输入或操作负责人应用；不采集真实文本 |
| 12 主屏息屏 | 负责人自己通过物理按键使主屏息屏；集成方只读取 N 的 hierarchy/screenshot/status。若负责人明确继续输入验收，由负责人在 fixture 的无敏感输入页定向操作并观察精确落点 | 记录每种策略的层级可用性、非黑帧、动作后结果；无输入能力或窗口不可用如实记限制，不 fallback0 | **人工**：Agent 禁止主屏息屏/唤醒/解锁及未授权输入；出现凭据锁立即停止，不继续摄像头/帧操作 |
| 13 流关闭 | 负责人退出 fixture 相机页；只读 helper status | 流关闭后 streams 为空，帧绘制停止；无残留相机客户端 | 页面导航由负责人；只读状态可自动 |
| 14 关闭 / 重复 | close N并观察Activity销毁且显示0未出现fixture；后续steal/连续多轮另验 | 本机同一关联的新设备无需再次授权已实证，但不承诺其它ROM/新关联；最近任务仍留Task条目，不自动清最近任务 | helper可自动；授权/输入/主屏仍人工，不复用旧N，不把单轮33分钟写成多轮flaky通过 |
| 15 最终清理 | stop helper；检查工具关联、文件、forward、摄像头数；负责人决定是否手动卸载fixture | helper无进程；标记关联无；aactl-displayd.apk/log与aactl-camera-UUID.img无；工具forward无；数量恢复基线 | helper清理可自动；卸载由负责人，不删除其它关联/文件 |

## 失败证据与回滚

保留触发失败的原始 CLI 信封、N/虚拟设备/流诊断、有限时间窗 helper 日志、当时目标屏截图/层级（脱敏）、动作时间线和指纹，禁止用重采结果冒充失败现场。FRAME_NOT_READY、DEVICE_LOCKED、DISPLAY_ASLEEP、权限错误与 VirtualDevice Activity 拦截分别记录。

用户锁定、离线或身份变化时守卫应拒绝继续操作；只报告残留清单，待负责人恢复授权状态再清理。守卫仍通过时停止本工具 helper，精确清理工具关联/图片/forward并验证数量。不得 disassociate-all、杀未知进程、操作负责人应用、重启设备或修改安全设置。不要放宽颜色、落点或焦点判据；脚手架原因独立修复并重跑新的完整轮次。

新fixture待负责人重装后重点验：断开/错误后最多3次500/1000/2000ms重连，次数与最终原因可见；成功回调不重置预算、暂停取消待执行重试；重发启动到已在顶部的实例可由用户开启新预算；标题/系统栏不遮挡状态、预览严格4:3不拉伸。不要通过截断dump制造故障，使用隔离测试环境或既有自然断流观察；helper的availability只报告观察到的失效，不自动重建。高分辨率、steal读回、IME、凭据锁息屏、第三方应用、多轮统计、模拟器四项保持待验。
