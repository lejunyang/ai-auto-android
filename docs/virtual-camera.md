# 虚拟屏静态图片摄像头

操作员可在 API35+ 且系统支持 VirtualCamera 的设备上，创建虚拟设备拥有的虚拟屏和后置摄像头。普通屏继续使用原 DisplayManager 路径。集成方与负责人已在NX888J/API36验证 **isolated、fixture、640×480单流的GPU出流、图片读回/切换及33分钟运行**；对应的是下述验收产物。高分辨率、steal读回、IME、息屏和第三方应用仍未验证。

## 操作员命令

```bash
aactl display start --device SERIAL --virtual-camera --helper PATH_TO_DISPLAYD_APK --json
aactl display create --device SERIAL --width 720 --height 1280 --dpi 240 --focus isolated --camera back --json
aactl camera set-image --device SERIAL --display N --image PATH_TO_LOCAL_PNG_OR_JPEG --json
aactl display list --device SERIAL --json
aactl display status --device SERIAL --json
aactl display close --device SERIAL --display N --json
aactl display stop --device SERIAL --json
# 可选：停止helper并保留工具关联，供下次start复用
aactl display stop --device SERIAL --keep-camera-association --json
# 只读核对指定用户，精确查com.android.shell + 00:00:00:00:00:10
adb -s SERIAL shell cmd companiondevice list USER_ID
```

`N` 必须取自本次 create 响应，不能是显示 0。`--camera` 目前只有 `back`；省略时仍创建普通屏。启用需要 stop 后以 `--virtual-camera` 重新 start；已有普通 helper 不会被隐式升级。未启用或旧 helper 没有能力位时返回 `VIRTUAL_CAMERA_NOT_ENABLED`，协议仍为 v2。

图片只能为本机普通 PNG/JPEG 文件，文件 ≤ 8 MiB，解码宽高各 ≤ 4096。Go 校验并暂存快照后 push 到 `/data/local/tmp/aactl-camera-UUID.img`；UUID 为随机 v4。helper 只接受该完整模式，拒绝符号链接和非普通文件，按预算解码后立即删除，Go 再清理同一路径。失败也会清理；stop 还会回收精确模式的崩溃遗留图片。不会接受任意设备路径或通配符。

图片按比例居中，余下区域黑色；不会拉伸或裁切。未设置图片时为黑色。支持运行中反复设置；每个摄像头配置 640×480、1280×720、1920×1080 的 YUV_420_888 / 30fps，逐流 EGL 绘制最高约 29.4fps，不按 capture request 无限补帧。流回调关闭才停相应出帧。关闭摄像头屏先销毁显示及Activity，等待流清空后释放摄像头/设备；超时BUSY保留资源继续出帧，见安全拆除说明。图片接收成功只表示内容已替换，不证明应用已经收到帧。

带摄像头的 list/status 屏对象有 `camera:"back"`、`cameraInfo:{cameraId,virtualDeviceId,streams,availability,availabilityReason}`（后两项为新增可选诊断）、实际 `displayFlags`。status 另只读返回系统 DisplayInfo 的 `removeMode`；1 表示销毁内容，缺省/null 表示未能读取、不能计为验证通过。`streams` 包含 `streamId,width,height,frames,error`；空数组表示当前无流。`cameraId` 是框架注册标识，应用在设备上下文看到的编号应以 fixture 的 `CAMERA_DEVICE` 文本为准。普通屏省略摄像头及removeMode字段，表示不带摄像头。尺寸 128..2048、dpi 72..640、最多两块屏的总预算保持。

`cameraInfo.availability` 为保守诊断：`available`表示注册成功且服务监视已建立、尚未观察到失败；`unknown`表示未能建立监视（旧helper缺字段也按未知处理）；`unavailable`表示观察到服务Binder死亡或真实流渲染失败，`availabilityReason`说明来源。旧`cameraId`仍保留作注册历史，不能据此认定它仍存在。Binder存活不等于每个相机仍注册，更不等于目标应用权限或读回通过；该字段不是完整CameraManager活性探测。失效状态保持到操作员关闭屏后重建，helper不自动重建或重试。

