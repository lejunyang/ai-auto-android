# 模拟器 UI 自动化：能力现状与实测结论

本文记录以 Android 模拟器为唯一目标设备做 AI 驱动 UI 自动化时的能力现状、实测证据和
待补能力。本文只陈述已验证事实与明确缺口，不代替 change spec，也不预先勾选任何验收结论。

**范围**：暂不考虑真机；扫码只考虑模拟器路径。

**实测环境**：Windows + `aactl` 0.1.0-win（`bin/aactl.exe`），AVD `osdk-test-35`
（`sdk_gphone64_x86_64`，Android 15 / API 35，x86_64），emulator 来自
`E:\osdk-data\data\installs\android-sdk\emulator`，adb 解析到
`android-platform-tools\37.0.1\adb.exe`。以下标注「实测」的条目均在该环境执行过；
标注「未验证」的条目只做了代码或文档查证。

## 1. 模拟器的连接形态

### 1.1 独立 transport，不算 USB 也不算 Wi-Fi

`internal/adb/parser.go` 的 `detectTransport` 按 serial 前缀判定，`emulator-` 前缀
优先于其它分支，返回独立取值 `emulator`：

```
case strings.HasPrefix(serial, "emulator-"):
    return "emulator"
case isMDNSServiceSerial(serial):
    return "wifi"
case ValidateEndpoint(serial) == nil:
    return "wifi"
case attributes["usb"] != "":
    return "usb"
```

实测 `aactl device info --device emulator-5554` 返回：

| 字段 | 值 |
| --- | --- |
| `serial` | `emulator-5554` |
| `state` | `device` |
| `transport` | `emulator` |
| `androidVersion` / `apiLevel` | `15` / `35` |
| `capabilities` | `adb.direct` 1.0.0，`available=true`，`permission=granted` |

所以「同一机器下是否算 USB 连接」的答案是：**不算**。它既不走 USB 属性分支，也不走
mDNS/endpoint 的 wifi 分支，而是 adb server 在 loopback 上直接管理的本地 transport。

### 1.2 无需配对、无需人工介入

实测：模拟器启动后 `adb devices` 直接是 `device` 状态，`get-state` 返回 `device`，
**不经过真机那种 `unauthorized` 阶段，无 RSA 授权弹窗，无需 `devices pair`**。

原因是 AVD 镜像预置了 adb 公钥信任，与真机首次连接必须在设备上点「允许调试」不同。

因此 `devices pair / connect / trust / forget` 这条无线链路对模拟器**完全不需要**。
`docs/next-phase-tasks.md` 中未完成的 N35（无线 ADB 生命周期）不阻塞模拟器路线。

### 1.3 可获得 root —— 这是自动化授权的前提

实测 `adb -s emulator-5554 root` 返回 `restarting adbd as root`，随后 `shell id`：

```
uid=0(root) gid=0(root) groups=0(root),1004(input),1007(log),1011(adb),...
context=u:r:su:s0
```

**这一条只在 `google_apis` 镜像成立**，`google_apis_playstore` 镜像的 `adb root`
会被拒绝。`scripts/emulator/profiles.json` 中 api-30/33/34 三个 profile 的 `tag`
均为 `google_apis`，与该前提一致。

## 2. 已有的模拟器基建

`scripts/emulator/` 已实现固定生命周期（N31 已验收）：`create` → `start-cold` →
`wait` → `save-snapshot` → `stop`，后续运行走 `start` → `wait` → `restore` → `stop`。
只有 `start-cold` 允许跳过固定快照，避免常规测试从脏状态启动。

`profiles.json` 固定了 API 30/33/34、`arm64-v8a`、`pixel_6`、`1080x2400`、`420dpi`、
`zh-CN`、`Asia/Shanghai`、`gestural` 导航与 `clean` 快照，并要求 SDK archive 的
`packageSha256` 与 state root 下 `package-receipts.json` 逐字一致，不符则拒绝启动。

**注意本次实测用的是本机既有 AVD `osdk-test-35`（API 35 / x86_64），不在上述 profile
矩阵内。** 本文所有实测结论都来自该 AVD；profile 矩阵中的 API 30/33/34 与 arm64
是否有行为差异，未验证。

