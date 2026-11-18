# 虚拟摄像头变更规格

状态：实施中；本规格独立于已验收 MVP 与虚拟屏规格，未验证能力不得写成已交付。

## 目标与契约

在操作员显式启用后，创建由一个 VirtualDevice 拥有的虚拟屏和一个后置虚拟摄像头；屏内应用只看到该设备的摄像头。普通虚拟屏继续走现有 DisplayManager 路径，尺寸、数量、凭据锁、身份核对、无安全显示标志、无主屏回退及退出清理契约保持。

- `aactl display start --device SERIAL --virtual-camera [--helper PATH]`：CLI 独有；固定 shell 包、标记 MAC `00:00:00:00:00:10`、profile `android.app.role.COMPANION_DEVICE_APP_STREAMING`，仅操作当前前台用户的精确关联。读取关联编号传入 helper；遗留关联复用；默认stop精确清理，可选保留见文末。启动失败只删本次新建，既有/崩溃遗留保留并说明，禁止disassociate-all。
- `aactl display create --device SERIAL --width W --height H --dpi D [--focus isolated|steal] [--camera back]`：默认无摄像头。MCP `android_display_create` 增加可选枚举 `camera: back`，沿用现有显示管理风险门；不提供 helper 启停或图片设置工具。
- `aactl camera set-image --device SERIAL --display N --image PATH`：仅 CLI；本地 PNG/JPEG ≤ 8 MiB，解码宽高各 ≤ 4096；固定前缀加 UUID 的临时路径，helper 校验后读取并立即删除，失败同样清理；按比例居中留黑边，运行中可切换。
- 后置摄像头提供 640×480、1280×720、1920×1080 的 YUV_420_888、30 fps；未设图输出纯色。只在流存在时绘制，有帧率上限，流关闭停止，关闭屏时释放摄像头与虚拟设备。
- 协议保持 v2，`hello.capabilities.virtualCamera` 表示本次启动是否启用摄像头入口；新客户端面对旧 helper 给出明确错误。新增 `camera.setImage` 和可选 `display.create.camera`；list/status 如实返回逐屏摄像头信息。
- 错误：`VIRTUAL_CAMERA_NOT_ENABLED` 提示操作员重启；`VIRTUAL_CAMERA_UNAVAILABLE` 附 API、服务或框架失败原因；`CAMERA_IMAGE_INVALID` 表示图片格式、预算或路径不合法。CLI/MCP 映射及错误表同步。

## 实现与安全边界

框架反射集中于 Android 后端；纯策略、协议、图片预算和缩放逻辑先写失败测试。shell 上下文满足 uid/package 归属，不复制探针中的权限操作、相机客户端与 Settings 缓存干预。绘制不使用 Paint/字体，只绘制位图或纯色。

产品不授予权限、不增加授权命令、不点按系统授权框。目标应用在虚拟设备上需要单独 CAMERA 授权，由用户完成。远程 Activity 策略由系统执行，不绕过。焦点和电源行为不从普通虚拟屏推断：记录实际附加标志、顶层焦点及主屏状态；没有应用、输入法或息屏证据的组合明确标为待人工验收。

测试应用仅修改 android/device-fixture：相机页正常请求权限，打开后置摄像头、显示预览和实际编号/朝向，ImageReader 采样四象限中心，输出稳定文本及错误原因。生产 App 与 Bridge 不修改。

## 设备与验收

模拟器固定 emulator-5554、API 35、read-only，三份既有回归各自冷启动完整通过一次；新错误路径验证摄像头创建结果、关联清理与普通屏可用。记录指纹和脚手架修复，不放宽判据。

真机仅 serial 324462538298（nubia NX888J / API 36 / arm64 / user release-keys），每步核对唯一在线 serial、指纹和凭据锁。只允许本工具 helper 的屏/摄像头/图片管理与只读观察；不安装应用、不改权限/设置、不输入/唤醒/休眠/解锁主屏、不启动第三方应用。非预期状态立即保留原始证据并清理、报告。

真机 helper 验收记录摄像头基线、创建归属、两种 focus 实际可观察结果与清理后的数量。负责人和集成方另行执行 fixture 安装、权限授权、画面读回及有应用的焦点/输入法/息屏验收；这些步骤必须独立标明人工原因，不在本次自动执行。证据仅留仓库外，不提交二进制或截图。

## 后续项与范围

只更新路线图 N60.6；视频输入、图片 MCP 主机路径准入、目标应用相机授权产品形态留后续。不修改其它规格验收结论及 risk.go、bridge_action.go、file_transfer.go。允许范围以任务书为准。若需偏离设计、协议升版或越界，先停下报告。

## 集成审查修复约束（2026-11-15 续）

本轮仅处理四项退回问题：两套显示标志分离命名，摄像头纯策略必须保留 DESTROY_CONTENT_ON_REMOVAL 并排除 SECURE；真机比较保留/去掉请求 OWN_DISPLAY_GROUP，若均可用则保留，与普通屏一致；实际读取两策略 removeMode，不能用注册成功替代销毁语义证据。普通文件清理不得被伴生关联/图片清理失败阻断，各项均尝试并公开分类失败。缓存目录先只读比对来源，不删启动前已存在的目录；若确定 helper 创建且无法从源头避免，只能按启动前不存在的证据对两条精确路径 rmdir 空目录。循环正则提升为包级。

本轮手机仍连接，不启动模拟器，原四轮回归保持待验；真机边界不变。全量验证前在 Git Bash 明确 export Go 1.26.5 PATH，运行五项 make；结束停止本轮 Gradle daemon。历史 progress 仅追加更正，不改写错误原文。

## 真机端到端验收后的修复（2026-11-15 续）

