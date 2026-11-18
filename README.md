# AI Auto Android

AI Auto Android 是一套本地优先的 Android 自动化 MVP，包括 Go `aactl`
CLI、MCP stdio 服务、Kotlin/Compose Android App 和三组可移植 Agent Skills。
电脑端可直接通过官方 ADB 观察和执行基础动作；安装 App 并由用户启用无障碍与
桌面桥后，可使用语义选择器和录制回放。

完整自主 AI 版本只定位于开源侧载、内部测试或企业受控分发。它不应被描述为可直接
上架 Google Play 的版本，发布的 debug APK 也不是生产安装包。

## 安装

默认推荐 [Release](https://github.com/lejunyang/ai-auto-android/releases) 中的完整包，
已包含 CLI、同版 `aactl-displayd.apk` 和官方 Platform-Tools `37.0.1` 的 ADB 运行文件：

1. 下载匹配系统/架构的 `aactl_<version>_<os>_<arch>.zip`（Windows）或
   `.tar.gz`（macOS/Linux），核对同一 Release 的 `SHA256SUMS`，整体解压。
   保留顶级目录内的 `platform-tools/` 和 helper，不要只移走 CLI。
2. 连接设备并开启 USB 调试；由用户在设备端核对并接受当前电脑的 ADB RSA 指纹。
3. 在解压目录打开终端，运行以下命令；macOS/Linux 使用 `./aactl` 替换 `.\aactl.exe`：

```powershell
.\aactl.exe doctor --json
.\aactl.exe devices list --json
```

`doctor` 的 `adbPath` 和 `adbSource` 显示实际路径及来源。完整包优先使用同目录
`platform-tools/adb[.exe]`，无需安装 SDK 或调整 PATH；也可将整个解压目录加入 PATH。
含 `_slim` 的精简包保留 CLI/helper，需要自行准备
[官方 Platform-Tools](https://developer.android.com/tools/releases/platform-tools)，设置
`AACTL_ADB_PATH` 指向其 ADB，或将其加入 PATH。当前不再单独发布裸 CLI。

本机 `dev-71ea6f7` 实测下载大小（MiB，版本变化会有差异）：

| 平台 | 完整包 | 精简包 |
| --- | ---: | ---: |
| macOS arm64 | 11.02 | 2.83 |
| macOS amd64 | 11.23 | 3.03 |
| Windows amd64 | 7.33 | 3.09 |
| Linux amd64 | 7.53 | 2.99 |

电脑上另一版本 ADB 与随包 ADB 交替使用时，会互相重启 ADB 服务、打断连接。
可设置 `AACTL_ADB_PATH` 指向所有工具共用的同一个 ADB，或改用精简包。
随包官方二进制的来源、许可风险和校验流程见[安全与分发](docs/security-distribution.md)。
本轮只在 Windows 实际运行包；macOS/Linux 仅核对归档结构与摘要，运行兼容性尚待验收。

从源码构建需要 Go `1.26.5`：

```bash
GO=/path/to/go make build VERSION=0.1.0
./bin/aactl version --json
```

Android App 需要 Android 11 / API 30 及以上。开发用 debug APK 作为约 72.63 MiB 的独立
`ai-auto-android_<version>_debug.apk` 位于同一 Release，不重复放进各平台包；源码产物位于
`android/app/build/outputs/apk/debug/app-debug.apk`；它使用调试签名，只能用于
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

## 虚拟显示、焦点策略与主屏息屏（CLI / MCP）

API33+ 可用 shell 身份的 `displayd` 创建后台虚拟屏，使用 `--display N` 在其上
启动、截图、读取窗口列表和输入；`aactl action sleep --device SERIAL` 使主屏息屏。
完整包与精简包都附带匹配的 helper；APK 不安装，只作 app_process 的 CLASSPATH。

```bash
aactl display start --device SERIAL --json
aactl display create --device SERIAL --width 720 --height 1280 --dpi 240 --focus steal --json
aactl observe hierarchy --device SERIAL --display N --json
aactl observe screenshot --device SERIAL --display N --output virtual.png --json
aactl display stop --device SERIAL --json
```

`N` 使用 create 返回的编号。按显示的层级是窗口列表，`format=uiautomator-windows-xml`；
普通层级读取的是活动窗口，虚拟屏有焦点时可能返回虚拟屏内容。凭据锁定拒绝取帧；
组休眠后必须关闭重建，其上应用内容会销毁。已在主屏运行的应用向虚拟屏启动会整体
移过去。API33 无法查询显示组唤醒状态，真机/OEM 与 API33/34 兼容性尚未验收。
MCP 已提供四个虚拟屏管理工具，以及带 displayId 的截图/层级和六类既有动作；
helper 启停仍只有 CLI，App Bridge 尚无多显示出口。focus 默认 isolated，保护主屏输入，
但虚拟屏没有输入法且主屏息屏时读不到层级；steal 可息屏挂机，会打断主屏输入法。
API33 两策略均无法隔离；0 明确主屏且不要求 helper。isolated 的 BACK 直接作用于应用，
空屏返回 DISPLAY_EMPTY；无窗口输出返回 WINDOWS_UNAVAILABLE。helper 协议已升 v2，
升级前先用旧 CLI 停止 v1 helper，再更新 CLI/helper 并启动。完整参数、选择建议与证据见
[虚拟显示操作](docs/virtual-display.md)。

API35+ 支持的设备可由操作员使用 `display start --virtual-camera`，再用
`display create ... --camera back` 和 CLI `camera set-image --display N --image PATH`
指定静态 PNG/JPEG。MCP 创建屏支持可选 `camera:"back"`，启停、图片路径与相机权限
仍由操作员/用户处理。NX888J/API36 的isolated/fixture/640×480读回、切图和33分钟单流已由集成方与负责人验收；需用户默认设备+虚拟设备两处CAMERA授权。其它档位、steal读回、IME/息屏及自动重连真机效果仍待验，见[虚拟摄像头](docs/virtual-camera.md)。
操作员可用`display stop --device SERIAL --keep-camera-association`保留关联供重启复用；
设备将常驻shell伴生关联及既有虚拟CAMERA授权，归还前以不带开关stop移除（无helper也可）。
保留后helper重启免重授的真机效果待验；MCP不开放此开关。
摄像头屏关闭先移除显示、最多等6秒流关闭再注销；VIRTUAL_CAMERA_BUSY时保留helper/关联，
请操作员让应用释放相机再重试。出流时禁止kill helper；A16系统卡死可能只能由负责人重启恢复。
当前已改拆除顺序，b6956真机关闭不卡死仍待集成方复验。

## MCP 配置

`aactl mcp serve` 通过 stdio 暴露 20 个类型化工具，其中视觉候选工具只读且不会执行
动作。MCP 客户端可使用以下通用配置；
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

### 远程主机上的设备

已有受信任 SSH 主机时，可让 MCP 通过 SSH stdio 启动该主机上的 `aactl`。
ADB、Bridge forward 和会话文件均留在设备所在主机，客户端不需要开放 ADB 或 Bridge
网络端口。先在该主机完成设备授权与 `bridge open`；首次 SSH host key 验证也由操作员完成。

```json
{
  "mcpServers": {
    "android-remote": {
      "command": "ssh",
      "args": ["-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
        "control-host", "/opt/ai-auto-android/aactl", "mcp", "serve"]
    }
  }
}
```

`control-host` 是用户已有的 SSH 配置别名，路径替换为远端实际路径；远端会话使用同一
操作系统账户。stdout 必须只输出 MCP，远端 shell 启动脚本不要打印欢迎文本。此配置复用
现有 stdio 协议，尚未做真实 SSH/设备端到端验收。内置 LAN 目前仍为邀请、认证和只读
探针入口，未接入 CLI/MCP 持续控制；详见[控制能力评估](docs/control-assessment.md)。
不要把 `ADB_SERVER_SOCKET` 指向远端作为 Bridge 远控方案：当前 Bridge 客户端始终连接
本机 `127.0.0.1` 的 forward 端口，doctor 也检查本机 5037。

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

- INTERNET / ACCESS_NETWORK_STATE 用于 Provider 网络访问与网络状态。
- CAMERA 用于用户主动触发的二维码扫描；无相机或拒绝权限时可手工输入邀请码。
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE 用于有通知、可暂停/停止的会话保活。
- POST_NOTIFICATIONS 用于会话状态通知，Android13+ 运行时授权由用户完成。
- RECORD_AUDIO 用于按需语音输入，未授权时返回权限缺失，不能据清单声明推定已授权。
- SYSTEM_ALERT_WINDOW 用于受控悬浮助理，须用户到系统设置手动授权。
- QUERY_ALL_PACKAGES 用于列出可启动应用并逐包配置授权，不等于获得这些应用的控制权限。
- AccessibilityService 必须由用户先接受 App 内独立醒目披露，再到系统设置手动
  启用；App 不能替用户授权。
- 用户必须配置允许的目标包。前台包不匹配、敏感目标或能力不可用时执行失败关闭。
- 桌面桥默认关闭，只绑定设备 loopback；每次由用户开启并使用一次性码建立短期会话。
- ADB 的 USB/无线调试授权属于电脑与设备间的独立信任，必须由设备用户确认。

## MVP 限制

- 仅支持 Android 11 / API 30 及以上；不支持 Root、Shizuku、Device Owner、
  蓝牙 ADB、互联网远控、OCR 或设备农场。
- 直接ADB只提供预定义的类型化命令，不提供任意shell；文件传输、判据与虚拟屏同样受各自边界约束。
- 录制订阅点击、长按、文本、滚动和窗口变化；由 App 执行器发起的 Back、Home、
  Recents 也会录制。原始触摸、物理系统导航和自由手势不在录制范围内。
- 普通 Bridge 快照不回传截图；获授权的 debug disposable emulator 原子视觉执行请求
  是例外，可携带有界 PNG 并在校验/执行后清零。App AI 默认发语义摘要，只有显式允许
  截图且取得有效授权时才发送有界图像与 observation 上下文。原始 ADB 截图/XML 可能含敏感内容。
- CLI 可列出但不能编辑录制脚本，也不能向回放传入 secret；MCP 不执行录制回放。
- 支付、购买、安装、授权和系统安全设置被禁止；发送、提交、删除等动作需要当次确认。

## 开发与验证

固定工具链为 Go `1.26.5`、JDK `21`、Android SDK `36`、AGP `9.1.1` 和
Gradle `9.3.1`。本机 SDK 路径不得提交到仓库。

使用最新正式版 osdk；CI 通过官方 `lejunyang/one-sdk@main` Action 和
`version: latest` 安装，不再手写下载器或固定 osdk 程序版本。Go/JDK/Android 等项目
工具链继续由 `osdk.toml` 固定。Action 只负责 osdk，本项目的工具安装和许可证选项由后续
显式步骤执行。安装前可用
`osdk android licenses show android-platforms` 查阅许可证；`--yes` 只处理普通确认，
不会替代 Android SDK 许可证同意。以下安装命令显式限定为本请求的 `android-sdk-license`：

```bash
osdk --yes trust .
osdk --yes install go node java android-platforms android-build-tools android-platform-tools -o accept-license=android-sdk-license
osdk exec -t go -t node -- make test verify build
make android-verify
```

Windows 的 `conda:m2-base` 用 `when` 限定平台；Windows 开发者另执行 `osdk --yes install`，
在激活 osdk 的 shell 中使用 Makefile。模拟器和镜像设为 lazy，只有设备测试需要时才运行
`osdk --yes install android-emulator android-system-images -o accept-license=android-sdk-license`。
系统安装工具链时仍可直接使用原有入口：

```bash
make test
make verify
make build
ANDROID_HOME=/path/to/android-sdk ./android/gradlew -p android \
  --no-daemon testDebugUnitTest assembleDebug lintDebug
AACTL_ACCEPT_ANDROID_SDK_LICENSE=android-sdk-license make release VERSION=0.1.0
# 仅精简包：不下载 Platform-Tools；Go/Gradle 依赖和工具链必须已在本地准备好。
make release VERSION=0.1.0 BUNDLES=slim DIST_DIR=dist-slim
```

`make test` 运行协议、Skills、Go 格式、单测和 vet；`make verify` 运行协议与
Skills smoke、fake ADB 定向测试和工具链元数据检查；`make doctor` 检查本机完整
工具链。发布器要求新的专属 `DIST_DIR`，不会覆盖已有目录；`SOURCE_DATE_EPOCH` 未设置时
取提交时间，归档固定排序、权限和时间。Windows 源码构建应显式传入真实 `GO=.../go.exe`，
避免无扩展名垫片无法被子进程定位。仅精简构建限制 Go/Gradle 离线；工具链需事先安装。
默认发布目录 `dist/` 被忽略；自定义目录请使用忽略的 `artifacts/` 或仓库外位置，所有产物和
下载缓存均不应提交。完整包下载/使用缓存前必须显式同意上述许可变量，CI 同样显式传入。

## 文档

- [文档导航](docs/README.md)
- [架构与信任边界](docs/architecture.md)
- [协议 v1](docs/protocol.md)
- [录制与回放](docs/recording.md)
- [真机 USB 验收](docs/device-usb-acceptance.md)
- [安全与分发](docs/security-distribution.md)
- [故障排查](docs/troubleshooting.md)
- [备选方案](docs/alternatives.md)
- [Task 12 验证记录](docs/validation.md)
