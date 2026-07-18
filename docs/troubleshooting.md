# 故障排查

先诊断传输，再诊断 App Bridge，最后诊断动作或录制。不要在设备状态未知时重复
执行动作，也不要把超时当成“肯定未执行”。

## 基线检查

```bash
aactl version --json
aactl doctor --json
aactl devices list --json
```

`version` 不需要 ADB。`doctor` 检查：

- ADB 可执行文件及版本；
- `adb server-status` 是否可读取；
- 本机 loopback `127.0.0.1:5037` 是否有 ADB server；
- `adb mdns check` 是否通过。

`doctor` 不会自动执行 `adb kill-server`。读取每个 `checks[]` 的 `name`、
`passed`、`message`，不要只看顶层 `healthy`。

## ADB 未找到

错误：`ADB_NOT_FOUND`

只安装官方
[Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)。
`aactl` 按以下顺序查找：

1. `AACTL_ADB_PATH`；
2. `PATH` 中的 `adb` / `adb.exe`；
3. `ANDROID_HOME/platform-tools`；
4. `ANDROID_SDK_ROOT/platform-tools`；
5. 默认 SDK 目录：
   - macOS：`~/Library/Android/sdk/platform-tools/adb`
   - Windows：`%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe`

macOS 当前终端临时指定：

```bash
export AACTL_ADB_PATH="$HOME/Library/Android/sdk/platform-tools/adb"
aactl doctor --json
```

Windows PowerShell 当前会话临时指定：

```powershell
$env:AACTL_ADB_PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
aactl doctor --json
```

路径必须指向文件；macOS 上还必须有可执行权限。不要从非官方镜像下载单独的
ADB 二进制。

## USB

### macOS

Android 官方说明 macOS 不需要额外 USB 驱动。设备未出现时依次检查：

1. 使用确认支持数据传输的线缆，不使用仅充电线；
2. 设备保持解锁，开启“开发者选项 > USB 调试”；
3. 更换电脑 USB 端口、线缆，避免未经供电的 Hub；
4. 在设备 USB 用途提示中选择可传输数据的模式；
5. 重新执行 `aactl devices list --json`。

### Windows

