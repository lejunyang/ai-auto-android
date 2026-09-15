# 宿主侧虚拟摄像头选型

**目的**：在宿主上提供一个可编程的虚拟摄像头，模拟器以 `-camera-back webcam0` 接入，
实现「模拟器长期待命、按需扫到运行时生成的码」且**不重启模拟器**。

前置结论见 `docs/mcp-text-input-and-webcam.md`：`webcam0` 通道已实测成立
（`fake_camera` 从 `back` 变 `none`，相机应用拍到宿主真实画面），
缺的只是宿主侧一个由软件驱动的摄像头设备。

## 1. 结论先行

**没有单一方案通吃三系统**，但有统一的上层 API：

| 层 | 选择 | 跨平台 |
| --- | --- | --- |
| 业务层（生成码、渲染帧） | 自己的代码 | 是 |
| **统一 API** | **pyvirtualcam** | **是** |
| 驱动层 | Windows/macOS 用 OBS，Linux 用 v4l2loopback | 否，各不相同 |

`pyvirtualcam` 是关键：它在三种驱动之上提供一致接口，业务代码写一次即可。

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

### 3.2 Windows — OBS Studio（推荐）或 Unity Capture（更轻）

| | OBS Studio | Unity Capture |
| --- | --- | --- |
| 体量 | ~140MB | ~1MB |
| 安装 | 常规安装包 | 跑一个 `.bat` 注册 DirectShow 滤镜 |
| 需要打开 GUI | **否**，装完注册即可写入 | 否 |
| 维护 | 活跃，主流 | 社区项目，更新慢 |
| 像素格式 | 常规 | 支持 RGBA（带透明通道） |

**推荐 OBS**：虽然体量大两个数量级，但它是 pyvirtualcam 在 Windows 上的默认后端，
装完后**不需要打开 OBS 窗口**就能写入——这点常被误解。

若在意体量（比如要塞进 CI 镜像），Unity Capture 是合理替代，代价是手动注册与较弱的维护。

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
| 虚拟摄像头 | **< 1 秒** | 宿主装一个软件 |

扫码若在自动化流程里反复出现，虚拟摄像头方案的优势随次数线性放大。

## 6. 与项目基线的关系

`scripts/emulator/` 那套「固定官方镜像 + 校验和 + 干净快照」的可复现基线**不受影响**：

- 不改 system image
- 不需要 root
- 不改 AVD 配置（只是启动参数多一个 `-camera-back webcam0`）
- 宿主软件属于**环境准备**，可写进文档并在 `doctor` 类命令里检查

这是它比「自建系统镜像」「刷 Magisk + LSPosed」优越的根本原因——那两条都要动 AVD 本身。

## 7. 本机现状

| 项 | 状态 |
| --- | --- |
| OBS Studio | **未安装** |
| OBS 虚拟摄像头 DirectShow 注册 | **未注册** |
| Python | 3.14.7，满足 pyvirtualcam 要求 |
| 实体摄像头 | `HD Webcam`（NV12），`webcam0` 通道已验证可用 |

## 8. 建议的落地顺序

1. 宿主装 OBS（Windows 本机），确认虚拟摄像头注册成功；
2. `pip install pyvirtualcam`，写一个最小验证：渲染一个已知 payload 的二维码推进去；
3. 模拟器以 `-camera-back webcam0` 启动，用扫码 app 验证能读到该 payload；
4. **验证换码**：不重启模拟器，改推第二个 payload，确认扫到的内容跟着变——
   这是整件事成立与否的判据；
5. 通过后再在 CLI/MCP 侧封装成 `aactl camera set-qr --payload <text>` 之类的命令，
   并在 `scripts/emulator/` 的环境检查里加上虚拟摄像头存在性检测。

**第 4 步是关键**，前三步都成立也不代表第 4 步成立（`imagefile` 就是栽在这里）。

## 9. 需要你决定

1. **是否接受宿主装 OBS**（约 140MB）。若在意体量，Windows 可改 Unity Capture，
   但 macOS 没有轻量替代。
2. **是否只做 Windows**。若团队只在 Windows 上跑自动化，可以直接用 Unity Capture，
   1MB 且不需要 GUI，比 OBS 更贴合。
3. 你点头后我可以把第 1–4 步跑完，给出端到端换码的实测结论。

## 参考来源

- pyvirtualcam 后端矩阵与安装要求：https://pypi.org/project/pyvirtualcam/
- Webcamoid 9.4.0 移除 macOS 虚拟摄像头：https://webcamoid.github.io/posts/2026/07/06/webcamoid-940-released/
- Apple CoreMediaIO Camera Extension（12.3 起取代 DAL）：https://developer.apple.com/documentation/coremediaio
- macOS 14.1 起非系统扩展摄像头不可用：https://support.apple.com/en-my/108387
- OBS Linux 安装（v4l2loopback 依赖）：https://obsproject.com/wiki/install-instructions
