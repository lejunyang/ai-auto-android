# 扫码通道结论与能力盘点

本文回答三个问题：`videofile` 是否满足「模拟器待命、按需扫码」的诉求、
当前自动化能力是否齐备、ADB 文本限制要不要解除。

结论基于实测，未验证的部分明确标注。前置事实见 `gestures-lint-and-camera.md`
与同目录其余三份文档。

**实测环境**：AVD `osdk-test-35`（API 35 / x86_64 / 320×640），emulator 36.6.11。

## 1. videofile 不满足诉求

### 1.1 诉求与判据

你的场景是：**模拟器长期待命，某时刻需要扫一个运行时才产生的码**
（例如一个登录码），不需要在扫码过程中动态替换，但不能重启模拟器。

判据因此很清楚：能否在模拟器运行期间，把摄像头看到的码换成一个**启动时还不存在**的值。

### 1.2 实测：覆盖视频文件无效

以 `-camera-back videofile:<固定路径>` 启动，基线解出 `FIRST`（一次命中，几何正确）。
随后覆盖同一路径为含 `SECOND` 的视频，**连续采样 40 秒（20 次），始终是 `FIRST`**。

视频本身只有 4 秒，40 秒内循环了约 10 轮，因此不是「还没播到」——
**emulator 在启动时就把视频读入，不会重读文件**。

### 1.3 videofile 的真实适用范围

它成立的是另一件事（上一轮已验证）：**一次会话内按时间循环播放一个预先确定的码序列**。

| 特性 | 结论 |
| --- | --- |
| 一次启动播多个码 | 可以 |
| 循环播放 | 是 |
| 换成启动时不存在的码 | **不行** |
| 随机访问某个码 | 不行，只能按时间等 |

所以它适合「用例已知要扫哪几个固定码」，不适合「扫运行时产生的码」。

## 2. 六条摄像头通道全部实测完毕

| 通道 | 能否运行时换码 | 实测判据 |
| --- | --- | --- |
| `imagefile:` 覆盖文件 | 否 | 覆盖后等 3/5/8/12 秒仍旧码 |
| `videofile:` 覆盖文件 | 否 | 覆盖后 40 秒（约 10 个循环）仍旧码 |
| `virtualscene-image wall\|table` | 否 | 命令返回 OK 但海报不在镜头视野 |
| `-virtualscene-poster` | 否 | 是**启动参数**，与 console 命令同名但不同物 |
| emulator console | 否 | 命令表无 camera 相关项 |
| `webcam0` | 理论可行，**未验** | 本机只有实体摄像头；需装宿主虚拟摄像头软件 |

### 2.1 virtualscene 为何不可用（已查清）

官方 ARCore 文档说明 wall 与 table 两个图片位置在虚拟场景的
**「餐厅区域，起始位置背后那道门」**，而默认镜头正对客厅电视。

尝试用 console `sensor set rotation` 扫 12 个 yaw 角度：
**12 张截图 MD5 各不相同**（画面确实在动），但每张都仍对着同一面墙。
该命令只微调设备姿态，不移动虚拟场景相机。**纯命令行下取景不可控。**

### 2.2 webcam 路的代价

`-webcam-list` 报告本机有 `HD Webcam`（`vid_04f2&pid_b71a`，NV12）。
要用它换码，需在宿主侧运行虚拟摄像头软件（OBS 虚拟摄像头之类）实时推帧。

**代价**：引入宿主外部依赖，与 `scripts/emulator/` 的「固定 SDK 版本 + 校验和 +
从干净快照启动」这套可复现基线冲突。**未验证。**

## 3. 一条绕开摄像头的路（未实现，建议）

App 侧的扫码链路**本身是解耦的**：

```
EmbeddedLanQrScanner   (CameraX ImageAnalysis.Analyzer)
        ↓ 产出 QrLuminanceFrame
LanQrFrameDecoder      (fun interface，单方法 decode(frame): String?)
        ↓
EmbeddedLanQrDecoder   (ZXing 实现)
```

`LanQrFrameDecoder` 是 `fun interface`，解码与相机之间只隔一个 `QrLuminanceFrame`
数据类。这意味着 **debug 变体可以从相机以外的地方喂帧**，完全不经过模拟器摄像头。

可行形态（未实现）：在 `DebugTestControlPlane` 加一个 test-only 方法，
接受一张图或一段 payload，构造 `QrLuminanceFrame` 直接交给解码器。

**优点**：无需重启模拟器、支持运行时产生的码、不依赖宿主软件、
与既有 test-only 授权边界一致（AGENTS.md 第 58 行允许 disposable emulator 上预置）。

**限制**：它绕过了真实相机，**因此不能用于验证「相机采集 → 解码」这条链路本身**。
若测试目标是扫码功能的端到端正确性，仍需真实摄像头通道；
若目标是「让扫码这一步过去，继续后面的自动化流程」，这条路足够。

**这是建议，未实现也未验证。**

## 4. 自动化能力盘点

### 4.1 已具备（均已实测）

| 能力 | CLI | MCP | 桥 |
| --- | --- | --- | --- |
| 截屏 | `observe screenshot` | kind=screenshot | — |
| UI 层级 | `observe hierarchy` | kind=hierarchy | — |
| 语义快照 | `bridge snapshot` | kind=semantic | `ui.snapshot` |
| 前台应用 | `observe foreground` | kind=foreground | `ui.foreground` |
| 应用清单 | `observe apps` | kind=apps | — |
| 录屏 | `observe recording` | `android_screen_record` | — |
| 点击/滑动 | `action tap\|swipe` | `ui.tap` / `ui.swipe` | — |
| 长按/滚动 | `action long-press\|scroll` | `ui.longClick` / `ui.scroll` | — |
| 文本/按键 | `action text\|key` | `ui.setText` / `ui.pressKey` | — |
| 启动/停止应用 | `action launch\|stop` | `app.launch`（stop 被风险门拒） | — |
| 等待判据 | `check wait` | `android_wait` | — |
| 断言判据 | `check assert` | `android_assert` | — |

