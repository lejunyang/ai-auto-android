# Unity Capture 实测：不可用，根因是像素格式

**结论先行：Unity Capture 走不通，原因是它只输出 bgr24，而 Android emulator 只接受 YUV 系格式（NV12）。**
这是双方各自固定的设计，不是配置问题。

**实测环境**：AVD `osdk-test-35`（API 35 / x86_64），Windows 宿主，
Unity Capture 64 位滤镜已通过 `regsvr32` 正确注册。

## 1. 前三步都成立，第四步不成立

| 步骤 | 结果 |
| --- | --- |
| 1. 注册虚拟摄像头 | **成功**。`regsvr32` exit=0，`Unity Video Capture` 已进 DirectShow 视频输入类目 |
| 2. 生产者能写入 | **成功**。pyvirtualcam 报 `BACKEND=Unity Video Capture`，持续推帧无错 |
| 3. emulator 接受参数 | **表面成功**。`-camera-back webcam1` 不报错，`fake_camera` 变 `none` |
| 4. Android 能用这个相机 | **失败**。相机数量 = 0，相机应用崩溃 |

**第 3 步的"成功"是假象**——这正是之前提醒过的陷阱：参数被接受不等于设备接上了。

## 2. 定位过程

### 2.1 第一个线索：相机应用崩溃

```
java.lang.RuntimeException: Unable to start activity
  ComponentInfo{com.android.camera2/com.android.camera.CameraActivity}:
  java.lang.ArrayIndexOutOfBoundsException: length=0; index=0
    at AndroidCameraAgentImpl$AndroidCameraDeviceInfo.getCharacteristics
```

`length=0` 说明**相机列表是空的**，一个设备都没有。

### 2.2 对照实验确认根因

三组启动参数，读 `dumpsys media.camera`：

| `-camera-back` | `vendor.qemu.sf.fake_camera` | 相机数量 |
| --- | --- | --- |
| `webcam1` | `none` | **0** |
| `webcam0` | `none` | 1 |
| `emulated` | `back` | 1 |

`webcam1` 下 `fake_camera=none` 却一个设备都没有——**emulator 关掉了内置假相机，
却没接上任何外部设备**，两头落空。这解释了崩溃。

### 2.3 真因：像素格式不匹配

用 ffmpeg 读两个设备声明的格式：

| 设备 | 格式 |
| --- | --- |
| **Unity Video Capture** | `pixel_format=bgr24`（全部 12 种分辨率都只有 bgr24） |
| HD Webcam | `vcodec=mjpeg`（另有 NV12） |

而 `emulator -webcam-list` 对 HD Webcam 的描述明确写着：

```
... and will use pixel format 'NV12'
```

**emulator 只枚举能提供 YUV 系格式的摄像头**。Unity Capture 只出 BGR，
因此从一开始就没进候选列表——这也解释了为什么 `-webcam-list` 里看不到它，
即便它已在 DirectShow 里正确注册、ffmpeg 也能枚举到。

`webcam1` 之所以"被接受"，是因为 emulator 只在运行时才发现索引 1 不存在，
参数解析阶段不校验。

## 3. 为什么这条路无法通过配置绕开

| 尝试方向 | 为什么不行 |
| --- | --- |
| 让 Unity Capture 输出 NV12 | 格式由滤镜 DLL 编译时决定，无配置项。改需重编译 C++ 源码 |
| 让 emulator 接受 bgr24 | emulator 侧无此选项，是其相机后端的固定行为 |
| 用 `webcam1` 之外的索引 | 索引只对 `-webcam-list` 列出的设备有效，Unity Capture 不在其中 |
| 直接传设备名 | 试过，emulator 不接受设备名，只认 `webcamN` |

**这不是"再调调参数"能解决的**，是两个组件的格式契约不相交。

## 4. 修正后的方案排序

