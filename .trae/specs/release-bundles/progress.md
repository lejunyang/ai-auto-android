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

## Round 4 — 2026-11-15 R3 真实发布、隔离环境与设备验收

### 构建和归档

- 实现提交 71ea6f72547c7c475ec6d01770f8225c0689b55f；真实版本 dev-71ea6f7。显式设置 AACTL_ACCEPT_ANDROID_SDK_LICENSE=android-sdk-license 与仓库外 PLATFORM_TOOLS_CACHE，使用固定 Go/Node/JDK/SDK，运行 make release，输出到忽略目录 artifacts/release-bundles/dist。Gradle :app:assembleDebug :displayd:assembleDebug 成功（1m10s，73任务，6执行/67缓存）。未上传或发布远端资产。
- SOURCE_DATE_EPOCH 未设置，取该提交时间 1794668686（2026-11-15T00:04:46+09:00）；tar/zip 逐项列表中的时间、固定排序及0755/0644权限正确，顶级目录与归档去扩展名一致。九资产 SHA256SUMS 全部重新计算并通过；无裸 CLI 资产或额外文件。
- 每个归档公共文件：README.txt、aactl-displayd.apk、aactl（Windows 为 aactl.exe）。helper为80915字节，SHA-256 7a0d8904db3e7a4e90143ceba8d764b27ad6957971b823b266640ac12620c53c。完整包额外文件如下；没有自动加入 LICENSE。

| 归档 | 字节数 | MiB | 归档内容（顶级目录内） |
| --- | ---: | ---: | --- |
| aactl_dev-71ea6f7_darwin_arm64.tar.gz | 11555764 | 11.02 | README.txt、aactl、aactl-displayd.apk、platform-tools/adb、platform-tools/NOTICE.txt |
| aactl_dev-71ea6f7_darwin_arm64_slim.tar.gz | 2963508 | 2.83 | README.txt、aactl、aactl-displayd.apk |
| aactl_dev-71ea6f7_darwin_amd64.tar.gz | 11772808 | 11.23 | README.txt、aactl、aactl-displayd.apk、platform-tools/adb、platform-tools/NOTICE.txt |
| aactl_dev-71ea6f7_darwin_amd64_slim.tar.gz | 3181363 | 3.03 | README.txt、aactl、aactl-displayd.apk |
| aactl_dev-71ea6f7_windows_amd64.zip | 7684529 | 7.33 | README.txt、aactl.exe、aactl-displayd.apk、platform-tools/adb.exe、platform-tools/AdbWinApi.dll、platform-tools/AdbWinUsbApi.dll、platform-tools/NOTICE.txt |
| aactl_dev-71ea6f7_windows_amd64_slim.zip | 3241320 | 3.09 | README.txt、aactl.exe、aactl-displayd.apk |
| aactl_dev-71ea6f7_linux_amd64.tar.gz | 7895309 | 7.53 | README.txt、aactl、aactl-displayd.apk、platform-tools/adb、platform-tools/NOTICE.txt |
| aactl_dev-71ea6f7_linux_amd64_slim.tar.gz | 3139841 | 2.99 | README.txt、aactl、aactl-displayd.apk |
| ai-auto-android_dev-71ea6f7_debug.apk | 76156488 | 72.63 | 独立 App debug APK，不放入平台包 |

九资产 SHA-256（与 SHA256SUMS 一致）：

```text
803baa5efcbdc2849e48922b0f2f5a8b19deb2a8dae79a1d8ead907b76b52151  aactl_dev-71ea6f7_darwin_amd64.tar.gz
d14b56ab1f971597e6f9a24d9034f7f57ee7416fccc963fb872fd123fdc405a4  aactl_dev-71ea6f7_darwin_amd64_slim.tar.gz
935c6897414387d80ddf425e079435d26d8710c25997512ca0c7b3e06a040c06  aactl_dev-71ea6f7_darwin_arm64.tar.gz
299b1da228922fcf24ecf20cce6c936fde283a5421c414961e15375d17adef73  aactl_dev-71ea6f7_darwin_arm64_slim.tar.gz
53888a06c6705be623f391d2f638d7f3ff433cf35ecab142f370b91cad320541  aactl_dev-71ea6f7_linux_amd64.tar.gz
974563dfb9f75e692a2fff4b75bdc372c07d88c615058d08c59ceba9e528acad  aactl_dev-71ea6f7_linux_amd64_slim.tar.gz
fd26552761ca3a1e964e6139b5d664dc8da849ecac4b368fa66a3e33a83f42a5  aactl_dev-71ea6f7_windows_amd64.zip
6102e04268686e91ed18f69f74d91666bb6b4f6c76b06e14ca307d453579f3df  aactl_dev-71ea6f7_windows_amd64_slim.zip
c71fb5b704be898d45095a5ecd0021bbf150d2a56c80e83bd44d51b5a4412093  ai-auto-android_dev-71ea6f7_debug.apk
```

