# displayd 设备端契约（协议 v2）

`displayd` 是以 shell uid 经 `app_process` 运行的独立辅助进程。APK 只作
`CLASSPATH`，不安装；无 Activity、权限声明或运行时第三方依赖。仅管理自己创建的
虚拟屏，没有主屏操作、任意命令、调用方指定标志或反射入口。

## 构建、启动与退出

Windows PowerShell，在仓库根目录沿用 Makefile 的工具链入口：

```powershell
osdk exec -t java -t android-platforms -t android-build-tools -t android-platform-tools -- ./android/gradlew.bat -p android --no-daemon :displayd:assembleDebug
adb -s emulator-5554 push android/displayd/build/outputs/apk/debug/displayd-debug.apk /data/local/tmp/aactl-displayd.apk
adb -s emulator-5554 shell 'CLASSPATH=/data/local/tmp/aactl-displayd.apk nohup app_process / --nice-name=aactl-displayd dev.aiauto.displayd.Main </dev/null >/data/local/tmp/aactl-displayd.log 2>&1 &'
adb -s emulator-5554 forward tcp:0 localabstract:aactl-displayd-v1
```

固定套接字名为 `aactl-displayd-v1`。`LocalServerSocket(String)` 仅监听
`localabstract`；不监听 TCP。电脑端 TCP 仅是调用方主动建立的 adb forward。
每个连接的第一步是 `getPeerCredentials()`，只接受 uid `0` 与 `2000`，其他对端
直接断开，不解析请求。进程自身也仅允许 shell/root 启动；默认无参数。
摄像头模式仅接受操作员固定入口的 `--virtual-camera-association ID USER_ID`，不开放其它配置。

`nohup`、重定向标准输入输出和进程入口的 `setsid()` 使辅助进程脱离启动它的
`adb shell`；后续连接共享同一组虚拟屏。同名进程重复启动会绑定失败，不接管旧进程。
`shutdown` 先释放全部虚拟屏与图像资源，响应后关闭套接字并退出；正常退出与
`SIGTERM` 也通过退出钩子清理。`SIGKILL` 无法执行 Java 钩子，由系统在 binder owner
死亡后回收虚拟屏。关闭虚拟屏固定销毁其上内容，不把任务搬回主屏。

停止后调用方应检查进程和显示屏已消失，移除实际分配的端口并清理推送文件：

```powershell
adb -s emulator-5554 shell pidof aactl-displayd
adb -s emulator-5554 forward --remove tcp:PORT
adb -s emulator-5554 shell rm -f /data/local/tmp/aactl-displayd.apk /data/local/tmp/aactl-displayd.log
```

启动命令通过 `--nice-name=aactl-displayd` 固定进程名，不能依赖 APK 包名检查进程。
只清理本次产生的文件与转发。Git Bash 下设备路径命令前需
`export MSYS_NO_PATHCONV=1`。

## 编码与边界

每个连接处理 **一个** 请求，发送一次响应后关闭；调用方每次重连。UTF-8 JSON
对象以 LF (`0x0a`) 结束，接受 CRLF。请求为扁平对象，仅允许字符串和非负十进制
整数；整数范围 `0..2147483647`，不接受小数、指数、前导零、重复键、嵌套值或未知字段。
`id` 与 `method` 必填，字段必须与下表一致；仅创建的 `focus` 可省略，默认 `isolated`。
JSON 字符串接受标准转义。

成功：`{"id":1,"ok":true,"result":{...}}\n`。
失败：`{"id":1,"ok":false,"error":{"code":"DISPLAY_NOT_FOUND","message":"..."}}\n`。
完整请求解析前发生的错误（含模式/参数校验错误）`id` 为 `null`。客户端以 `code`
作稳定判定，`message` 仅供诊断。未授权 uid、连接预算不足、超时/断连时可以没有响应。

| 预算 | v2 上限（与 v1 相同） |
| --- | --- |
| 请求行、响应头 | 各 16384 字节，不含 LF（CR 计入预算） |
| PNG 二进制体 | 16777216 字节，编码写入前即检查预算 |
| 单屏宽、高 | 各 128..2048 像素（含边界） |
| dpi | 72..640（含边界） |
| 同时拥有的虚拟屏 | 2 块 |
| 并发连接 | 4 个，无排队；超限直接关闭 |
| 连接时间 | 读取空闲超时 5 秒、从接入开始总期限 10 秒 |
| 帧缓存 | 每屏仅保留最新 Image，ImageReader 最多 3 张缓冲 |

### 方法