接受集成方与负责人20:45–21:51 JST提供的NX888J/API36证据：isolated、640×480单流读回、黑色占位、三次横图/竖图及切回、等比留边方向、约29fps/33分钟57386帧、关闭Activity且未迁移主屏、同一伴生关联重建无需再授权均已验证；执行者及产物摘要见progress。不能扩大为高分辨率、steal读回、IME、凭据锁息屏、第三方App、多轮flaky或模拟器结论。

本轮仅修A–E：显示定向按包名启动先固定argv解析单个同包launcher组件，异常不启动且不回退；双设备CAMERA授权前提与权限复查推断分开写；完整回收media.camera输出后仅在主机过滤，服务/渲染失效只报告不自动重建；fixture仅测试模块修复有限重连、标题遮挡、4:3预览；最近任务残留仅登记，不改变清理行为。新增可用性诊断须保守标注依据/未知，不能把Binder存活或旧cameraId当成应用读回成功。

本轮允许在工具新建虚拟屏启动已安装dev.aiauto.fixture并观察PAGE:MAIN；不安装新fixture、不动两处权限，权限框出现立即停止，不点按。新fixture真机验收留负责人重装后执行。模拟器四项仍待验，规格状态单一集成者顺序维护。

## 可选保留伴生关联（负责人2026-11-16决策）

- 仅操作员CLI增加display stop --device S --keep-camera-association；布尔写法与--virtual-camera相同，只允许stop，拒绝=false等非法值。不增加配置、环境变量、设备持久状态或MCP入口，不改协议版本/错误码/schema。
- 带开关stop不执行disassociate，仍独立尝试helper文件与工具UUID图片清理，合并公开失败。只读精确查询当前/启动用户的com.android.shell+00:00:00:00:00:10关联；存在时返回keptCameraAssociation:{userId,id}，无关联时省略，查询失败可见但不阻断其它清理。
- 普通模式helper运行时带开关按忽略处理，与不带开关一样不触发companiondevice，不返回保留字段；helper未运行时带开关查询当前用户、保留既有关联并完成幂等清理。不带开关的stop命令序列、成功JSON和错误分类保持；无helper时也可用不带开关stop移除保留关联。
- start --virtual-camera复用精确既有关联；EnsureCameraAssociation返回Created区分调用前存在/本次新建。启动失败仅删除本次新建关联；既有关联保留，错误说明保留/删除/清理失败。失败仍停止可核验helper并清文件/图片。崩溃遗留关联启动失败由原先删除改为保留，不能宣称所有默认失败行为不变。
- 审查Java退出路径是否解除关联；没有则不改Java。保留期间设备常驻shell的COMPANION_DEVICE_APP_STREAMING关联及负责人此前授予目标应用的虚拟设备CAMERA授权；归还/转手前用不带开关stop清理，绝不disassociate-all或代授权。
- 场景：运行/未运行helper、无关联/查询失败、普通模式忽略、默认stop兼容、新建/复用后启动失败均需Go测试；模拟器一次冷启动扩展脚本验证保留编号不变、重启复用、默认stop移除及最终无关联。不用模拟器证明相机授权持久性。
- 真机“保留关联后helper重启无需重授CAMERA”仅有同关联重建屏的既有旁证，仍未验证。本轮不连接真机；只允许emulator-5554，指定read-only/no-snapshot启动，boot_completed后等45秒，只跑摄像头脚本一次。
## 摄像头安全拆除（2026-11-19续）

- 目标是活动流存在时不主动注销摄像头、关闭VirtualDevice或完成helper退出。屏先display.release（销毁内容位保留），出帧照常；等待onStreamClosed清空流表后才停止残余线程（不无限join）、VirtualCamera.close、VirtualDevice.close。创建失败也走同一空流检查，意外未释放资源保留所有权。
- 纯逻辑CameraRelease使用同一流表monitor、可注入单调时钟/等待器/假资源，具名等待上限6000ms；wait期间释放monitor，回调remove后notifyAll。每个请求在进入AndroidBackend锁前记录deadline，shutdown所有屏共享6秒等待预算，避免两屏累计12秒；其它请求仍受既有全局串行锁，读请求可能等待最多该局部排空预算，框架Binder已卡死不在本地计时可保证范围。
- 超时抛VIRTUAL_CAMERA_BUSY，Go/CLI/MCP retryable=true，CLI退出4。保留原屏记录/编号，cameraInfo.releasePending=true；显示已移除，仅可读诊断及重试close/shutdown，禁止将其当可用输入/取帧目标。仍占两屏容量。status对已移除显示不再读DisplayInfo/电源状态，state=0、groupAwake=null、displayGroupId=-1表示无当前显示组；保留创建时实际flags。
- 创建失败若意外有流而无法排空，保留内部资源记录，status.pendingCameraReleases公开计数，shutdown可重试释放，不丢失引用触发Binder死亡。
- shutdown对全部屏及创建残留应用同一流程；任一BUSY则进程不退出、helper文件/关联/图片不清理，成功屏可释放，未完成记录保留。Main仅在backend安全完成shutdown后才允许退出，即使写响应失败也不得把BUSY请求当成功。
- 保持协议v2和MCP工具数；只新增BUSY错误和可选状态字段。Go单次wire保持具名10秒，覆盖6秒等待及余量；明确透传BUSY而不改成PROTOCOL_ERROR。产品不force-stop应用、不kill helper、不自动重试/重建，外部kill/崩溃无法由本地关闭策略阻止。
- 文档说明A16出流时kill/崩溃风险、卡死症状与负责人重启恢复；不以本轮单测宣称已修复真机卡死。本轮不执行任何设备命令，不启动模拟器；真机关闭、force-stop后BUSY恢复、保留关联免重授均由负责人重启后集成方另验。
