# 宿主虚拟摄像头：DirectShow 类不成立

> **本文结论已收窄，请先读 `camera-virtual-stacks.md`。**
> 本文最初的标题与结论是「整条路线不成立」，那个推广过宽：
> 实测覆盖的只是 **DirectShow 类**虚拟摄像头（OBS、Unity Capture），
> 而 Windows 11 还有一套 **Media Foundation Frame Server** 原生虚拟相机
> （`MFCreateVirtualCamera`），它会登记 PnP 实例，尚未验证。
> 下文的实测数据全部有效，仅「适用范围」被收窄。

**已证实的结论：Android emulator 看不到 DirectShow 类虚拟摄像头**，
因为它的枚举机制只接受**有 PnP 硬件实例的设备**，而 DirectShow 滤镜没有 PnP 实例。

这不是 OBS 或 Unity Capture 的缺陷，也不是像素格式问题（见 §2）。

## 1. 决定性证据

同一时刻、生产者正在推帧，两个程序枚举同一台机器：

| 枚举者 | 结果 |
| --- | --- |
| **ffmpeg**（DirectShow） | `HD Webcam`、`Unity Video Capture`、**`OBS Virtual Camera`** |
| **emulator** `-webcam-list` | 只有 `HD Webcam` |

Windows PnP 设备表（`Get-PnpDevice -Class Camera`）：

| 设备 | Class | InstanceId |
| --- | --- | --- |
| HD Webcam | Camera | `USB\VID_04F2&PID_B71A&MI_00\...` |
| IR Camera | Camera | `USB\VID_04F2&PID_B71A&MI_02\...` |
| OBS Virtual Camera | **无 PnP 实例** | — |
| Unity Video Capture | **无 PnP 实例** | — |

两个虚拟摄像头都只是 DirectShow 滤镜，**没有 PnP 硬件实例**。
emulator 走的是 Media Foundation / PnP 路径，因此看不到它们。

## 2. 格式假说已被证伪

上一轮我判断 Unity Capture 失败的原因是像素格式（只出 bgr24，而 emulator 要 NV12），
并据此推断 OBS 会成功。**格式部分是对的，结论是错的**：

| 设备 | 像素格式 | emulator 能否枚举 | `webcam1` 下相机数 |
| --- | --- | --- | --- |
| HD Webcam | NV12 / mjpeg | 是 | 1 |
| Unity Video Capture | 仅 bgr24 | 否 | 0 |
| **OBS Virtual Camera** | **nv12 / yuv420p / yuyv422** | **否** | **0** |

OBS 提供的格式正是 emulator 要的，**依然枚举不到、依然接不上**。
所以格式不是决定因素，PnP 实例才是。

这纠正了我上一轮的判断：我当时把 Unity Capture 的失败归因于格式，
是因为格式差异确实存在且显眼，但没有验证「格式修好后是否就能用」。
OBS 恰好提供了这个对照——它把格式变量消掉了，结果不变。

## 3. `-camera-back webcam1` 的行为

三组对照（`dumpsys media.camera`）：

| 参数 | `fake_camera` | 相机数量 | 后果 |
| --- | --- | --- | --- |
| `emulated` | `back` | 1 | 内置假相机 |
| `webcam0` | `none` | 1 | 真实 USB 摄像头 |
| `webcam1`（OBS 在推帧） | `none` | **0** | 相机应用崩溃 |

`webcam1` 会让 emulator **关掉内置假相机却接不上任何设备**，
Android 侧相机列表为空，相机应用崩在
`ArrayIndexOutOfBoundsException: length=0`。

**这是个陷阱**：参数不报错、`fake_camera` 也变成了 `none`，看起来像成功了。
必须查相机数量才能发现问题。

## 4. 修正后的方案排序

| 方案 | 状态 | 代价 |
| --- | --- | --- |
| **重启换 `imagefile` / `videofile`** | **已验证可用，现为首选** | 约 90 秒/次 |
| **Linux + v4l2loopback** | **未验，优先级最高** | 它创建真实 `/dev/videoN` 设备节点，不是纯软件滤镜 |
| MF Frame Server 虚拟相机（Win11） | 未验 | 需 MSVC 自行编译 VCamSample 一类项目 |
| 自建系统镜像预置虚拟相机 HAL | 理论可行，未验 | 脱离官方 system-image，校验和体系失效 |
| 模拟器刷 Magisk + LSPosed | 理论可行，未验 | 与「固定官方镜像 + 干净快照」冲突 |
| ~~DirectShow 类虚拟摄像头（OBS / Unity Capture）~~ | **已证伪** | — |
| ~~桥 App 内实现虚拟摄像头~~ | 已证伪（权限模型） | — |

