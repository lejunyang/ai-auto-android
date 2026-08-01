# 可移植工具根与 Windows CI 规格

## 目标

移除本地 runner 对单台 macOS `/Volumes/...` 路径的编译期依赖，并修复 GitHub
Actions Windows checkout、路径、权限位与链接能力差异导致的持续失败。

## 范围

- 使用显式 `AACTL_TOOLCHAIN_ROOT` 作为跨平台工具根；兼容已有
  `ANDROID_TOOLS_ROOT`，两者同时存在时必须解析为同一路径。
- Android SDK、AVD、Java、Gradle、Go cache、emulator state 与 APK cache 必须位于
  显式工具根内；不允许仅因路径绝对就写入任意系统位置。
- 本机 shell 仍可把工具根配置为
  `/Volumes/aigo S7 Media/SDK/android-tools`，但仓库代码、测试和文档示例不得把该值
  作为唯一合法根。
- 新增 `.gitattributes`，使 Go、Node、Shell、YAML、Kotlin 等源码 checkout 为 LF，
  Windows batch 文件 checkout 为 CRLF，避免 `gofmt` 因 `core.autocrlf` 误报。
- Windows 测试不得假设 POSIX `/tmp`、Unix 权限位或无条件文件 symlink 能力；安全
  测试仍必须覆盖路径逃逸、junction/链接和目标不被遍历。
- 核对 `verify.yml`、emulator runner、artifact semantics 和 local matrix 的 Windows
  workflow；不降低 Ubuntu/macOS 验证。

## 非目标

- 不把 SDK、cache 或 APK 移回仓库或用户 home 默认目录。
- 不允许 runner 自动猜测工具根、系统 SDK 或任意可执行文件。
- 不绕过 Windows 安全检查；平台不提供 Unix mode 时只跳过无意义的 mode 断言，
  symlink 测试需使用平台支持的 junction 或明确能力探针。
- 不 push、rerun 或取消远程 Actions；本 change 只修复代码和 workflow，远程复验
  需要提交进入 GitHub。

## 允许修改

- `.trae/specs/portable-toolchain-windows-ci/`
- `.gitattributes`
- `.github/workflows/*.yml`
- `scripts/toolchain-environment.mjs`
- `scripts/check-go-format.sh`
- `scripts/lan-interop/`
- `scripts/webview-semantic-matrix/`
- `scripts/test-lab/`
- `test-lab/apps/README.md`
- `test-lab/artifacts/`
- `scripts/emulator/`
- `docs/next-phase-tasks.md`

## 验收

- 单元测试覆盖工具根缺失、不一致、路径逃逸、POSIX 根和 Windows 风格根。
- `rg '/Volumes/aigo S7 Media'` 只允许历史规格/设备证据和本机配置说明，不得出现在
  生产 runner 或测试输入常量。
- CRLF 模拟 checkout 下 Go 格式检查不误报；真实未格式化 Go 仍失败。
- Emulator runner、N34 artifacts、N46、LAN interop、N43 matrix、N47、N52 的
  跨平台测试全部通过。
- `make test`、`make verify`、Android 构建、comments 与 diff-check 通过。

## 失败与清理

- 测试临时目录仅使用 `os.tmpdir()` 或注入目录，并在 finally 清理。
- 不修改外置 SDK 内容；本机现有 `ANDROID_TOOLS_ROOT` 配置保持可用。
- 远程日志或 API 读取失败只保留公开 job/step 结论，不猜测未取得的错误文本。
