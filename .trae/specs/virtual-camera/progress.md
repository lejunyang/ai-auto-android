# 实施记录（仅追加）

## 2026-11-15 VC1 开始

- 已读取根 AGENTS.md；git status --short --branch 显示 virtual-display-screen-off 干净，HEAD aacd60d78b32a95355f8349a550f264d43bce242。
- worktree：本目录对应 virtual-display-screen-off；E:/Projects/ai-auto-android 对应 main，不修改。
- 现有 helper 协议为 v2、普通虚拟屏使用 DisplayManager；本变更采用可选能力与方法，不升版本。
- 已读一次性探针作参考；其中授予/撤销权限与裸客户端探测不进入产品实现。
- 尚未操作任何设备，尚未实现或验收虚拟摄像头。真机端到端安装、授权、画面读回及应用焦点/输入法/息屏验证由负责人和集成方执行。

## 2026-11-15 VC2–VC4 实现与静态验证

- 规格先提交 bdefd6c。helper 新增 CameraPolicy、VirtualCameraBackend 与逐流 EGL 渲染；普通屏仍走原 DisplayManager 调用。摄像头屏由 VirtualDevice 分配组，不沿用普通屏 OWN_DISPLAY_GROUP；返回实际 displayFlags/focusIsolated、cameraInfo 归属和逐流错误/帧数。
- 图片先校验固定 UUID 模式；O_NOFOLLOW/fstat 验证普通文件、8 MiB/4096/PNG/JPEG 预算；读取后立即删除，拒绝路径和失败路径清理；GL 仅上传位图、保持比例居中留黑边，无 Paint/字体、无相机权限操作。
- 新增测试先覆盖策略/协议；首次 Gradle 调度较慢，首批测试执行时实现已写入，不能将该轮称为红灯证据。另加流尺寸预算测试，vcam-red.log 明确因缺少 acceptsStream 编译失败，随后实现白名单，定向单测/构建/lint 通过。未执行设备流读回。
- 编译问题：公开 Os API 是 remove 而非 unlink，已修正。lint 对 ContextImpl 包归属字段报普通应用隐藏 API 限制；仅在不安装、shell app_process 的上下文初始化方法加有中文理由的局部 lint 注解，不修改设备隐藏 API 策略，运行时异常仍不可用。
- Go 新增固定 argv、精确包名+标记 MAC 的关联管理（复用遗留、部分失败清理、stop 清理）、能力协商、图片快照及 UUID 上传；CLI/MCP 仅显示创建暴露 camera:back。定向 Go 五包测试通过；新增测试覆盖三种错误码、CLI/MCP 枚举和权限/主机路径入口不扩张。
- fixture 独立 CameraActivity 正常申请权限，预览与四象限采样，输出编号/朝向/错误；CameraSample 单测覆盖颜色及 YUV 行/列步长。helper/fixture 单测、assembleDebug、lintDebug 通过。make comments 通过（616 个手写文件、16 个检查器自测）。
- 设备顺序调整：ADB 当前仅连接真机，先完成授权内 helper 验证并清理，再由用户断开真机后进行 emulator-first 回归。未安装任何应用、未授予权限、未发送输入、未切换主屏电源。
- 真机只读基线：唯一 serial 324462538298 / transport24，指纹 nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6416:user/release-keys，API36/arm64-v8a，当前用户0，secure=true 且 deviceLocked=0。media.camera 总设备5、normal/public各2、virtual provider 0 devices；companiondevice list 无关联。

## 2026-11-15 VC6 真机 helper 逐步证据

- 本地实现提交：5d4906f（helper）、460efe2（Go/CLI/MCP）、8cf040a（fixture）。无push。以下只有helper操作，没有安装/卸载应用、授予/撤销权限、系统设置、应用启动、输入、唤醒/休眠/解锁。
- 首轮 UTC09:38:29–09:38:51：固定上述指纹/transport24、当前用户0且deviceLocked=0。start --virtual-camera成功；isolated屏54/设备7/camera v7_1007/实际flags24456，group1/state2/awake=true，topFocusedDisplayId保持0；总相机5→6、normal2→3、public保持2、虚拟provider0→1。hierarchy返回DISPLAY_EMPTY；截图FRAME_NOT_READY。脚手架错误要求无应用空屏必须有帧，先保存响应后立即stop清理。相机恢复5/2/2/0、工具关联/文件/forward无遗留；该轮不计完整通过。
- 根因与修复：禁止启动应用的helper-only场景没有合成内容，FRAME_NOT_READY是既有合法状态，不能靠等待或操作手机产生帧。脚本将这条结果明确写成“无帧，不构成取帧能力通过”，未放宽任何有应用的截图/颜色判据，不重发设备动作。
- 第二轮 UTC09:39:49–09:40:32：同一唯一设备、指纹和未锁定守卫。CLI SHA256 2be9b53d8d860621b9acf02167c1db628debf3699ec946000300ae5e39e25a19；helper SHA256 1b03e07bf02534fe645f662ce93fa927603cd183e1af4cee57cf4c38ed2ac4bb。制品及原始响应写于仓库外 Temp/aactl-vcam-helper-20261115-02（不提交二进制/截图/证据）。
- isolated：屏55、VirtualDevice8、cameraId v8_1008；实际Display flags24456/0x5f88、focusIsolated=true、无SECURE；group1/state2/groupAwake=true，keepAwakeError=null，topFocusedDisplayId=0。hierarchy=DISPLAY_EMPTY，截图=FRAME_NOT_READY。横图640×480及竖图480×640先后imageSet:true，streams为空。close后数量恢复基线。
- steal：屏56、VirtualDevice9、cameraId v9_1009；实际flags20360/0x4f88、focusIsolated=false、无SECURE；group1/state2/groupAwake=true、keepAwakeError=null、空屏topFocusedDisplayId=0。hierarchy=DISPLAY_EMPTY，截图=FRAME_NOT_READY；横图/竖图均imageSet:true，streams为空。close后恢复基线。
- 每次创建media.camera总数6、normal3、public2、virtual provider1；每次close与最终stop均总数5、normal2、public2、virtual provider0。最终helper进程、工具标记关联、aactl-displayd.apk/log、UUID图片与工具forward均无遗留。系统关联Max ID单调增加到3但无关联行，这不是关联泄漏。
- 只验证空屏下实际标志、组状态、顶层焦点、注册归属、图片接收与清理。没有相机客户端，无流；GPU绘制、预览/ImageReader读回、三档实际请求、有应用的焦点/IME以及主屏息屏时层级/取帧/输入全部未验证，不外推普通屏结论。负责人验收说明见device-acceptance.md。
- 真机清理后已请用户断开ADB，以便满足唯一设备规则运行四轮模拟器；等待期间完成文档与全量静态验证。当前不再对真机执行任何修改。

## 2026-11-15 初次全量验证

- make comments/test/verify/build/android-verify全部成功。协议56项；skills三份及镜像；Go全包及linux/darwin/windows交叉vet成功；Android199任务，单测/assembleDebug/lintDebug成功。脚本纯逻辑6项通过。
- 随后补充启动用户清理生命周期测试与错误路径映射、文档/skills；定向Camera Go测试通过，最终验收还会针对最终改动重跑必要检查并统计测试数量。

## 2026-11-15 静态工作交付、模拟器等待设备切换