Windows 可能需要设备对应的 ADB USB 驱动。Google 设备可使用 Google USB
Driver，其他品牌优先使用厂商官方驱动，参见
[安装 OEM USB 驱动程序](https://developer.android.com/studio/run/oem-usb)。

在“设备管理器”检查设备是否出现在“Android Device”“便携设备”或“其他设备”：

- 黄色感叹号：更新为匹配设备的厂商 ADB 驱动；
- 只有便携设备、没有 ADB Interface：确认 USB 调试和驱动；
- 驱动正常但列表为空：换数据线/端口，解锁设备后重新插拔；
- 不要修改或关闭 Windows 驱动签名校验来强装未知驱动。

驱动修复后重新运行：

```powershell
aactl doctor --json
aactl devices list --json
```

## 设备状态

### 没有设备

`devices list` 可能成功返回 `count: 0`；对不存在 serial 操作时返回
`DEVICE_NOT_FOUND`。确认 USB、模拟器启动状态或 Wi-Fi endpoint，然后重新发现。
不要复用旧 Wi-Fi serial，因为无线调试重启后端口可能变化。

### `unauthorized`

错误：`ADB_UNAUTHORIZED`

1. 停止自动化并保持设备解锁；
2. 在设备上比较并接受当前工作站的 RSA 指纹；
3. 如果提示未出现，重新插拔 USB；必要时由用户在开发者选项中撤销旧调试授权
   后重新连接；
4. 再次执行 `aactl devices list --json`；
5. 仅在状态为 `device` 且 `adb.direct.available` 为 true 后继续。

不能从电脑端绕过 RSA 对话框，也不能替用户批准。

### `offline`

错误：`DEVICE_OFFLINE`

`offline` 表示 transport 存在但 adbd 无响应。先停止动作：

- USB：解锁设备，换线/端口并重新插拔；
- Wi-Fi：确认同一网络、无线调试仍开启，再用当前连接 endpoint 执行
  `devices connect`；
- 模拟器：等待完整启动，再重新列出设备；
- 运行 `aactl doctor --json`，确认 5037 和 ADB server 状态。

不要在 `offline` 上重试动作。`aactl` 不会重启共享 ADB server；只有操作者确认
不会中断 Android Studio、模拟器或其他 ADB 客户端后，才可在产品外按 Android
官方指南显式重启 server，然后重新运行 doctor 和发现流程。

### 多设备

始终展示并选择精确 serial：

```bash
aactl devices list --json
aactl device info --device SERIAL --json
```

不要按列表第一项、型号或 USB/Wi-Fi 类型推断目标。后续每条 CLI 命令都携带
`--device SERIAL`，每个 MCP 请求都携带 `"device":"SERIAL"`。

## Android 11+ Wi-Fi 调试

设备和工作站必须位于允许本地对等通信的同一网络。设备端打开“无线调试”，选择
“使用配对码配对设备”。配对 endpoint 和连接 endpoint 通常端口不同。

```bash
# 输入设备显示的配对 HOST:PORT；命令随后从 stdin 读取 6 位码。
aactl devices pair 192.0.2.10:37123 --json

# 使用无线调试主页面显示的连接 HOST:PORT，不复用配对端口。
aactl devices connect 192.0.2.10:40117 --json

aactl devices list --json
```

不要把配对码写入命令参数、日志或文档。码过期后在设备上生成新码。

### 已配对但未连接

ADB 依赖 mDNS 自动发现已配对设备。常见原因：

- 企业 Wi-Fi/AP 隔离阻止组播或设备间通信；
- VPN、防火墙或安全软件阻止 mDNS/TLS 端口；
- 工作站和设备不在同一网络；
- 设备关闭无线调试、切换网络或 endpoint 已变化；
- Platform-Tools 过旧。

先运行 `aactl doctor --json` 并检查 `adb.server` 中的 mDNS 信息和
`adb.mdns`。该检查只证明 ADB mDNS 后端可用，不证明当前网络一定能发现设备。
如果 mDNS 不通但设备显示连接 endpoint，可直接执行：

```bash
aactl devices connect HOST:PORT --json
```

仍失败时换用允许本地组播的网络，或回退 USB。项目不提供传统
`adb tcpip 5555` 流程；它不属于当前 `aactl` 安全接口，也没有 Android 11+
无线调试的配对保护。

官方步骤见
[通过 Wi-Fi 使用 ADB](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)。

## ADB server 与 5037

`adb.port.5037` 失败表示本机 loopback 5037 没有可连接的 ADB server，或被
防火墙/其他进程影响。`adb.server` 失败则表示 `server-status` 不可用。

处理顺序：

1. 确认 `AACTL_ADB_PATH`、Android Studio 和其他终端使用同一套
   Platform-Tools；
2. 关闭重复或陈旧的第三方 ADB 工具；
3. 检查本机安全软件是否阻止 loopback 5037；
4. 由操作者评估共享客户端影响后，在 `aactl` 外显式修复 ADB server；
5. 再运行 `aactl doctor --json`。

不要让 Agent 自动执行 `adb kill-server`，因为它会中断所有共享 ADB 客户端。

## Bridge

先在 Android App 中确认：

1. 已接受无障碍披露并配置目标包；
2. 已在 Android 系统设置中手动启用本 App 的无障碍服务；
3. App 的“桌面桥”页面已开启并显示未过期的一次性码；
4. App 进程仍存活；当前 Bridge 不是独立的持久前台服务。

然后：

```bash
aactl bridge open --device SERIAL --json
aactl bridge info --device SERIAL --json
aactl bridge snapshot --device SERIAL \
  --package com.example.app --max-depth 64 --json
```

### `DEVICE_UNREACHABLE`

- 确认设备状态为 `device`；
- 确认 App Bridge 仍开启，App 进程没有被系统终止；
- 关闭旧会话后在 App 生成新码，再执行 `bridge open`；
- 如果 open 失败，CLI 会清理本次新建的 forward，不要手工复用输出端口。

### `AUTH_REQUIRED`、`AUTH_INVALID`、`AUTH_EXPIRED`

旧 token 不可恢复或刷新。在 App 中生成新的一次性码，重新执行：

```bash
aactl bridge open --device SERIAL --json
```

不要读取、复制或记录本地 token 文件。新 `open` 会尝试关闭同设备旧会话并清理
旧 forward。

### `CAPABILITY_UNAVAILABLE`

查看 `bridge info` 返回的 capabilities：

- `ui.snapshot` / `action.execute` 不可用：用户需手动启用并配置无障碍服务；
- `recording.replay` 不可用：无障碍运行时未就绪；
- `recording.list` 不依赖无障碍运行时；若调用失败，确认 App 桌面桥已开启且 CLI
  保存的短期 token 仍有效。

### `PERMISSION_DENIED`

通常表示未接受 App 内披露、未配置目标包、请求包不在白名单，或当前前台包与
目标不同。回到 App 修正设置并人工导航到目标包；不要扩大白名单绕过检查。

### `REQUEST_REPLAYED`、`RATE_LIMITED`、`MESSAGE_TOO_LARGE`

- 重放错误：每个请求使用新的 UUID `requestId`；
- 限流：降低并发，等待已有请求完成；
- 消息过大：缩小语义树深度或动作参数；单条消息含换行最多 1 MiB。

完成后关闭：

```bash
aactl bridge close --device SERIAL --json
```

## 动作与选择器

### `SELECTOR_NOT_FOUND`

获取同一目标包的新快照，只在能重新识别同一唯一控件时重试一次：

```bash
aactl bridge snapshot --device SERIAL \
  --package com.example.app --max-depth 64 --json
```

应用升级、语言变化、资源 ID 改动或列表滚动都可能使旧选择器失效。不要直接改用
猜测坐标。

### `SELECTOR_AMBIGUOUS`

停止并请求人工接管。不要选择第一个候选，也不要降低唯一性阈值。需要更稳定的
资源 ID、内容描述或祖先上下文后再生成动作。

### `ACTION_FAILED`、`DEADLINE_EXCEEDED`

先重新观察再决定是否重试。超时后动作可能已经发生；发送、提交、删除等外部
副作用必须先核对结果，避免重复操作。

直接文本只接受最多 10,000 字节的非敏感 ASCII 字母、数字、空格和
`@._+-,:/`。中文、换行、密码和验证码不能通过直接文本命令输入。

## 录制

- `SCRIPT_NOT_FOUND`：在 App 中选择现有脚本并重新确认 UUID；CLI 没有列表命令。
- `SECRET_REQUIRED`：含 secret 的脚本必须从 App 详情页提供临时值并回放；
  CLI/MCP 当前不能传 secret。
- 只录到窗口变化：这是当前 APK 的已知限制，服务配置尚未订阅点击、长按、文本
  和滚动事件，见[录制与回放](recording.md)。
- 选择器低置信度或歧义：停止，不以保存坐标猜测执行。

## 快速错误表

| code | 下一步 |
| --- | --- |
| `ADB_NOT_FOUND` | 安装官方 Platform-Tools 或设置 `AACTL_ADB_PATH` |
| `ADB_UNAUTHORIZED` | 设备端核对并接受 RSA 指纹 |
| `DEVICE_OFFLINE` | 修复 USB/Wi-Fi transport 后重新发现 |
| `DEVICE_NOT_FOUND` | 核对当前 serial/endpoint |
| `MULTIPLE_DEVICES` | 明确选择一个 serial |
| `AUTH_*` | App 生成新码并重新 `bridge open` |
| `CAPABILITY_UNAVAILABLE` | 查看 capability reason；不使用 shell 模拟 |
| `PERMISSION_DENIED` | 修正披露、目标包或人工授权 |
| `SELECTOR_AMBIGUOUS` | 停止并人工接管 |
| `DEADLINE_EXCEEDED` | 先观察和核对结果，再判断是否重试 |

## 官方参考

- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [在硬件设备上运行应用](https://developer.android.com/studio/run/device)
- [安装 OEM USB 驱动程序](https://developer.android.com/studio/run/oem-usb)
- [SDK Platform-Tools 版本说明](https://developer.android.com/tools/releases/platform-tools)