## 3. 自动装桥并开启无障碍：路径成立，但需要设计决策

### 3.1 AGENTS.md 已授权此场景

`AGENTS.md` 第 58 行：

> 仅可在 disposable emulator、debug/test 变体和明确 test-only 标记下自动预置测试服务、
> 测试 Bridge 或无敏感数据 fixture；release 变体不得包含这些入口。

第 59 行同时限定：真机的无障碍服务、MediaProjection、安装确认等**必须由用户完成**。

所以「模拟器上自动开无障碍」不违反既有边界，但**实现必须把这条路径限制在 disposable
emulator + debug 变体上**，且不得让同一入口在 release 变体或真机上可用。

### 3.2 关键写入点实测可用

实测 `settings` 的 secure 命名空间可读可写可删：

```
settings get secure enabled_accessibility_services   -> null（初始未启用）
settings get secure accessibility_enabled            -> 0
settings put secure enabled_accessibility_services <pkg>/<service>   -> 写入成功
settings delete secure enabled_accessibility_services -> Deleted 1 rows
```

即：无障碍服务的启用状态可以完全由 adb 侧写入，**不需要在设备上手点系统设置**。
完整开启通常需要同时写 `enabled_accessibility_services` 与 `accessibility_enabled=1`。

**未验证**：写入后 App 侧的 AccessibilityService 是否真的被系统绑定并开始收事件。
这一步必须在桥 APK 装上之后端到端验证，不能从「写入成功」推断「服务已生效」。

### 3.3 仍未实现

`scripts/emulator/` 与 `test-lab/` 下搜索 `adb root` / `remount` /
`settings put secure` / `enabled_accessibility` / `pm grant` / `appops`
**全部零命中**。也就是说自动预置能力目前一行都没有。

`android/test-control/` 目录下只有 `reporting/README.md`，N32 声称的
Debug-only Test Control Plane 没有 Kotlin 实现落在该目录。

## 4. 观察能力：截屏已有，录屏可用但未接入

### 4.1 截屏（已有）

`observe screenshot` 走 `adb -s SERIAL exec-out screencap -p`，PNG。
实测在模拟器上正常返回，本文档的验证截图即由此得到。

### 4.2 录屏（设备支持，项目未接）

实测设备侧 `screenrecord` 可用：

```
Android screenrecord v1.3.  Records the device's display to a .mp4 file.
  --size WIDTHxHEIGHT     默认主显示分辨率，不支持时 1280x720
  --bit-rate RATE         默认 20Mbps
  --time-limit TIME       默认 180 秒，设为 0 取消限制
  --display-id ID         指定物理显示 ID
  --bugreport             叠加时间戳等信息
```

`aactl` 侧没有任何录屏入口（`observe` 只有 `screenshot|hierarchy`，MCP 的
`android_observe` 只有 `screenshot|hierarchy|semantic|recordings`）。

接入时需要处理的点：`screenrecord` 是**阻塞式**的，需要后台起进程并在停止时发
`SIGINT`（`adb shell` 侧通常用 `pkill -INT screenrecord`）才能得到完整 mp4；
180 秒默认上限对长流程不够；文件先落 `/sdcard` 再 pull，需要清理。

### 4.3 异常自愈缺少的一块：前台包名读不到

UI 自动化要在 app 变更时「从界面获得信息并更正」，第一步是知道**现在在哪个界面**。

`internal/adb/direct.go` 与 `internal/service/` 中，`activity` 只出现在 `Launch`
的**入参校验**（`validateActivity`）里，没有任何回读逻辑；`mCurrentFocus` /
`topResumedActivity` / `foreground` 等关键词零命中。

实测这些信息 adb 侧本来就能拿：

```
adb shell dumpsys activity activities | grep topResumedActivity
  -> topResumedActivity=ActivityRecord{... com.android.camera2/com.android.camera.CameraActivity t15}
```

协议层的 `ui.assert` 已定义 `kind` 枚举 `["node","text","package","window","uiStable"]`
与 `operator` 枚举 `["exists","notExists","equals","contains","matches","stable"]`，
其中 `package` 与 `window` 正是「当前在哪」的断言主体 —— **判据已在协议里，取值来源没接**。

