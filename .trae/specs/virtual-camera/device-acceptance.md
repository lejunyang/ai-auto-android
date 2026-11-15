# 负责人/集成方真机端到端验收

本次 Agent **没有执行**以下安装、授权、应用启动、输入或主屏息屏步骤。负责人需亲自完成敏感授权与安装确认，集成方陪同执行其余步骤。只使用本仓库 debug fixture，不下载第三方 APK，不接触负责人应用或数据；不把脚本成功当成 Camera2 画面成功。

## 环境与停止条件

明确 serial `324462538298`，NX888J/API36/arm64；预期指纹见 docs/virtual-camera.md。ADB 每次操作前检查 `adb devices -l` 只有该在线 serial，并核对 transport、指纹和当前用户0。通过 `dumpsys trust` 的当前用户 `deviceLocked=0` 确认未锁定；离线、身份变化、凭据锁定或状态未知时立即停止，不重试、不唤醒、不解锁。所有命令显式带 serial 或 `--device`，所有虚拟屏动作显式带本次 N。

记录 host OS、CLI/helper/fixture SHA-256、APK package/version/ABI/source（仓库本地 debug 构建）、设备指纹、语言/密度/导航模式和开始时间。证据保存在仓库外，截图/层级须限额脱敏，完成验收后按项目策略清理，不提交二进制或原始 UI 数据。

## 独立 helper 检查（可自动）

没有其它 helper 或工具标记关联时，运行：

```powershell
node scripts/verify-virtual-camera-device.mjs --device 324462538298 --adb PATH_TO_ADB_EXE --aactl PATH_TO_AACTL_EXE --helper PATH_TO_DISPLAYD_APK --evidence NEW_DIRECTORY_OUTSIDE_REPO
```

脚本逐步输出目标、argv、预期与结果，建立基线；先记录普通helper启动/初始化/停止的缓存状态，再验证isolated/steal的注册、显示归属、实际标志、removeMode=1、空屏观察、两次图片接收、关闭和最终恢复。脚本不会安装/启动应用、申请/授予权限、输入或切换主屏。`report.ok`仅代表这些helper判据通过；空屏FRAME_NOT_READY明确记录，不是读回通过。执行完须确认`cleaned:true`，然后才开始下表。

清理清单同时记录`/data/local/tmp/dalvik-cache`与`arm64`的存在性、inode、权限、mtime/ctime和子项。启动前已存在的共享目录保持原样；本机系统uiautomator包装脚本会重定向ANDROID_DATA并使用它，不归helper删除。最终`sharedRuntimeCache`必须与基线一致；变化则保留并报告负责人，不擅自递归删或rmdir。stop部分失败在`error.details.cleanup`公开每类结果，需分别检查helperFiles、cameraAssociation、cameraImages。

## 有应用的分步验收

表中“人工”不是自动化脚本中的确认开关。负责人亲自执行，Agent 不代点；集成方先观察再执行一个动作，再核对后置条件。