- 后续本地提交：5122c34（相机失败原因/清理、启动用户生命周期、JPEG精确8MiB与孤儿图片清理测试、CLI/MCP提示）；22bb2c8（fixture迟到回调代次隔离）；65a1c43（真机helper与模拟器错误路径验收脚本）；291748c（竖图颜色反转，避免后续端到端把旧帧当切图成功）。未push。
- 最终make comments/test/verify/build各exit0，make android-verify exit0；Go348个顶层测试通过，含子测试共713个pass事件、5个既有设备/平台条件测试跳过、0失败。Android622项（621通过、1项既有RecordingScriptStoreTest跳过）、0失败：app564、fixture10、displayd42、web-fixture6。协议56项、脚本纯逻辑6项、skills三份及镜像/617手写文件注释覆盖/16检查器自测通过。最终MCP描述收窄普通屏结论后，MCP包测试再通过。
- 真机helper证据对应第二轮报告中的制品SHA；后续只改初始化失败诊断与测试/文档，没有重新执行真机操作。不能声称最终制品已完成真实相机流验收。
- 本次等待中ADB仍只有324462538298/transport24，未启动emulator。已通过异步问题请用户断开真机，满足单设备规则后才能执行：steal冷启动一次、isolated冷启动一次、MCP stdio冷启动一次、virtual-camera错误路径冷启动一次。四项当前均未运行，不记通过；VC5与最终汇合保持未完成。
- 无需新的产品授权；待解决的是物理设备连接条件。真机此前已stop、精确关联/工具临时文件/forward清理、摄像头5/2/2/0恢复；没有留待用户清理的工具资源。当前应用读回/GPU/输入法/息屏与三档真实请求仍按device-acceptance.md由负责人/集成方后续验收。

## 集成审查退回：更正与修复启动

- 已复读AGENTS，工作区干净，分支virtual-display-screen-off，HEAD 0f62a64；main worktree不动。本轮只修四项审查问题，不启动模拟器，不触碰现有dalvik-cache目录，不修改三份风险门。
- 更正此前第13行及VC2–VC4记录中的“摄像头屏由VirtualDevice分配组，不沿用普通屏OWN_DISPLAY_GROUP”：该说法错误。原代码把DisplayManager请求标志与Display结果标志混用，`flags & ~(1 << 8)`实际去掉DESTROY_CONTENT_ON_REMOVAL，未去掉请求OWN_DISPLAY_GROUP；旧实测24456/20360均含Display.FLAG_OWN_DISPLAY_GROUP=256，不能证明设备组策略，也未读取removeMode。历史行保持原样，本轮以新增纯策略/实际removeMode/对照取证纠正，不再把旧注册/清理证据作为销毁内容证据。
- 修复计划见VC8–VC11；普通清理与相机清理独立尝试并公开分类失败；缓存先比对存在性/mtime与调用来源，再决定是否有权限清理。原模拟器四轮继续待验，GPU/画面读回/输入法/息屏能力不新增通过结论。

## 集成审查修复：实现、对照取证与最终验证

- 修复规格提交1b67f52。问题1提交410769a：新增FrameworkDisplayFlags，将DisplayManager请求与Display结果命名空间隔离；Policy普通屏数值不变。CameraPolicy.displayFlags纯函数强制保留DESTROY_CONTENT_ON_REMOVAL=0x100并排除请求SECURE；产品保留请求OWN_DISPLAY_GROUP=0x800。AndroidBackend焦点读取改结果具名常量，摄像头status只读DisplayInfo.removeMode，读不到保留未知；Go/MCP显示状态可传递该值。
- 失败/通过证据：CameraPolicyTest.displayRequestPreservesDestroyAndFocusPolicies先因缺少纯函数编译失败，补实现后通过；requestAndResultNamespacesRemainDistinct断言请求销毁0x100、请求组0x800、结果组0x100不同；PolicyTest继续断言普通屏15819/64971/130507未变。CameraStatusRemoveModePreservesUnknown验证1与null不会混淆。没有把请求位当作系统生效证据。
- 真机对照使用同一CLI、指纹/transport24/用户0且未锁定，两个本地helper构建仅差请求OWN_DISPLAY_GROUP；无产品开关。保留APK SHA256 a7bbe34eabac1fb38c3eaea9ddd99b44f9c73bd6bf2f66f8d05f273639511fe9；临时去掉APK cc9277dbce79420c669ff9b856412078f3a98cdf09ac969ce7892d9291a07f56。实验源副本/制品/报告均在仓库外Temp/aactl-vcam-review-20261115，不提交。
- 保留方案：UTC11:23–11:24，isolated屏57/设备10，flags24456/组1/awake=true/removeMode=1；steal屏58/设备11，flags20360/组1/awake=true/removeMode=1。去掉方案：UTC11:27，isolated屏59/设备12，flags24200/组2/awake=true/removeMode=1；steal屏60/设备13，flags20104/组3/awake=true/removeMode=1。四组合均无SECURE，每次关闭后相机回到5/2/2/0。两方案均能创建，没有反对保留的证据，因此按要求选与普通屏一致的保留方案；临时源码已恢复且全量验证使用产品版本。
- 问题2提交551b1fe：helper确已退出后先清普通文件，再独立尝试关联和图片；errors.Join保留所有失败。失败响应的error.details.stopped=true、cleanup.helperFiles/cameraAssociation/cameraImages分别有ok/error，成功仍只有stopped:true；不吞掉关联清理失败。每次forward的既有finally清理不变，不删除未知forward。
- Go测试：TestStopWithoutHelperAttemptsFilesWhenCameraAssociationFails（服务不可用/未知格式）、TestStopWithoutHelperAndNoAssociationKeepsSuccessShape、TestStopRunningOrdinaryHelperSkipsCompanionCleanup、TestStopMergesAllCleanupFailures通过。TestCLIStopAbsentHelperReportsCameraCleanupAndStillRemovesFiles经真实CLI/service/vdisplay/adb适配器，在API33和34各验证不可用/未知格式/无关联共6个子场景：文件和图片清理均尝试，失败分类在CLI信封可见。普通运行helper不调用相机关联清理，forward建立/移除计数相等。
- 问题4提交f818f8e：companionMaxIDLine提升包级，不在list循环重复编译；原新旧格式和精确标记匹配测试继续通过。
- 问题3：最初只读stat见根目录777/shell:shell/inode396399，子目录arm64为700/inode478273且为空；两者mtime/ctime均1794735523（JST18:38:43）。helper直接启动的ANDROID_DATA=/data。只读读取本机/system/bin/uiautomator，脚本在非root时令run_base=/data/local/tmp、mkdir -p dalvik-cache并export ANDROID_DATA到该目录后exec app_process；ART对重定向缓存创建架构子目录。权限与路径吻合，旧mtime也位于首轮hierarchy的18:38:42–45内。来源机制指向系统uiautomator包装脚本（本工具及其它调用者共用），非摄像头helper特有；无法仅凭mtime唯一确定历史调用者。
- 普通helper启动→status初始化→stop，摄像头helper启动→四种对照创建/关闭→stop，以及最终脚本中的hierarchy前后，存在性、inode、权限、mtime/ctime、子项全部与基线相同。当前目录启动前已存在，未改、未删、未rmdir；登记给负责人决定。未增加不必要的目录清理入口。脚本增加逐阶段/最终检查和sharedRuntimeCache；若相对基线变化，保留并报告，不声称清理通过。
- 脚手架问题：最初stat格式用管道字符，adb shell二次解释为管道，查询失败且没有设备写入；改为固定逗号格式后取证，未把失败当“不存在”。本机曾出现进程启动/命令输出延迟，先查询会话并等待完成，没有重复派发设备动作、没有放宽判据。注释检查首次拒绝常量类缺少可识别用途动作，补为“定义/防止”，之后通过。
- 最终脚本scripts/verify-virtual-camera-device.mjs于UTC11:31:20–11:32:35完整重跑，ok=true/cleaned=true。最终CLI SHA256 9ae875f4cb26694b202b22353b27cabd8dc1359ef47686ebfb9037ba95aab0fc，helper SHA与上述保留构建一致；证据在仓库外final-helper目录。isolated屏61/设备14/camera v14_1014，flags24456/组1/state2/awake=true/removeMode=1；steal屏62/设备15/camera v15_1015，flags20360/组1/state2/awake=true/removeMode=1；keepAwakeError均null，顶层空屏焦点均0。两张图各成功接收；无流，层级DISPLAY_EMPTY/截图FRAME_NOT_READY，未算画面通过。
- 最终清理：helper无进程，工具关联无行（Max ID6是历史计数）、APK/log/UUID图片无、工具forward无，总摄像头5/normal2/public2/virtual0；两条共享缓存与基线逐字一致并明确保留。没有安装/卸载应用、权限/设置变化、主屏输入/电源操作或第三方应用启动。
- 按要求先在Git Bash export PATH=/e/osdk-data/data/installs/go/1.26.5/bin:$PATH，再直接运行make comments/test/verify/build/android-verify，全部exit0。Go354顶层通过、含子测试727个pass、5项既有条件跳过、0失败；Android624项（623通过、1项既有跳过）、0失败，displayd44、fixture10、app564、web-fixture6；协议56项、三平台vet、skills镜像、619手写文件注释覆盖及16个检查器自测通过。
- 按要求cd android && ./gradlew --stop，exit0、1 Daemon stopped；随后--status返回No Gradle daemons are running（当前9.3.1）。本轮不启动模拟器，VC5四项及最终模拟器汇合保持待验。应用读回/GPU/有应用焦点IME息屏/实际应用销毁生命周期仍未验证，不新增交付结论。

