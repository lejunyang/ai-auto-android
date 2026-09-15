# 观察能力扩展：三侧对齐、录屏、扫码与证书拦截

本文记录为模拟器 UI 自动化新增的观察能力、三侧对齐结果，以及扫码与证书拦截两项的
实测结论。前置事实见 `emulator-ui-automation.md`，本文不重复其内容。

本文只陈述已验证事实与明确缺口，不代替 change spec，也不预先勾选任何验收结论。

**实测环境**：Windows + `aactl`（由 `cmd/aactl` 现场构建），AVD `osdk-test-35`
（`sdk_gphone64_x86_64`，Android 15 / API 35，x86_64），emulator 与 adb 均来自
`E:\osdk-data\data\installs`。桥 APK `dev.aiauto.android` 0.1.0 已装在该 AVD 上。

以下标注「实测」的条目均在该环境执行过；标注「未验证」的只做了代码或文档查证。

## 1. 本轮新增的能力

### 1.1 能力清单与三侧对齐

| 能力 | CLI | MCP | App Bridge |
| --- | --- | --- | --- |
| 前台应用与 Activity | `observe foreground` | `android_observe` kind=`foreground` | `ui.foreground` |
| 已安装应用清单 | `observe apps` | `android_observe` kind=`apps` | 不提供（见 1.4） |
| 屏幕录制 | `observe recording` | `android_screen_record` | 不提供（见 1.4） |

三侧共用 `internal/service` 的同一套校验与语义：CLI 与 MCP 都经由
`service.Automation` 接口，新增的 `Foreground`、`Apps`、`Record` 三个方法写在该接口上，
因此任何一侧遗漏都会导致编译失败，而不是运行时才发现能力不齐。

### 1.2 实现分层

```
CLI  observe foreground/apps/recording ─┐
                                        ├─→ service.Automation ─→ adb.Client（纯 adb）
MCP  android_observe / screen_record  ──┘        │
                                                 └─→ 三项均不需要设备端安装任何组件
App Bridge  ui.foreground ───────────────────→ AccessibilityRuntime（需桥 + 无障碍）
```

新增文件：

- `internal/adb/observe_extra.go`：`Foreground`、`InstalledApps`、`RecordScreen`
- `internal/adb/observe_extra_test.go`：7 个单测，覆盖真实输出格式与非法参数
- `internal/service/observe_extra.go`：三项能力的服务层校验与路由

### 1.3 三处刻意的设计取舍

**录屏用固定时长，不做「开始/停止」两段式。** 设备端 `screenrecord` 是阻塞式的，
「提前停止」需要通过 `adb shell` 发 SIGINT 或 `pkill -INT`，这在不同 adb 版本上行为
不一致。改用 `--time-limit` 让它自行结束，结果可复现；需要不定长录制时由上层分段调用。

**MCP 侧不返回视频字节，只回摘要。** 单次录制实测 17KB–24KB（4–5 秒静态画面），
但长录制或动态画面可达数十 MB，会超出多数 MCP 客户端的消息上限。视频本体由 CLI
的 `--output` 写文件获取。

**`Foreground` 优先取 Activity 字段，仅在全缺时退回焦点窗口，并如实标注 `source`。**
焦点窗口可能是对话框、输入法或系统弹窗，与「当前应用」不等价。用它代表当前应用会在
弹窗出现时给出误导性包名，而那恰是自动化最需要准确判断的时刻。因此返回值带
`source` 字段（`resumedActivity` / `currentFocus` / 桥侧的 `activeWindow`），
让调用方能区分可信度。

### 1.4 桥侧只加 `ui.foreground`，不加另两项

应用清单与录屏都是纯 adb 能力，桥侧提供它们没有增量价值，还会扩大 App 的权限面
（列应用需要 `QUERY_ALL_PACKAGES`，录屏需要 MediaProjection 并弹系统确认）。

`ui.foreground` 则相反：它走无障碍活动窗口，比 dumpsys 文本解析更直接，且在桥已连接
时不必再起一次 adb 进程。实现上复用 `ui.snapshot` 的同一条通道与同一份目标包白名单，
但只取根节点的 `packageName` 与 `className` 后丢弃其余内容——不把子节点文本带出，
那些可能含密码或验证码。

协议侧同步更新了 `protocol/schema/v1/bridge-request.schema.json`（新增
`uiForeground` 定义与 method 枚举项），`node protocol/scripts/validate.mjs` 通过：
14 schemas、13 valid fixtures、35 invalid fixtures、4 compatibility checks。

### 1.5 实测结果

**CLI 侧**（`emulator-5554`）：

| 命令 | 结果 |
| --- | --- |
| `observe foreground` | `com.android.camera2` / `com.android.camera.CameraActivity` / `resumedActivity` |
| `observe apps` | count=1，`dev.aiauto.android` |
| `observe apps --with-details=true` | 追加 `versionName=0.1.0`、`versionCode=1` |
| `observe apps --include-system=true` | count=242 |
| `observe recording --seconds 5` | 23891 字节，`ftyp` 有效，sha256 与产物一致 |
| `observe recording --seconds 999` | 被拒：`seconds must be between 1 and 180.` |
| `observe apps --with-details=maybe` | 被拒：`with-details must be true or false.` |

