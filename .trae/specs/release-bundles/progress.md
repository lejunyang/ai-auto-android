# 发布包进展（append-only）

## Round 1 — 2026-11-14 开工核对与规格

- `git status --short --branch`：仅 virtual-display-screen-off...origin/virtual-display-screen-off，无改动。
- `git worktree list --porcelain`：本目录 HEAD d565282cbd8a0a4b78373d0f2d64d8fa8302292b；main 在 E:/Projects/ai-auto-android，HEAD f861a615768cca81c39b57d666c4ca9efe3e4233。本任务不动其它 worktree，不 push。
- 已读根 AGENTS.md、release.sh/release.yml、locator、doctor 入口、osdk.toml、displayd Gradle、路线图 N60.2 与相关文档。pin 为 android-platform-tools 37.0.1；displayd 采用 debug 变体，不安装 APK。
- 官方元数据地址为 https://dl.google.com/android/repository/repository2-3.xml。web 工具无法打开该 XML，改用 PowerShell Invoke-WebRequest 下载到仓库外 TEMP/aactl-release-bundles-cache；未换源。实际解析 XML 确认稳定 channel-0 的 37.0.1、uses-license=android-sdk-license；更新的 preview 37.0.3 不取用。
- 本机命令含 osdk 垫片；osdk 无 which 子命令，后续用安装目录中的真实 .exe 路径，不依赖 Node/Go 对裸命令的解析。该探测未修改项目。
- 当前仅建立规格；所有实现和验收尚未开始。

## Round 2 — R1 RED/GREEN

- RED：新增 locator 顺序/损坏/符号链接/CWD 与 doctor 两格式来源测试后运行定向测试；原实现因缺 Executable、ResolveWithSource、Resolution 编译失败，确认测试先于实现。
- GREEN：新增同一次解析返回路径/来源的端口，随包候选从解析符号链接后的可执行文件目录读取；损坏显式拒绝，候选须常规文件。CLI doctor 加 adbSource，未改 MCP 或既有风险门。
- `go test ./internal/adb ./internal/cli -count=1` 通过（8.663s / 32.318s）。Windows 上 executable 符号链接和 dangling symlink 实际创建并验证通过，无跳过；非执行文件按注入 linux GOOS 验证执行位。
