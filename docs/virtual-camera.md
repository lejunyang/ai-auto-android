# 虚拟屏静态图片摄像头

操作员可在 API 35+ 且系统支持 VirtualCamera 的设备上，创建一个虚拟设备拥有的虚拟屏和后置摄像头。普通虚拟屏继续使用原来的 DisplayManager 路径。当前已验证 NX888J/API36 的 helper 注册、归属、图片接收和清理；**尚未完成真实应用画面读回、GPU 出流及有应用的焦点/输入法/息屏验收**。

## 操作员命令

```bash
aactl display start --device SERIAL --virtual-camera --helper PATH_TO_DISPLAYD_APK --json
aactl display create --device SERIAL --width 720 --height 1280 --dpi 240 --focus isolated --camera back --json
aactl camera set-image --device SERIAL --display N --image PATH_TO_LOCAL_PNG_OR_JPEG --json
aactl display list --device SERIAL --json
aactl display status --device SERIAL --json
aactl display close --device SERIAL --display N --json
aactl display stop --device SERIAL --json
```

`N` 必须取自本次 create 响应，不能是显示 0。`--camera` 目前只有 `back`；省略时仍创建普通屏。启用需要 stop 后以 `--virtual-camera` 重新 start；已有普通 helper 不会被隐式升级。未启用或旧 helper 没有能力位时返回 `VIRTUAL_CAMERA_NOT_ENABLED`，协议仍为 v2。

图片只能为本机普通 PNG/JPEG 文件，文件 ≤ 8 MiB，解码宽高各 ≤ 4096。Go 校验并暂存快照后 push 到 `/data/local/tmp/aactl-camera-UUID.img`；UUID 为随机 v4。helper 只接受该完整模式，拒绝符号链接和非普通文件，按预算解码后立即删除，Go 再清理同一路径。失败也会清理；stop 还会回收精确模式的崩溃遗留图片。不会接受任意设备路径或通配符。

图片按比例居中，余下区域黑色；不会拉伸或裁切。未设置图片时为黑色。支持运行中反复设置；每个摄像头配置 640×480、1280×720、1920×1080 的 YUV_420_888 / 30fps，逐流 EGL 绘制最高约 29.4fps，不按 capture request 无限补帧。流关闭即停止，关闭屏释放其设备、摄像头、位图和输入面。图片接收成功只表示内容已替换，不证明应用已经收到帧。

带摄像头的 list/status 屏对象有 `camera:"back"`、`cameraInfo:{cameraId,virtualDeviceId,streams}`、实际 `displayFlags`。status 另只读返回系统 DisplayInfo 的 `removeMode`；1 表示销毁内容，缺省/null 表示未能读取、不能计为验证通过。`streams` 包含 `streamId,width,height,frames,error`；空数组表示当前无流。`cameraId` 是框架注册标识，应用在设备上下文看到的编号应以 fixture 的 `CAMERA_DEVICE` 文本为准。普通屏省略摄像头及removeMode字段，表示不带摄像头。尺寸 128..2048、dpi 72..640、最多两块屏的总预算保持。

## MCP 与权限

`android_display_create` 在原有 `device,width,height,dpi,focus` 参数之外接受可选 `camera:"back"`，仍经过显示管理风险门。例：

```json
{"device":"SERIAL","width":720,"height":1280,"dpi":240,"focus":"isolated","camera":"back"}
```

MCP 不提供 helper 启停、图片设置、主机路径或权限授予入口，工具总数仍为 20。`risk.go`、`bridge_action.go`、`file_transfer.go` 的风险门未改变。

**目标应用在虚拟设备上需要单独的 CAMERA 授权。产品不会替应用授予权限，也不会自动点按系统权限框。** 负责人可在 fixture 打开相机页后，先用 `observe screenshot/hierarchy --display N` 查看该屏的系统权限框，再自己通过最新画面/节点决定并执行一次定向点击；每次点击后重新观察。若授权界面不能被可靠展示或定位，停止，由负责人用可见界面处理，不使用 pm grant、appops 或设置旁路。具体产品授权形态仍待负责人决定。

系统可以因应用的远程显示声明、用户限制或 VirtualDevice Activity 策略拒绝启动；应用需支持远程设备显示。fixture 相机 Activity 显式声明支持；遇到启动失败应保留错误和活动状态，不更改应用声明、不绕过系统策略、不转向显示 0 重试。

## 关联与安全边界

只有操作员 `display start --virtual-camera` 会为当前前台用户建立固定 `com.android.shell` + `00:00:00:00:00:10`、profile `android.app.role.COMPANION_DEVICE_APP_STREAMING` 的伴生关联；通过系统 list 读回编号。相同标记遗留可复用，重复或不可解析的匹配拒绝处理。标记地址为本工具保留，不应手工用于其它用途。

启动失败、停止时只按精确包名和地址移除工具关联；不使用 disassociate-all。正常 helper 通过 hello 返回启动用户，停止时按该用户清理；helper 崩溃后的 stop 使用当前用户，**崩溃后切换过 Android 用户时需回到启动用户清理**。不自动切换系统用户。进程身份、socket inode/fd、APK 摘要与临时 forward 核对沿用现有实现。

