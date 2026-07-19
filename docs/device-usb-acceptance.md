# 真机 USB 验收

本清单用于在 macOS 或 Windows 上验证一台 Android 11 / API 30 及以上真机，
覆盖 USB 调试、App 安装、直接观察与操作、无障碍、桌面 Bridge 和录制回放。
所有电脑端设备操作只使用 `aactl` 或 Gradle，不使用 raw ADB。

以下 `./bin/aactl` 示例使用 macOS/Bash 写法。Windows PowerShell 使用同一参数，
将可执行文件替换为 `.\bin\aactl.exe`，并把行末 `\` 改为反引号或写在一行。

## USB 模式怎么选

手机通知中的“仅充电”“传输文件”“传输图片”是 USB 功能选择，不代表线缆能力。
验收必须使用支持数据的线缆，但手机 USB 用途通常可以选择：

| 手机 USB 用途 | 是否适合 ADB | 选择建议 |
| --- | --- | --- |
| 仅充电 | 是 | 默认选择；ADB 调试接口不依赖 MTP/PTP |
| 传输文件 / Android Auto | 是 | 仅在厂商设备不枚举或 Windows 驱动未绑定时尝试 |
| 传输图片 / PTP | 是，但没有额外收益 | 无需为了 ADB 选择 |

因此先保持“仅充电”。只要 `aactl devices list --json` 返回状态为 `device`，
就不需要切换模式。若列表为空，先确认线缆支持数据、手机已解锁和 USB 调试已开启；
仍不出现时再临时切换“传输文件”，触发设备重新枚举。macOS 不需要 OEM USB
驱动；Windows 可能需要厂商官方 ADB 驱动。

## 1. 准备手机

1. 使用专门的测试机或不含敏感数据的测试账号，关闭通知预览，离开密码、验证码、
   支付和私密消息页面。
2. 在“设置 > 关于手机”中连续点击版本号或系统版本，按设备提示手动解锁并开启
   开发者选项。不同厂商路径可能不同，可在设置中搜索“开发者选项”。
3. 在开发者选项中手动开启“USB 调试”，阅读系统风险提示后确认。
4. 使用支持数据的 USB 线连接电脑，保持手机解锁。USB 用途先选“仅充电”。
5. 手机出现 RSA 指纹对话框时，核对这是当前电脑后由用户手动接受。不要让 Agent
   点击、绕过或修改设备信任存储；不确定时拒绝并重新核对。

部分厂商另有“USB 调试（安全设置）”“通过 USB 安装”或类似开关。基础发现、
截图和无障碍 Bridge 不应预先要求这些权限。“通过 USB 安装”不是本验收前提；
若设备已经在线且观察成功，只有直接 `tap`、`text`、`key` 等输入明确被厂商安全
策略拒绝时，才评估“USB 调试（安全设置）”。该开关会扩大已授权电脑的输入权限，
应由用户在专用测试机上手动开启并在验收后关闭。它不能替代标准 USB 调试或 RSA
确认。

## 2. 发现并锁定设备

在仓库根目录构建并执行：

```bash
make build
./bin/aactl version --json
./bin/aactl doctor --json
./bin/aactl devices list --json
```

检查 `doctor` 的 ADB executable、server、`127.0.0.1:5037` 和 mDNS 项。首次连接
可能先显示 `unauthorized`；完成设备端 RSA 确认后重新运行列表。只有选中设备状态
为 `device` 且 `adb.direct.available` 为 `true` 时继续。

存在多台设备时，人工选择一个精确 `serial`，后文用 `SERIAL` 表示。不要按型号、
连接顺序或 USB/Wi-Fi 类型猜测目标：

```bash
./bin/aactl device info --device SERIAL --json
```

如果设备未出现：

- 确认物理线缆支持数据，不要把手机界面的“仅充电”误认为充电专用线；
- 解锁手机，换电脑直连端口和线缆，避免未经供电的 Hub；
- 保持 USB 调试开启，临时把 USB 用途切到“传输文件”后重新发现；
- Windows 检查并安装厂商官方 ADB 驱动，不关闭驱动签名校验；
- 不自动重启共享 ADB server，也不绕过 `unauthorized`。

## 3. 构建并安装 App

先构建 debug APK：

```bash
./android/gradlew -p android --no-daemon assembleDebug
```

Gradle 安装会使用 Android 工具链。为避免装到错误设备，显式提供上一步选择的
serial。

macOS：

```bash
ANDROID_SERIAL=SERIAL ./android/gradlew -p android --no-daemon installDebug
```

Windows PowerShell：

```powershell
$env:ANDROID_SERIAL = "SERIAL"
.\android\gradlew.bat -p android --no-daemon installDebug
```

安装失败时不要自动开启“通过 USB 安装”或点击系统安装授权。先读取 Gradle 错误；
需要设备端安装确认或厂商开发者权限时，由用户评估风险并手动处理。debug APK 使用
调试签名，只用于开发验收。

安装完成后可启动 App：

```bash
./bin/aactl action launch --device SERIAL \
  --package dev.aiauto.android --json
