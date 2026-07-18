# AI Auto Android

AI Auto Android 是一套面向 Android 的本地优先自动化工具，包含 Android 执行 App、跨平台 `aactl` CLI、MCP 服务和可移植 Agent Skills。

项目当前按照 [.trae/specs/build-ai-android-automation-mvp/spec.md](.trae/specs/build-ai-android-automation-mvp/spec.md) 逐步实现。完整自主 AI 版本定位为开源侧载、内部测试或企业受控分发，不承诺通过 Google Play 的 Accessibility API 政策审核。

## 仓库结构

| 路径 | 职责 |
| --- | --- |
| `protocol/` | 版本化 JSON Schema、夹具和兼容性约束 |
| `cmd/` | `aactl` 可执行程序入口 |
| `internal/` | CLI、ADB、Bridge 和 MCP 的内部实现 |
| `android/` | Kotlin + Jetpack Compose Android App |
| `skills/` | Agent Skills 及一层引用资料 |
| `tests/` | 跨模块契约测试支持 |
| `integration-tests/` | 设备与模拟器端到端场景 |
| `docs/` | 架构、协议、安全、录制和备选方案 |

## 工具链

- Go `1.26.5`
- JDK `21`
- Android compile/target SDK `36`
- Android Gradle Plugin `9.1.1`
- Gradle `9.3.1`

版本来源统一记录在 `.tool-versions`、`.go-version`、`.java-version` 和 `android/gradle/libs.versions.toml`。本项目不提交 `local.properties` 或任何本机 SDK 路径。

## 开发入口

```bash
make verify
make doctor
```

`make verify` 校验仓库结构与版本约束，不要求所有 SDK 已安装。`make doctor` 检查当前机器上的 Git、Go、Java 和 ADB，并在版本不匹配或工具缺失时失败。

后续实现会逐步开放：

```bash
make test
make build
make android-test
make android-build
```

## Agent Skills

可移植发布源位于 `skills/<skill-name>`，Trae 项目级自动发现镜像位于
`.trae/skills/<skill-name>`。三个 Skill 分别覆盖设备发现、设备观察和受控自动化。

```bash
make skills-sync
make skills-check
```

其他兼容 Agent Skills 的客户端可直接安装 `skills/android-device-discovery`、
`skills/android-device-observation` 或 `skills/android-device-automation`。完整路径、
触发场景和官方校验命令见 [skills/README.md](skills/README.md)。

## 安全基线

- 设备操作必须显式指定设备，不在多设备环境中猜测目标。
- CLI 使用参数数组启动 ADB，不向 Agent 暴露任意 shell。
- API Key、配对码、会话 token、密码和验证码不得进入 Git 或日志。
- 支付、授权、安装等高风险动作必须阻断或等待人工确认。