## 集成方与负责人提供的端到端验收（2026-11-15 20:45–21:51 JST）

- 本节由本轮用户输入提供，执行者为集成方与负责人，不是本Agent；原始证据在仓库外，不入库。serial324462538298，NX888J/API36/arm64，用户0、全程未锁定，指纹nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6416:user/release-keys。
- 产物SHA256：aactl.exe=9ae875f4cb26694b202b22353b27cabd8dc1359ef47686ebfb9037ba95aab0fc；displayd-debug.apk=a7bbe34eabac1fb38c3eaea9ddd99b44f9c73bd6bf2f66f8d05f273639511fe9；device-fixture-debug.apk=05ade33a16f9342d412dba6ea73c1ef4f27d7712aba548669fbb22489e9e9517。本地debug构建，负责人自行adb install，未用-g。
- 已证实1：start --virtual-camera成功；isolated摄像头屏720×1280/240dpi，显示63/64，flags24456、removeMode1、组1、awake=true，相机5→6。
- 已证实2：启动fixture/.CameraActivity后权限框在虚拟屏，包com.android.permissioncontroller，文案含android.app.role.COMPANION_DEVICE_APP_STREAMING；mTopFocusedDisplayId保持0。负责人自己用action tap --display63完成授权。
- 已证实3：CAMERA_DEVICE:id=0,facing=back,orientation=0,visible=0,display=N；虚拟上下文只见一个后置摄像头。
- 已证实4–6：未设图四象限均black；quadrants.png(640×480)连续3次CAMERA:TL=red,TR=green,BL=blue,BR=white；运行中portrait.png(480×640)3次CAMERA:TL=white,TR=blue,BL=green,BR=red；左右黑边各约21.8%（理论140/640），无镜像倒置；切回横图恢复原顺序。
- 已证实7：640×480单流约29fps，最长连续33分钟57386帧、error=null。不是多轮flaky统计或三档实际出流结论。
- 已证实8–9：close销毁fixture Activity，显示0没有出现fixture；同一伴生关联关闭后新virtualDeviceId重建，fixture无需重新授权可开相机（仅本机实测，不承诺其它ROM/关联）。
- 已证实10：最终无helper、工具关联/文件/forward，相机恢复5；dalvik-cache原样保留。
- 权限事实B：仅虚拟设备CAMERA授权、默认设备未授权时，20:51:26（连接后21秒）及20:59:46（约3分钟）被CameraService restricted断流；日志isUidTrusted0/isUidActive1/isUidVisible1/privacy0/procState2，Uid mode CAMERA:ignore。负责人在系统设置授予默认设备CAMERA后granted=true、Uid mode foreground，33分钟未断、主屏切13次应用、无restricted。推断：后续权限复查可能按默认设备判定；本输入未证明系统内部机制，不能写成已证实原因。产品不代授权。
- 系统失效事实C：设备端提前截断dumpsys media.camera后20:55:12 Virtual camera service died、客户端因HAL状态变化被evicted、虚拟摄像头消失；20:55:43再次截断时物理0–4也移除后重加。完整回传主机再过滤无该问题。当时helper流frames103、error=eglSwapBuffers failed EGL=12301，旧cameraId仍在；close该屏再建恢复。不自动制造崩溃，不自动恢复。
- 其它发现：按包名隐式am start在摄像头屏两次resolve失败(-91)，同包显式组件成功；fixture断流状态复位、标题遮挡、预览比例需修。close后Task #758仍在最近任务是已知行为，不自动清理，交负责人决定。
- 未验证保持待验：1280×720/1920×1080实际出流、steal带应用读回、IME、主屏息屏（负责人手机有凭据锁且不关闭）、任何第三方应用、多轮flaky、模拟器四项。新fixture不由Agent安装，修复后真机待负责人重装。
- 本轮起点复核：45ef319，worktree干净，仍只在virtual-display-screen-off本地提交；不push、不动main、不改三份风险门。不改变既有两处权限；本轮仅获准在自建虚拟屏启动已安装fixture，权限框出现即停。

## 端到端续修 A–E：实现、真机复验与清理（2026-11-15）