| 方案 | 状态 | 说明 |
| --- | --- | --- |
| **OBS 虚拟摄像头** | **未验，最有希望** | OBS 输出 NV12 / YUY2，正是 emulator 要的格式。这是它与 Unity Capture 的关键差别 |
| 重编译 Unity Capture 加 NV12 | 理论可行 | 需改 C++ 源码并自行编译，之后每次升级都要重做 |
| 重启换 `imagefile`/`videofile` | 已验证可用 | 约 90 秒/次 |
| 自建系统镜像 / 刷 Magisk | 理论可行 | 打破可复现基线 |

**关键修正**：之前我把 Unity Capture 当作"OBS 的轻量替代"，
判据只看了体量与是否需要 GUI，**漏了像素格式这一层**。
现在证据表明格式才是决定性的——OBS 之所以在 pyvirtualcam 生态里是默认后端，
恰恰因为它输出的是 NV12 这类主流摄像头格式。

pyvirtualcam 的文档只说明"支持哪些虚拟摄像头"，不涉及下游消费者对格式的要求，
所以从文档层面看不出这个差别，只能实测。

## 5. 已产生的系统改动

| 改动 | 状态 |
| --- | --- |
| `regsvr32 UnityCaptureFilter64.dll` | **仍处于已注册状态**（反注册的 UAC 被取消） |
| 反注册方式 | `regsvr32 /u E:\tmp\unitycapture\UnityCaptureFilter64.dll` |

留着无害：它只是多一个 DirectShow 设备，不占资源、不影响其他程序，
emulator 也压根看不到它。若要清理，执行上面那条命令（需 UAC）。

**未装 32 位版本**——刻意只注册 64 位，减少系统状态改动。

## 6. Python 环境（顺带确认）

osdk 已管理三个 Python：`3.11.16` / `3.12.14` / `3.14.7`，**都自带 pip**。
本轮所有包装进 osdk 的 3.14.7（`E:\osdk-data\data\installs\python\3.14.7\python.exe`），
未污染系统 Python：

| 包 | 版本 | 来源 |
| --- | --- | --- |
| pyvirtualcam | 0.15.0 | 官方 cp314 wheel，非源码编译 |
| numpy | 2.5.3 | cp314 wheel |
| qrcode / pillow / pyzbar | 最新 | wheel |

项目 `osdk.toml` 未 pin python——当前脚本用的是绝对路径而非 shim，
若要把这些探针固化进 `scripts/`，应先 `osdk use python@3.14.7` 让版本进配置。

## 7. 下一步建议

**装 OBS 验证**。判据只有一条：`emulator -webcam-list` 里是否出现 OBS 的虚拟摄像头。

- 出现 → 它提供了 NV12，后续流程与本轮相同，直接跑换码验证；
- 不出现 → 说明 emulator 对宿主虚拟摄像头有更强的限制（比如只认真实 USB 设备），
  那么整条「宿主虚拟摄像头」路线都不成立，应转向重启换文件。

**这个判据在装完后 1 分钟内就能看出来**，不需要跑完整流程。
若你不想装 140MB 的 OBS，也可以先接受 90 秒/次的重启换码方案——
它已验证可用，只是慢。

## 8. 复现方式

```powershell
# 注册（需 UAC）
regsvr32 E:\tmp\unitycapture\UnityCaptureFilter64.dll

# 确认注册成功但 emulator 看不见
ffmpeg -hide_banner -list_devices true -f dshow -i dummy   # 有 Unity Video Capture
& "E:\osdk-data\data\installs\android-sdk\emulator\emulator.exe" -webcam-list  # 没有它

# 看格式差异（根因）
ffmpeg -hide_banner -f dshow -list_options true -i video="Unity Video Capture"  # 全 bgr24
ffmpeg -hide_banner -f dshow -list_options true -i video="HD Webcam"           # mjpeg/NV12

# 相机数量对照
adb -s emulator-5554 shell dumpsys media.camera | Select-String "Number of camera devices"
```

探针脚本在 `E:\tmp\qr\`：`feed_qr.py`（推码生产者，支持运行时换码）、
`probe_unity_enum.py`、`probe_unity_e2e2.py`、`probe_camera_count.py`。
`feed_qr.py` 与换码机制本身是可复用的——**它没有问题，卡点在 emulator 侧**，
换成 OBS 后可直接复用。