DirectShow 这条路的吸引力在于「不碰 AVD、不破坏可复现基线」，
现在它不成立；但**同样不碰 AVD 的另外两条（Linux v4l2loopback、MF 虚拟相机）都还没验**。

**当前建议**：先用重启换文件（90 秒/次）。它已验证可用、不破坏基线，
且 `feed_qr.py` 的渲染逻辑可以直接复用——只需把「推给虚拟摄像头」
改成「写成 PNG 并用 `-camera-back imagefile:` 重启」。

**下一个该验的是 Linux 上的 v4l2loopback**（判据见 `camera-virtual-stacks.md` §5）。
自建镜像是大工程，不该在还没被频次逼到之前就做。

## 5. 已产生的宿主改动

| 改动 | 状态 | 清理方式 |
| --- | --- | --- |
| OBS Studio 32.2.1 | 已安装 | `winget uninstall OBSProject.OBSStudio` |
| OBS Virtual Camera | 随 OBS 注册 | 随 OBS 卸载移除 |
| Unity Capture 64 位滤镜 | 已注册 | `regsvr32 /u E:\tmp\unitycapture\UnityCaptureFilter64.dll`（需 UAC） |
| pyvirtualcam / qrcode / pyzbar / numpy / pillow | 装在 osdk 的 Python 3.14.7 | `pip uninstall` |

**都不影响项目**：这些是宿主环境的软件，未改仓库、未改 AVD、未改 system image。
留着也无害；OBS 本身是常用工具，Unity Capture 只是多一个 emulator 看不见的 DirectShow 设备。

**安装来源**：OBS 经 winget 装自 `github.com/obsproject/obs-studio` 官方 release，
发布者为 OBS Project，安装器 SHA256 由 winget 自动校验（`bbb95e52...a289`）。
osdk 明确不管理宿主软件（`osdk pkg` 只报告 winget / Homebrew 状态），故此处用 winget 是恰当的。

## 6. 可复用的产物

`E:\tmp\qr\feed_qr.py` —— 二维码帧生产者，改控制文件 `payload.txt` 即可运行时换码。
**它本身没有问题**，卡点全在 emulator 侧。改用 `imagefile` 方案时，
其中的渲染部分（含已实测的几何配方）可直接复用。

几何配方（此前实测得出，仍有效）：emulator 对摄像头/图片输入是「填满预览并裁剪」
而非缩放，可见区偏向原图左侧，因此码要偏小、放左上。

## 7. 验证方式与范围

| 检查 | 结果 |
| --- | --- |
| ffmpeg 与 emulator 同时刻枚举对比 | 见 §1，ffmpeg 见 3 个、emulator 见 1 个 |
| PnP 设备表 | 见 §1，两个虚拟摄像头均无 PnP 实例 |
| OBS 像素格式 | `nv12 / yuv420p / yuyv422`，符合 emulator 要求 |
| `webcam1` 下相机数量 | 0（OBS 与 Unity Capture 都是 0） |
| pyvirtualcam 连接 OBS | 成功，`BACKEND=OBS Virtual Camera` |
| 项目回归 | `go test ./internal/...` 全部通过 |

**未覆盖**：未验证 macOS / Linux 上 emulator 是否同样排除虚拟摄像头。
Linux 的 v4l2loopback 会创建真实的 `/dev/videoN` 设备节点，
**在那里这条路可能是通的**——若将来把自动化跑在 Linux CI 上，值得重测。
本结论只对 Windows 成立。

## 8. 复现方式

```powershell
# 起生产者
& "E:\osdk-data\data\installs\python\3.14.7\python.exe" E:\tmp\qr\feed_obs.py

# 另开一个窗口，同时刻对比两者的枚举结果
ffmpeg -hide_banner -list_devices true -f dshow -i dummy
& "E:\osdk-data\data\installs\android-sdk\emulator\emulator.exe" -webcam-list

# 看 PnP 实例差异（根因）
Get-PnpDevice -Class Camera -Status OK | Select-Object FriendlyName, InstanceId

# webcam1 下的相机数量
adb -s emulator-5554 shell dumpsys media.camera | Select-String "Number of camera devices"
```

探针脚本：`probe_obs_enum.py`、`probe_obs_e2e.py`、`probe_camera_count.py`、
`probe_unity_e2e2.py`（均在 `E:\tmp\qr\`）。