**MCP 侧**：工具数 8，`android_observe` 的 kind 枚举为
`screenshot / hierarchy / semantic / recordings / foreground / apps`。
`foreground` 与 `apps` 的返回值与 CLI 侧逐字段一致；`android_screen_record` 返回
17717 字节的摘要；`seconds: 999` 在 schema 层即被拒。

**Android 侧**：`:app:compileDebugKotlin` 与 `:app:testDebugUnitTest` 均 BUILD SUCCESSFUL。

### 1.6 途中修正的两处

**`parseNamedOptions` 不接受无值选项。** 我最初把布尔开关设计成
`--with-details` 即为真，实测报 `Option --with-details requires a value.`——
既有解析器对所有选项一律要求非空值。没有改动那条约定（它同时约束所有其他命令），
改为必须写 `--with-details=true`，并在注释里写明这个原因。

**`TestMCPServeReservesCLIStdoutForProtocolTransport` 断言写死 7 个工具。**
改为与 `mcpserver.ToolNames` 逐项比对：写死数量在每次新增工具时都会失败，却无法区分
「多暴露了不该暴露的」与「正常新增」，按名单比对能区分这两种情况。

## 2. 扫码：几何已解决，换码仍需重启

### 2.1 imagefile 的真实几何行为

`emulator -camera-back imagefile:<path>` 对图片是**填满预览并裁剪**，不是缩放留边。
竖屏相机预览下，它取原图的一块区域放大填满，可见区偏向原图左侧。

三轮实测归纳（截图均在 `E:\tmp\qr\`）：

| 喂图 | 预览结果 | 能否解码 |
| --- | --- | --- |
| 640×480 横图，居中大码 | 只见左侧，右半丢失 | 否 |
| 720×1280 竖图，居中 640px 码 | 码被放得极大，仅存一角 | 否 |
| 720×960（3:4），居中 300px 码 | 码偏右且右缘被裁 | 否 |
| **720×960，200px 码，左上角 (201,380)** | **完整可见** | **是** |

最后一组解出 `AACTL-POS-L2-002`，与喂入内容一致。**结论：码要小、要偏左上。**

这个几何规律是在 API 35 / x86_64 / 竖屏相机预览下测得的，
分辨率或方向改变后是否仍成立**未验证**。

### 2.2 三条运行时换码的路都不通

| 方案 | 结果 |
| --- | --- |
| 覆盖 imagefile 指向的文件 | **不生效**。等待 3/5/8/12 秒后仍读到旧码，说明启动时已读入内存 |
| `virtualscene-image wall\|table <path>` | 命令返回 OK，但**画面不变**。virtualscene 是 3D 房间，镜头正对电视（贴默认棋盘格），wall 与 table 都不在视野内 |
| emulator console | 命令表里**没有** camera 相关命令，只有 `virtualscene-image` |

`emulator -help-camera-back` 的全部取值：`emulated`、`webcam<N>`、`environment`、
`imagefile:`、`videofile:`、`image360:`、`none`。另有
`-virtualscene-poster <name>=<filename>`。

**所以换一张码目前必须重启 AVD**，本机冷启动实测 45–50 秒。

### 2.3 尚未验证的两条候选

- **`videofile:`**：把多个二维码做成一段视频，靠播放推进换码。这样一次启动能覆盖多个码，
  但无法随机访问，且视频循环时序与测试步骤如何对齐未知。
- **`webcam0`**：本机 `-webcam-list` 报告有 `HD Webcam`（`vid_04f2&pid_b71a`，NV12）。
  若用虚拟摄像头软件在宿主侧喂帧，理论上可运行时换码，但需要额外的宿主组件，
  且与 emulator-first 的可复现要求相冲突。

两条都**未验证**。若扫码场景确实需要一次会话多次换码，应先验 `videofile:`——
它不引入宿主外部依赖。

## 3. 证书拦截：链路成立，可逆且不丢原证书

### 3.1 API 35 的两处硬约束

| 约束 | 实测结果 |
| --- | --- |
| `adb remount` | **被拒**：`Device must be bootloader unlocked` |
| `/system/etc/security/cacerts` 写入 | **被拒**：`Read-only file system` |

但 `adb root` 可用（`uid=0(root)`），且 **API 34+ 起系统 CA 实际由
`/apex/com.android.conscrypt/cacerts` 提供**——该目录存在，实测 145 个证书。

### 3.2 tmpfs 覆盖可行，且完整可逆

实测链路（`E:\tmp\mitm\probe_cacerts.py`）：

```
原始状态                     145 个证书
mount -t tmpfs tmpfs <dir>   → 0 个（原目录被遮住）
umount <dir>                 → 145 个（完整恢复）

