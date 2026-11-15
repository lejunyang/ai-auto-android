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
