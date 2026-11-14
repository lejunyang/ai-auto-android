# 宿主侧虚拟摄像头选型

现状核对：2026-11-14，基线 `984ee31`。本文件维护选型状态；早期对 OBS/Unity 的
推荐已被后续实测否定。依据见[摄像头当前路线](camera-virtual-stacks.md)与
[DirectShow 实测](host-virtual-camera-verdict.md)，本轮未运行设备或核查宿主安装。

**目的**：在宿主上提供一个可编程的虚拟摄像头，模拟器以 `-camera-back webcam0` 接入，
实现「模拟器长期待命、按需扫到运行时生成的码」且**不重启模拟器**。

前置结论见 `docs/mcp-text-input-and-webcam.md`：`webcam0` 通道已实测成立
（`fake_camera` 从 `back` 变 `none`，相机应用拍到宿主真实画面），
该证据只适用于实体摄像头，不能推断 OBS/Unity 软件后端同样可用。

## 1. 结论先行

**没有单一方案通吃三系统**，但有统一的上层 API：

| 层 | 选择 | 跨平台 |
| --- | --- | --- |
| 业务层（生成码、渲染帧） | 自己的代码 | 是 |
| **统一 API** | **pyvirtualcam** | **是** |
| 驱动层 | Windows 的 OBS/Unity 已否定；Linux v4l2loopback、Windows MF、macOS 路线均未验 | 各平台单独验收 |

`pyvirtualcam` 可统一宿主推帧 API，但不解决 emulator 枚举。以下 macOS/第三方版本
说明保留早期调研口径，本轮未重新核实，不能据此声称任何平台已经完成换码。

## 2. 为什么不是 Webcamoid / AkVirtualCamera

它曾经是最接近「三系统统一」的候选——同一套 `AkVCamManager` CLI 管理 Windows 与
macOS 的虚拟摄像头。但 **Webcamoid 9.4.0（2026-07-06）把 macOS 虚拟摄像头整个移除了**，
官方理由是「维护不善、不稳定、在新版系统上很可能已经无法正常工作」，
并把 Windows 上的虚拟摄像头也标为「不再推荐，建议改用 Local Streaming」。

跨平台优势没了，反而只剩 Windows 一条腿，不如直接用更主流的方案。

## 3. 各平台推荐

### 3.1 Linux — v4l2loopback（最干净）

```bash
sudo apt install v4l2loopback-dkms
sudo modprobe v4l2loopback devices=1 video_nr=10 card_label="AACTL Virtual Cam"
```

- 内核模块，约 200KB，**无需任何 GUI**
- 装完直接出现 `/dev/video10`，`ffmpeg` 或 `pyvirtualcam` 均可写入
- **注意**：开了 Secure Boot 的机器需要给模块签名，否则 `modprobe` 会被拒

这是三个平台里最适合 CI / headless 环境的。

### 3.2 Windows — OBS / Unity Capture 已否定

两者都是 DirectShow 滤镜，无 PnP 实例；emulator 的 Media Foundation 路径不枚举。
OBS 虽提供 NV12 等格式仍失败，因此原“OBS 推荐/Unity 轻量替代”不再适用。
统一推帧 API、注册成功或 `fake_camera=none` 都不能代替 Android 相机数和解码后置。
Windows MF Frame Server 是另一条待验路线，不能把 DirectShow 失败外推到它。
完整对照数据见 [host verdict](host-virtual-camera-verdict.md) §1-3。

### 3.3 macOS — OBS Studio 30+（几乎是唯一选择）

```bash
brew install --cask obs
# 首次需手动：打开 OBS -> 点 "Start Virtual Camera" -> 再点 "Stop" -> 关闭 OBS
```

**这一步无法自动化**，是 Apple 的系统扩展注册机制要求的。

#### 为什么 macOS 选择面这么窄

| 时间点 | 变化 |
| --- | --- |
| macOS 12.3 | CoreMediaIO Camera Extension 取代 DAL 插件，Apple 官方建议迁移 |
| macOS 14.1 (Sonoma) | **非系统扩展的摄像头设备直接不可用** |

系统扩展必须由 Apple 签名并公证（notarize），且用 Developer ID 签名的还需带
secure timestamp。这道门槛淘汰了几乎所有小型开源方案——
Webcamoid 移除 macOS 虚拟摄像头正是因为这个。