- 规格先提交fa8a7fb；本轮从45ef319继续，仅virtual-display-screen-off本地提交，不push、不动main。本节不改写此前进度：上节33分钟/颜色读回是集成方与负责人对旧制品的证据，本轮没有重新运行CameraActivity或安装新fixture。
- A提交c9572a7：仅显示定向且只给包名的路径，先固定cmd package resolve-activity --brief --components及MAIN/LAUNCHER参数，严格接受单个同包组件后am start -W --display N -n。--brief在框架实现中可能附元数据，固定--components消除元数据，而非放宽多行解析。普通无display路径保持原argv；命令失败、异常/跨包/多行/截断输出在启动前拒绝，ACTION_FAILED，不回退主屏。TestDisplayLaunchResolvesSingleSamePackageComponent、TestDisplayLaunchRejectsUntrustedResolverOutput和既有TestDisplayLaunchAndHierarchyNeverUsePrimaryFallback通过；先跑失败测试再实现。
- C第3点已实现，提交c580cda：CameraAvailability保守状态机，经virtual_camera Binder死亡通知/实际渲染失败标记unavailable及原因，不能建立监视时unknown；服务死亡优先于渲染错误，关闭屏前不自动恢复，旧cameraId仅作历史。Go/MCP增加可选availability/availabilityReason并兼容旧helper缺字段；协议仍v2，没有新工具、自动重建或自动重试。低成本依据是现有服务Binder监视与流错误回调，不额外拉起服务或轮询摄像头。监视存活不等于每个相机仍注册，也不证明权限/读回；未主动诱发系统崩溃。CameraAvailabilityTest四项（monitoredRegistrationDoesNotMeanFrameReadback、unmonitoredRegistrationRemainsUnknown、deathIsTerminalAndMoreSpecificThanRenderFailure、renderFailureIsBoundedAndSurvivesStreamRemoval）、TestCameraAvailabilityWirePreservesFailureAndLegacyUnknown、TestMCPCameraAvailabilityOutputDoesNotAddRecoveryTool通过。
- D提交6a4c1bf，仅test-only device-fixture：错误/断开正确清空资源与OPENING状态，代次屏蔽迟到回调；最多3次500/1000/2000ms重连，成功重开不重置预算，暂停取消重试，耗尽显示最终错误/次数；用户明确再次启动可开始新预算。隐藏ActionBar、避开系统栏/刘海，预览居中严格4:3完整容纳。CameraSessionPolicyTest四项（retriesAreBoundedAcrossSuccessfulReopens、pauseCancelsPendingRetryAndLateCallbacks、openingDoesNotDuplicateAndErrorsRemainBounded、previewContainsFourByThreeWithoutStretching）通过，纯逻辑先红后绿；fixture共14项单测、lint/build通过。Agent未安装新构建，真机重连/状态可见性/几何仍待负责人重装后验收。
- B/E文档：virtual-camera、troubleshooting、device-acceptance、MCP camera短说明明确本机稳定需要默认设备+虚拟设备两处CAMERA，由负责人自行完成，既有权限保持。restricted与ignore/foreground是实测事实，后续按默认设备复查只是推断；不代授权。close销毁Activity后最近任务仍留Task是已知系统行为，不自动清理，N60.6登记负责人决策。仅更新N60.6及其后续表，三份风险门未动。
- C完整dump与A验收脚本提交9e21f2e：全部已跟踪脚本media.camera审查，仅verify-virtual-camera-device.mjs有实际调用；直接将adb stdout/stderr完整写入主机文件，等待exit0后才在主机过滤，无输出缓冲截断、设备端head/管道或主动提前终止。文档禁止设备端截断，恢复只能由操作员关闭受影响屏再新建，不重启设备。脚本可选--launch-fixture true固定已装fixture的包/摘要，仅自建isolated屏按包名启动；权限框出现即停。
- 本轮五项make之前在Git Bash export PATH=/e/osdk-data/data/installs/go/1.26.5/bin:$PATH，随后直接运行make comments、make test、make verify、make build、make android-verify，全部exit0。Go358个顶层测试通过、含子测试742个pass事件、5项既有条件跳过、0失败；Android632项（631通过、1项既有app测试跳过）、0失败：app564、displayd48、device-fixture14、web-fixture6。协议56项、三平台vet、skills三份及镜像、624手写文件中文注释/16检查器自测通过。
- 真机脚本本轮于UTC13:21:29–13:25:26（JST22:21:29–22:25:26）完整运行，report.ok=true、cleaned=true。仓库外证据C:/Users/LJY/AppData/Local/Temp/aactl-vcam-e2e-fixes-20261115/final-helper/report.json；完整dump/层级/截图不入库。每步唯一serial324462538298/transport24，相同nubia指纹/API36/arm64，用户0、deviceLocked=0。新CLI SHA256=00eb44d3521fcdcab5e1a3854f4dc1ecbea919598bd4ae65d200c2865114f68f；新helper=a3fefb4871560611cdb10d1af216b3a9b7f1c8b949e890bba6eea56807df17f3；已装fixture仍为上节05ade33a…摘要/versionCode1，本轮没有安装/卸载或权限操作。
- 普通helper启动/初始化/停止后缓存未变。isolated摄像头屏65/设备18/camera v18_1001，displayFlags24456、组1、groupAwake=true、removeMode1，availability=available、streams为空；先观察DISPLAY_EMPTY，仅包名启动dev.aiauto.fixture后同屏层级读到PAGE:MAIN，无permissioncontroller，截图成功80560字节。未启动相机页，没有相机读回/流，图片两次接收均成功。steal屏66/设备19/camera v19_1002，flags20360、组1、awake=true、removeMode1、available、streams为空；未启动应用，层级DISPLAY_EMPTY/截图FRAME_NOT_READY如实记录，两次图片接收成功。
- 每次创建相机5→6、虚拟provider0→1，每次close及最终stop恢复5/normal2/public2/virtual0，完整dump的provider标识保持virtual/0-4。最终helper无进程、工具关联无行（Max ID8仅历史计数）、APK/log/UUID图片无、工具forward无。两条共享dalvik-cache基线与结束的存在性/inode/权限/mtime/ctime/子项完全一致，原样保留；未rmdir/递归删除。没有显示0输入/电源操作、负责人其它应用、权限/设置变更、设备重启或root。
- 本机曾出现命令/进程启动延迟，先查看已有会话并等待完成，没有重复派发设备动作、放宽判据或盲重试。先前测试正例曾使用不被既有组件校验接受的嵌套类名，改正测试为普通全限定名，产品校验未放宽。
- 结束后执行cd android && ./gradlew --stop，exit0；随后--status为No Gradle daemons are running（9.3.1），无本轮daemon残留。后续仅文档/规格提交与静态审查，不再启动Gradle。未启动模拟器，四项继续待验；新fixture设备效果、高分辨率实际出流、steal带应用读回、IME、凭据锁息屏、第三方应用、多轮flaky统计均未新增通过结论。

<a id="integrator-recheck-20261115"></a>

## 集成方复验与模拟器回归记录（2026-11-15 22:35–23:06 JST）

本节事实由集成方提供，执行者为集成方，负责人自行完成新版fixture安装与必要相机授权；原始证据及失败产物均在仓库外，不入库。本轮Agent仅记录文档/规格，不复跑设备、不连接手机、不启动模拟器，也不改任何代码、测试、脚本或构建。起点9f68242、worktree干净、未push；只在virtual-display-screen-off本地提交，main不动。

### A 真机复验

- serial324462538298，HEAD 9f68242构建；CLI SHA256为00eb44d3521fcdcab5e1a3854f4dc1ecbea919598bd4ae65d200c2865114f68f；helper为此前a7bbe34e…之后重新构建的a3fefb4871560611cdb10d1af216b3a9b7f1c8b949e890bba6eea56807df17f3（以本次模拟器环境记录中的完整摘要为准）。不混用旧制品的33分钟结论与新制品身份。
- A1：摄像头屏67上“打开相机页→切到主页（相机正常关闭）→重开相机页”做两轮，availability始终available、availabilityReason=null，每次重开均读回CAMERA:TL=red,TR=green,BL=blue,BR=white；helper日志无渲染错误。已验证正常关闭/重开无误报，未验证restricted强制断流时是否误报，两处授权都在时无法复现。
- A2：aactl action launch --display 67 --package dev.aiauto.fixture成功并读到PAGE:MAIN。
- A3：新事实是虚拟设备CAMERA授权不跨helper重启。display stop删除伴生关联，下一次display start --virtual-camera新建关联，实测编号7→8→9；虚拟授权挂在关联对应的持久设备标识上，新关联时系统权限框重新出现，负责人必须再授权。同一次helper运行、同一关联内关闭重建摄像头屏不需重授，此前证据不变。是否跨重启保留关联以保留授权，代价是设备常驻一条shell伴生关联，已登记N60.6由负责人决定；本轮不改stop清理行为，不代授权。
- A4：新版fixture SHA256为1dc42ad123f249f33fbcf5ab8f53ce08a278db01de7b7abbb7bbaaf433085adb，负责人自行adb install -r，两处授权保留、安装后未再弹框。横图读回CAMERA:TL=red,TR=green,BL=blue,BR=white；切竖图读回CAMERA:TL=white,TR=blue,BL=green,BR=red。状态文本bounds为[0,0][720,29]及[0,29][720,82]且截图可见，无标题栏；TextureView为[0,411][720,951]，即720×540（4:3）；元数据retries=0/3。未制造强制断流，自动重连仍只有单测覆盖，不计真机通过。
- A5：真机最终无helper、无工具关联、无工具临时文件、无forward，相机5个，fixture无Activity残留；/data/local/tmp/dalvik-cache原样保留。此为集成方清理记录，本轮Agent未接触设备。