以下请求都必须再带 `id`（示例取 `1`），除 `focus` 外字段没有默认值。

| 方法及请求字段 | `result` |
| --- | --- |
| `hello` | `protocolVersion:2`、`apiLevel:int`、`supported:bool`、`capabilities`、`limits` |
| `display.create`, `width:int`, `height:int`, `dpi:int`, 可选 `focus:"isolated"|"steal"` | `displayId:int`, `width:int`, `height:int`, `dpi:int`, `focus:string`, `focusIsolated:bool` |
| `display.list` | `displays:[{displayId,width,height,dpi,focus,focusIsolated}]` |
| `display.close`, `displayId:int` | `displayId:int`, `closed:true` |
| `display.capture`, `displayId:int` | `displayId:int`, `width:int`, `height:int`, `format:"png"`, `bytes:int`，随后是恰好 `bytes` 字节 PNG |
| `status` | `credentialLocked:bool`、`displays:[{displayId,width,height,dpi,focus,focusIsolated,state,displayGroupId,groupAwake}]`、`keepAwakeIntervalMs:2000`、`lastActivityUptimeMs:int`、`keepAwakeError:string|null` |
| `shutdown` | `stopped:true` |

示例请求：

```json
{"id":1,"method":"hello"}
{"id":2,"method":"display.create","width":720,"height":1280,"dpi":240,"focus":"isolated"}
{"id":3,"method":"display.list"}
{"id":4,"method":"display.capture","displayId":2}
{"id":5,"method":"status"}
{"id":6,"method":"display.close","displayId":2}
{"id":7,"method":"shutdown"}
```

截图成功时先读取到 LF 的 JSON 头，再按 `result.bytes` 精确读二进制体，**没有**
base64，也没有二进制体后的换行。失败头不带二进制体，取帧不会隐式等待新帧。
调用方应先检查协议版本与上限再分配读取缓冲，不根据 EOF 判断帧长度。

`hello.capabilities` 包含 `virtualDisplay`（API 33+）、`binaryPng:true`、
`credentialLockGuard:true`、`groupAwakeQuery`（API 34+）、`focusIsolation`（API 34+）；`limits` 包含
`maxMessageBytes`、`maxFrameBytes`、`maxDisplays`、`minDimension`、`maxDimension`、
`minDpi`、`maxDpi`，值与预算表一致。API <33 可调用 `hello` 并得到
`supported:false`；`display.create` 返回 `UNSUPPORTED_API`。

摄像头扩展保留协议 v2。`capabilities.virtualCamera` 表示本次启动是否显式启用入口，
不表示 GPU 或系统服务已经通过实际出流验收；`virtualCameraUser` 返回启动用户用于精确停止清理。
旧helper缺少能力位时，新客户端给VIRTUAL_CAMERA_NOT_ENABLED，不发送未知摄像头字段。

- `display.create` 可选 `camera:"back"`；普通请求字段和 DisplayManager 路径保持。
- `camera.setImage` 请求必须有 `id,method,displayId,path`，path仅接受
  `/data/local/tmp/aactl-camera-UUID.img` 的随机v4 UUID模式，响应 `displayId,imageSet:true`。
- 摄像头屏的create/list/status对象增加 `camera:"back"`、`displayFlags` 和
  `cameraInfo:{cameraId,virtualDeviceId,streams:[{streamId,width,height,frames,error}]}`；
  普通屏省略这些字段。摄像头屏的focusIsolated根据实际Display标志计算，不按API直接推断。

摄像头status增加可选`removeMode`，来自同一DisplayInfo的只读字段，1表示系统报告
REMOVE_MODE_DESTROY_CONTENT，null/缺省表示未验证。不得用请求中含销毁位代替实际读数。
请求采用DisplayManager命名空间（DESTROY_CONTENT_ON_REMOVAL=0x100、OWN_DISPLAY_GROUP=0x800）；
结果采用Display命名空间（OWN_DISPLAY_GROUP=0x100、OWN_FOCUS=0x800、禁止抢顶层焦点=0x1000）。
旧实现误删请求销毁位已修复；CameraPolicy纯函数保留销毁位并排除SECURE。
真机保留/去掉请求OWN_DISPLAY_GROUP均能创建且removeMode=1，因此产品保留该位，与普通屏一致。
保留方案实际flags24456/20360、组1；去掉的临时实验24200/20104、组2/3；全部groupAwake=true。
不再宣称摄像头屏必由VirtualDevice分配显示组，完整对照与验收边界见虚拟摄像头文档。