**另注**：pyvirtualcam 0.14 起，在 macOS 13+ 上只支持 OBS 30 或更高版本。

## 4. 统一 API：pyvirtualcam

```python
import pyvirtualcam
import numpy as np

# 后端自动选择：Windows/macOS 找 OBS，Linux 找 v4l2loopback
with pyvirtualcam.Camera(width=1280, height=720, fps=20) as cam:
    frame = render_qr_code("AACTL-LOGIN-TOKEN-xxx")  # 自己的渲染逻辑
    while True:
        cam.send(frame)
        cam.sleep_until_next_frame()
```

- 支持 Windows / macOS / Linux，已有 Python 3.14 的预编译 wheel
- 本机 Python 3.14.7，**可直接 `pip install pyvirtualcam`**
- 换码 = 换 `frame` 的内容，**不涉及模拟器任何操作**

### 一个限制

OBS 只提供**单个**摄像头实例。如果将来需要同时喂两路不同的码
（比如两个模拟器各扫各的），Windows 上要改用 Unity Capture，
macOS 上则做不到——这是 OBS 的设计限制，不是 pyvirtualcam 的。

## 5. 成本对比

| 方案 | 每次换码耗时 | 一次性成本 |
| --- | --- | --- |
| 当前：重启换 `imagefile`/`videofile` | **约 90 秒**（冷启动 50s + 预置配对 40s） | 无 |
| 待验虚拟摄像头路线 | 未核实；原“<1秒”仅为方案估计，Windows DirectShow 已失败 | Linux 内核模块或 Windows MF 构建环境；macOS 未验 |

只有枚举、相机数量与不重启解码两个不同 payload 均通过后，才能比较换码成本。

## 6. 与项目基线的关系

`scripts/emulator/` 那套「固定官方镜像 + 校验和 + 干净快照」的可复现基线**不受影响**：

- 不改 system image
- 不需要 root
- 不改 AVD 配置（只是启动参数多一个 `-camera-back webcam0`）
- 宿主软件属于**环境准备**，可写进文档并在 `doctor` 类命令里检查

这是它比「自建系统镜像」「刷 Magisk + LSPosed」优越的根本原因——那两条都要动 AVD 本身。

## 7. 宿主状态（当前未核实）

| 项 | 状态 |
| --- | --- |
| OBS Studio | 早期未安装；后续实测记录已安装，当前是否保留未核实 |
| OBS 虚拟摄像头 DirectShow 注册 | 后续已注册但 emulator 不枚举；当前注册状态未核实 |
| Python | 3.14.7，满足 pyvirtualcam 要求 |
| 实体摄像头 | `HD Webcam`（NV12），`webcam0` 通道已验证可用 |

## 8. 后续验证顺序与边界

1. 先选 Linux v4l2loopback 或 Windows MF 的独立环境，建立 change spec；N55 记录 Linux 目标、依赖与清理。
2. 先确认 `emulator -webcam-list` 枚举候选，再检查 Android 相机数，最后验证不重启扫到两次不同的新 payload。枚举通过只算前置。
3. 只在全部判据通过后评估 CLI/MCP 封装；当前没有产品化摄像头喂帧入口。失败时保留否定结论并清理该任务资源。

宿主内核模块、系统扩展与驱动安装涉及系统状态，后续实施需明确环境与授权，
本次文档审计不执行安装、注册、推帧或任何设备步骤。macOS 的旧研究没有设备证据，
保持未核实；Windows 不再建议重复安装 OBS/Unity 验证同一条已失败路线。

## 参考来源

- pyvirtualcam 后端矩阵与安装要求：https://pypi.org/project/pyvirtualcam/
- Webcamoid 9.4.0 移除 macOS 虚拟摄像头：https://webcamoid.github.io/posts/2026/07/06/webcamoid-940-released/
- Apple CoreMediaIO Camera Extension（12.3 起取代 DAL）：https://developer.apple.com/documentation/coremediaio
- macOS 14.1 起非系统扩展摄像头不可用：https://support.apple.com/en-my/108387
- OBS Linux 安装（v4l2loopback 依赖）：https://obsproject.com/wiki/install-instructions