| 步骤 | 目标 / 动作 | 预期与判据 | 执行者与人工原因 |
| --- | --- | --- | --- |
| 1 安装 | 负责人安装本次本地 `android/device-fixture/build/outputs/apk/debug/device-fixture-debug.apk`，确认包 dev.aiauto.fixture 与摘要 | 安装完成且版本/签名匹配；无需打开任何负责人应用 | **人工**：真机安装确认不允许自动执行；不使用 -g，不预授权限 |
| 2 基线 | 只读 `dumpsys media.camera`、`cmd companiondevice list 0`、进程/forward 列表 | 总/normal/public/virtual provider 各有基线；其它关联原样保留 | 集成方可自动只读；每步仍守卫身份/未锁定 |
| 3 启动 | `aactl display start --device 324462538298 --virtual-camera --helper PATH --json` | v2、virtualCamera:true；只有工具精确关联新增 | 集成方可执行 helper 操作 |
| 4 创建 | `aactl display create --device 324462538298 --width 720 --height 1280 --dpi 240 --focus isolated --camera back --json` | 记录 N/virtualDeviceId/cameraId/实际标志；注册数量+1，无 SECURE | 集成方可执行；N 从响应取，不填主屏0 |
| 5 打开相机页 | 负责人自行运行 `aactl action launch --device 324462538298 --display N --package dev.aiauto.fixture --activity .CameraActivity --json` | 该屏出现 fixture 权限框或相机页；不得向显示0重试 | **人工**：当前 Agent 真机授权范围不包含应用启动；只启动新装 fixture |
| 6 CAMERA 授权 | 查看 `aactl observe screenshot/hierarchy --device 324462538298 --display N` 的最新系统权限框；负责人自己选择允许相机使用。可在可靠展示时，按最新节点/图像自己执行一次 `aactl action tap --device 324462538298 --display N --x X --y Y` | 相机页不再报 PERMISSION_REQUIRED/DENIED；元数据记录 actual id、facing=back、visible 列表与 display=N | **人工**：按虚拟设备单独授权；禁止 pm grant、appops、自动点击和未知坐标；无法可靠展示则停止，由负责人可见界面处理 |
| 7 占位读回 | 未设图片，观察 fixture 文本与 helper status | `CAMERA:TL=black,TR=black,BL=black,BR=black`；streams 非空、frames 递增且 error=null，预览黑色 | 集成方可只读采样；最多在10秒预算内轮询观察，不重发启动/授权动作 |
| 8 横图 | 用 helper 检查生成的 quadrants.png 执行 `aactl camera set-image --device 324462538298 --display N --image PATH --json` | 10秒内至少3次独立层级样本含 `CAMERA:TL=red,TR=green,BL=blue,BR=white`，预览对应方向无镜像；helper frames 持续增长 | 图片设置与只读采样可自动；imageSet:true 本身不算读回成功 |
| 9 运行中切换 | set-image 换本脚本生成的 portrait.png，再换横图；第二张顺序反转，避免把旧帧误当切换成功 | 竖图10秒内3次文本为 `CAMERA:TL=white,TR=blue,BL=green,BR=red`；保持比例、居中、左右黑边；切回横图恢复原顺序 | 可自动设置/采样，负责人核对预览留边；仅四中心文本不能证明全部边缘几何；早期helper探测生成的旧竖图不用于此判据 |
| 10 分辨率 | 用 fixture 的固定640×480请求验收；1280×720/1920×1080 当前只有输入配置声明，需后续扩展测试客户端请求不同输出 | 每种实际请求都要有流尺寸、帧数、ImageReader 颜色证据；未执行的尺寸写未验 | 后续测试扩展独立提交，不把配置声明当实际三档读回 |
| 11 两屏焦点与IME | 负责人在无敏感数据的输入场景建立基线；依次对本次虚拟屏测试页进行可见操作，记录顶层焦点/IME客户端显示编号和输入结果 | isolated 与 steal 分别记录主屏是否被打断、虚拟屏是否有IME；不预设旧普通屏结论 | **人工**：本次 Agent 禁止主屏输入或操作负责人应用；不采集真实文本 |
| 12 主屏息屏 | 负责人自己通过物理按键使主屏息屏；集成方只读取 N 的 hierarchy/screenshot/status。若负责人明确继续输入验收，由负责人在 fixture 的无敏感输入页定向操作并观察精确落点 | 记录每种策略的层级可用性、非黑帧、动作后结果；无输入能力或窗口不可用如实记限制，不 fallback0 | **人工**：Agent 禁止主屏息屏/唤醒/解锁及未授权输入；出现凭据锁立即停止，不继续摄像头/帧操作 |
| 13 流关闭 | 负责人退出 fixture 相机页；只读 helper status | 流关闭后 streams 为空，帧绘制停止；无残留相机客户端 | 页面导航由负责人；只读状态可自动 |
| 14 关闭 / 重复 | close N，确认虚拟相机消失；用 steal 重做步骤4–13，每种策略至少连续3次；关闭后新设备的CAMERA授权可能需重新由负责人完成 | 相机数回到基线，每次循环有原始通过/失败计数和耗时；失败先留证据，不自动重试动作 | helper可自动；安装/授权/输入/主屏切换仍人工，不复用旧N或伪称授权持久 |
| 15 最终清理 | stop helper；检查工具关联、文件、forward、摄像头数；负责人决定是否手动卸载fixture | helper无进程；标记关联无；aactl-displayd.apk/log与aactl-camera-UUID.img无；工具forward无；数量恢复基线 | helper清理可自动；卸载由负责人，不删除其它关联/文件 |

## 失败证据与回滚

保留触发失败的原始 CLI 信封、N/虚拟设备/流诊断、有限时间窗 helper 日志、当时目标屏截图/层级（脱敏）、动作时间线和指纹，禁止用重采结果冒充失败现场。FRAME_NOT_READY、DEVICE_LOCKED、DISPLAY_ASLEEP、权限错误与 VirtualDevice Activity 拦截分别记录。

用户锁定、离线或身份变化时守卫应拒绝继续操作；只报告残留清单，待负责人恢复授权状态再清理。守卫仍通过时停止本工具 helper，精确清理工具关联/图片/forward并验证数量。不得 disassociate-all、杀未知进程、操作负责人应用、重启设备或修改安全设置。不要放宽颜色、落点或焦点判据；脚手架原因独立修复并重跑新的完整轮次。