主机stop独立尝试普通文件、相机关联和图片清理；部分失败通过错误details.cleanup分类公开，
不因伴生服务失败而跳过普通文件。`/data/local/tmp/dalvik-cache[/arm64]`是系统uiautomator
包装脚本可创建的共享缓存，本轮启动前已存在且各阶段stat未变，不归helper删除；验收脚本
记录其基线与最终状态，交负责人决定，不能把它漏写为“所有临时目录均已删除”。

图片预算8MiB、解码宽高各4096，PNG/JPEG；读取后立即删除，失败也清理。三档YUV/30fps、
位图EGL渲染、单流约29.4fps上限、流关闭停绘；不使用Paint/字体、不修改任何应用权限。
完整策略及分层验收见[虚拟摄像头](../../docs/virtual-camera.md)。

`state` 是 Android `Display.getState()` 的整数常量（常见 OFF=1、ON=2、DOZE=3）。
API 34+ 的 `groupAwake` 来自 `IPowerManager.isDisplayInteractive(displayId)`，查询的是
该屏所在电源组；API 33 缺少此按屏查询，返回 `null`，不拿主屏或全局状态代替。
`lastActivityUptimeMs` 是最近一次成功上报活动的设备 uptime，尚未上报为 0。
保活调用错误在 `keepAwakeError` 中报告；锁定是正常跳过，不代表保活错误。

### 错误码

| code | 原因与调用方处理 |
| --- | --- |
| `INVALID_REQUEST` | JSON、字段集合、字段类型或整数字面量非法 |
| `UNKNOWN_METHOD` | 方法不属于固定集合 |
| `INVALID_ARGUMENT` | 宽高/dpi 超范围，或 focus 不属于 isolated/steal |
| `MESSAGE_TOO_LARGE` | 请求行或响应头超预算；拒绝后关闭连接，不继续读超长输入 |
| `FRAME_TOO_LARGE` | PNG 编码超预算；不发送任何帧体 |
| `DISPLAY_LIMIT` | 已拥有 2 块屏，先关闭再创建 |
| `UNSUPPORTED_API` | API <33，不能创建虚拟屏 |
| `DISPLAY_NOT_FOUND` | 不归本进程所有（包括主屏 0）或已被系统移除，绝不回退主屏 |
| `FRAME_NOT_READY` | 尚未收到图像，调用方重新观察后可另发请求 |
| `DEVICE_LOCKED` | 当前前台用户被凭据锁定，拒绝帧，不尝试唤醒/解锁 |
| `DISPLAY_ASLEEP` | 显示组明确未唤醒或显示状态非 ON，拒绝帧；关闭并重建虚拟屏（其上应用被销毁） |
| `LOCK_STATE_UNAVAILABLE` | 无法可靠读取当前用户锁定状态，拒绝帧并跳过保活 |
| `CAPTURE_FAILED` | PNG 编码器返回失败 |
| `FRAMEWORK_ERROR` | 固定框架接口或隐藏 API 调用失败，详情仅在设备日志 |
| `SHUTTING_DOWN` | 并发请求遇到已停止的后端 |
| `VIRTUAL_CAMERA_NOT_ENABLED` | 未以摄像头模式启动，或目标普通屏没有摄像头；提示操作员重新启动/创建 |
| `VIRTUAL_CAMERA_UNAVAILABLE` | API<35、系统服务/特性缺失或创建失败；message保留API、异常类型和定位原因 |
| `CAMERA_IMAGE_INVALID` | 图片不是PNG/JPEG、超过8MiB/4096、路径不是固定UUID模式或读取失败 |

策略由代码映射为固定标志，仍拒绝原始 `flags`：

以下标志和IME/息屏行为仅适用于普通 DisplayManager 屏；VirtualDevice 屏见
[摄像头扩展](../../docs/virtual-camera.md)，按实际标志及设备验收报告结果。

| focus | API 33 | API 34+ | 代价 |
| --- | --- | --- | --- |
| `isolated`（默认） | `15819`，`focusIsolated:false` | `130507`，`focusIsolated:true` | 不打断主屏输入；主屏息屏时窗口层级不可用 |
| `steal` | `15819`，`focusIsolated:false` | `64971`，`focusIsolated:false` | 可息屏读层级，但会抢占顶层焦点、打断主屏输入法 |

API 34+ 的 `isolated` 追加 `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED`（`1 << 16`）。
`focus` 是请求策略，`focusIsolated` 表示当前 API 下是否启用了隔离标志，不是OEM行为保证；
API33不支持该隔离能力，不能依赖 `isolated` 保护主屏输入。
输入法不服务 `isolated` 虚拟屏，文本只能使用按键注入支持的受限 ASCII；BACK直接交给
应用，根页面会退出，不先收键盘。`steal` 下输入法可服务虚拟屏，BACK先收键盘的行为不同。
两种策略都不承诺中文/IME文本注入。

