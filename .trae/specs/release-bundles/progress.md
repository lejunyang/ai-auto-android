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

## Round 3 — R2 离线测试、官方固定与打包

- RED：先增加 scripts/releasebundles/main_test.go，原仓库因缺 config/run 等打包接口而编译失败；随后实现发布器。
- GREEN：Go 标准库离线测试 20/20（含子测试）通过；验证四目标的完整/精简目录、文件/权限/时间、九资产摘要、第二次生成逐字节一致、精简不读清单/不调用下载器、损坏缓存清除、旧输出保留与所有失败无 staging/part 残留。workflow-portability 6/6、定向 vet、注释检查通过。
- 实现策略：release.sh 只传显式 GO/Gradle wrapper；Go 发布器下载/校验/白名单读取、构建同版 CLI 与 :app:assembleDebug / :displayd:assembleDebug，在 staging 完成后 rename；已存在 DIST_DIR 明确拒绝并保留，不递归覆盖。精简源码构建另限制 GOPROXY=off / GOSUMDB=off / GOTOOLCHAIN=local 和 Gradle --offline，缺缓存会失败；不联网补依赖。
- 官方清单取值方法：2026-11-14 JST 用 Invoke-WebRequest 从 https://dl.google.com/android/repository/repository2-3.xml 下载 XML；按 remotePackage path=platform-tools 且 revision=37.0.1 选择 channel-0、uses-license=android-sdk-license；从每个 archive 的 host-os/complete/url、size、checksum[type=sha1] 取值。macosx 映射 darwin，windows 文件名后缀 win，Linux 后缀 linux。
- 三份原始值与实际下载计算：
  - darwin：platform-tools_r37.0.1-darwin.zip，16110554 字节，SHA-1 6ae73f4de6452dc57e62ec02b68eed92a4c21661，SHA-256 ee39ad5967e95c2a07f04dbcbde96b1a0c916ba376096db5d2f498b7727a5d1d。
  - linux：platform-tools_r37.0.1-linux.zip，9054187 字节，SHA-1 477254aa5f903c15cf51001717bdf347fb6b53e0，SHA-256 d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1。
  - windows：platform-tools_r37.0.1-win.zip，8044989 字节，SHA-1 e03e78b1d80b396f1c3358e31251cb31740e1110，SHA-256 45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99。
- 首次手工下载核对命令漏设任务要求的许可环境变量；该缓存未用于发布。已删除这三份缓存，显式设置 AACTL_ACCEPT_ANDROID_SDK_LICENSE=android-sdk-license 并加前置校验后重新从同一 Google 官方地址下载，size/SHA-1 与 XML 全部匹配，SHA-256 与首次计算一致。后续真实发布必须经过程序许可门；未绕过确认或换源。本次纠正不隐去原始过程。
- 缓存在仓库外 TEMP/aactl-release-bundles-cache；XML 与二进制不入 Git。官方归档只读结构审查无异常路径/链接；Go debug/macho 确认 macOS ADB 同时含 CpuAmd64/CpuArm64，依赖系统 framework；debug/elf 的 Linux ADB 只依赖系统库，无归档 lib64 依赖。Windows 包保留两个指定 DLL。此项仅静态结构审查，不是 macOS/Linux 运行验证。