## 5. 摄像头与扫码：模拟器路径已验证可行

### 5.1 emulator 支持直接喂图作为摄像头输入

`emulator -help-camera-back` 的取值：

| 模式 | 含义 |
| --- | --- |
| `emulated` | 软件「假」摄像头 |
| `webcam<N>` | 使用宿主机摄像头 |
| `environment` | 渲染 AVD `environment.ini` 定义的虚拟场景 |
| `imagefile:<filename>` | **渲染指定图片文件** |
| `videofile:<filename>` | 渲染视频文件 |
| `image360:<filename>` | 渲染全景图 |
| `none` | 关闭摄像头 |

另有 `-virtualscene-poster <name>=<filename>`，可把 png/jpeg 作为虚拟场景中的海报。

本次实测的 AVD `config.ini` 原为 `hw.camera.back=emulated`、`hw.camera.front=none`。

### 5.2 端到端实测：预览里确实是喂进去的二维码

步骤：

1. 用 `qrcode` 生成内容为 `https://example.com/aactl-probe-12345` 的二维码，
   保存为 640×480 PNG。
2. `adb emu kill` 后以 `-camera-back imagefile:E:\tmp\qr\probe.png` 重启 AVD。
3. `pm grant com.android.camera2 android.permission.CAMERA`，
   `am start -n com.android.camera2/com.android.camera.CameraActivity`。
4. 点掉两个首启弹窗（见 5.3），`exec-out screencap -p` 抓屏。

结果：**截屏中央即为该二维码**，三个定位角与数据区清晰可辨，
暗像素 52.9% / 亮像素 42.6%。

**一处未闭合**：`cv2.QRCodeDetector` 未能解码。原因是把 640×480 横图喂进竖屏预览，
**二维码右侧被裁掉**，图形不完整。这是喂图宽高比问题，不是能力问题 ——
按设备预览比例生成（如 1080×2400 或居中留白的方图）应可解码，**未验证**。

### 5.3 实测踩到的坑：首启弹窗与坐标匹配陷阱

相机首次启动连续弹两层：

1. `Remember photo locations?` + `NEXT`
2. `Allow Camera to access this device's location?` +
   `Precise / Approximate / While using the app / Only this time / Don't allow`

第一版探针按「文字前缀匹配确认词」找按钮，把标题
`Allow Camera to access this device's location?`（以 `Allow` 开头）当成了按钮，
连点四轮同一坐标 `(160,138)` 无效。改为**只匹配 `clickable="true"` 的节点**后一次
点中 `Only this time @ (160,459)`。

这个坑正是本项目坐标级动作的固有风险的具体实例：**匹配到了、点下去了、没有报错、
但点的不是目标**。也说明 `ui.assert` 那套后置校验是必需的，不是可选项。

## 6. 待补能力清单

按「是否阻塞 UI 自动化闭环」排列。每项都标注了归属层与已知代价，**均未实现**。

### 6.1 阻塞自愈闭环

| 能力 | 现状 | 归属 |
| --- | --- | --- |
| 读当前前台包名与 Activity | 零实现；`dumpsys activity activities` 可得 | 纯 adb |
| 列出已安装应用 | 全仓零命中；桥协议 8 个方法里也没有 | 纯 adb |
| `ui.assert` 暴露到 MCP | 协议已定义，MCP 未暴露 | 暴露层 |
| `ui.wait` 暴露到 MCP | 协议已定义，MCP 未暴露 | 暴露层 |

前两项是自愈的信息来源，后两项是自愈的判据执行。四项齐了才能形成
「动作 → 校验 → 发现偏离 → 重新观察 → 更正」的回路。

### 6.2 观察能力扩展

| 能力 | 现状 | 备注 |
| --- | --- | --- |
| 录屏 | 设备支持 `screenrecord v1.3`，项目未接 | 需处理阻塞式停止与 180s 上限 |
| 摄像头喂图 | emulator 支持 `imagefile:`，项目未接 | 需按预览比例生成图；重启 AVD 才能换图 |
| UI 树体积控制 | 仅桥侧 `maxDepth`，直连侧无预算机制 | 桌面侧那套字符预算与分区下钻可参照 |

