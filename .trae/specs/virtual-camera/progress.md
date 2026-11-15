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