## MCP 与权限

`android_display_create` 在原有 `device,width,height,dpi,focus` 参数之外接受可选 `camera:"back"`，仍经过显示管理风险门。例：

```json
{"device":"SERIAL","width":720,"height":1280,"dpi":240,"focus":"isolated","camera":"back"}
```

MCP 不提供 helper 启停、图片设置、主机路径或权限授予入口，工具总数仍为 20。`risk.go`、`bridge_action.go`、`file_transfer.go` 的风险门未改变。

**本机稳定使用需要目标应用在默认设备与虚拟设备两处都有CAMERA授权。产品不代授权，也不自动点系统权限框。** 负责人先在系统设置里给目标应用默认设备的相机权限，再在虚拟屏相机页的系统框中完成虚拟设备授权。可通过最新`observe screenshot/hierarchy --display N`由负责人自己判断并点按该框；若无法可靠展示或定位则停下人工处理，不使用pm grant/appops旁路。具体产品授权形态仍待负责人决定；其它ROM需独立验收。

**默认stop删除关联，虚拟设备授权不跨新关联继承。** 本机此前默认stop/start使编号7→8→9，需负责人重新授权；同一helper运行、同一关联内关闭重建屏不需重授。负责人已决定增加可选`display stop --device SERIAL --keep-camera-association`，用现有关联供下一次`start --virtual-camera`复用，默认stop仍移除关联。**已在b6956单台真机验证关联10及虚拟设备CAMERA授权跨手机重启、helper重启保留**：2026-11-19集成方复用关联10，无权限框且实际读回；带开关stop后再次start仍复用10并读回。仅此单机复验，不保证其它ROM或新关联继承，见下方b6956验收。

实测：仅虚拟授权时两次断流（连接约21秒、约3分钟），CameraService日志restricted，Uid mode CAMERA:ignore；负责人在系统设置补默认授权后，granted=true、Uid mode foreground，连续33分钟/主屏切13次应用没有restricted。**推断**：服务后续权限复查可能按默认设备判定，未证明系统内部机制。这是本机系统兼容性前提，不是本项目自动授予或绕过权限的理由。

系统可以因应用的远程显示声明、用户限制或 VirtualDevice Activity 策略拒绝启动；应用需支持远程设备显示。fixture 相机 Activity 显式声明支持；遇到启动失败应保留错误和活动状态，不更改应用声明、不绕过系统策略、不转向显示 0 重试。

显示定向且只提供包名时，CLI固定执行`cmd package resolve-activity --brief --components`加MAIN/LAUNCHER参数，只接受一个同包组件，再显式`am start -W --display N -n`。`--components`用于去掉`--brief`可能附带的元数据；跨包、多行、异常或截断输出均拒绝，不回退主屏。非显示定向启动保持原来的monkey/显式Activity路径。2026-11-15本轮在自建isolated摄像头屏65上，仅按包名启动已安装的旧fixture成功，层级读到同包`PAGE:MAIN`且截图成功，无权限框；这只验证启动修复，不是新增相机画面读回。

## 系统虚拟摄像头服务失效

**禁止在设备端截断`dumpsys media.camera`输出**，包括`| head`、提前关闭管道或其它只读前几行的组合。必须完整重定向到电脑文件，进程成功结束后才在电脑端过滤。仓库验收脚本直接把stdout写主机文件，没有stdout缓冲上限或设备端过滤，也不因读取前几行提前终止命令。

集成方20:55:12截断后观察到`Virtual camera service died`、客户端因HAL状态变化被evicted、虚拟摄像头消失；20:55:43再次截断时真实相机0–4也移除后重加。完整回传再过滤未出现该问题。helper当时仍有旧cameraId，流frames103、`eglSwapBuffers failed EGL=12301`；现在会在观察到服务死亡或渲染失败后报告unavailable。不要为验证诊断主动制造崩溃。仅在系统服务仍响应且已安全释放时，由操作员关闭受影响屏后重建；遇BUSY先让应用释放相机。若virtual_camera已卡死、help超时或cameraserver残留失效相机，不继续关闭/重建尝试，由负责人重启手机恢复；产品不自动重建、重试或重启设备。

