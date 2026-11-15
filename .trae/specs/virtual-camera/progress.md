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