**摄像头的一个结构性限制**：`-camera-back` 是**启动参数**，换图需要重启 AVD。
若要在一次会话内切换多个二维码，需要另找运行时通道（`virtualscene-poster` 是否可
热替换、或改用 `videofile:` 播放多码序列），**未验证**。

### 6.3 动作能力扩展

| 能力 | 协议层 | MCP 层 | 备注 |
| --- | --- | --- | --- |
| `ui.longClick` | 已定义 | 仅视觉通道 | |
| `ui.scroll` | 已定义 | 未暴露 | |
| 中文文本输入 | **无字符集限制** | 被 ASCII 正则拒 | 见下 |
| 多指手势 | 未定义 | 未暴露 | 三层皆空 |

中文输入的限制来自两处，**不是协议层**：
- `internal/mcpserver/schemas.go:103` 的 `"pattern":"^[-A-Za-z0-9 @._+,:/]*$"`
- `internal/adb/direct.go:180` 注释「Text 只接受受限 ASCII 文本」，
  第 188 行的错误信息说明这是 `adb shell input text` 的限制

`protocol/schema/v1/action.schema.json` 的 `ui.setText` 只有 `maxLength: 10000`。
在模拟器上可考虑的路径：走桥侧无障碍 `setText`（绕开 `input text`），或预置一个
test-only IME。两条都**未验证**。

### 6.4 自动预置（省人工）

| 能力 | 前提 | 状态 |
| --- | --- | --- |
| `adb root` | `google_apis` 镜像 | 实测可用 |
| 写 secure settings 开无障碍 | root 或 `WRITE_SECURE_SETTINGS` | 写入实测可用，绑定生效未验证 |
| 安装桥 APK | debug 变体 | 未验证（`pm install -t` 等） |
| 运行时权限预授 | — | `pm grant` 实测可用（相机权限） |

自动预置必须满足 AGENTS.md 的两条约束：**只在 disposable emulator + debug/test 变体
生效**，且 **release 变体不得包含这些入口**。

## 7. 待确认的设计决策

以下问题会影响实现方向，尚无结论：

1. **桥是否仍是必需**。若前台包名、应用列表、录屏都从纯 adb 补齐，剩下只有
   语义快照与稳定选择器必须靠桥。是否为此维持完整桥链路，取决于 UI 自动化对
   「元素级定位」的依赖程度 —— 坐标级已被 5.3 证明会静默点错。
2. **摄像头换图的运行时通道**。若必须重启 AVD 才能换二维码，扫码场景的每个用例都要
   付一次冷启动代价（本次实测约 45–50 秒）。
3. **API 35 / x86_64 与 profile 矩阵的关系**。本文实测在 API 35 x86_64 上完成，而
   `profiles.json` 固定 API 30/33/34 arm64。是否把 x86_64 纳入 profile，或本地开发
   与矩阵验收分用不同配置。
4. **中文输入走桥还是走 test-only IME**。前者依赖桥常驻，后者需要额外预置组件。
5. **录屏产物的脱敏与清理**。`AGENTS.md` 要求失败产物「限额且脱敏」，录屏比截屏
   体积大得多，需定上限与保留策略。

## 8. 复现方式

本文实测可按以下方式复现（`E:\tmp\qr\` 下的探针脚本为一次性产物，非仓库内容）：

```powershell
# 起模拟器并喂二维码图
$emu = 'E:\osdk-data\data\installs\android-sdk\emulator\emulator.exe'
Start-Process $emu -ArgumentList '-avd','osdk-test-35',
  '-camera-back','imagefile:E:\tmp\qr\probe.png','-no-snapshot-save','-no-boot-anim'

# 连接形态
adb devices -l
.\bin\aactl.exe device info --device emulator-5554

# root 与无障碍写入
adb -s emulator-5554 root
adb -s emulator-5554 shell settings get secure enabled_accessibility_services

# 录屏能力
adb -s emulator-5554 shell screenrecord --help

# 前台 Activity
adb -s emulator-5554 shell dumpsys activity activities | Select-String topResumedActivity
```

读 UI 层级时注意：**PowerShell 内联 `python -c` 会吞掉引号**，需要引号的探针一律写成
`.py` 文件再执行。本次因此失败两次。
