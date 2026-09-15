# 虚拟摄像头的两套 Windows API 与待验路线

**这是摄像头喂帧方向的当前结论与待办入口。** 其余几份文档记录各自的实测过程，
本文负责说清「已经排除了什么、为什么、下一个该验什么」。

## 1. 一句话结论

Android emulator **在 Windows 上看不到 DirectShow 类虚拟摄像头**，
因为它只枚举有 PnP 实例的设备。这已实测证实。

但这**不等于**「所有宿主虚拟摄像头都不行」——Windows 11 另有一套
Media Foundation Frame Server 原生虚拟相机，会登记 PnP 实例，**尚未验证**。
Linux 的 v4l2loopback 创建真实设备节点，**同样尚未验证**。

## 2. 两套互不相通的采集栈

| | DirectShow 滤镜 | MF Frame Server 虚拟相机 |
| --- | --- | --- |
| 注册方式 | 运行时 `regsvr32` 注册 COM 组件 | `MFCreateVirtualCamera`（Win11 Build 22000+） |
| PnP 实例 | **无** | **有**，登记在 `KSCATEGORY_VIDEO_CAMERA` 等类目下 |
| Windows「已连接的相机」列表 | 通常不出现 | 出现，与硬件同列 |
| 代表实现 | OBS Virtual Camera、Unity Capture | VCamSample（smourier）、Qt WindowsVirtualCamera |
| emulator 能否枚举 | **否（实测）** | **未验** |

**Android emulator 在 Windows 上以 Media Foundation 作为摄像头后端**，
这一点在官方 release notes 里明确写着（27.x 版本的「相机」条目）。
所以它只认新栈的设备。

OBS 官方 KB 也承认其 Virtual Camera 不是 Media Foundation 设备、
迁移到 MF 是低优先级——两边说法吻合。

## 3. 实测数据（Windows 11 Build 26200）

同一时刻、生产者正在推帧：

| 设备 | 栈 | ffmpeg | PnP Camera 类 | emulator |
| --- | --- | --- | --- | --- |
| HD Webcam | 真实 UVC | 是 | 是 | **是**（`webcam0`） |
| OBS Virtual Camera | DirectShow | 是 | **否** | **否** |
| Unity Video Capture | DirectShow | 是 | **否** | **否** |

`-camera-back webcam1`（指向不存在的索引）的后果：

| 参数 | `fake_camera` | 相机数量 |
| --- | --- | --- |
| `emulated` | `back` | 1 |
| `webcam0` | `none` | 1 |
| `webcam1` | `none` | **0** |

它会关掉内置假相机却接不上任何设备，Android 相机列表为空，
相机应用崩在 `ArrayIndexOutOfBoundsException: length=0`。
**参数不报错、`fake_camera` 也变成 `none`，看起来像成功——必须查相机数量。**

### 格式不是决定因素

| 设备 | 像素格式 |
| --- | --- |
| HD Webcam | NV12 / mjpeg |
| Unity Video Capture | 仅 bgr24 |
| **OBS Virtual Camera** | **nv12 / yuv420p / yuyv422** |

OBS 提供的格式完全符合 emulator 要求，**依然接不上**。
我曾把 Unity Capture 的失败归因于格式（它只有 bgr24），
那个观察是对的但不是根因——OBS 恰好消掉了这个变量，结果不变。

## 4. 关于网上「能用」的说法

这个方向的网络资料混淆严重，三点辨析：

**① 多数教程说的不是 AVD。** 雷电、夜神、MuMu、BlueStacks 自带虚拟摄像头功能，
设置里可直接选图片或视频文件当输入——那是各家模拟器自行实现的
（内部提供一个符合 Camera2 的虚拟设备，从宿主取帧），与官方 AVD 无关。

**② 讨论 OBS + AVD 的帖子，结论多与本文一致。** 例如 CSDN 那篇被反复引用的问答，
标题本身就是「OBS虚拟摄像头无法被安卓模拟器识别」，
其机制解释（「模拟器未加载对应 DirectShow 或 Media Foundation 视频采集源」）与实测吻合。

**③ 但网络资料里有编造内容，需交叉核验。** 同一篇提到
「Google 已在 Emulator 34.2.12+ 实验性支持 Virtual Camera via WebRTC Capture
（通过 CDP 注入 MediaStream）」——通读官方 release notes **无任何此类记载**。
WebRTC 在 emulator 里用于容器音频串流与远程交互，与摄像头注入无关。

## 5. 待验路线与判据

### 5.1 Linux + v4l2loopback（优先级最高）

**为什么值得验**：v4l2loopback 是内核模块，创建的是真实的 `/dev/videoN` 设备节点，
不是用户态 COM 滤镜。emulator 在 Linux 上通过 V4L2 枚举摄像头，
**这个设备在 V4L2 视角里与真实摄像头没有区别**。

**判据**（1 分钟内可判）：

```bash
sudo modprobe v4l2loopback devices=1 video_nr=10 card_label="AACTL Virtual Cam"
emulator -webcam-list        # 列表里是否出现该设备
```