## 安全拆除与系统死锁（b6956三种出流拆除路径各1次未卡死）

**helper正在出流时绝不可kill或让其崩溃。** A16设备上外部终止会触发Binder死亡注销，可能卡死系统virtual_camera；Java shutdown hook不能取消外部终止，也不能修复已死锁的系统服务。产品没有force-stop应用、杀helper或自动重试/重建路径。

新的摄像头屏关闭顺序：

1. 保留DESTROY_CONTENT_ON_REMOVAL，先display.release销毁Activity；此时摄像头继续出帧。
2. 最多等待6000ms，直到全部流收到onStreamClosed、流表为空。条件等待释放同一monitor，回调remove后notifyAll；不持锁等待Binder注销。
3. 流空后才发残余线程停止信号（不无限join）、VirtualCamera.close、VirtualDevice.close；最后释放显示取帧资源并移除屏记录。
4. 超时返回VIRTUAL_CAMERA_BUSY，retryable=true、CLI退出4；保留摄像头/设备/图片/出帧和原编号，进程不退出。操作员应让目标应用自行释放相机；必要时由有授权的操作员手动`am force-stop <pkg>`，然后显式重试close/stop。**产品不执行force-stop，不能通过kill helper代替。**

待释放记录仍在list/status、占用两屏容量，cameraInfo.releasePending=true。其显示已进入释放，不能再用作输入/启动/取帧/设置图片目标；仅保留诊断及原编号close重试。status不查询失效DisplayInfo/电源，state=0、displayGroupId=-1、groupAwake=null，displayFlags是创建时实际值，不冒充当前有效显示状态。创建失败若意外有活动流，内部引用继续保留，status.pendingCameraReleases给出无显示编号的残留计数，须用shutdown/stop重试。

shutdown逐屏执行同一流程，允许成功屏先释放；任一BUSY则保留未完成记录、监听和进程，Main不执行System.exit，Go stop不删helper文件、图片或关联（带/不带保留开关均如此）。创建失败回滚遇BUSY也不得删除本次新建关联。Go wire及helper连接预算均10秒；每个请求在进入AndroidBackend全局锁前记录排空deadline，两屏与排队的关闭请求不各自叠加完整6秒。为保留既有串行框架边界，本轮未重构全局锁；其它只读请求可能等待该局部排空预算。已有系统Binder死锁、框架release调用阻塞、主机调度或更短调用方deadline仍可能造成超时，不能把局部6秒等待上限当成系统调用可被中断的保证。

2026-11-18 23:05–23:30 CST，集成方在更新到b6956（构建2026-11-16）的NX888J上确认三次640×480四色读回、525帧/available、主屏焦点0；应用出流中close屏2后，显示移除与NOT_PRESENT紧邻，fixture ANR，cameraserver/出帧/worker卡Binder。close和stop均响应头超时。集成方留证并终止已核验helper后服务仍不恢复，timeout 8 cmd virtual_camera help退出124、失效v1_1000残留使相机数为6；真实0–4当时closed且无client。随后普通屏fixture相机重试耗尽、首次失败原因未取得，不能判断真实相机已坏或正常。最终无helper/显示/forward/工具文件，关联10保留；该事故后负责人已自行重启，2026-11-19集成方使用70f8ae3构建复验，三种出流拆除路径各1次未卡死，关联10及授权跨手机/helper重启保留，见下方记录；单次成功不能证明A16全部时序不再死锁。完整事实、授权执行者与制品摘要见[progress](../.trae/specs/virtual-camera/progress.md)。

读源码：本轮核对仓库外16_main.cc（标记android-16.0.0_r1）无startThreadPool，a17_main.cc有；16版Controller/Service/Provider同步注销并发送NOT_PRESENT，VirtualDevice.close及CameraDescriptor.binderDied也走注销；q2_Camera3Device补充样本显示disconnectImpl持mInterfaceLock并可能join请求线程。**推断**：活动会话注销时HAL/cameraserver/应用和帧队列形成环形等待，与现场线程栈相符；不能仅凭上游快照断言OEM二进制的全部内部机制。A17源码改动不等于已回补此A16设备。本轮在线地址打开失败，核对依据是提供的本地源码快照，不提交源码副本。