### Windows 隔离解压

- 两包解压到仓库外 TEMP/aactl-release-bundles-864657eba0d64fefab95ab01eeead272。清除 AACTL_ADB_PATH、ANDROID_HOME、ANDROID_SDK_ROOT，PATH 仅 C:/WINDOWS/System32（不含 adb）；另用专属空 USERPROFILE 避免默认用户 SDK 干扰。清除 AACTL_DISPLAYD_APK，排除 helper 环境覆盖。
- 完整包 doctor --json 退出0、healthy=true、adbSource=bundled，adbPath精确指向解压目录 platform-tools/adb.exe，版本37.0.1-15733141。
- 精简包同环境 doctor --json 退出4，error.code=ADB_NOT_FOUND，明确提示使用 full aactl bundle 或安装官方 Platform-Tools 并设置 AACTL_ADB_PATH。
- 原始 doctor-full.json、doctor-slim.json、逐包 archive-listings.txt、摘要和打包测试 JSONL 保存在忽略证据目录。未在 macOS arm64、macOS amd64 或 Linux amd64 系统执行任何归档程序；这些包仅通过结构/权限/时间和摘要检查。

### 模拟器单次冒烟

- 启动前设备列表为空，无 emulator/qemu；已有 ADB server PID8332 为测试前进程，版本37.0.1，最终保留。
- 明确启动 E:/osdk-data/data/installs/android-emulator/37.1.11/emulator.exe：-avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio -port 5554 -no-window。额外 -port 固定serial，-no-window支持后台验证，Start-Process 使用 WindowStyle Hidden。未加载快照，未修改基础AVD、PIN、安全设置或授权。
- 唯一设备 emulator-5554，transportId=22（每一步由CLI connection.transportId复核），API35、google_apis/x86_64 system image revision9；320×640、160dpi、en-US、navigation_mode=2（手势）。boot_completed=1。设备指纹：google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys。
- 六步骤只使用解压完整包的 CLI/ADB；各步前复查唯一设备、在线状态、固定transport和fingerprint，动作后再读取状态验证，不盲重试：
  1. devices list：count1且唯一serial一致。
  2. observe screenshot --display 0 --device emulator-5554：PNG320×640、205038字节，SHA-256 033052ada3f8e377c67a385270f29299052d53b9b497c29ee58ecaef18009874，人工查看为正常主屏，无敏感数据。
  3. display start --device emulator-5554（不传 --helper、无helper环境变量）：protocolVersion2、apiLevel35；shell PID3994；设备APK摘要与包内helper一致。
  4. display create --width 320 --height 640 --dpi 160：displayId2、focus=isolated、focusIsolated=true；status确认displayGroupId1、groupAwake=true、state2。
  5. display close --display 2：closed=true，随后list.displays为空。
  6. display stop：stopped=true，随后shell进程、/data/local/tmp/aactl-displayd*及forward均为空。
- 之后只向 emulator-5554 发送一次 emu kill；最终CLI devices count0，宿主emulator/qemu为0。无本任务新增ADB服务残留；原PID8332仍在。cleanup-device.json和cleanup-host.json保留零残留证据。
- 本轮是打包的单次冒烟，不替代原v2协议/焦点测试或跨平台真机矩阵；v1拒绝握手由既有internal/vdisplay单测随全量通过，未另行重跑真实v1→v2升级。

### 脚手架问题与修正（判据未放宽）

- 首次 osdk exec -- make 找不到裸命令，改为明确的共享m2-base make.exe，Go也使用1.26.5真实exe；无安装/改pin。
- 一次全量格式检查发现用于静态依赖审查的临时 artifacts/release-bundles/deps.go 未格式化；移除该一次性文件后重跑全部检查通过。未把临时源码留在仓库或排除格式检查。
- 首次模拟器查询到online但boot_completed尚为空，等待后读取到1才开始操作，没有在未就绪设备上做动作。
- 临时守卫误用ADB不支持的get-transport-id，动作前即失败；从CLI原始devices JSON确认字段位于connection.transportId，修正后以固定22检查。截图尚未执行，未重复动作。
- emu kill后20秒检查发现退出中的emulator launcher；官方stdout明示等待20秒优雅退出。未杀未知进程、未再次发送关闭；随后只读复核时进程已自然退出，设备与宿主最终零残留，原失败事实保留。后续脚手架应给关闭观察留足60秒并持续核实相同归属。

