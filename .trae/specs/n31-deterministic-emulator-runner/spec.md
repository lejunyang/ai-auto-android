# N31 固定 API Emulator Runner 规格

## 目标

为 API 30、33、34 建立可重复、可并发互斥、可从干净快照启动的本地
Emulator 控制面。所有 SDK、AVD、缓存和运行时状态位于仓库外
`/Volumes/aigo S7 Media/SDK/android-tools`，仓库只保存可复现配置和脚本。

## 范围

- 管理 create、start、wait-for-boot、snapshot restore、stop 和 delete 生命周期。
- 固定 system image、ABI、设备规格、分辨率、语言、时区、导航模式和 WebView。
- 所有成功结果返回明确 serial、AVD 名称和设备指纹。
- 使用跨进程锁阻止 AVD 或 serial 被多个任务复用。
- 为镜像缺失、磁盘不足、端口冲突、启动超时、残留进程和版本不匹配返回稳定错误码。
- 提供 macOS 实现和 Windows 等价命令路径及 CI smoke。

## 安全边界

- 不执行共享 `adb kill-server`，不接管非本任务创建的 emulator。
- stop 或 delete 失败时不得提前释放设备锁。
- 无法解析 SDK、emulator 或 WebView 版本时失败关闭。
- 不修改生产 Bridge、release Manifest 或普通真机授权流程。

## 允许修改

- `scripts/emulator/`
- `android/settings.gradle.kts`
- Android 测试根构建配置
- N31 专用 CI workflow
- 本 change spec

## 验收

- 单元测试覆盖生命周期、错误码、互斥锁、stop 失败和版本解析失败。
- API 30、33、34 各连续完成 10 次启动、快照恢复和销毁。
- serial 不重复，快照状态一致，失败后无本任务 emulator 进程或锁残留。
- 运行 `git diff --check`、`make comments` 和相关 Android 构建。