**自愈回路已闭合**：前台包名（信息来源）+ `wait`/`assert`（判据执行）+
失败时返回实际观察值（纠正依据）。

### 4.2 明确的缺口

| 缺口 | 影响 | 归属 |
| --- | --- | --- |
| **中文文本输入** | 无法在任何中文输入场景做自动化 | 见第 5 节 |
| 扫运行时产生的码 | 见第 1–3 节 | 摄像头通道 |
| 多指手势 | 缩放、旋转类交互做不了 | 协议层也没有 |
| 桥侧长按/滚动 | 只有坐标级，无元素级版本 | 桥协议 |
| 元素级动作（直连） | 直连侧全是坐标，重排后会静默点错 | 需桥 |

### 4.3 我的判断：中文输入是当前最大的缺口

其余几项都有替代或影响有限：多指手势少见；元素级定位可走桥；
扫码有「用例已知固定码」这条可用路径。

**而中文输入没有替代**：只要目标应用需要输入中文（搜索、表单、聊天），
自动化就到此为止。

## 5. ADB 文本限制：建议解除，但不是改正则

### 5.1 限制的真实位置与成因

| 位置 | 内容 |
| --- | --- |
| `internal/adb/direct.go` | `directText = ^[A-Za-z0-9 @._+\-,:/]*$` |
| `internal/mcpserver/schemas.go:103` | 同一套正则 |
| `protocol/schema/v1/action.schema.json` | **无字符集限制**，只有 `maxLength: 10000` |

**协议层本来就允许中文，限制是直连层自己加的。**

成因在 `Text()` 的实现：它把空格替换成 `%s` 再交给 `adb shell input text`。
这套转义是 `input text` 的约定，对非 ASCII 不可靠——**所以正则是对实现缺陷的诚实兜底，
而不是凭空收紧**。直接放宽正则只会让失败从「明确拒绝」变成「静默输错」。

### 5.2 三条可行路径

| 路径 | 做法 | 代价 |
| --- | --- | --- |
| **A. 走桥侧无障碍 setText** | 桥协议 `action.execute` 已有 `ui.setText`，无障碍直接写节点文本，不经 `input text` | 需要桥连接与披露同意 |
| **B. test-only IME** | 装一个接收 broadcast 输入文本的输入法，切为默认后写入 | 需新增 APK 组件；仅 debug；切换 IME 会影响真实输入行为 |
| **C. `input keyevent` 逐字符** | 用 Unicode 码点逐个发键 | 实测 Android 对非 ASCII 的 `KEYCODE_` 支持有限，**大概率走不通** |

### 5.3 建议：先做 A

**理由**：

1. **零新增组件**。桥协议的 `ui.setText` 已经定义，Kotlin 侧无障碍 `setText`
   也已在用（回放引擎走的就是这条），只需把 Go 侧的桥客户端补一个方法暴露出来 ——
   与本轮 `ui.foreground` 的改法完全同构。
2. **绕开根因**。无障碍写的是节点的文本属性，不经过 `adb shell input text`
   的转义层，字符集问题从源头消失。
3. **与既有安全模型一致**。无障碍 setText 受目标包白名单约束，
   而 `input text` 是全局的——走桥反而更可控。

**A 不覆盖的场景**：没有桥连接时（纯直连模式）仍然只能 ASCII。
是否要为此再做 B，取决于你是否需要「不装 App 也能输中文」。
我的判断是暂时不需要——中文输入几乎总是发生在需要语义定位的复杂界面，
那种场景本来就该用桥。

**直连侧的正则建议保留**，但把错误信息改成指路：
现在只说「不支持这些字符」，应该说「非 ASCII 文本请使用桥侧 setText」。

## 6. 建议的下一步

按「缺口真实性 × 代价」排：

1. **桥侧 setText 暴露到 Go 侧**（解中文输入，与 `ui.foreground` 同构，代价小）
2. **直连侧错误信息指路**（一行改动，避免调用方卡在死路上）
3. **test-only 扫码注入**（解运行时扫码，见第 3 节；需先确认你接受「绕过真实相机」）

第 3 项要先和你确认取舍再动手。

## 7. 验证方式与范围

| 检查 | 结果 |
| --- | --- |
| videofile 覆盖换码 | 40 秒 20 次采样，全部旧码 |
| videofile 基线 | 一次命中，几何正确 |
| virtualscene 转镜头 | 12 个 yaw 角度，MD5 各异但都未扫到海报 |
| webcam 可用性 | `-webcam-list` 报告 1 个实体摄像头 |
| App 扫码解耦程度 | `LanQrFrameDecoder` 为 `fun interface`，与 CameraX 分离 |

**未验证**：`webcam0` + 宿主虚拟摄像头软件；test-only 帧注入（尚未实现）；
桥侧 setText 对中文的实际效果（第 5 节的建议 A 未动手）。

## 8. 复现方式

```powershell
# videofile 覆盖测试
python E:\tmp\qr\make_live_videos.py          # 造两段单码视频
emulator -avd osdk-test-35 -camera-back videofile:E:\tmp\qr\live_video.mp4
python E:\tmp\qr\probe_video_overwrite.py     # 基线 + 覆盖 + 40 秒采样

# 通道盘点
python E:\tmp\qr\probe_channels.py
```

探针脚本在 `E:\tmp\qr\`（非仓库产物）。
