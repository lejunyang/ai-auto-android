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
