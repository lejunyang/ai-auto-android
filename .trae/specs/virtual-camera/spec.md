# 虚拟摄像头变更规格

状态：实施中；本规格独立于已验收 MVP 与虚拟屏规格，未验证能力不得写成已交付。

## 目标与契约

在操作员显式启用后，创建由一个 VirtualDevice 拥有的虚拟屏和一个后置虚拟摄像头；屏内应用只看到该设备的摄像头。普通虚拟屏继续走现有 DisplayManager 路径，尺寸、数量、凭据锁、身份核对、无安全显示标志、无主屏回退及退出清理契约保持。

- `aactl display start --device SERIAL --virtual-camera [--helper PATH]`：CLI 独有；固定 shell 包、标记 MAC `00:00:00:00:00:10`、profile `android.app.role.COMPANION_DEVICE_APP_STREAMING`，仅操作当前前台用户的精确关联。读取关联编号传入 helper；遗留关联复用，启动失败与 stop 精确清理，禁止 disassociate-all。
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