## Round 5 — R4 文档收口与最终验证

- README 安装先完整包、用户 RSA 授权与运行，再精简包；填入 Round4 的四平台实测大小及独立 App 72.63 MiB。虚拟显示示例默认同目录 helper，保留源码路径与旧 CLI 先停 v1 的升级顺序。排障记录实际 adbPath/adbSource、损坏随包拒绝和共用 ADB/精简包两种版本冲突规避方法。
- security-distribution 记录官方地址、固定清单、三重校验、白名单、原始 NOTICE、显式许可开关和负责人已知风险的决定；2026-11-15只读核对 Android 官方 studio/terms 与 Platform-Tools 发布说明，不替负责人作法律判断。项目无 LICENSE；未添加自拟许可。
- 路线图只收口 N60.2 及对应摘要/波次指引，其它子项行逐字保留。N60整项仍未勾；后续许可决策、linux/arm64、macOS 公证/隔离属性、macOS/Linux 实机运行和包管理器分发均独立登记未实施，含依赖/owner/步骤/证据/提交/清理。
- 额外对八个真实归档只读检查：排序、顶级目录、0755/0644、时间1794668686、tar uid/gid0均通过；同目标 full/slim 的CLI逐字节同版，全部helper摘要一致，所有随包官方ADB/DLL/NOTICE与已验证官方zip中的对应文件逐字节相同。原始 inventory.json 位于忽略证据目录，临时检查程序放在仓库外，不提交。
- 保存的官方 XML SHA-256：d670de8bce15cc4ecf5d1a135f2168137ccbb8402111dfc8efcf9cb6f3b2f28f。固定清单的原始字段来源仍是 Round3 指定 XML 节点，SHA-256 为实际下载计算值，不是凭记忆或 XML 提供。

最终命令与数量：

| 检查 | 结果与计数 |
| --- | --- |
| make comments | 通过，604个受管手写文件；检查器16个正反例 |
| make test | 通过：Go全部包、格式、vet；linux/darwin/windows cross-vet均通过；336个本机可编译顶层Test定义（go test -list统计，不是子用例执行数，包含既有条件跳过设备测试）；协议56项；Skills3份及镜像/命令例检查 |
| make verify | 通过：fake ADB、协议、Skills、注释和toolchain metadata |
| make build | 通过，默认dev CLI仍为本地忽略产物，不替换用于冒烟的已解压版本 |
| make android-verify | 通过，17秒/199任务（15执行/184缓存），testDebugUnitTest/assembleDebug/lintDebug；XML统计614项、0failure/error、1既有跳过：app564/fixture8/webfixture6/displayd36 |
| 发布离线定向 | 20/20（含子测试），无网络；两次伪输入归档逐字节一致 |
| workflow-portability | Node6/6 |
| 真实release/校验 | 8归档+1 App APK；SHA256SUMS 9/9；Windows full/slim隔离结果、API35六步及清理通过 |
| 文档与范围 | 五份更新文档37个本地链接目标存在；git diff --check通过；相对d565282的android/与internal/mcpserver/完整diff为空，无其它规格/风险门修改 |

- 核心Go/Make在文档修改后已再次完整通过，日志 make-final.log；Android验证后未改Android源码，无需重复相同缓存检查。所有外部工具在本机验证中使用明确真实exe路径，未改全局PATH或项目pins。
- 未覆盖：macOS arm64/amd64、Linux amd64实际运行；linux/arm64完整包；macOS公证/下载隔离属性；真机/OEM/API33/34、实际v1→v2升级与长期flaky统计。本轮不为这些能力作验收结论。
- 设计未偏离；实现选择为已存在DIST_DIR拒绝覆盖，包内只附ADB所需子集。过程纠正（首次手工许可变量遗漏、裸make入口、临时Go格式、守卫字段、启动/关闭时机）见Round3/4，不隐藏失败或放宽判据。唯一设备、授权及官方来源边界保持。
- 本规格R0–R4按实现/单测/实际设备证据勾选；提交只含允许范围的文本源码/配置/文档，不含二进制/缓存/发布产物。最终提交后再用git status/worktree与设备/进程只读复核；不push，不移除指定worktree。