## 关联与安全边界

只有操作员 `display start --virtual-camera` 会为当前前台用户建立固定 `com.android.shell` + `00:00:00:00:00:10`、profile `android.app.role.COMPANION_DEVICE_APP_STREAMING` 的伴生关联；通过系统 list 读回编号。相同标记遗留可复用，重复或不可解析的匹配拒绝处理。标记地址为本工具保留，不应手工用于其它用途。

默认停止时只按精确包名和地址移除工具关联；不使用 disassociate-all。正常 helper 通过 hello 返回启动用户，停止时按该用户清理；helper 崩溃后的 stop 使用当前用户，**崩溃后切换过 Android 用户时需回到启动用户清理**。不自动切换系统用户。进程身份、socket inode/fd、APK 摘要与临时 forward 核对沿用现有实现。

`--keep-camera-association`仅CLI的stop接受，支持裸开关或`=true`，拒绝`=false`、重复或其它子命令携带。摄像头模式或helper未运行时只做一次只读list：精确匹配到关联才返回`keptCameraAssociation:{"userId":0,"id":9}`；无匹配省略字段，查询失败在原有`error.details.cleanup.cameraAssociation`公开。文件/UUID图片清理仍独立尝试并合并失败；若其它清理失败而已查询到保留编号，编号同时出现在error.details。普通模式运行helper时忽略开关，不执行任何companiondevice命令，也不返回保留字段；其它普通清理保持原样。

保留期间设备常驻一条`com.android.shell`的`COMPANION_DEVICE_APP_STREAMING`伴生关联，以及负责人此前授予目标应用的虚拟设备CAMERA授权。**归还/转手前执行`aactl display stop --device SERIAL`（不带开关，helper未运行也可）移除保留关联**；若普通模式helper正在运行，先停止它，再在helper未运行时执行一次默认stop。只读检查用`adb -s SERIAL shell cmd companiondevice list USER_ID`，只认`com.android.shell`+`00:00:00:00:00:10`那一条，Max ID不是关联。崩溃后切换过用户需回到启动用户，不动其它关联；绝不disassociate-all、不代授权或代点权限框。

2026-11-16本轮在API35指定模拟器完整跑一次：关联编号1，带开关stop后仍1且helper/临时文件为0，重启仍复用1，默认stop后无关联；最终所有工具资源归零、模拟器退出且ADB为空。该模拟器结果仅证明编号生命周期，不证明真机CAMERA授权跨helper重启；独立的b6956真机授权持久性证据见下方2026-11-19复验。

启动失败语义有一处明确变化：EnsureCameraAssociation仍复用精确既有关联，Created只标记本次新建。后续失败先保留关联停止可核验helper，确认安全停止后才清文件/图片和本次新建关联；BUSY或未确认停止则连本次新建关联也保留，防止间接触发活动设备注销；调用前存在的关联（包括崩溃遗留）改为原样保留，原先会删除。错误详情cameraAssociation.action为kept/removed/removeFailed并保留原启动错误/清理错误。默认stop的命令序列、JSON和删除行为不变，不能把启动失败变化含糊为“全部默认行为不变”。

helper 未运行时，stop 仍尝试普通文件清理，然后独立尝试相机关联和UUID图片清理；关联服务不可用或格式未知不能阻止其它清理。普通运行中的非摄像头 helper 停止不调用 companiondevice。默认stop全部成功时仍返回 `{"stopped":true}`；部分失败返回错误，`error.details.stopped=true` 表示进程已停止，`error.details.cleanup` 分列 `helperFiles`、`cameraAssociation`、`cameraImages` 的 `ok/error`。所有清理错误合并保留，不静默吞掉关联残留，也不把进程停止等同于全部清理成功。forward仍逐调用清理，不删除来源未知的forward。

## 显示标志审查修正

