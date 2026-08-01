# 可移植工具根与 Windows CI 进度

本文件 append-only，记录远程失败证据、RED/GREEN、跨平台验证和集成状态。

## Round 1

- GitHub 公共 API 显示 `verify.yml` 最近三次均失败；最新 run `30197184628` 测试
  `5dcc70f`。Repository metadata、Ubuntu/macOS Go、Android、Protocol、Skills
  全部成功，唯一失败为 `Go (windows-latest)` 的 `Check Go formatting`，后续测试
  和构建均被跳过。
- 仓库没有 `.gitattributes`；GitHub Windows checkout 默认 CRLF 转换会让
  `gofmt -l` 将格式正确但为 CRLF 的 Go 文件列为需格式化。这是主 CI 的确定根因。
- 三个跨平台 workflow 在同一远端提交也只有 Windows 失败：artifact 与 local matrix
  都停在 N34 artifact semantics，emulator runner 停在 runner tests。公开
  annotations 只含退出码，完整日志下载需要认证；本 change 将通过平台能力测试和
  路径/权限静态审计修复确定的 Windows 假设。
- 本地外置配置通过 `ANDROID_TOOLS_ROOT` 指向
  `/Volumes/aigo S7 Media/SDK/android-tools`，但四个生产 runner 把该路径写成唯一
  合法根。该限制应迁移为显式环境根，保留本机配置而不绑定仓库代码。

## Round 2

- 新增共享 `resolveToolchainEnvironment`：首选 `AACTL_TOOLCHAIN_ROOT`，兼容
  `ANDROID_TOOLS_ROOT`；两者同时存在但不一致时失败关闭。POSIX 使用
  `path.posix`、Windows 盘符/UNC 使用 `path.win32`，所有 SDK/cache/state 路径必须
  位于显式根内。
- verified install、passive smoke、LAN interop 与 N43 matrix 已迁移共享解析器。
  生产 runner、测试输入和 N46 README 不再包含机器专属卷名；本机外置
  `android-tools.env` 仅新增 `AACTL_TOOLCHAIN_ROOT="$ANDROID_TOOLS_ROOT"`，原
  source 与目录布局不变。
- 共享环境和四个 runner 定向测试通过；本机真实外置环境也通过新根解析。

## Round 3

- 新增 `.gitattributes`：Go/Node/Shell/YAML/Kotlin/Java/XML/JSON/Markdown 固定
  LF，`.bat/.cmd` 固定 CRLF。`verify.yml` 与 Makefile 统一调用
  `sh scripts/check-go-format.sh`。
- 临时 Git snapshot 在 `core.autocrlf=true` 下验证：Go 与 MJS 工作树保持 LF，
  `gradlew.bat` 为 CRLF，Go 格式脚本通过。该证据复现并关闭远端 Windows gofmt
  的确定根因。
- `verify.yml` 新增 Ubuntu/Windows/macOS portability job，直接运行共享工具根和
  emulator runner tests；静态 workflow test 防止格式脚本和平台矩阵回退。

## Round 4

- Emulator fake SDK 按实际平台创建 `adb/emulator/java` 或 `.exe`，并新增强制
  `platform:"win32"` 的 metadata/receipt 验证，不依赖 Unix executable mode。
- N34 绝对路径逃逸 fixture 使用当前平台根生成，不再硬编码 `/tmp`；Windows 无文件
  symlink 权限时仅跳过无法构造的单个文件链接 fixture并清理临时目录，目录逃逸仍以
  junction 全量验证。
- N34 49/49、N47 59/59、N52 21/21、emulator/toolchain 18/18、受影响 Node
  86/86 和 comments/diff-check 通过。完整 Go/Android 与最终 metadata 验证在提交
  前继续执行；远程 Windows Actions 需该提交 push 到 GitHub 后才能确认变绿。

## Round 5

- 包含当前改动的临时 Git snapshot 以 `core.autocrlf=true` 重新 checkout：Go 与
  MJS 为 LF，`gradlew.bat` 为 CRLF，`sh scripts/check-go-format.sh` 通过；
  toolchain/emulator portability 18/18 通过。
- 最终受影响 Node 主批次 88/88、N34 49/49、N47 59/59、N52 21/21 通过；
  `make test`、`make verify`、`make build`、Go format、comments 和 diff-check 通过。
- Android 全模块 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、
  `assembleDebugAndroidTest`、`assembleRelease` 共 374 tasks 通过。随后 `clean`
  删除构建 APK，repository scan 确认工作区与全部 Git 历史 APK 路径均为零。
- 本机现有外置 root 经 `AACTL_TOOLCHAIN_ROOT` 与 `ANDROID_TOOLS_ROOT` 同值校验，
  四个生产 runner 环境预检通过；仓库代码、runner tests 和用户文档示例中的机器专属
  `/Volumes/aigo S7 Media` 路径为零。
- GitHub 远端 `main` 仍为 `5dcc70f`，没有包含本地后续 16+ 提交。本 change 未
  push，也未伪报远端 Actions 已变绿；远程复验必须在用户允许 push 后进行。
