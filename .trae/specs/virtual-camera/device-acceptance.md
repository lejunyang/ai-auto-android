# 负责人/集成方真机端到端验收

2026-11-15 20:45–21:51 JST的640×480/isolated读回及22:35–23:06 JST的复验由集成方与负责人完成，原始证据在仓库外，摘要见[progress](progress.md#integrator-recheck-20261115)及docs/virtual-camera.md。负责人自行安装新版fixture（SHA256 1dc42ad123f249f33fbcf5ab8f53ce08a278db01de7b7abbb7bbaaf433085adb），读回、文本可见及4:3预览已验；断流后自动重连仍未验。本轮Agent仅登记文档，不安装、不修改权限、不接触设备。

## 环境与停止条件

明确 serial `324462538298`，NX888J/API36/arm64；2026-11-19集成方复验已核对的指纹为nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6956:user/release-keys，旧b6416仅是历史证据。ADB 每次操作前检查 `adb devices -l` 只有该在线 serial，并核对 transport、指纹和当前用户0。通过 `dumpsys trust` 的当前用户 `deviceLocked=0` 确认未锁定；离线、身份变化、凭据锁定或状态未知时立即停止，不重试、不唤醒、不解锁。所有命令显式带 serial 或 `--device`，所有虚拟屏动作显式带本次 N。

记录 host OS、CLI/helper/fixture SHA-256、APK package/version/ABI/source（仓库本地 debug 构建）、设备指纹、语言/密度/导航模式和开始时间。证据保存在仓库外，截图/层级须限额脱敏，完成验收后按项目策略清理，不提交二进制或原始 UI 数据。

**禁止设备端截断相机dump**（如`dumpsys media.camera | head`、提前关闭设备端管道）。本机曾因此使虚拟服务死亡，甚至物理0–4也移除重加。须完整写电脑文件，命令成功结束后才在电脑端过滤；不要为验证故意触发故障。脚本已采用完整stdout文件，无输出缓冲上限/设备端过滤或提前终止。若服务异常先保留完整证据；系统仍响应且流可排空才close后重建。若help超时/cameraserver残留失效虚拟相机，停止重建尝试，交负责人重启手机；产品不自动恢复/重启。出流时禁止kill helper，崩溃也可能触发A16死锁。

## 独立 helper 检查（可自动）

没有其它 helper 或工具标记关联时，运行：

```powershell
node scripts/verify-virtual-camera-device.mjs --device 324462538298 --adb PATH_TO_ADB_EXE --aactl PATH_TO_AACTL_EXE --helper PATH_TO_DISPLAYD_APK --evidence NEW_DIRECTORY_OUTSIDE_REPO
```

脚本先记录普通helper与缓存，再验证两策略注册、标志/removeMode、图片接收与清理。默认不启动应用；前轮用`--launch-fixture true`仅在自建isolated屏按包名启动已装dev.aiauto.fixture并断言PAGE:MAIN。该选项当前固定核对旧APK的05ade33a…摘要，本次1dc42ad…新版结果属于集成方另行验收，不表示脚本已支持新版摘要，不放宽身份校验。权限框出现即停，绝不点按；不安装、不授予权限、不输入或切换主屏。`report.ok`仅代表这些判据通过；helper空屏FRAME_NOT_READY不是相机读回通过。须确认`cleaned:true`。

清理清单同时记录`/data/local/tmp/dalvik-cache`与`arm64`的存在性、inode、权限、mtime/ctime和子项。启动前已存在的共享目录保持原样；本机系统uiautomator包装脚本会重定向ANDROID_DATA并使用它，不归helper删除。最终`sharedRuntimeCache`必须与基线一致；变化则保留并报告负责人，不擅自递归删或rmdir。stop部分失败在`error.details.cleanup`公开每类结果，需分别检查helperFiles、cameraAssociation、cameraImages。

**默认stop后新关联须由负责人重新授予虚拟CAMERA。** 默认stop删除关联，start新建（本机7→8→9）；同一helper/关联内close/create不需重授。现在可显式`aactl display stop --device SERIAL --keep-camera-association`保留，再start复用；只读核对`adb -s SERIAL shell cmd companiondevice list USER_ID`中com.android.shell+00:00:00:00:00:10及编号。无匹配省略keptCameraAssociation，查询失败分类可见且其它清理继续；普通模式运行helper忽略开关、不查关联。

保留期间设备常驻一条shell的COMPANION_DEVICE_APP_STREAMING伴生关联及负责人此前授予目标应用的虚拟设备CAMERA授权。归还/转手前必须不带开关`aactl display stop --device SERIAL`移除（无helper亦可；普通helper运行时先停、再默认stop）。默认独立helper脚本仍会清关联。产品不代授权/点框、不disassociate-all；启动失败只移除本次新建关联，既有/崩溃遗留保留，错误说明结果。**2026-11-19集成方在b6956单机验证关联10及虚拟CAMERA授权跨手机/helper重启保留，无新权限框且实际读回；不是用模拟器或同关联重建屏替代。** 仅此单机证据，不保证其它ROM或新关联继承。

## 有应用的分步验收

表中“人工”不是自动化脚本中的确认开关。负责人亲自执行，Agent 不代点；集成方先观察再执行一个动作，再核对后置条件。

| 步骤 | 目标 / 动作 | 预期与判据 | 执行者与人工原因 |
| --- | --- | --- | --- |
| 1 安装 | 负责人安装本次本地 `android/device-fixture/build/outputs/apk/debug/device-fixture-debug.apk`，确认包 dev.aiauto.fixture 与摘要 | 安装完成且版本/签名匹配；无需打开任何负责人应用 | **人工**：真机安装确认不允许自动执行；不使用 -g，不预授权限 |
| 1a 默认设备CAMERA | 负责人在系统设置的应用权限页给fixture默认设备相机权限；已授予则保持，不为测试撤销重授 | 记录默认设备granted=true及CAMERA Uid mode foreground；这是本机33分钟稳定运行前提之一 | **人工**：系统设置及敏感权限只能负责人处理，Agent不打开设置/点权限/pm grant/appops |
| 2 基线 | 完整取回 `dumpsys media.camera` 到主机后过滤，另读关联、进程/forward | 总/normal/public/virtual provider有基线；不设备端head/提前关管道，其它关联保留 | 可自动完整读取；每步守卫身份/未锁定 |
| 3 启动 | `aactl display start --device 324462538298 --virtual-camera --helper PATH --json` | v2、virtualCamera:true；只有工具精确关联新增。stop后的新关联不会继承旧虚拟设备授权，需在步骤6由负责人再授权 | 集成方可执行helper操作，不代授权限 |
| 4 创建 | `aactl display create --device 324462538298 --width 720 --height 1280 --dpi 240 --focus isolated --camera back --json` | 记录 N/virtualDeviceId/cameraId/实际标志；注册数量+1，无 SECURE | 集成方可执行；N 从响应取，不填主屏0 |
| 5 打开相机页 | 集成方陪同负责人运行 `aactl action launch --device 324462538298 --display N --package dev.aiauto.fixture --activity .CameraActivity --json` | 出现fixture相机页或虚拟设备权限框；不向显示0重试。集成方已验新版读回/布局；helper重启未保留关联而产生的新权限框仍需负责人处理 | 新fixture安装与接下来的授权由负责人处理；Agent若遇权限框立即停，不代点 |
| 6 虚拟设备CAMERA | 查看同屏最新截图/层级的系统权限框，由负责人自己决定并按可靠节点/坐标点按；同关联已授权则不动，helper重启新关联须重新授权 | 与默认设备授权一起记录；只授虚拟设备曾在21秒/约3分钟被restricted断流，默认授权后稳定33分钟。元数据应为id0/back/orientation0/visible0/display=N | **人工**：第二处单独授权；禁止Agent自动点击/pm grant/appops。推断后续复查可能按默认设备判定，不写成已证机制 |
| 7 占位读回 | 未设图片，观察 fixture 文本与 helper status | `CAMERA:TL=black,TR=black,BL=black,BR=black`；streams 非空、frames 递增且 error=null，预览黑色 | 集成方可只读采样；最多在10秒预算内轮询观察，不重发启动/授权动作 |
| 8 横图 | 用 helper 检查生成的 quadrants.png 执行 `aactl camera set-image --device 324462538298 --display N --image PATH --json` | 10秒内至少3次独立层级样本含 `CAMERA:TL=red,TR=green,BL=blue,BR=white`，预览对应方向无镜像；helper frames 持续增长 | 图片设置与只读采样可自动；imageSet:true 本身不算读回成功 |
| 9 运行中切换 | set-image 换本脚本生成的 portrait.png，再换横图；第二张顺序反转，避免把旧帧误当切换成功 | 竖图10秒内3次文本为 `CAMERA:TL=white,TR=blue,BL=green,BR=red`；保持比例、居中、左右黑边；切回横图恢复原顺序 | 可自动设置/采样，负责人核对预览留边；仅四中心文本不能证明全部边缘几何；早期helper探测生成的旧竖图不用于此判据 |
| 10 分辨率 | 用 fixture 的固定640×480请求验收；1280×720/1920×1080 当前只有输入配置声明，需后续扩展测试客户端请求不同输出 | 每种实际请求都要有流尺寸、帧数、ImageReader 颜色证据；未执行的尺寸写未验 | 后续测试扩展独立提交，不把配置声明当实际三档读回 |
| 11 两屏焦点与IME | 负责人在无敏感数据的输入场景建立基线；依次对本次虚拟屏测试页进行可见操作，记录顶层焦点/IME客户端显示编号和输入结果 | isolated 与 steal 分别记录主屏是否被打断、虚拟屏是否有IME；不预设旧普通屏结论 | **人工**：本次 Agent 禁止主屏输入或操作负责人应用；不采集真实文本 |
| 12 主屏息屏 | 负责人自己通过物理按键使主屏息屏；集成方只读取 N 的 hierarchy/screenshot/status。若负责人明确继续输入验收，由负责人在 fixture 的无敏感输入页定向操作并观察精确落点 | 记录每种策略的层级可用性、非黑帧、动作后结果；无输入能力或窗口不可用如实记限制，不 fallback0 | **人工**：Agent 禁止主屏息屏/唤醒/解锁及未授权输入；出现凭据锁立即停止，不继续摄像头/帧操作 |
| 13 流关闭 | 负责人退出 fixture 相机页；只读 helper status | 流关闭后 streams 为空，帧绘制停止；无残留相机客户端 | 页面导航由负责人；只读状态可自动 |
| 14 关闭 / 重复 | close N并观察Activity销毁且显示0未出现fixture；后续steal/连续多轮另验 | 本机同一helper/关联内的新设备无需再次授权已实证；stop/start换关联后须负责人重授。最近任务仍留Task条目，不自动清最近任务 | helper可自动；授权/输入/主屏仍人工，不复用旧N，不把单轮33分钟写成多轮flaky通过 |
| 15 最终清理 | stop helper；检查工具关联、文件、forward、摄像头数；负责人决定是否手动卸载fixture | helper无进程；标记关联无；aactl-displayd.apk/log与aactl-camera-UUID.img无；工具forward无；数量恢复基线 | helper清理可自动；卸载由负责人，不删除其它关联/文件 |

## 失败证据与回滚

保留触发失败的原始 CLI 信封、N/虚拟设备/流诊断、有限时间窗 helper 日志、当时目标屏截图/层级（脱敏）、动作时间线和指纹，禁止用重采结果冒充失败现场。FRAME_NOT_READY、DEVICE_LOCKED、DISPLAY_ASLEEP、权限错误与 VirtualDevice Activity 拦截分别记录。

用户锁定、离线或身份变化时守卫应拒绝继续操作；只报告残留清单，待负责人恢复授权状态再清理。守卫仍通过且可安全排空时，停止helper后再清理工具资源。BUSY必须保留helper/文件/图片/关联，先由操作员让应用释放相机再重试；禁止kill helper或自行force-stop应用。系统已卡死时停止操作，交负责人重启，不能把保留资源写成清理通过。不得 disassociate-all、杀未知进程、操作负责人应用、重启设备或修改安全设置。不要放宽颜色、落点或焦点判据；脚手架原因独立修复并重跑新的完整轮次。

新版fixture已由负责人安装，集成方读回横/竖图正确，状态文本bounds为[0,0][720,29]与[0,29][720,82]且截图可见；预览[0,411][720,951]为720×540（4:3），retries=0/3。正常关闭/重开相机两轮availability始终available、reason=null，每次重开均正确读回；按包名在屏67启动读到PAGE:MAIN。

仍待验：断开/错误后最多3次500/1000/2000ms自动重连、最终原因/次数、成功回调不重置预算与暂停取消；restricted强制断流下availability是否误报。当前只有自动重连纯逻辑单测覆盖，真机未制造强制断流；不要截断dump、撤销权限或绕过授权来触发。高分辨率、steal带应用读回、IME、凭据锁息屏、第三方应用、多轮统计仍待验。模拟器四项已执行，MCP为2/3通过、1次超时，[失败及未证归因](progress.md#mcp-cold-start-failure-20261115)保留，冷启动稳定规则和多轮统计待补。

## 保留关联真机步骤（b6956单机已验，本轮仅记录）

| 目标 | 动作 | 预期与判据 / 人工原因 |
| --- | --- | --- |
| 已授权基线 | 负责人完成两处CAMERA，记录用户/工具关联编号并读回横图 | 记录实际关联与读回；敏感权限仅负责人处理，不预置或撤销 |
| 保留停止 | stop --keep-camera-association后完整只读list并检查helper/文件/图片/forward | 返回实际保留编号且与基线相同，其它工具资源清理；无关联不能报已保留 |
| 复用重启与授权 | start --virtual-camera，重建摄像头屏，负责人打开相机页 | 编号不变、无新权限框且实际颜色读回才算免重授通过；若弹框则如实失败，由负责人决定授权，Agent不代点 |
| 显式移除 | 不带开关stop；若普通模式helper在运行，先停后再默认stop | 工具关联消失、其它关联不变、工具文件/forward无、摄像头回基线；归还/转手前必须做 |

上述授权持久性已由集成方于2026-11-19在b6956单机验证：手机重启后复用关联10，屏2无GrantPermissionsActivity且三次横图读回；屏3出流中stop --keep-camera-association返回保留10，再次start仍复用10，屏4无权限框且横图读回。授权来自2026-11-18负责人对话明确授权集成方代点“使用期间允许”，本轮不新增授权。最终默认stop移除关联10，详见[完整复验](progress.md#b6956-recheck-20261119)。

## 出流关闭与BUSY恢复复验（单屏三种拆除各1次已验，BUSY仍待验）

本轮Agent不执行任何设备命令。2026-11-18发生系统卡死后，负责人已自行重启，
2026-11-19集成方核对b6956基线并完成下述单屏复验，关联10跨手机重启保留、最终默认stop移除。
后续每轮仍须在实际指纹/ROM上建立独立基线，现有helper脚本若仍固定旧b6416指纹，
不能直接照跑或去掉守卫；由集成方独立核对更新后执行，不将旧ROM通过外推新ROM。

| 步骤/目标 | 动作 | 预期与判据 / 人工边界 |
| --- | --- | --- |
| 恢复系统基线 | 负责人重启手机；集成方只读核对b6956身份、完整相机dump及服务响应 | 真实/虚拟相机与关联各自记录；不能用closed无client断言真实相机功能正常。手机重启只由负责人做 |
| 出流关闭 | fixture正常授权/四色读回后，应用仍出流时close原N | 显示先销毁，帧继续至onStreamClosed，流空后注销；无ANR/死锁、helper仍响应才算通过。BUSY也不得kill |
| 观察待释放 | 若BUSY，读取list/status | 原N保留且releasePending=true，仍占容量；state0/组-1/awake=null是无有效显示诊断，不能继续输入/启动/取帧；pendingCameraReleases>0须shutdown释放 |
| 人工释放相机后重试 | 负责人自行退出应用相机；如需`am force-stop <pkg>`，负责人先明确授权集成方执行一次 | 产品/Agent不执行force-stop。流空后同N重复close或stop可完成；失败保留证据，不杀helper、不盲重试 |
| 两屏shutdown | 准备两摄像头屏流，执行stop；另一屏可先成功释放 | 一次共享6秒排空预算，任何BUSY则进程/未完资源保留，Go不清文件/图片/关联；全部安全释放才退出 |
| 保留关联免重授 | 安全stop --keep-camera-association后重启helper并读回 | 编号不变且无新权限框/实际读回才算通过；关联10已在b6956单机验证，见上节 |
| 最终清理 | 仅安全退出后核对进程、屏、文件、forward、相机数量；按事先选择保留/移除精确关联 | BUSY或系统卡死不可伪称cleaned；cmd virtual_camera help超时/失效相机残留则停止，由负责人重启 |

协议新增VIRTUAL_CAMERA_BUSY（retryable=true，CLI退出4），不是PROTOCOL_ERROR；等待所有流
的上限6000ms，Go单次wire为10秒。条件变量等待释放流表锁，出帧不在等待前停；
AndroidBackend仍串行，只读可能等待该局部预算，系统Binder已卡死时没有本地强制中断保证。
2026-11-19集成方以70f8ae3构建在b6956单机实测：屏2出流788帧时close成功756ms（CLI计时864ms）；
屏3出流326帧时带开关stop成功2183ms、保留关联10；屏4出流324帧时默认stop成功2282ms、移除关联10。
三种出流拆除路径各1次未卡死，无ANR、fixture Activity销毁、相机回到5，cmd virtual_camera help退出0。
关闭复验及免重授已勾选；应用均在6秒内释放，未触发BUSY，因此BUSY实际触发/人工force-stop后同编号恢复、
两块摄像头屏同时shutdown及创建失败残留仍未勾选。单次成功不证明A16所有时序均不再死锁。
最终无helper/工具关联/虚拟屏/forward/工具临时文件、fixture无Activity；共享dalvik-cache原样保留，
fixture仍安装且默认设备CAMERA授权保留。环境、产物、日志顺序及完整清理见[复验记录](progress.md#b6956-recheck-20261119)。