DisplayManager 的**请求**标志与 Display.getFlags 的**结果**标志不同：请求 `DESTROY_CONTENT_ON_REMOVAL=0x100`、`OWN_DISPLAY_GROUP=0x800`；结果 `OWN_DISPLAY_GROUP=0x100`。旧实现误删请求的0x100，原“已去掉OWN_DISPLAY_GROUP”的说法撤回，历史progress仅追加更正。修复后 CameraPolicy.displayFlags 纯函数强制保留销毁位、排除请求SECURE；两套具名常量分别维护，焦点也按结果命名空间读取。

2026-11-15在同一NX888J/API36、唯一serial、未锁定条件下，使用只差请求OWN_DISPLAY_GROUP的两个本地构建，各创建两种focus空屏并读取真实状态：

| 请求OWN_DISPLAY_GROUP | focus | 显示 / 设备 | 实际displayFlags | 显示组 | groupAwake | removeMode |
| --- | --- | --- | --- | --- | --- | --- |
| 保留（产品） | isolated | 57 / 10 | 24456 / 0x5f88 | 1 | true | 1 |
| 保留（产品） | steal | 58 / 11 | 20360 / 0x4f88 | 1 | true | 1 |
| 去掉（临时实验） | isolated | 59 / 12 | 24200 / 0x5e88 | 2 | true | 1 |
| 去掉（临时实验） | steal | 60 / 13 | 20104 / 0x4e88 | 3 | true | 1 |

四个组合均可创建且无SECURE；没有反对保留方案的实测证据，因此按普通屏既有请求策略**保留OWN_DISPLAY_GROUP**。这不表示其显示组由VirtualDevice分配。临时实验没有产品开关，源码已恢复。该对照轮次仅以removeMode=1确认系统策略，没有应用生命周期测试；随后集成方在isolated的fixture上验证了Activity销毁且没有迁移显示0，见下节。

## 共享缓存目录来源与保留

本机启动前已存在 `/data/local/tmp/dalvik-cache`（777、shell:shell，inode396399）与其空子目录 `arm64`（700，inode478273），两者mtime/ctime均1794735523，即2026-11-15 18:38:43 JST。普通helper启动/初始化/停止、摄像头helper启动、两种显示组各两策略创建/关闭/停止的对比均保持存在性、inode、权限和mtime不变。

真机 `/system/bin/uiautomator` 包装脚本明确在非root时使用run_base=/data/local/tmp，`mkdir -p` 创建dalvik-cache，并将ANDROID_DATA改为run_base后执行app_process；ART会为该重定向缓存创建0700的架构子目录。helper直接启动时ANDROID_DATA=/data，源码没有创建这两条目录。旧mtime还恰在上一轮首次hierarchy（18:38:42–45）之间。证据指向系统UIAutomator层级命令（本工具与其它调用者共用），不是摄像头helper特有缓存；不能仅凭mtime唯一归属历史调用者。

