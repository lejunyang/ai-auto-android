# 中文输入通道与虚拟摄像头可行性

本文记录两件事：解除 ADB 文本限制（桥侧 setText 通道已打通并实测写入中文），
以及「在桥 App 里实现虚拟摄像头供其他 app 使用」的可行性判定。

前置事实见同目录其余文档。**实测环境**：AVD `osdk-test-35`（API 35 / x86_64），
桥 APK 与 device-fixture 均由本轮源码构建安装。

## 1. 中文输入已打通

### 1.1 新增能力

`aactl bridge set-text --device <serial> --text <文本> [定位选项]`

定位三选一，按可靠性排列：

| 选项 | 策略 | 说明 |
| --- | --- | --- |
| `--resource-id` | resourceId | 最稳，结构性标识 |
| `--description` | contentDescription | 次之 |
| `--match-text` | text | 最弱——写入后自身就变，不能用于重复定位 |

可选 `--package` 限定目标应用。

### 1.2 为何要单独一条通道，而不是放宽正则

**限制的根因不在正则，在 `adb shell input text` 本身**：它把空格编码成 `%s`，
整套转义只对 ASCII 可靠，非 ASCII 会被静默写错而不是报错。

因此直接放宽 `internal/adb/direct.go` 的 `directText` 正则，
只会把「明确拒绝」变成「静默输错」——后者在自动化里更难排查。

桥侧走无障碍 `ACTION_SET_TEXT`，参数是 `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`，
文本原样经 JSON 送到 App 侧写入节点，**全程不经 Shell 转义**，任意 Unicode 都安全。

### 1.3 协议层本来就允许中文

| 层 | 限制 |
| --- | --- |
| `protocol/schema/v1/action.schema.json` 的 `uiSetText` | 只有 `maxLength: 10000`，**无字符集限制** |
| `internal/adb/direct.go` | ASCII 正则 |
| `internal/mcpserver/schemas.go` | 同一套正则 |

所以这不是放宽协议，而是补上一条本就该有的通道。

### 1.4 直连侧错误信息改为指路

原文案只说「不支持这些字符」，把调用方堵在死路上。现在：

```
text contains characters that ADB input text cannot encode reliably;
direct ADB text accepts ASCII letters, numbers, spaces, and @._+-,:/.
For Chinese or any other non-ASCII text use the App Bridge instead:
aactl bridge set-text --device <serial> --text <value> --resource-id <id>
```

**正则保留不动**——它是对 `input text` 缺陷的诚实兜底。

### 1.5 实测结果

用项目自带的 `device-fixture`（`dev.aiauto.fixture:id/text_input`，有稳定 resource-id）：

| 步骤 | 结果 |
| --- | --- |
| 写入前 | `text=''` |
| `bridge set-text --text 中文输入测试` | `{"route":"nodeAction","matchedPath":[0,1],"matchScore":1.0}` |
| **回读层级** | **`text='中文输入测试'`** |
| 对照：同一文本走直连 | 被拒，错误信息指向桥 |

**判据取回读层级而非返回值**：无障碍 setText 返回成功不等于文本真落进去了。

### 1.6 途中的一个有用信号

中间一次尝试用无 resource-id 的输入框，得到的错误是
`SELECTOR_NOT_FOUND: Text input requires a unique semantic node`
——**不是字符错误**。这本身就证明中文已经通过整条链路到了 App 侧，
没有被任何字符检查拦截，问题纯粹在选择器。

## 2. 虚拟摄像头：桥 App 做不到，已实测确认

### 2.1 诉求

控制**其他 app** 扫到的码（不是自己 app 内解码），
因此需要向系统注册一个虚拟摄像头，让任意 app 打开相机时拿到我们的帧。

### 2.2 系统里确实有这套机制

`service list` 显示：

```
281  virtualdevice: [android.companion.virtual.IVirtualDeviceManager]
282  virtualdevice_native: [android.companion.virtualnative.IVirtualDeviceManagerNative]
```

Android 从 14 起有官方 Virtual Camera（`VirtualDeviceManager`）。

### 2.3 但两道权限门都对普通 app 关闭（实测）

| 权限 | 保护级别 | `pm grant` 结果 |
| --- | --- | --- |
| `CREATE_VIRTUAL_DEVICE` | `prot=internal\|role` | `SecurityException: Permission ... is managed by role` |
| `CAMERA_INJECT_EXTERNAL_CAMERA` | `prot=signature` | `SecurityException: ... not requested/not signature` |

- **`internal|role`** 意味着它只能通过角色授予，而对应角色是
  `COMPANION_DEVICE_APP_STREAMING`——面向配套设备投屏，需系统预置或特许，
  普通 app 无法申请。
- **`signature`** 意味着必须用平台签名密钥签名，即成为系统应用。

