# Task 12 验证记录

验证日期：2026-07-18。执行环境为 macOS arm64、Go 1.26.5、JDK 21.0.7、
Node.js 24.13.0、Android SDK/Build Tools 36.0.0。下列命令中的 `$GO` 指向固定的
Go 1.26.5 可执行文件，`$ANDROID_HOME` 指向本机 Android SDK；不在仓库记录本机
绝对路径。

## 自动验证

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| Go、协议、Skills、格式、vet | `GO="$GO" make test` | 通过；Go JSON 结果含 140 个通过的测试/子测试事件 |
| 集成 smoke 与元数据 | `GO="$GO" make verify` | 通过；含 fake ADB、协议、Skills 和工具链元数据 |
| CLI 构建 | `GO="$GO" make build VERSION=0.1.0` | 通过；arm64 本机构建返回版本 `0.1.0` |
| 协议 | `GO="$GO" make protocol-test` | 通过；9 个 Schema、10 个合法和 12 个非法 fixture |
| 官方 Agent Skills 校验 | `skills-ref validate skills/<skill-name>` | 三个 Skill 全部有效；工具来自 `agentskills/agentskills` 提交 `38a2ff82958afee88dadf4831509e6f7e9d8ef4e` |
| Go race | `go test -race -count=1 ./internal/adb ./internal/bridge ./internal/service ./internal/mcpserver ./internal/cli` | 5 个关键包通过 |
| fake ADB 与桌面 Bridge | `go test -count=1 -v ./internal/adb ./internal/bridge ./internal/cli` | 68 个顶层测试、111 个含子测试的 PASS 事件 |
| Android 全量 | `ANDROID_HOME="$ANDROID_HOME" ./android/gradlew -p android --no-daemon testDebugUnitTest assembleDebug lintDebug` | 131 个单测通过，0 failure/error/skip；APK 构建成功 |
| Android Bridge 契约 | `./android/gradlew -p android --no-daemon testDebugUnitTest --tests 'dev.aiauto.android.bridge.*'` | 22 个测试通过 |
| GitHub Actions | `actionlint .github/workflows/verify.yml .github/workflows/release.yml` | 通过 |
| Shell 与 diff | `sh -n scripts/release.sh scripts/verify-toolchains.sh`、`git diff --check` | 通过 |

Android lint 有 0 个 error 和 16 个 warning：12 个固定工具链/依赖的新版本提示、
1 个 `mipmap-anydpi-v26` 在 minSdk 30 下可合并提示、3 个 `SharedPreferences`
KTX 建议。它们不影响本次构建；Task 12 不进行无关依赖升级或业务重构。

工作流使用 `contents: read` 作为默认权限，仅发布 job 使用 `contents: write`；
所有 checkout 均禁用凭据持久化。8 个第三方 Action 引用均为完整 40 位 SHA，
并已用对应上游 tag 复核。

## 设备验证

实际执行：

```bash
"$ANDROID_HOME/platform-tools/adb" devices -l
if test -x "$ANDROID_HOME/emulator/emulator"; then
  "$ANDROID_HOME/emulator/emulator" -list-avds
fi
find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -type d
```

结果为 ADB 设备列表为空，SDK 未安装 emulator 二进制，系统镜像数量为 0，
AVD 数量为 0。因此截图、真实 action、App Bridge 和 recording smoke
**按环境条件跳过**。没有下载大型系统镜像，也没有声称模拟器或真机通过。

替代覆盖已经实际执行：

- fake ADB 覆盖显式 serial、多设备、unauthorized/offline、超时、截图、层级、
  类型化动作、端口转发和注入拒绝；
- Go Bridge 覆盖 hello/session、token、超时、消息上限、断连、forward 清理、
  本地会话权限和 CLI 映射；
- Android Bridge 的 22 个契约测试覆盖 Dispatcher、SessionManager、
  NDJSON Server 和 Android 方法适配器。

这些替代测试验证协议和失败边界，但不等价于真实设备截图、输入、无障碍授权、
Bridge 配对或录制回放。

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
| `aactl_0.1.0_darwin_arm64` | 6,288,962 | `5a9b55d0bdc43b16db939aff0350004571270a25b27e8847122c55802edec445` |
| `aactl_0.1.0_darwin_amd64` | 6,715,712 | `8b8c13ab5d5b7d3972d38dbfbb2c336abdb5f791a6110a47803cf052fa47e474` |
| `aactl_0.1.0_windows_amd64.exe` | 6,789,632 | `152b914b1692d2566fdf3383721ac655ddd78e8170e257c869d31bc176b401f2` |
| `aactl_0.1.0_linux_amd64` | 6,582,434 | `dc54c2792f33c9ff5c6b0a7dfc1a904ef813c7dda18c0d89e43d7355b426e125` |
| `ai-auto-android_0.1.0_debug.apk` | 31,901,513 | `6ceb529b28e0de8e2176cba2f09ee8be77ec9dad2ac05472f29fbbe2d9d08b6a` |

`file` 分别识别 CLI 为 Mach-O arm64、Mach-O x86_64、PE32+ x86_64 和静态
ELF x86_64。`apksigner verify --verbose --print-certs` 验证 APK 的 v2 签名，
签名者为 `CN=Android Debug`；它不是生产发布签名。

## 官方来源复核

2026-07-18 重新访问并核对了以下来源：

- [MCP Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)
- [Agent Skills Specification](https://agentskills.io/specification)
- [Google Play AccessibilityService API 政策](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Android 开发者验证 FAQ](https://developer.android.com/developer-verification/guides/faq)
- 各 GitHub Action 上游仓库的固定版本 tag