协议提升为 v2，因为新客户端必须获得策略和实际隔离字段，不能把 v1 的缺失字段当作
已隔离。行/帧编码及预算不变，固定 socket 名 `aactl-displayd-v1` 保留为传输端点；
版本通过 `hello.protocolVersion` 协商，版本不匹配应重建并重新启动 helper。
升级前先用旧版 CLI 停止 v1 helper，再更新 CLI/helper 并启动；新版客户端按 v2 握手，
不会向版本不匹配的进程发送包括 shutdown 在内的管理命令。
不含 `SECURE`，安全窗口
仍由系统遮黑。每 2 秒在虚拟屏 displayId 所属组上报 `userActivity`，不调用 `wakeUp`。
每次上报前检查当前前台用户的 `isDeviceSecure && isDeviceLocked`，凭据锁定时不调用
电源接口。取帧在编码前后各检查一次凭据锁、显示有效性、显示组唤醒状态与显示状态；
锁定或组查询失败也拒绝，不发送帧体。拒绝连接时的关闭异常仅影响当前连接，不终止进程。

### 显示组休眠后的恢复

API 35 模拟器经历「设 PIN → 息屏 → 清除 PIN 并解锁」后，虚拟屏可能继续 Dozing，
即使 `state=2`、保活时间戳递增也不能证明该组已唤醒。此时返回 `DISPLAY_ASLEEP`。
调用方须 `display.close` 后 `display.create`，在新的 displayId 上重新启动应用；
**关闭会销毁该屏上的应用内容**，不会搬回主屏，也不承诺保留应用状态。

本实现不自动唤醒已休眠的显示组。API 35 的固定 `DEVICE_POWER` binder `wakeUp`
只操作默认显示组；绑定显示的 WakeLock 唤醒路径另依赖 `TURN_SCREEN_ON` 权限/兼容
策略（本次 shell targetSdk=35，未授予该权限），没有可靠恢复证据，不引入该路径。
依据：[AOSP PowerManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/power/PowerManagerService.java)
中的 `wakeUp` 与 `applyWakeLockFlagsOnAcquireLocked`。

API 33 **无法按屏查询电源组**：`groupAwake=null`、`groupAwakeQuery=false`。取帧只
能检查凭据锁与 `Display.getState()` 是否 ON；若系统在组休眠时仍报告 ON，无法可靠
识别该情况，不能承诺拒绝所有休眠黑帧。没有使用主屏或全局状态代替；API 33 仍待设备验证。

## 验证边界

JVM 测试独立覆盖协议解析、预算、uid 白名单、版本标志与凭据锁定决策。API 35
模拟器冒烟证据见 `.trae/specs/virtual-display-screen-off/progress.md` Round 1。
T1b 的 API 35 PIN 往返验证了 DEVICE_LOCKED → DISPLAY_ASLEEP → 重建后正常帧。
焦点策略 v2 的 `steal` CLI 流程在 API35 连续三个冷启动通过：息屏层级/输入/取帧、
65秒保活和上述PIN往返均保留，见[焦点规格进度](../../.trae/specs/vdisplay-mcp-and-focus/progress.md) Round13。
此前息屏无窗口输出被CLI误报ACTION_FAILED，原因是exec-out把cat诊断合入stdout且退出0。
主机端现只精确识别本次随机路径的完整ENOENT诊断为WINDOWS_UNAVAILABLE，同时保留退出1、
分离stderr形态；截断、超限、其它诊断和畸形XML继续拒绝。不带显示编号的旧行为本次不改。
Round20最小复现及Round24三个完整isolated冷启动均得到真实WINDOWS_UNAVAILABLE；
主屏输入采用Settings主进程的添加网络对话框SSID，全部焦点/IME/文本判据保持，
BACK/空屏/重启、息屏取帧/输入及唤醒后层级恢复均通过。对话框只取消不保存，网络列表不变。
旧搜索基线的失败仍保留在历史记录，未计为成功。MCP stdio在Round26三个冷启动完整通过，两策略均由MCP创建并完成列表/状态/观察/六类动作/关闭；
helper启停仍只有CLI，20个MCP工具与三道既有风险门边界保持。
API33/34、真机/OEM仍留待后续任务，不把标志能力字段当成这些平台的实测结论。
