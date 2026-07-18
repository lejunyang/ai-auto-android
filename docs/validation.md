# Task 13 验证记录

验证日期：2026-07-19。执行环境为 macOS arm64、Go 1.26.5、JDK 21.0.7、
Node.js 24.13.0、Android SDK 36、Build Tools 36.0.0 和 Platform-Tools
37.0.0。下列 `$GO` 指向固定的 Go 1.26.5 可执行文件，`$ANDROID_HOME`
指向本机 Android SDK；仓库不记录本机绝对路径。

## 自动验证

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| Go、协议、Skills、格式、vet | `GO="$GO" make test` | 通过；Go JSON 结果含 170 个通过的测试/子测试事件，其中 92 个顶层测试 |
| 集成 smoke 与元数据 | `GO="$GO" make verify` | 通过；含 fake ADB、协议、Skills 和工具链元数据 |
| CLI 构建 | `GO="$GO" make build VERSION=0.1.0` | 通过；arm64 本机构建返回版本 `0.1.0` |
| Go 全仓 race | `GORACE=halt_on_error=1 "$GO" test -race -count=1 ./...` | 全部包通过 |
| 协议 | `node protocol/scripts/validate.mjs` | 通过；10 个 Schema、11 个合法 fixture、14 个非法 fixture 和 4 个兼容检查，共 29 项 |
| Agent Skills | `node scripts/skills.mjs check` | 3 个发布 Skill 及 Trae 镜像全部通过 |
| fake ADB 与 Go Bridge | `"$GO" test -json -count=1 ./internal/adb ./internal/bridge ./internal/cli` | 123 个通过事件，其中 78 个顶层测试 |
| Android 全量 | `ANDROID_HOME="$ANDROID_HOME" ./android/gradlew -p android --no-daemon testDebugUnitTest assembleDebug lintDebug` | 145 个单测通过，0 failure/error/skip；APK 构建成功 |
| Android Bridge 契约 | Android 全量结果中的 `dev.aiauto.android.bridge.*` | 27 个测试通过 |
| Diff | `git diff --check` | 通过 |

当前会话的 sandbox 会在 Gradle 完成后尝试限制 Kotlin 用户目录清理。实际执行时
通过 `GRADLE_OPTS=-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process`
让 Kotlin 编译器进程内运行；Gradle 任务、Android SDK 和构建输出不变。

Android lint 有 0 个 error 和 16 个 warning：固定工具链或依赖的新版本提示、
minSdk 下可合并的资源目录提示，以及 `SharedPreferences` KTX 建议。Task 13
不进行无关依赖升级或资源重构。

## Bridge 与录制契约

- Android `recording.list` 从 App 私有 `RecordingScriptStore` 读取真实摘要，生产
  `DesktopBridgeController` 通过工厂显式注入该存储；
- 返回字段限定为 `id`、`name`、`targetPackages`、`stepCount`、`createdAt`
  和 `requirements`，不返回步骤、变量、`secretRef` 或 secret 值；
- 未完成 hello、无 token、错误 token 或过期 token 时，Dispatcher 在读取存储前
  拒绝请求；
- protocol schema、Go fixture 和 Android fixture 完全一致；Go 服务再次校验
  UUID、包名、时间、步骤数量和 requirements；
- 点击、长按、文本、滚动和窗口事件已由发布配置订阅；App Accessibility 执行器
  成功发起的 Back、Home、Recents 会进入真实 `RecordingController` 去重链路；
- 回放在每次动作和等待前重新获取快照并验证当前前台包属于脚本目标包；快照失败
  不再被误判为 `notExists`；
- MCP 动作使用显式低风险 allowlist，`app.stop` 等动作返回
  `ACTION_NOT_ALLOWED`；MCP 录制回放始终返回 `CONFIRMATION_REQUIRED`。

## 设备条件

实际执行：

```bash
"$ANDROID_HOME/platform-tools/adb" devices -l
if test -x "$ANDROID_HOME/emulator/emulator"; then
  "$ANDROID_HOME/emulator/emulator" -list-avds
fi
if test -d "$ANDROID_HOME/system-images"; then
  find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -type d
fi
if test -d "$HOME/.android/avd"; then
  find "$HOME/.android/avd" -maxdepth 1 -name '*.avd'
fi
```

ADB 37.0.0 可执行，但设备列表为空。SDK 未安装 emulator 二进制，不存在
`system-images` 目录，也没有 AVD 配置。因此真实设备/模拟器的截图、动作、
Accessibility 授权、Bridge 配对和录制回放 smoke **条件不可用，未执行**。
本记录不声称真机或模拟器通过。

替代验证已实际执行：

- fake ADB 覆盖显式 serial、多设备、unauthorized/offline、超时、截图、层级、
  类型化动作、5037/mDNS 诊断、传输归一化、端口转发和注入拒绝；
- Go Bridge 覆盖 hello/session、token、超时、消息上限、断连、forward 清理、
  `recording.list` 会话复用和脱敏结果解析；
- Android Bridge 的 27 个契约测试覆盖 Dispatcher、SessionManager、NDJSON
  Server、真实 RecordingScriptStore 工厂和 Android 方法适配器；
- Android 录制测试覆盖发布事件订阅对应映射、环境摘要、显式全局动作生产链路、
  目标包校验、快照失败和选择器失败。

这些替代测试验证协议、注入和失败边界，但不等价于真实设备 smoke。

## 发布制品

执行：

```bash
GO="$GO" ANDROID_HOME="$ANDROID_HOME" make release VERSION=0.1.0
cd dist
shasum -a 256 -c SHA256SUMS
```

`shasum` 对 5 个制品均返回 `OK`。`dist/` 只包含下列制品和
`SHA256SUMS`；该目录被 Git 忽略。

| 制品 | 字节 | SHA-256 |
| --- | ---: | --- |
| `aactl_0.1.0_darwin_arm64` | 6,306,018 | `936c8428e71d76946e5be071081699a0412c7810a226c4ab7c727f5ed866fb09` |
| `aactl_0.1.0_darwin_amd64` | 6,744,944 | `0cfa3b4e23ae3cf7a13a82c1d784e2973b727045e020086d898b8804f0ef55ac` |
| `aactl_0.1.0_windows_amd64.exe` | 6,816,768 | `c3d43990f8aaf44f8490992565d1176a711029290d6a50d1aad8fd1a9239e61e` |
| `aactl_0.1.0_linux_amd64` | 6,607,010 | `c406b5402b6712220e9188481a0c8d1c93f5dc72bfd63b54eaf40d1da344faa8` |
| `ai-auto-android_0.1.0_debug.apk` | 31,605,233 | `494c82e74cf2a0ce99bfa8f3bcd5c9af7f9f690d7b595119e2cdbe37ecb216d8` |

`file` 分别识别 CLI 为 Mach-O arm64、Mach-O x86_64、PE32+ x86_64 和静态
ELF x86_64。`apksigner verify --verbose --print-certs` 验证 APK 的 v2 签名，
签名者为 `CN=Android Debug`；它不是生产发布签名。