完整流程：
1. 先 cp <dir>/* 到 /data/local/tmp/cacerts-backup   → 备份 145 个
2. mount -t tmpfs tmpfs <dir>
3. cp 备份/* 回 <dir> + chmod 644 + chown root:root  → 145 个
```

**顺序是关键**：必须在挂 tmpfs *之前*把原证书复制出来，挂载后原目录已被遮住，
再复制只会得到空目录。

**注入自己的 CA 只需再往该目录写一个 `<subject_hash>.0` 文件。** 这一步
**未验证**，因为需要 openssl 生成自签证书并计算 hash。

### 3.3 与既有安全约束的冲突需要先解决

`docs/alternatives.md:208` 已有一条约束：

> Agent 动作面不得直接暴露安装、卸载、授权、擦除、证书、VPN 或系统更新 API。

因此证书注入**不能**做成 CLI 或 MCP 的一个动作。可行的位置是
`scripts/emulator/` 的预置阶段——与 `AGENTS.md:58` 允许的「disposable emulator 上
自动预置」同一性质，且不进入 AI 可调用的动作面。

**这是设计决策，尚未实现，也未向你确认。**

### 3.4 读请求与改请求的差别

你提到读请求用于 debug、改请求用于测试。这两件事的代价不同：

- **读**：装 CA + 配代理即可，上述链路够用。
- **改**：需要一个能改写响应的代理（mitmproxy 之类），并且**该代理的规则本身需要
  版本管理**——否则测试结果不可复现。这部分完全未调研。

模拟器侧配代理的通道未实测，候选有 `emulator -http-proxy`、
`settings put global http_proxy`、以及 AVD 设置里的手动代理。**三者都未验证。**

## 4. 待补与待确认

### 4.1 已知缺口（未实现）

| 项 | 说明 |
| --- | --- |
| `ui.assert` / `ui.wait` 暴露到 MCP | 协议层已定义，MCP 未暴露。自愈回路缺这两项判据执行 |
| `ui.longClick` / `ui.scroll` 暴露到 MCP | 同上，协议层已有 |
| 中文文本输入 | 限制在 `internal/mcpserver/schemas.go` 与 `internal/adb/direct.go`，协议层无此限制 |
| 自动装桥与开无障碍 | 写 secure settings 实测可行，但「写入后服务是否真被绑定」未验证 |
| 证书注入的 CA 生成与代理配置 | 见 3.2、3.4 |
| 扫码运行时换码 | 见 2.3，`videofile:` 与 `webcam0` 均未验证 |

### 4.2 待确认的设计决策

1. **录屏是否需要「提前停止」。** 当前是固定时长。若 UI 自动化需要「出错时才保留录像」，
   固定时长会浪费大量存储；但两段式的可靠停止在本机未找到跨版本一致的做法。
2. **录屏产物的保留策略。** `AGENTS.md` 要求失败产物「限额且脱敏」。录屏体积远大于截图，
   需定上限与清理规则。脱敏尤其棘手——录像会连续捕获密码输入过程。
3. **证书注入放在哪一层。** 见 3.3，倾向 `scripts/emulator/` 预置阶段。
4. **`--with-details` 的代价。** 每个包一次 `dumpsys`，242 个系统包时会很慢。
   当前默认关闭，是否需要并发查询或只查指定包未定。
5. **扫码若必须重启换码，是否接受。** 每个用例一次 45–50 秒冷启动。

### 4.3 既有失败，与本轮无关

`internal/adb` 的 `TestWatchDevicesReportsInitialAndChangedMDNSEvents` 失败
（`ADB calls = 10, watch must remain bounded`）。已用 `git stash` 移除本轮新增文件后
复现同一失败，确认是既有问题，**未处理**。

## 5. 验证方式与范围

| 检查 | 结果 |
| --- | --- |
| `go build ./...` | 通过 |
| `go test ./internal/...` | 除 4.3 那条既有失败外全部通过 |
| 新增单测 | 7 个全部通过（前台解析、路径含等号、非法参数、MP4 校验） |
| `gofmt -l internal cmd` | 无输出 |
| `go vet ./...` | 通过 |
| `node scripts/check-comment-coverage.mjs` | 通过，覆盖 484 个手写文件 |
| `node protocol/scripts/validate.mjs` | 通过，52 项 |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL |
| CLI 端到端 | 见 1.5，真实设备 |
| MCP 端到端 | 见 1.5，真实设备，含 schema 层拒绝 |

**未验证的部分**：桥侧 `ui.foreground` 只做了编译与单测，**没有在真机/模拟器上通过
真实桥连接调用过**——需要先在 App 内开启桥并完成一次性码配对。这是本轮最大的验证缺口。

复现命令见各节；探针脚本在 `E:\tmp\qr\` 与 `E:\tmp\mitm\`，非仓库产物。

读 UI 层级或写探针时注意：**PowerShell 内联 `python -c` 会吞掉引号**，
需要引号的一律写成 `.py` 文件。本轮因此失败两次。
