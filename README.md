# AI Auto Android

AI Auto Android 是一套本地优先的 Android 自动化 MVP，包括 Go `aactl`
CLI、MCP stdio 服务、Kotlin/Compose Android App 和三组可移植 Agent Skills。
电脑端可直接通过官方 ADB 观察和执行基础动作；安装 App 并由用户启用无障碍与
桌面桥后，可使用语义选择器和录制回放。

完整自主 AI 版本只定位于开源侧载、内部测试或企业受控分发。它不应被描述为可直接
上架 Google Play 的版本，发布的 debug APK 也不是生产安装包。

## 安装

运行 `aactl` 需要官方
[Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)。
从 GitHub Release 下载与系统匹配的 `aactl_<version>_<os>_<arch>`，按同一
Release 中的 `SHA256SUMS` 验证后，将其重命名为 `aactl`（Windows 保留
`.exe`）并加入 `PATH`。

从源码构建需要 Go `1.26.5`：

```bash
GO=/path/to/go make build VERSION=0.1.0
./bin/aactl version --json
```

Android App 需要 Android 11 / API 30 及以上。开发用 debug APK 位于 Release
或 `android/app/build/outputs/apk/debug/app-debug.apk`；它使用调试签名，只能用于
开发与验证。生产分发前必须配置受控发布签名并重新验证 APK。

## 快速开始

在设备上启用开发者选项和 USB 调试，连接数据线，并在设备端核对、接受当前电脑的
ADB RSA 指纹。所有设备操作都显式指定 `SERIAL`：

```bash
aactl version --json
aactl doctor --json
aactl devices list --json
aactl device info --device SERIAL --json
aactl observe screenshot --device SERIAL --output screenshot.png --json
aactl observe hierarchy --device SERIAL --json
```

Android 11+ 无线调试使用设备显示的两个不同 endpoint；配对码由交互式 stdin
读取，不要放进参数或日志：

```bash
aactl devices pair PAIR_HOST:PORT --json
aactl devices connect CONNECT_HOST:PORT --json
```

录制列表要求先开启桌面桥并建立 token 会话；语义观察和人工回放还要求在 App
中接受披露、配置目标包并手动启用无障碍服务：

```bash
aactl bridge open --device SERIAL --json
aactl bridge snapshot --device SERIAL --package com.example.app --json
aactl recording list --device SERIAL --json
aactl recording replay --device SERIAL \
  --script 123e4567-e89b-42d3-a456-426614174000 --json
aactl bridge close --device SERIAL --json
```

## MCP 配置

`aactl mcp serve` 通过 stdio 暴露五个类型化工具。MCP 客户端可使用以下通用配置；
`command` 必须是客户端实际可访问的可执行文件路径，或已在其 `PATH` 中的
`aactl`：

```json
{
  "mcpServers": {
    "android": {
      "command": "aactl",
      "args": ["mcp", "serve"]
    }
  }
}
```

MCP 不提供 ADB 配对、Bridge 建立、权限授予或任意 shell。先由用户通过 CLI 和
App 建立所需信任，再让 MCP 使用已协商的类型化能力。录制列表通过
`android_observe` 的 `kind=recordings` 读取；兼容保留的
`android_recording_replay` 始终返回 `CONFIRMATION_REQUIRED` 且不执行回放。

## Agent Skills

三个发布源可分别安装到兼容 Agent Skills 的客户端：

```text
skills/android-device-discovery
skills/android-device-observation
skills/android-device-automation
```

Trae 项目级镜像位于对应的 `.trae/skills/<skill-name>`；只编辑 `skills/`，
再运行 `make skills-sync`。规范、触发场景及官方 `skills-ref` 校验命令见
[skills/README.md](skills/README.md)。

## Android 权限

- App 清单只声明网络权限，用于访问用户配置的 OpenAI 兼容 Provider。
- AccessibilityService 必须由用户先接受 App 内独立醒目披露，再到系统设置手动
  启用；App 不能替用户授权。
- 用户必须配置允许的目标包。前台包不匹配、敏感目标或能力不可用时执行失败关闭。
- 桌面桥默认关闭，只绑定设备 loopback；每次由用户开启并使用一次性码建立短期会话。
- ADB 的 USB/无线调试授权属于电脑与设备间的独立信任，必须由设备用户确认。

## MVP 限制

- 仅支持 Android 11 / API 30 及以上；不支持 Root、Shizuku、Device Owner、
  蓝牙 ADB、互联网远控、OCR 或设备农场。
- 直接 ADB 仅提供类型化观察、输入和应用控制，不提供任意 shell。
- 录制订阅点击、长按、文本、滚动和窗口变化；由 App 执行器发起的 Back、Home、
  Recents 也会录制。原始触摸、物理系统导航和自由手势不在录制范围内。
- Bridge 不传截图；截图和 UIAutomator XML 是可能包含敏感内容的原始 ADB 产物。
- CLI 可列出但不能编辑录制脚本，也不能向回放传入 secret；MCP 不执行录制回放。
- 支付、购买、安装、授权和系统安全设置被禁止；发送、提交、删除等动作需要当次确认。

## 开发与验证

固定工具链为 Go `1.26.5`、JDK `21`、Android SDK `36`、AGP `9.1.1` 和
Gradle `9.3.1`。本机 SDK 路径不得提交到仓库。

```bash
make test
make verify
make build
ANDROID_HOME=/path/to/android-sdk ./android/gradlew -p android \
  --no-daemon testDebugUnitTest assembleDebug lintDebug
make release VERSION=0.1.0
```

`make test` 运行协议、Skills、Go 格式、单测和 vet；`make verify` 运行协议与
Skills smoke、fake ADB 定向测试和工具链元数据检查；`make doctor` 检查本机完整
工具链。发布目录 `dist/` 和所有构建缓存均被忽略，不应提交。

## 文档

- [文档导航](docs/README.md)
- [架构与信任边界](docs/architecture.md)
- [协议 v1](docs/protocol.md)
- [录制与回放](docs/recording.md)
- [安全与分发](docs/security-distribution.md)
- [故障排查](docs/troubleshooting.md)
- [备选方案](docs/alternatives.md)
- [Task 12 验证记录](docs/validation.md)