### B 模拟器四项回归

环境osdk-test-35，-read-only -no-snapshot，每项前冷启动；指纹google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys，API35，320×640、160dpi、en-US、导航模式2、x86_64。制品摘要同本节A；手机现已断开，模拟器最终关闭，本轮不启动。

| 项目 | 次数 | 结果 / 范围 |
| --- | --- | --- |
| verify-virtual-display.mjs | 1 | 通过 |
| measure-virtual-display-focus.mjs --mode mitigated --scenario acceptance | 1 | 通过；含普通虚拟屏按包名启动com.google.android.deskclock，覆盖新的launcher解析路径 |
| verify-virtual-camera.mjs | 1 | 通过；摄像头不可用的明确错误、关联清理、普通虚拟屏继续可用 |
| verify-virtual-display-mcp.mjs | 3 | **2通过、1失败**；两次通过均64次工具调用、isolated和steal两策略通过；三轮清理核对全0 |

<a id="mcp-cold-start-failure-20261115"></a>

### MCP 冷启动失败记录与未证归因

- 失败轮在boot_completed后约34秒开跑（仅留8秒缓冲）；android_display_list返回DISPLAYD_NOT_RUNNING之后，startHelper()报DEADLINE_EXCEEDED: ADB command exceeded its deadline.。失败证据已留在仓库外，清理核对全0；该轮保留为失败，不由后续通过覆盖。
- 另两轮在boot_completed后等待45秒再开跑，两轮均64次工具调用、isolated与steal通过、清理核对全0。归因未证实：等待时间与结果的相关性仅3个样本，不能写成等待不足是确定根因。N60.6登记冷启动稳定等待规则与MCP多轮flaky统计；不放宽任何判据。
- 集成方第一次连续运行四项时，后三项因前一项按设计已关闭模拟器而以“设备不在线”停止。这是执行流程疏漏，不计入产品结果，也不并入上述完整轮次；每份脚本结束会关闭模拟器，连续运行须逐项重启，已写入docs/virtual-camera.md验收一节。

### 状态边界与本轮文档范围

- 已勾选模拟器四项“已执行”（明确MCP 2/3并链接失败记录）、新版fixture读回/文本可见/4:3预览，以及正常关闭重开两轮availability无误报；不是全部稳定性验收通过。VC5、原等待VC5的VC7已汇合，失败与待办继续公开。
- 保持未勾选：restricted强制断流下availability是否误报、自动重连真机、1280×720/1920×1080实际出流、steal带应用读回、IME、主屏息屏、第三方应用、冷启动稳定等待规则与MCP/其它多轮统计。两轮正常关闭和三轮MCP不替代多轮flaky结论。
- 仅修改本规格tasks/checklist/progress/device-acceptance、docs/virtual-camera、docs/troubleshooting、docs/next-phase-tasks的N60.6以及android/displayd/README；progress历史不改。仅重新运行make comments；其余四项由集成方在9f68242上独立跑过，本轮纯文档不复跑。

- 本轮文档验证：已在Git Bash设置指定Go PATH后直接运行make comments，exit0，覆盖624个手写文件、16个注释检查器自测通过。未运行其它构建/设备命令；未修改允许范围外的文件。提交前审查progress历史字节前缀完整、失败链接可定位、N60.6以外路线图保持不变；只做一个本地docs(camera)提交，不push。

## 可选保留关联开工（2026-11-16）

- 已复读AGENTS并核对Git：起点1af876e、virtual-display-screen-off与远端一致、worktree干净；main worktree仍f861a61，不动。负责人已决定增加默认关闭的stop可选保留开关，本轮先补规格再实现，所有提交仅本地、不push。
- 规则选定：普通模式运行helper忽略开关，不触发companiondevice或返回保留字段；helper未运行/摄像头模式才只读查询精确关联，无关联省略keptCameraAssociation。默认stop的argv与JSON保持，但摄像头启动失败保留既有关联是明确的失败语义变化，绝不写成所有默认行为不变。
- 本轮依VC18–VC21顺序实施，不派发并行Agent；只操作指定read-only模拟器一次摄像头脚本，不连接真机、不跑另外三份脚本；真机授权保留保持待验。

<a id="keep-camera-association-20261116"></a>

## 可选保留关联交付与模拟器验收（2026-11-16）

- 负责人已决定默认关闭的CLI stop --keep-camera-association；b79a5ac先提交规格，3485a73实现只读FindCameraAssociation、可选保留结果、独立清理及CLI/service窄路由。无匹配时省略keptCameraAssociation，查询失败在原分类cleanup中公开，不阻断helper文件/UUID图片清理；有编号而其它清理失败时错误details仍公开编号。普通模式运行helper忽略开关，不调用companiondevice、不报告保留字段；helper未运行时带开关只读查询当前用户并保留。默认stop的argv、成功JSON及失败分类保持，默认stop也是移除保留关联的唯一命令，普通运行helper先停后再默认stop。
- deac12e增加CameraAssociation.Created，成功新建与复用区分；启动后续失败先以保留方式停可核验helper、清文件/图片，再仅删除本次新建关联。调用前存在（含崩溃遗留）的关联原样保留，这是相对此前失败即删的明确变化，不能写成“所有默认失败行为不变”。错误公开cameraAssociation.action=kept/removed/removeFailed、原启动原因与清理失败。
- a6a2c34收紧失败诊断：helperCleanup不带中间stop暂存的kept字段，避免最终已删除却又显示保留；最终关联处理只看cameraAssociation.action。原错误和清理错误仍在原因链，按既有errors.As选择分类，裸错误维持INTERNAL_ERROR，不新增错误码。对应断言先失败，修正后定向及全量通过。
- Java只读审查Main.close→AndroidBackend.close/Screen.close→VirtualCameraBackend.close：只释放显示、帧、相机、VirtualDevice和Binder监视，不调用disassociate/removeAssociation，不假设关联即将删除。因此本轮没有修改任何Java。协议版本、MCP schema/工具数/入口、三份风险门均无改动，不增加配置文件、环境变量或设备端持久状态。
- 新增11项Go测试均通过：
  - TestFindCameraAssociationReadsOnlyExactMarker
  - TestStopKeepAssociationReportsActualIDAndCleansFiles
  - TestStopKeepAssociationAbsentOrQueryFailureStillCleans
  - TestStopKeepAssociationMergesIndependentCleanupErrors
  - TestStopOrdinaryHelperIgnoresKeepAssociationFlag
  - TestCameraFailedStartCleansOnlyNewAssociation
  - TestCameraFailedStartReportsRemovalAndIndependentCleanupFailures
  - TestCameraStartFailurePreservesRawErrorClassification
  - TestCLIStopKeepAssociationRealAdapterContract
  - TestCLIKeepAssociationFlagOnlyAcceptedByStop
  - TestServiceKeepAssociationUsesStopOnlyBackend