**`pm grant` 连 root 都绕不过**：这两个错误来自权限模型本身，不是运行时权限拒绝。

### 2.4 本机模拟器还有一层限制

`adb shell id` 报 `uid=2000(shell)`，`/system` 只读——
这台 AVD 当前会话未保持 root（前几轮验证证书时 `adb root` 可用，
但 `remount` 被拒：`Device must be bootloader unlocked`）。

即便取得 root，前述两道门是**框架层的权限模型**，与是否 root 无关。

### 2.5 业界的实际做法

| 方案 | 前提 |
| --- | --- |
| 官方 Virtual Camera | 系统签名或 COMPANION_DEVICE_APP_STREAMING 角色 |
| VCAM / android_virtual_cam / CamSwap | **root + Xposed（LSPosed）** |
| v4l2loopback 内核模块 | 改内核 |
| 改厂商 HAL | 改系统镜像 |

**没有一条是普通 app 能做的。** 全都要求系统级介入。

### 2.6 结论与可行替代

**在桥 App 里实现虚拟摄像头给其他 app 用：不可行。** 它需要的是系统应用身份，
而桥 App 是普通应用；这不是代码问题，是 Android 权限模型的设计。

对「模拟器待命 + 让其他 app 扫到指定码」这个诉求，剩下的可行方向：

| 方向 | 可行性 | 代价 |
| --- | --- | --- |
| **宿主侧虚拟摄像头 + `-camera-back webcam0`** | 未验证，理论可行 | 需装 OBS 虚拟摄像头之类宿主软件；与 emulator-first 的可复现基线冲突 |
| **自建系统镜像，预置虚拟相机 HAL** | 理论可行 | 需自行构建 AOSP 镜像，脱离官方 system-image，`profiles.json` 的校验和体系失效 |
| **模拟器带 root + Xposed** | 理论可行 | 需给 AVD 刷 Magisk + LSPosed；与「固定官方镜像 + 干净快照」冲突 |
| 每次重启换 `imagefile`/`videofile` | **已验证可行** | 单次冷启动约 50 秒 |

**我的判断**：前三条都要打破 `scripts/emulator/` 那套「固定官方镜像 + 校验和 +
从干净快照启动」的可复现基线，而那套基线是这个项目测试策略的地基。

若扫码场景不频繁，**每次重启换视频/图片**（50 秒）可能比引入宿主依赖或自建镜像更划算。
若确实频繁，宿主侧虚拟摄像头是代价最小的一条——但需要先接受「测试环境多一个外部依赖」。

**这是取舍判断，需要你拍板。三条路我都没有实现。**

## 3. 验证方式与范围

| 检查 | 结果 |
| --- | --- |
| `go build ./...` | 通过 |
| `go test ./internal/...` | 全部通过 |
| `gofmt -l internal cmd` | 无输出 |
| `node scripts/check-comment-coverage.mjs` | 通过，覆盖 492 个手写文件 |
| `node protocol/scripts/validate.mjs` | 通过，52 项 |
| 中文写入端到端 | 见 1.5，真实设备回读确认 |
| 直连拒绝并指路 | 见 1.5 |
| 虚拟摄像头权限门 | 见 2.3，两个权限的 `pm grant` 均被拒 |

**未覆盖**：
- 桥侧 setText 只验了中文，emoji 与其它非 BMP 字符未验；
- `--description` 与 `--match-text` 两种定位方式未单独实测（只验了 `--resource-id`）；
- MCP 侧未暴露 setText——当前只有 CLI。若 AI 需要输中文，还要补一个 MCP 工具。

## 4. 待确认

1. **MCP 侧要不要也暴露 setText。** 现在 AI 走 `android_action_execute` 的
   `ui.setText` 仍受 ASCII 正则限制。补一个走桥的 MCP 工具就能解，但会引入
   「同一语义两个工具」的问题，需要想清楚命名与选择时机。
2. **扫码方向如何取舍。** 见 2.6 的四条路。
3. **`--match-text` 是否该保留。** 它写入后自身就变，只能用一次，
   容易让调用方写出不可重复执行的脚本。

## 5. 复现方式

```powershell
# 预置并配对（详见 provision-bridge.mjs）
node scripts/emulator/provision-bridge.mjs provision `
  --device emulator-5554 --target-packages dev.aiauto.fixture,dev.aiauto.android

# 写中文
.\bin\aactl.exe bridge set-text --device emulator-5554 `
  --text 中文输入测试 --resource-id dev.aiauto.fixture:id/text_input

# 对照：直连被拒
.\bin\aactl.exe action text --device emulator-5554 --text 中文输入测试

# 虚拟摄像头权限门
adb -s emulator-5554 shell pm grant dev.aiauto.android android.permission.CREATE_VIRTUAL_DEVICE
```

探针脚本在 `E:\tmp\`（非仓库产物）。