```

## 4. 直接能力 smoke

先在手机上打开一个不含敏感信息的测试页面。截图路径必须位于源码仓库外，验收后
删除：

```bash
./bin/aactl observe screenshot --device SERIAL \
  --output /ABSOLUTE/TEMP/PATH/aiauto-screen.png --json
./bin/aactl observe hierarchy --device SERIAL --json
```

检查截图返回 `format: "png"`、非零 `sizeBytes` 和 `sha256`；层级返回
`format: "uiautomator-xml"`。不要提交、上传或粘贴原始截图和 XML。

使用已知、无外部副作用的测试包验证启动动作：

```bash
./bin/aactl action launch --device SERIAL \
  --package com.example.target --json
```

重新获取 hierarchy 或截图，确认前台页面确实变化。命令成功只表示请求已提交，
不能替代结果观察。不要用支付、发送、删除、授权或系统安全设置作为动作 smoke。

## 5. 配置目标包和无障碍

目标包必须来自被测 App 的开发者、商店链接或受控测试资料；不要猜测。回到
“AI 安卓自动化”App 后：

1. 打开“无障碍执行”。
2. 阅读醒目披露，在“允许操作的目标应用包名”中填入
   `com.example.target`。只加入本次验收所需的包。
3. 手动勾选同意并保存，进入 Android 系统无障碍设置。
4. 由用户手动启用“AI 安卓自动化执行服务”，确认系统风险提示。
5. 返回 App，确认状态显示系统服务已启用。

App 不能替用户开启无障碍。当前前台包不在允许列表时，快照、动作和回放应失败
关闭；不要通过扩大白名单解决目标不匹配。

## 6. Bridge smoke

在 App 的“桌面桥”页面点击“开启桌面桥”。App 会显示两分钟有效且使用一次后
失效的短期码。保持 App 进程存活，在电脑执行：

```bash
./bin/aactl bridge open --device SERIAL --json
./bin/aactl bridge info --device SERIAL --json
```

`bridge open` 会在交互式输入中读取短期码；不要把码写入参数、日志或验收记录。
检查 `bridge info` 中 `ui.snapshot`、`action.execute`、`recording.list` 和
`recording.replay` 能力。然后人工打开目标 App 的非敏感页面并执行：

```bash
./bin/aactl bridge snapshot --device SERIAL \
  --package com.example.target --max-depth 64 --json
```

结果应只包含目标包的脱敏语义树。密码、验证码、支付、`FLAG_SECURE` 或系统保护
页面不可作为成功样本，也不得尝试绕过。

## 7. 录制与回放 smoke

选择一个无 secret、无发送/删除/购买等外部副作用的短流程，例如在本地测试 App
中打开页面、输入公开测试文本并滚动。

1. 在 Android App 中进入“操作录制”，填写脚本名称和
   `com.example.target`，点击“开始录制”。
2. 切到目标 App，手动完成一次点击、普通文本输入和滚动。不要输入密码、验证码、
   token 或个人信息。
3. 回到录制页，确认步骤预览出现对应的 `ui.click`、`ui.setText`、`ui.scroll`
   等语义步骤，结束并保存。
4. 在 App 详情页检查目标包、步骤和潜在副作用。然后在电脑读取脱敏摘要：

```bash
./bin/aactl recording list --device SERIAL --json
```

5. 从列表选择刚审核过的 UUID。人工把目标 App 导航到脚本起始状态并保持前台，
   再执行：

```bash
./bin/aactl recording replay --device SERIAL \
  --script 123e4567-e89b-42d3-a456-426614174000 --json
```

通过标准是 `succeeded: true`，每个步骤状态为成功，且人工观察到预期 UI 结果。
如果 `requiresIntervention` 为 true，或出现 `SELECTOR_*`、
`CONDITION_TIMEOUT`、`ACTION_FAILED`，立即停止，不猜测坐标、不跳过失败步骤。

包含 `secretRef` 的脚本不能通过 CLI 回放；只能在 App 详情页临时提供 secret。
secret 不应进入命令、日志、脚本或验收记录。

## 8. 收尾和通过标准

完成后关闭短期会话：

```bash
./bin/aactl bridge close --device SERIAL --json
```

同时在 App 中关闭桌面桥，删除仓库外的临时截图，并按测试环境策略删除录制脚本。
不再需要电脑调试时，在手机开发者选项中手动关闭 USB 调试；若曾开启厂商的
“USB 调试（安全设置）”，一并关闭。

真机验收只有在以下项目都有实际证据时才算通过：

- `doctor` 健康，精确 serial 状态为 `device`；
- debug APK 安装并启动；
- 截图、hierarchy 和一个无副作用直接动作经观察验证；
- App 目标包和无障碍由用户手动授权；
- Bridge info 与包级语义快照成功；
- 当前发布配置录到点击、普通文本和滚动步骤；
- 脚本列表返回脱敏摘要，审核后的无 secret 脚本回放成功；
- Bridge 已关闭，临时敏感产物已清理。

设备缺席、`unauthorized`、`offline`、权限未手动授予或能力不可用时，应记录为
“条件不具备/未执行”，不能用单元测试或 fake ADB 声称真机通过。
