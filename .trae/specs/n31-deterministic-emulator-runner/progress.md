# N31 Progress

本文件 append-only，记录实现、测试、设备证据、失败与清理结果。

## Round 1

- 已创建独立 change spec。
- 当前状态：实现与设备验收尚未开始。
- 基线发现：文档所述 `next/n31-emulator-runner` 分支在当前 clone 与远端均不存在，
  本地 reflog 和不可达对象也无法恢复；必须依据已知 Major 重新实现并复验。

## Round 2

- 已将 Go 1.26.5、Temurin 21.0.7+6、Gradle 9.3.1、Android Command-line Tools
  22.0、Platform-Tools 37.0.0、Emulator 36.6.11、Platform/Build Tools 36 和
  API 30/33/34 Google APIs ARM64 镜像安装到用户指定的仓库外 SDK 根。
- 三套镜像分别固定 revision 16/17/14，安装前校验 Google repository 官方 SHA-1、
  archive size 和本地 SHA-256，事务安装完成后原子生成 package receipt。
- 已实现显式 SDK/JDK/AVD/state root、owned AVD marker、全局原子 serial allocator、
  AVD/port 双重 lease、create/start/wait/snapshot/restore/stop/delete、稳定错误码和
  Windows native `.exe` 路径 smoke。
- 已关闭历史 Major：stop 未确认 PID 与 transport 双重消失时保留 runtime/lease；
  WebView package/version 无法解析或不匹配时失败关闭。
- Node 测试 12/12 通过，覆盖完整 fake 生命周期、锁互斥、serial 轮转、启动状态写
  失败、snapshot 超时、Windows 路径和版本失败关闭；中文注释检查覆盖 188 个文件
  通过，`git diff --check` 通过。
- API 30/33/34 baseline 已分别固定 WebView 91.0.4472.114、109.0.5414.123、
  113.0.5672.136；每个 API 均完成 clean marker、snapshot 保存、dirty marker、
  restore 后 clean marker及 fingerprint 不变验证。
- API 34 首次 snapshot save 暴露 60 秒预算不足，明确失败后将独立 snapshot 预算
  调整为 300 秒并增加 `SNAPSHOT_SAVE_TIMEOUT`/`SNAPSHOT_RESTORE_TIMEOUT` 测试；
  复验取得明确成功回执。
- 当前正在运行每个 API 10 轮的 macOS 本地矩阵；正式矩阵和 Windows CI smoke
  未完成前保持 N31 未完成且不提交。

## Round 3

- macOS arm64 正式矩阵通过：API 30、33、34 各运行 10 轮，分别耗时
  159055ms、156180ms、162818ms；30 个 serial 全局唯一。
- 每轮均从 owned `clean` snapshot 启动，fingerprint 与 baseline 一致；marker
  在启动后为 `clean`，写为 `dirty-N` 后 restore 再次为 `clean`，30/30 一致。
- API 30/33/34 fingerprint 分别为
  `51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707`、
  `082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f`、
  `1ac57e687c4b30bdefdb296b1b79158e9e9b3d8297f13817a9edb153ecf5ea8f`。
- 矩阵后 `aactl devices list --json` 返回 0 台设备，owned emulator 进程、
  runtime 文件和 AVD/port lease 均无残留；未执行共享 `adb kill-server`。
- Windows 等价路径由注入 `win32` 的 `adb.exe`、`emulator.exe`、`java.exe`
  argv 单测覆盖；AVD 管理直接调用官方 `AvdManagerCli` Java 主类，不依赖 `.bat`
  或 shell。已新增 Ubuntu/macOS/Windows 专用 CI smoke；本轮未在 Windows 主机
  运行真实 emulator，不把 CI 路径测试描述为 Windows 设备矩阵。
- 使用 JDK 直接调用 `AvdManagerCli` 的真实 create/delete smoke 已通过：临时
  `ai-auto-direct-cli-smoke` AVD 创建、owner marker 校验和删除均成功，无目录、
  runtime、lease 或 emulator 进程残留。
- `make verify` 通过 ADB smoke、协议 29 项、3 组 Skills 及镜像、CLI 示例、188
  个受管文件注释检查、14 个正反例和工具链元数据；runner 测试 12/12 通过。
- 仓库外机器可读报告位于 SDK state 的 `reports/n31-matrix.json`，不提交设备产物。