- 既有TestCameraAssociationExactCreateReuseAndCleanup强化Created与遗留复用断言；TestCameraStartFailureCleanupAndRunningHelperProtection明确本次新建且只删一次，未放宽既有测试。原Stop独立失败清理、普通helper保护和CLI API33/34链路继续通过。固定argv集成测试确认保留仅list 0，默认仍list 0→disassociate 0 com.android.shell 00:00:00:00:00:10→list 0；CLI仅stop接受裸开关/=true，重复、false/其它值及其它子命令均拒绝。
- 所有验证先在Git Bash export PATH=/e/osdk-data/data/installs/go/1.26.5/bin:$PATH，再直接运行make comments/test/verify/build/android-verify，最终全部exit0。第一轮全量后复核诊断新增断言/修正，故再次跑最终五项；最终测试覆盖全部生产变更。注释629个手写文件/16个检查器自测，协议56项、skills三份与镜像、Go全量与三平台vet通过。Android632项（631通过、1项既有app跳过）、0失败：app564、displayd48、fixture14、web-fixture6；本轮无Java变更。
- 模拟器脚本3e32dc2已扩展保留/同号复用/显式移除，并在finally前清理钩子无条件核对精确关联基线、工具文件，停止在保留阶段也会默认stop清关联；失败先留现场，不盲重试stop。不运行其它三份模拟器脚本。原始报告/日志全部在仓库外C:/Users/LJY/AppData/Local/Temp/aactl-camera-keep-20261116/scripts/emulator/build/virtual-camera/keep-association-1/report.json，不提交二进制、截图、原始证据。
- 启动前ADB为空且无既有emulator/qemu进程；仅启动emulator -avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio（PID71100）。boot_completed首次观察为UTC2026-11-15T16:39:48.187Z，另显式等待45秒；正式脚本开始UTC16:44:30.056Z、结束16:44:50.591Z，即JST2026-11-16 01:44:30–01:44:50。从首次完成观察到脚本开始实际281869ms（含主机调度/记录开销），满足至少45秒，不把等待视为已证flaky根因修复。
- 唯一serial emulator-5554/transport34，AVD osdk-test-35/API35/x86_64，320×640/160dpi、en-US、导航模式2，指纹google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。最终CLI SHA256=0f768e65018aa8dc62b2b7b0abe55a64eb9f755f53561d483e416dd96cdc0a07；helper=a3fefb4871560611cdb10d1af216b3a9b7f1c8b949e890bba6eea56807df17f3；fixture=1dc42ad123f249f33fbcf5ab8f53ce08a278db01de7b7abbb7bbaaf433085adb。只在该disposable emulator安装本地test-only fixture，不连接/操作真机，不修改任何应用权限。

| 模拟器步骤 | 实测输出 / 判据 |
| --- | --- |
| start --virtual-camera | userId=0，工具关联id=1；重复start能力开启且关联行不变 |
| 请求摄像头屏 | VIRTUAL_CAMERA_UNAVAILABLE，API35/RemoteException诊断；无遗留屏，不另推断本轮底层原因 |
| 普通虚拟屏 | 创建/关闭通过，未带camera字段 |
| stop --keep-camera-association | stopped=true，keptCameraAssociation={userId:0,id:1}；完整list仍1，helper=0、临时文件=0 |
| 再次start --virtual-camera | virtualCamera=true，完整list仍id=1 |
| 默认stop | 工具关联消失，其它关联行与基线相同；remainingToolAssociations=0 |
| 普通helper未启用摄像头 | 请求camera返回VIRTUAL_CAMERA_NOT_ENABLED |
| finally及宿主复核 | report.ok=true、cameraAssociations=0；cleanup的helper/displays/forwards/temporaryFiles均0；emulatorKillRequested=true；随后ADB为空、emulator/qemu进程均无 |

- 脚手架现象：附加只读启动记录在Node REPL跨调用引用了块内变量bootFingerprintKeep而报未定义；未改变设备状态。正式脚本自身仍逐步执行唯一设备/指纹守卫，未重复正式脚本或放宽判据；改从已落盘boot-observation.json与最终report读取，核对真实等待时长281869ms。该错误不计为产品失败，也不把事后时间核对伪称事前记录。
- 文档同步：已搜索所有--virtual-camera文档位置，更新README、虚拟屏/摄像头/架构/协议说明、安全分发、排障、helper README、模拟器说明、skills参考及镜像和本规格人工步骤。progress旧行不改；N60.6仅主行及“跨helper重启保留关联”一行更新，其它遗留行保持。保留的常驻shell COMPANION_DEVICE_APP_STREAMING关联与既有虚拟CAMERA授权、精确只读核对、归还/转手前默认stop、无helper亦可、普通模式忽略、禁止disassociate-all/代授权/点框均明确写出。
- 真机“保留关联后helper重启无需重新授权”仍未验证，本轮没有真机；仅有既有同关联重建屏免重授的旁证。强制断流availability、自动重连、高分辨率、steal带应用读回、IME/息屏/第三方及多轮统计等旧未验项没有新增通过结论，既有MCP 2/3和失败记录保留。
- 结束后cd android && ./gradlew --stop，exit0；--status显示No Gradle daemons are running（9.3.1），进程正常退出。后续仅文档提交与只读Git检查，不再启动Gradle。所有提交仅当前分支本地，不push、不动main。


本轮提交文件清单（收口文档与此记录同一提交，其hash见git log）：

| 提交 | 文件 |
| --- | --- |
| b79a5ac | `.trae/specs/virtual-camera/checklist.md`、`.trae/specs/virtual-camera/progress.md`、`.trae/specs/virtual-camera/spec.md`、`.trae/specs/virtual-camera/tasks.md` |
| 3485a73 | `internal/adb/association_keep_test.go`、`internal/adb/virtual_camera.go`、`internal/cli/association_keep_test.go`、`internal/cli/display.go`、`internal/service/association_keep_test.go`、`internal/service/display.go`、`internal/vdisplay/association_keep_test.go`、`internal/vdisplay/camera.go`、`internal/vdisplay/client.go` |
| deac12e | `internal/adb/virtual_camera.go`、`internal/adb/virtual_camera_test.go`、`internal/vdisplay/association_start_test.go`、`internal/vdisplay/camera.go`、`internal/vdisplay/camera_test.go`、`internal/vdisplay/client.go` |
| 3e32dc2 | `scripts/emulator/verify-virtual-camera.mjs` |
| a6a2c34 | `internal/vdisplay/association_start_test.go`、`internal/vdisplay/camera.go` |
| 收口docs(camera) | `.trae/skills/android-device-automation/references/commands.md`、`.trae/specs/virtual-camera/checklist.md`、`.trae/specs/virtual-camera/device-acceptance.md`、`.trae/specs/virtual-camera/progress.md`、`.trae/specs/virtual-camera/spec.md`、`.trae/specs/virtual-camera/tasks.md`、`README.md`、`android/displayd/README.md`、`docs/architecture.md`、`docs/next-phase-tasks.md`、`docs/protocol.md`、`docs/security-distribution.md`、`docs/troubleshooting.md`、`docs/virtual-camera.md`、`docs/virtual-display.md`、`scripts/emulator/README.md`、`skills/android-device-automation/references/commands.md` |

## b6956真机死锁事实与安全拆除开工（2026-11-19）

本节现场由集成方提供，执行于2026-11-18 23:05–23:30 CST（JST次日00:05–00:30），原始证据仓库外，不是本Agent执行。本轮不执行任何设备命令，包括不枚举ADB、不启动模拟器。