出现即成立，之后流程与本轮相同：`feed_qr.py` 换 v4l2 后端推码，
`-camera-back webcamN` 启动，验证不重启换码。

**代价**：仅需一个内核模块（约 200KB），不需要 GUI，最适合 CI。
Secure Boot 的机器需给模块签名。

### 5.2 MF Frame Server 虚拟相机（Windows，次优先）

本机 Windows 11 Build 26200 满足 `MFCreateVirtualCamera` 所需的 22000。
现成实现：`smourier/VCamSample`（C++）、Qt 的 `WindowsVirtualCamera`
（含 `VCamManager` 控制台工具，可命令行创建/推流/移除）。

**判据同上**：`emulator -webcam-list` 里是否出现。

**代价**：需 MSVC 自行编译，比装个包重得多。因此排在 Linux 方案之后。

### 5.3 当前可用方案

**重启换 `imagefile` / `videofile`**，约 90 秒/次（冷启动 50s + 预置配对 40s），
已验证可用、不破坏可复现基线。

`feed_qr.py` 的渲染逻辑可直接复用，只需把「推给虚拟摄像头」
改成「写 PNG + `-camera-back imagefile:` 重启」。

几何配方（已实测）：emulator 对摄像头/图片输入是「填满预览并裁剪」而非缩放，
可见区偏向原图左侧，因此码要偏小、放左上。

## 6. 已排除的路线

| 路线 | 判据 |
| --- | --- |
| DirectShow 类虚拟摄像头（OBS / Unity Capture） | 无 PnP 实例，emulator 不枚举（本文 §3） |
| 桥 App 内实现虚拟摄像头 | `CREATE_VIRTUAL_DEVICE` 是 `internal\|role`、`CAMERA_INJECT_EXTERNAL_CAMERA` 是 `signature`，`pm grant` 均被拒，root 也绕不过 |
| `imagefile:` / `videofile:` 覆盖文件换码 | 启动时读入内存，覆盖无效（videofile 采样 40 秒全旧码） |
| `virtualscene-image wall\|table` | 12 个 yaw 角度扫描，海报始终不在镜头视野 |
| emulator console 运行时切摄像头 | `help` 命令表无任何相机项 |

## 7. 本机遗留的宿主改动

| 改动 | 状态 | 清理方式 |
| --- | --- | --- |
| OBS Studio 32.2.1 | 已安装 | `winget uninstall OBSProject.OBSStudio` |
| Unity Capture 64 位滤镜 | 已注册 | `regsvr32 /u E:\tmp\unitycapture\UnityCaptureFilter64.dll`（需 UAC） |
| pyvirtualcam / qrcode / pyzbar / numpy / pillow | 装在 osdk 的 Python 3.14.7 | `pip uninstall` |

**均不影响项目**：未改仓库、未改 AVD、未改 system image。
OBS 装自 winget（发布者 OBS Project，下载源 `github.com/obsproject/obs-studio` 官方 release，
安装器 SHA256 由 winget 校验）。osdk 明确不管理宿主软件（`osdk pkg` 只报告状态），
故此处用 winget 恰当。

## 8. 相关文档

| 文档 | 内容 |
| --- | --- |
| `emulator-ui-automation.md` | 模拟器连接形态、免配对、root 可用性 |
| `mcp-text-input-and-webcam.md` | MCP setText、`webcam0` 实物摄像头通道验证 |
| `unity-capture-verdict.md` | Unity Capture 实测过程与格式差异 |
| `host-virtual-camera-verdict.md` | DirectShow 类的完整实测数据（结论已按本文收窄） |
| `text-input-and-virtual-camera.md` | 中文输入打通、桥 App 虚拟摄像头权限门 |

## 9. 参考来源

- Android Emulator release notes（Windows 上以 Media Foundation 为摄像头后端）：
  https://developer.android.google.cn/studio/releases/emulator
- `MFCreateVirtualCamera`（最低 Windows Build 22000）：
  https://learn.microsoft.com/en-us/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera
- Frame Server Custom Media Source：
  https://learn.microsoft.com/en-ca/Windows-hardware/drivers/stream/frame-server-custom-media-source
- OBS Virtual Camera 非 MF 设备（第三方整理，与 OBS 论坛讨论一致）：
  https://altercam.com/rus/obs-virtual-camera.html
- OBS 论坛：Virtual Camera 与仅支持 MF 的应用不兼容：
  https://obsproject.com/forum/threads/windows-obs-studio-virtual-camera-incompatibility.145024/
- Qt WindowsVirtualCamera（含 VCamManager）：
  https://git.qt.io/qtmultimedia/WindowsVirtualCamera/-/tree/master
- pyvirtualcam 后端矩阵：https://pypi.org/project/pyvirtualcam/
- v4l2loopback 安装（OBS 官方 wiki）：https://obsproject.com/wiki/install-instructions
- 第三方模拟器自带虚拟摄像头（说明与 AVD 的差别）：
  https://blog.csdn.net/qq_33060405/article/details/148641777
- 被反复引用的 OBS + 模拟器问答（标题即「无法识别」）：
  https://ask.csdn.net/questions/9216672