helper 未运行时，stop 仍尝试普通文件清理，然后独立尝试相机关联和UUID图片清理；关联服务不可用或格式未知不能阻止其它清理。普通运行中的非摄像头 helper 停止不调用 companiondevice。全部成功时仍返回 `{"stopped":true}`；部分失败返回错误，`error.details.stopped=true` 表示进程已停止，`error.details.cleanup` 分列 `helperFiles`、`cameraAssociation`、`cameraImages` 的 `ok/error`。所有清理错误合并保留，不静默吞掉关联残留，也不把进程停止等同于全部清理成功。forward仍逐调用清理，不删除来源未知的forward。

## 显示标志审查修正

DisplayManager 的**请求**标志与 Display.getFlags 的**结果**标志不同：请求 `DESTROY_CONTENT_ON_REMOVAL=0x100`、`OWN_DISPLAY_GROUP=0x800`；结果 `OWN_DISPLAY_GROUP=0x100`。旧实现误删请求的0x100，原“已去掉OWN_DISPLAY_GROUP”的说法撤回，历史progress仅追加更正。修复后 CameraPolicy.displayFlags 纯函数强制保留销毁位、排除请求SECURE；两套具名常量分别维护，焦点也按结果命名空间读取。

2026-11-15在同一NX888J/API36、唯一serial、未锁定条件下，使用只差请求OWN_DISPLAY_GROUP的两个本地构建，各创建两种focus空屏并读取真实状态：

| 请求OWN_DISPLAY_GROUP | focus | 显示 / 设备 | 实际displayFlags | 显示组 | groupAwake | removeMode |
| --- | --- | --- | --- | --- | --- | --- |
| 保留（产品） | isolated | 57 / 10 | 24456 / 0x5f88 | 1 | true | 1 |
| 保留（产品） | steal | 58 / 11 | 20360 / 0x4f88 | 1 | true | 1 |
| 去掉（临时实验） | isolated | 59 / 12 | 24200 / 0x5e88 | 2 | true | 1 |
| 去掉（临时实验） | steal | 60 / 13 | 20104 / 0x4e88 | 3 | true | 1 |

四个组合均可创建且无SECURE；没有反对保留方案的实测证据，因此按普通屏既有请求策略**保留OWN_DISPLAY_GROUP**。这不表示其显示组由VirtualDevice分配。临时实验没有产品开关，源码已恢复。removeMode=1证明系统当前报告销毁策略；没有启动任何应用，所以不声称已做应用销毁生命周期或“不会迁移主屏”的动态测试。

## 共享缓存目录来源与保留

本机启动前已存在 `/data/local/tmp/dalvik-cache`（777、shell:shell，inode396399）与其空子目录 `arm64`（700，inode478273），两者mtime/ctime均1794735523，即2026-11-15 18:38:43 JST。普通helper启动/初始化/停止、摄像头helper启动、两种显示组各两策略创建/关闭/停止的对比均保持存在性、inode、权限和mtime不变。

真机 `/system/bin/uiautomator` 包装脚本明确在非root时使用run_base=/data/local/tmp，`mkdir -p` 创建dalvik-cache，并将ANDROID_DATA改为run_base后执行app_process；ART会为该重定向缓存创建0700的架构子目录。helper直接启动时ANDROID_DATA=/data，源码没有创建这两条目录。旧mtime还恰在上一轮首次hierarchy（18:38:42–45）之间。证据指向系统UIAutomator层级命令（本工具与其它调用者共用），不是摄像头helper特有缓存；不能仅凭mtime唯一归属历史调用者。

**现有共享目录不删除，交负责人决定是否清理。** stop只删除工具固定文件和精确UUID图片，不擅自rmdir这些启动前已存在的目录。验收脚本已将两条路径加入逐阶段及最终残留检查，并在`sharedRuntimeCache`公开原样保留；若从不存在变为存在或元数据变化，会保留并报告，不能声称清理通过。没有递归删除、通配符或“空目录即归工具”的规则。框架创建机制见[AOSP ART GetDalvikCache](https://android.googlesource.com/platform/art/+/ed0dcade7b/libartbase/base/file_utils.cc)。

凭据锁定保护、目标无效不回退主屏、不含 SECURE、安全窗口系统遮黑、每 2 秒按虚拟显示组保活保持。摄像头创建和设置图片也检查凭据锁。锁定或状态未知时停止，不唤醒、不解锁、不自动重发输入。

## 本次设备证据与限制

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

## 错误与后续

| code | CLI 退出码 | 处理 |
| --- | --- | --- |
| VIRTUAL_CAMERA_NOT_ENABLED | 6 | 操作员停止并用 --virtual-camera 重启；普通屏需重建为 --camera back；旧 helper 需先更新 |
| VIRTUAL_CAMERA_UNAVAILABLE | 6 | API<35、服务/特性缺失或框架创建失败；保留 message 的 API、异常与原因，不改用真实摄像头 |
| CAMERA_IMAGE_INVALID | 2 | 检查 PNG/JPEG、8 MiB/4096 上限或内部临时路径/读取失败，不循环上传 |

视频输入、MCP 图片主机路径准入、目标应用相机授权产品形态及跨设备/OEM矩阵登记在 N60.6；本次未实现。GPU 实际画面读回和有应用的焦点/IME/息屏组合必须按人工验收结果更新本规格，不能把注册或编译成功作为端到端交付。

框架依据：[AOSP VirtualDeviceManager](https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/companion/virtual/VirtualDeviceManager.java)、[显示标志](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/view/Display.java)、[伴生设备 shell 命令](https://android.googlesource.com/platform/frameworks/base/+/master/services/companion/java/com/android/server/companion/CompanionDeviceShellCommand.java)。实际 OEM 行为以本机证据为准。