- serial324462538298系统已更新：nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6956:user/release-keys，构建2026-11-16，旧b6416不能代替本轮身份。fixture仍1dc42ad1…，默认CAMERA granted/foreground。aactl为37c8916构建0f768e65018aa8dc62b2b7b0abe55a64eb9f755f53561d483e416dd96cdc0a07；helper a3fefb4871560611cdb10d1af216b3a9b7f1c8b949e890bba6eea56807df17f3。
- start --virtual-camera新建关联10；create得到屏2、cameraId v1_1000、flags24456。系统权限框在屏2；负责人在对话中明确授权集成方代点，集成方点“使用期间允许”。这不是产品自动授权，也不扩大本Agent权限。随后三次CAMERA:TL=red,TR=green,BL=blue,BR=white；CAMERA_DEVICE:id=0,facing=back,...,display=2,retries=0/3；status为640×480/525帧/available，主屏top focus仍0。
- 出流中close屏2：23:08:08.889逻辑显示移除，.891 CameraService onDeviceStatusChanged v1_1000 newStatus=0、evicted due to device status change from HAL。fixture主线程卡ICameraDeviceUser.cancelRequest，23:08:13被ANR杀掉；转储cameraserver线程卡HAL repeatingRequestEnd、processCaptureRequest及disconnectImpl锁。CLI close和随后stop --keep-camera-association各10秒后PROTOCOL_ERROR响应头超时；helper一个worker与出帧线程卡Binder，其余请求排队AndroidBackend锁。
- 集成方留证后对已核验cmdline=aactl-displayd、属主shell的helper执行kill；system_server记录Virtual camera binder died，但virtual_camera未恢复：timeout 8 cmd virtual_camera help退出124，cameraserver残留失效v1_1000、总数6；真实0–4为closed且无client。此为现场历史，不新增任何kill路径。
- 后续普通虚拟屏3打开fixture：CAMERA:ERROR:OPEN_IllegalStateException:Failed to register a camera service listener:RETRIES=3/3:EXHAUSTED；重试日志addListenerHelper already subscribed。首次失败原因未取得，不能断言真实相机已坏，也不能断言正常。
- 清理：普通helper默认stop成功，不触碰伴生服务；无helper、显示、forward、工具临时文件；关联10按计划保留。免重授仍未验证；系统相机服务恢复须负责人重启手机。

读源码核对（仅仓库外快照，16_标记android-16.0.0_r1，a17_标记android17-release；没有提交源码副本）：16_main.cc只有setThreadPoolMaxThreadCount(6)和joinThreadPool，无startThreadPool；a17_main.cc有startThreadPool。16_VirtualCameraController.unregisterCamera同步调用服务，CameraDescriptor.binderDied及VirtualDeviceImpl.close也走注销。16_VirtualCameraService/Provider的removeCamera没有先关闭会话而同步通知NOT_PRESENT；q2_Camera3Device.cpp的disconnectImpl持mInterfaceLock且可能join请求线程。这些是读源码结果，不能仅据此认定OEM二进制与上游完全相同。推断：A16该启动路径仅主Binder线程处理，活动会话注销时HAL/cameraserver/应用及帧队列构成跨进程环形等待；helper出流中外部kill/崩溃同样触发Binder死亡注销。此与现场栈相符但不是对OEM内部机制的独立证明；A17源码修正不能视为已回补此A16设备。在线源码打开失败，本轮依据提供的可读快照核对，不声称重新下载验证。

- 我们的旧触发路径经仓库核对：Screen.close先display.release再camera.close，VirtualCameraBackend.close立刻停流并注销，不等待应用onStreamClosed；Main在收到shutdown请求后即使backend失败也会在finally退出。Go只接受固定错误集，wire10秒。按VC22–VC25改为6秒排空门与BUSY，不能声称已修复卡死。
- Git起点4105965与远端一致，worktree干净，test(adb) CI修复为集成方提交，不改写；main worktree仍f861a61。仅当前分支本地提交，不push、不动main/三份风险门。


<a id="camera-teardown-20261119"></a>

## 安全拆除实现与静态验证（2026-11-19，真机待验）

- 规格先提交45d5d51。5d82a66修改Java：先display.release（销毁内容标志保持），保持仍工作的出帧，CameraRelease在同一流表monitor上按单调时钟最多等6000ms；wait释放monitor，onStreamClosed移除流/发停止信号并notifyAll。流空才封口、停止残余线程（不join）、VirtualCamera.close→VirtualDevice.close；Binder注销在monitor外。超时/中断返回BUSY，不停止活跃流、不注销、不丢引用；资源调用失败也保留重试所有权。
- AndroidBackend在进入全局串行锁前记录每请求排空deadline，shutdown全部显示和创建残留共享该deadline，避免两屏各等6秒；队列中的后续关闭请求不重置预算。本轮保留全局框架串行边界，status/list可能等待本地排空预算。外部Binder若已卡死/框架release本身阻塞或调用方更短deadline不在局部计时可中断保证内，不能写所有请求必在6秒完成。
- 摄像头BUSY保留原屏编号/容量、cameraInfo.releasePending=true，显示已进入释放；不再用于输入/启动/截图/图片目标，不查询失效DisplayInfo或按旧id保活。status.state=0/groupId=-1/groupAwake=null，displayFlags为创建时实际快照。创建失败的意外活动流保留内部Screen引用，status.pendingCameraReleases公开无编号残留数，shutdown重试；客户端验证已知显示+残留计数总和最多2。成功释放记录才移除；普通DisplayManager屏保留既有立即释放路径。
- ShutdownCompletion只在backend.execute安全完成shutdown后授予退出资格，BUSY/框架异常即使进入finally也不System.exit；监听/线程池/剩余资源保留。shutdown可先释放成功屏，失败屏留待下一次显式close/stop。创建失败清理也用同一空流门，不假设绝无流。外部kill/崩溃的Binder死亡不可由Java hook取消，不新增kill或force-stop路径。
- 09845be新增VIRTUAL_CAMERA_BUSY常量、allowed列表/可重试映射、CLI退出4；Go单次wire具名10秒，跨语言测试读取Java等待常量，断言至少3秒余量（当前6秒等待+4秒余量）。直接close、默认stop、keep-stop均透传BUSY及下一步提示，stop在shutdown失败处返回，不删helper文件/图片/关联，forward仍按调用清理。启动失败回滚也必须确认stopped=true才删本次新建关联；回滚遇BUSY则保留新建关联和helper，避免通过disassociate间接关闭活动设备。MCP只扩可选状态/schema与close提示，没有新工具/入口/风险门/版本变更。
- 新增Java11项、Go7项均通过，既有测试未改弱。Java新增测试：
  - CameraReleaseTest.activeStreamsTimeoutWithoutStoppingOrUnregistering
  - CameraReleaseTest.streamClosureBeforeDeadlinePreservesRenderingThenClosesInOrder
  - CameraReleaseTest.conditionWaitReleasesMonitorForStreamCallback
  - CameraReleaseTest.shutdownSharesOneDeadlineAndDoesNotCompleteWhenBusy
  - CameraReleaseTest.interruptedWaitKeepsResourcesAndReportsBusy
  - CameraReleaseTest.emptyCreationRollbackClosesWithoutWaiting
  - CameraReleaseTest.failedCameraCloseRetainsDeviceAndCanRetry
  - CameraReleaseTest.unregisterRunsOutsideStreamMonitor
  - CameraReleaseTest.shutdownPrefersBusyAndPreservesAllFailures
  - ShutdownCompletionTest.busyShutdownCannotExitAndNextRequestCanRetry
  - ShutdownCompletionTest.normalRequestOrFrameworkFailureCannotExit