**现有共享目录不删除，交负责人决定是否清理。** stop只删除工具固定文件和精确UUID图片，不擅自rmdir这些启动前已存在的目录。验收脚本已将两条路径加入逐阶段及最终残留检查，并在`sharedRuntimeCache`公开原样保留；若从不存在变为存在或元数据变化，会保留并报告，不能声称清理通过。没有递归删除、通配符或“空目录即归工具”的规则。框架创建机制见[AOSP ART GetDalvikCache](https://android.googlesource.com/platform/art/+/ed0dcade7b/libartbase/base/file_utils.cc)。

凭据锁定保护、目标无效不回退主屏、不含 SECURE、安全窗口系统遮黑、每 2 秒按虚拟显示组保活保持。摄像头创建和设置图片也检查凭据锁。锁定或状态未知时停止，不唤醒、不解锁、不自动重发输入。

## 既有helper空屏证据

真机指纹 `nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6416:user/release-keys`，API36、arm64-v8a、当前用户0；2026-11-15 唯一设备、凭据未锁定。以下均是**无应用的空屏**：

| 项目 | isolated | steal |
| --- | --- | --- |
| 显示 / 虚拟设备（修复后复跑） | 61 / 14 | 62 / 15 |
| 注册相机 | v14_1014 | v15_1015 |
| 实际 Display.getFlags | 24456（0x5f88） | 20360（0x4f88） |
| focusIsolated / SECURE | true / 无 | false / 无 |
| 顶层焦点 | 仍为 0 | 仍为 0（空屏无窗口，不能外推应用启动后行为） |
| 显示组 / state / groupAwake | 1 / 2 / true | 1 / 2 / true |
| 系统removeMode（修复后读取） | 1，销毁内容 | 1，销毁内容 |
| 主屏亮屏时层级 / 截图 | DISPLAY_EMPTY / FRAME_NOT_READY | DISPLAY_EMPTY / FRAME_NOT_READY |
| 两次图片接收 | 横图、竖图均 imageSet:true | 横图、竖图均 imageSet:true |
| 实际流 / 应用预览 | 无流，未验 | 无流，未验 |
| 输入法、主屏息屏层级/取帧/输入 | 待负责人验收 | 待负责人验收 |

每次创建总摄像头 5→6、normal 2→3、public API1 保持2、virtual provider 0→1；每次关闭与最终 stop 都恢复 5/2/2/0。无应用时截图没有合成帧是实际观察，未计为截图通过。不能沿用普通虚拟屏在 API35 上的输入法/息屏结论。

完整逐步记录见[本规格进度](../.trae/specs/virtual-camera/progress.md)，负责人后续执行[真机端到端验收](../.trae/specs/virtual-camera/device-acceptance.md)。helper 专用自动脚本为 `scripts/verify-virtual-camera-device.mjs`，只执行本工具允许的操作；需要显式 adb.exe/aactl.exe 路径和仓库外新证据目录。

## 集成方与负责人端到端证据（20:45–21:51 JST）

2026-11-15，同一NX888J/API36/arm64指纹、用户0且全程未锁定。负责人自行安装本地debug fixture、未用-g，并亲自完成两处授权；集成方操作和采证，原始证据仓库外。对应SHA256：CLI `9ae875f4cb26694b202b22353b27cabd8dc1359ef47686ebfb9037ba95aab0fc`，helper `a7bbe34eabac1fb38c3eaea9ddd99b44f9c73bd6bf2f66f8d05f273639511fe9`，fixture `05ade33a16f9342d412dba6ea73c1ef4f27d7712aba548669fbb22489e9e9517`。

- isolated显示63/64，720×1280/240dpi，flags24456/removeMode1/组1/awake=true，相机5→6。权限框位于虚拟屏，permissioncontroller文案含COMPANION_DEVICE_APP_STREAMING，顶层焦点仍0；负责人自己定向点按授权。
- fixture只见id0、back、orientation0、visible0，display=N；未设图四象限全黑。横图640×480连续3次red/green/blue/white；竖图480×640连续3次white/blue/green/red；切回恢复。左右黑边各约21.8%（140/640），无镜像/倒置。
- 640×480单流约29fps，最长33分钟57386帧error=null。该记录不是多轮flaky统计，也不代表其它档位/策略通过。
- close后fixture Activity销毁，显示0未出现fixture。同一伴生关联重建新virtualDeviceId时无需再授权可开相机；这是本机实测，不承诺所有ROM/新关联都继承授权。
- 关闭后Task #758仍留在系统最近任务是已知行为，与Activity销毁不同；本工具不自动清理最近任务。是否处理已登记N60.6，待负责人决定。
- 最终工具进程、关联、文件、forward均无，相机回到5；共享dalvik-cache原样保留。

新版fixture每个恢复周期最多3次退避（500/1000/2000ms），成功重开不重置预算；暂停取消旧回调，耗尽显示最终原因与次数，用户明确再启动可开启新预算。页面隐藏ActionBar并避开系统栏，TextureView按4:3完整容纳。负责人已安装新版，集成方验证读回、文本可见和预览几何，见下节；**断流后自动重连仍只有单测覆盖，未完成真机验证**。

<a id="integrator-recheck-20261115"></a>

## 集成方复验与模拟器回归（22:35–23:06 JST）

2026-11-15由集成方执行，负责人完成新版fixture安装及必要授权，原始证据在仓库外。HEAD为9f68242，CLI SHA256为00eb44d3521fcdcab5e1a3854f4dc1ecbea919598bd4ae65d200c2865114f68f，helper为a3fefb4871560611cdb10d1af216b3a9b7f1c8b949e890bba6eea56807df17f3；不同于上节旧验收产物，不能混用摘要。

- 真机屏67上“相机页→主页正常关闭相机→重开相机页”两轮，availability始终available、availabilityReason=null，每次重开都读回red/green/blue/white，helper日志无渲染错误。restricted强制断流是否误报仍未验证，两处授权均在时无法复现。按包名launch同屏成功，读到PAGE:MAIN。
- 新fixture SHA256为1dc42ad123f249f33fbcf5ab8f53ce08a278db01de7b7abbb7bbaaf433085adb，由负责人adb install -r，两处授权保留且安装后未再弹框。横图读回red/green/blue/white，竖图white/blue/green/red；状态节点bounds为[0,0][720,29]和[0,29][720,82]，截图可见且无标题栏；TextureView为[0,411][720,951]，720×540（4:3），元数据retries=0/3。未制造强制断流，不能据此认定自动重连通过。
- 真机最终无helper、工具关联/临时文件/forward，摄像头5个、fixture无Activity残留；dalvik-cache原样保留。

模拟器osdk-test-35采用-read-only -no-snapshot，每项前冷启动；API35、x86_64、320×640/160dpi、en-US、导航模式2，指纹google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。

| 脚本 | 次数与结果 | 验收范围 |
| --- | --- | --- |
| verify-virtual-display.mjs | 1/1通过 | 普通steal虚拟屏回归 |
| measure-virtual-display-focus.mjs --mode mitigated --scenario acceptance | 1/1通过 | 普通isolated焦点；含按包名启动com.google.android.deskclock，覆盖新launcher解析路径 |
| verify-virtual-camera.mjs | 1/1通过 | 摄像头不可用的明确错误、关联清理、普通屏继续可用 |
| verify-virtual-display-mcp.mjs | **2/3通过、1次失败** | 两次通过各64次工具调用，isolated/steal均通过；三轮清理核对全0 |

MCP失败轮在boot_completed后约34秒开跑（仅8秒缓冲），android_display_list返回DISPLAYD_NOT_RUNNING后，startHelper()报DEADLINE_EXCEEDED: ADB command exceeded its deadline.；原始失败证据已保留。另两轮在boot_completed后等待45秒再开跑并通过。**归因未证实**：只有3个样本，不能认定等待时间是根因；冷启动稳定等待规则与多轮flaky统计已登记N60.6，不放宽任何判据。见[完整失败记录](../.trae/specs/virtual-camera/progress.md#mcp-cold-start-failure-20261115)。

**每份脚本结束会关闭模拟器，连续运行须逐项重启。** 集成方首次连续运行时，后三项因前项已按设计关闭模拟器而以“设备不在线”停止，属于执行流程疏漏，不计入产品结果或上述轮次。各项重新冷启动后的结果才列入表。本轮Agent仅登记提供的证据，不接触手机或启动模拟器。

<a id="b6956-recheck-20261119"></a>

## b6956真机复验（2026-11-19 01:0x–02:11 CST）

负责人已自行重启手机，集成方在同一NX888J/API36/arm64、用户0且未锁定条件下使用70f8ae3构建复验，原始证据仓库外。本轮Agent仅登记文档，未访问设备。指纹为nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6956:user/release-keys；CLI SHA256为ea429de225b89d909fcd3caf65ff4623eab9be42b5214454ecdb0f64f47740f3，helper为49307540443b2df252f53f35a308207f2c493c008569ebc414a406abcf4f18fe，fixture仍为1dc42ad1…；集成方独立复跑五项make全部通过。

重启后相机5个、无virtual_camera进程、无helper/forward/工具临时文件；关联10跨手机重启保留，fixture默认设备CAMERA仍为foreground。start复用10，屏2（v1_1000、flags24456）直接打开CameraActivity，没有出现GrantPermissionsActivity，三次横图读回red/green/blue/white且retries=0/3。授权来源为2026-11-18负责人在对话中明确授权集成方代点“使用期间允许”；本轮不新增授权。

| 出流拆除路径 | 拆除前 / 结果 | 拆除后判据 |
| --- | --- | --- |
| close屏2 | 1路640×480、788帧；756ms成功（CLI计时864ms） | 先移除显示，再应用发起disconnect，随后NOT_PRESENT与disconnect完成；Activity销毁、display list为空、相机5、help退出0、无ANR |
| stop --keep-camera-association | 屏3/v2_1001、竖图white/blue/green/red读回2次、326帧；2183ms成功，返回stopped:true及keptCameraAssociation:{userId:0,id:10} | helper退出，关联10仍在，无forward/工具临时文件，fixture无Activity，相机5、help退出0、无ANR |
| 默认stop | 再次start复用10，屏4无权限框且横图读回、324帧；2282ms成功，返回stopped:true | helper退出，关联10移除，相机5，virtual_camera可响应，全程无ANR |

最终无helper/工具关联/虚拟屏/forward/工具临时文件；伴生列表为空，Max ID:10不是仍有一条关联。仅共享dalvik-cache原样保留（系统uiautomator产生），fixture仍安装、默认设备CAMERA授权保留且无Activity。**本次三种出流拆除路径各1次未卡死，不证明A16所有时序下都不再发生死锁；出流时禁止kill helper的警告不变。** BUSY未实际触发，人工force-stop后同编号恢复、两屏同时shutdown、创建失败残留继续待验。完整日志时间线及清理摘要见[progress](../.trae/specs/virtual-camera/progress.md#b6956-recheck-20261119)。

## 错误与后续

| code | CLI 退出码 | 处理 |
| --- | --- | --- |
| VIRTUAL_CAMERA_NOT_ENABLED | 6 | 操作员停止并用 --virtual-camera 重启；普通屏需重建为 --camera back；旧 helper 需先更新 |
| VIRTUAL_CAMERA_UNAVAILABLE | 6 | API<35、服务/特性缺失或框架创建失败；保留 message 的 API、异常与原因，不改用真实摄像头 |
| VIRTUAL_CAMERA_BUSY | 4 | retryable=true；保持helper与关联，先由操作员让应用释放相机，再重试close/stop，禁止kill helper |
| CAMERA_IMAGE_INVALID | 2 | 检查 PNG/JPEG、8 MiB/4096 上限或内部临时路径/读取失败，不循环上传 |

仍未验证：VIRTUAL_CAMERA_BUSY实际触发与人工force-stop后同编号重试恢复（本次均在6秒内释放，未触发BUSY）、两块摄像头屏同时shutdown、创建失败残留、restricted强制断流下availability是否误报、新fixture断流后自动重连、1280×720/1920×1080实际出流、steal带应用读回、IME、主屏息屏、任何第三方应用及多轮统计。模拟器四项已执行，MCP保留2/3结果，不代表flaky问题关闭。负责人手机有锁屏密码，息屏会进入凭据锁，本工具不要求关闭凭据锁来验收。视频、图片MCP主机路径准入、双授权产品形态、BUSY恢复/两屏shutdown/创建残留复验、冷启动稳定规则与MCP多轮统计、最近任务是否处理及跨OEM矩阵在N60.6登记；不把已有单流实证外推为全部能力交付。

框架依据：[AOSP VirtualDeviceManager](https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/companion/virtual/VirtualDeviceManager.java)、[显示标志](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/view/Display.java)、[伴生设备 shell 命令](https://android.googlesource.com/platform/frameworks/base/+/master/services/companion/java/com/android/server/companion/CompanionDeviceShellCommand.java)。实际 OEM 行为以本机证据为准。

升级需同时部署本轮CLI与helper，只有更新CLI不能改变旧helper的拆除顺序。
旧helper仍出流时不要直接stop/kill来升级；先由操作员让应用正常释放相机并确认流为空，
再停止并替换制品。无法确认空流或系统已卡死时停止操作，由负责人处理/重启，不自动终止进程。