- Go新增测试：
  - TestCameraBusyCloseAndStopPreserveLiveResources
  - TestPendingCameraStateIsVisibleButNotAnActionTarget
  - TestWireBudgetCoversSharedCameraDrain
  - TestStatusRejectsPendingCameraReleaseOverBudget
  - TestCLICameraBusyCloseAndStopAreRetryable
  - TestMCPCameraBusyCloseAndPendingStatus
  - TestCameraFailedStartBusyShutdownRetainsNewAssociation
- 失败与改正：Go新测试先因BUSY常量/待释放字段未定义编译失败；MCP新增status测试真实捕获releasePending未进入输出schema，补可选字段/残留计数后通过。中文注释检查曾拒绝ShutdownCompletion用途注释缺少可识别动作，改为“管理安全拆除成功后的退出资格”后通过。一次文档替换脚手架误用status schema的integer锚点（实际为const），未写入文件，按真实schema修正；没有放宽测试或验收判据。
- 全量验证前在Git Bash export PATH=/e/osdk-data/data/installs/go/1.26.5/bin:$PATH，直接运行make comments、make test、make verify、make build、make android-verify，最终全部exit0。注释636个手写文件/16个检查器自测；协议56项、skills三份及镜像、Go全量与三平台vet通过。Android643项，642通过、1项既有app测试跳过、0失败：app564、displayd59、fixture14、web-fixture6；单测/lint/构建均通过。日志在仓库外C:/Users/LJY/AppData/Local/Temp/aactl-camera-teardown-20261119，不提交构建产物/证据/AOSP源码副本。
- Gradle清理：先在E:/osdk-data/cache/pkg/gradle查询No daemon，但宿主只读检查发现本轮targeted osdk调用的daemon47164实际位于C:/Users/LJY/.gradle。按该实际user home执行cd android && ./gradlew --stop，exit0、1 Daemon stopped；--status为No Gradle daemons are running且47164 STOPPED，宿主GradleDaemon进程复核为空。没有按PID强杀，没有修改系统配置；后续只改文档，不再启动Gradle。
- 文档更新四处关闭语义/忙碌处理/禁止出流kill/死锁症状与负责人重启恢复；README、架构/协议错误表/安全分发、skills引用及镜像同步。N60.6新增b6956验收优先项并保留旧MCP 2/3失败等其它未验项。仍只能说“已改拆除顺序，真机待验”：本轮没有任何设备命令、没有ADB枚举、没有模拟器启动，没有授权/安装/force-stop/kill/设备重启。
- 真机关闭不死锁、人工force-stop后同编号BUSY恢复、创建残留/两屏shutdown设备行为、保留关联helper重启免重授均未验证，保持未勾选，须负责人重启b6956手机后由集成方执行。旧高分辨率/steal/IME/息屏/第三方/强制断流诊断/多轮统计也未新增通过结论。现场的关联10与失效v1_1000/相机数6来自集成方记录，不冒称Agent已恢复或清理系统状态。

本轮源码核对引用（根目录为前节给出的仓库外scratchpad/aosp；prefix/tag按集成方说明，本轮实际逐文件读取；不以在线打开失败的页面冒充下载核验）：

| 快照 | 核对位置 | SHA-256 |
| --- | --- | --- |
| 16_main.cc | 43/65 | 796317b19e967209e241d35842241b893aa4ce8792fa9f7f8b4e19a206a15b5f |
| a17_main.cc | 44/45/67 | 67f8e554f439ca2368fb19f30b771542b2287334ab86e02985279ecf9fb52dbe |
| 16_VirtualCameraController.java | 115/125/149/160/168/267/269 | d09ce40c5744c234728733dc86e608d3fa49ebfe0649821cf5b5cfe3c9ebf459 |
| 16_VirtualDeviceImpl.java | 777/805/828/833/1621 | 870a2734ee0dfc0aa4424adf88b1647649cdd27c046883be353e1a1daf2fe341 |
| 16_VirtualCameraProvider.cc | 193/202/209 | d541602cf443522b4f987a05d7e9d1d119ad55a3abb712de64b0b3243d369d67 |
| q2_Camera3Device.cpp | 295/297/353 | 81ad0f8697b841455e4a0ebf2cf6a0f6acc1f90e27e4dc23f248cf4b0f6943b4 |
| q2_ProcessState.cpp | 435/437 | 56f7cd3f88dd04605be08b5488dd1ac9de888a425a8011e5b07782f262fd9478 |

补充读源码：16_VirtualCameraService.cc:326/345调用Provider.removeCamera；q2_CameraService.cpp:524–589在NOT_PRESENT路径直接disconnectClients，660–668的disconnectClient同步调用客户端disconnect；q2_Camera3Device.cpp:295/297/353持接口锁并join。Controller的service binderDied只清表，与CameraDescriptor.binderDied（267–269注销）不得混淆。上游代码与现场环形等待的对应关系仍是推断，不声称已验证OEM二进制内部线程池实现或修复效果。



本轮提交文件清单（收口文档与本记录同一提交，hash见git log）：

| 提交 | 文件 |
| --- | --- |
| 45d5d51 | `.trae/specs/virtual-camera/checklist.md`、`.trae/specs/virtual-camera/progress.md`、`.trae/specs/virtual-camera/spec.md`、`.trae/specs/virtual-camera/tasks.md` |
| 5d82a66 | `android/displayd/src/main/java/dev/aiauto/displayd/AndroidBackend.java`、`android/displayd/src/main/java/dev/aiauto/displayd/CameraRelease.java`、`android/displayd/src/main/java/dev/aiauto/displayd/Main.java`、`android/displayd/src/main/java/dev/aiauto/displayd/ShutdownCompletion.java`、`android/displayd/src/main/java/dev/aiauto/displayd/VirtualCameraBackend.java`、`android/displayd/src/test/java/dev/aiauto/displayd/CameraReleaseTest.java`、`android/displayd/src/test/java/dev/aiauto/displayd/ShutdownCompletionTest.java` |
| 09845be | `internal/apperr/error.go`、`internal/cli/camera_busy_test.go`、`internal/mcpserver/camera_busy_test.go`、`internal/mcpserver/display.go`、`internal/vdisplay/association_start_test.go`、`internal/vdisplay/camera.go`、`internal/vdisplay/camera_busy_test.go`、`internal/vdisplay/client.go` |
| docs(camera)收口 | `.trae/skills/android-device-automation/references/commands.md`、`.trae/skills/android-device-observation/references/commands.md`、`.trae/specs/virtual-camera/checklist.md`、`.trae/specs/virtual-camera/device-acceptance.md`、`.trae/specs/virtual-camera/progress.md`、`.trae/specs/virtual-camera/tasks.md`、`README.md`、`android/displayd/README.md`、`docs/architecture.md`、`docs/next-phase-tasks.md`、`docs/protocol.md`、`docs/security-distribution.md`、`docs/troubleshooting.md`、`docs/virtual-camera.md`、`skills/android-device-automation/references/commands.md`、`skills/android-device-observation/references/commands.md` |

- 本轮记录仅追加；最终文档格式、允许范围与提交后工作区状态见收尾报告，不以静态验证冒充设备恢复。
- 本轮本地构建CLI SHA256=ea429de225b89d909fcd3caf65ff4623eab9be42b5214454ecdb0f64f47740f3，helper SHA256=49307540443b2df252f53f35a308207f2c493c008569ebc414a406abcf4f18fe；均未部署到设备，摘要仅供后续集成核对。文档补充同版CLI/helper升级要求：旧helper活动流未释放时不得stop/kill升级。
