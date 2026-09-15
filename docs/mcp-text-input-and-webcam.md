# MCP 文本输入与 webcam 通道验证

本文记录两件事：把桥侧 setText 暴露到 MCP（已完成并端到端实测），
以及 `webcam0` 通道能否满足「模拟器待命、按需扫指定码」（已验到底，结论明确）。

**实测环境**：AVD `osdk-test-35`（API 35 / x86_64 / 320×640），
宿主 Windows，唯一实体摄像头 `HD Webcam`（NV12）。

## 1. MCP 侧 setText 已可用

### 1.1 新增工具

`android_bridge_set_text`，入参：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `device` | 是 | 设备序列号 |
| `text` | 是 | 任意 Unicode，1–10000 字符，**无字符集限制** |
| `package` | 否 | 限定目标应用 |
| `resourceId` / `description` / `matchText` | **三选一至少填一个** | 定位方式，按可靠性排列 |

「至少填一个」用 schema 的 `anyOf` 约束。**这不是形式检查**：三个全空会匹配到
任意可编辑节点，写入位置将不可预期——比报错更糟。

### 1.2 为何与 `ui.setText` 并存而不是替换

两者语义相同但约束不同，刻意都保留：

| | `android_action_execute` 的 `ui.setText` | `android_bridge_set_text` |
| --- | --- | --- |
| 通道 | `adb shell input text` | App Bridge 无障碍 `ACTION_SET_TEXT` |
| 字符集 | 仅 ASCII | 任意 Unicode |
| 前置条件 | 无 | 需桥已连接 |
| 定位 | 先点击聚焦 | 语义节点直接命中 |

直连那条不需要装任何东西，桥那条能写中文但要预置。工具描述里写明了差异，
模型按文本内容与桥状态选择。

### 1.3 返回结构化证据而非不透明 JSON

```json
{"route": "nodeAction", "matchedPath": [0, 1], "matchScore": 1}
```

三个字段显式声明在输出 schema 里。理由：只回一个成功标志，
**无法区分「写对了」与「写进了另一个同类输入框」**。`matchScore` 与
`matchedPath` 是调用方判断写入是否落到预期元素的唯一依据。

### 1.4 途中踩的一个坑

handler 最初返回 `json.RawMessage`，SDK 直接 panic：

```
AddTool "android_bridge_set_text": output schema must have type "object"
```

SDK 从返回类型推断输出 schema，`RawMessage` 推不出对象结构。改成显式结构体
`bridgeSetTextOutput` + `OutputSchema` 后通过。

**另一个教训**：`go test` 全绿之后我没重新 `go build`，探针跑的还是旧二进制，
于是看到「测试通过但 server 启动即 panic」。测试用的是包内构建，
`bin/aactl.exe` 是独立产物，改完必须重新构建。

### 1.5 端到端实测

| 检查 | 结果 |
| --- | --- |
| `tools/list` | 11 个工具，`android_bridge_set_text` 已登记 |
| 写入前 | `text=''` |
| 调用返回 | `{"matchScore":1,"matchedPath":[0,1],"route":"nodeAction"}` |
| **回读界面** | **`text='MCP中文写入验证'`** |
| schema 守卫（三个定位字段全不填） | 已拒绝 |

判据取回读界面层级，不取工具返回值。

## 2. webcam0 通道：机制成立，但换码还缺宿主侧一环

### 2.1 通道确实通了

以 `-camera-back webcam0` 启动后：

| 证据 | 值 |
| --- | --- |
| `vendor.qemu.sf.fake_camera` | **从 `back` 变成 `none`** |
| 相机应用连拍两帧 MD5 | `294a395b23e9` / `2c568107c43c`，**不同** |
| 肉眼确认 | 拍到宿主桌面木纹与笔记本边缘，是真实画面 |

`fake_camera=none` 说明内置假相机让位了；两帧不同是真实摄像头信号的特征
（假相机是静止合成图）。**这是六条通道里第一条真正把外部画面接进模拟器的。**

### 2.2 但它满足不了「运行时换码」

要让模拟器待命、按需扫一个运行时产生的码，需要在**不重启模拟器**的前提下
改变摄像头送出的内容。三项实测：

| 检查 | 结果 |
| --- | --- |
| emulator console 有无相机命令 | `help` 命令表里**无任何相机项** |
| `camera list` / `webcam list` / `camera set back webcam0` | 全部 `KO: unknown command` |
| 宿主有无可写入的虚拟摄像头 | `-webcam-list` 只有 `HD Webcam`，**无虚拟设备** |

所以 `webcam0` 是**启动时绑定**的：它把某个宿主摄像头接进来，
但接进来之后模拟器侧没有任何切换或喂图的接口。

### 2.3 差的是宿主侧那一环

通道本身是成立的——**只要宿主上那个摄像头的画面能被程序控制，
换码就不需要重启模拟器**。缺的是一个由软件驱动的虚拟摄像头设备
（OBS 虚拟摄像头、Unity Capture 之类），让我们把生成的二维码渲染进去。

本机没装这类软件，所以这条路我验到了机制成立为止，**没有验证端到端换码**。

### 2.4 六条通道的最终结论

| 通道 | 能否接入外部画面 | 能否运行时换码 |
| --- | --- | --- |
| `imagefile:` | 可以（启动时读入） | 否，覆盖文件无效 |
| `videofile:` | 可以（启动时读入） | 否，40 秒采样全旧码 |
| `virtualscene-image` | 否，海报不在镜头视野 | — |
| `-virtualscene-poster` | 是启动参数，非 console 命令 | — |
| emulator console | 命令表无相机项 | — |
| **`webcam0`** | **可以，已实测** | **需宿主侧虚拟摄像头** |

## 3. 扫码方案的取舍

| 方案 | 状态 | 代价 |
| --- | --- | --- |
| **宿主装虚拟摄像头 + `webcam0`** | 通道已验，缺宿主软件 | 测试环境多一个外部依赖；但**换码不需重启**，单次开销接近零 |
| 每次重启换 `imagefile`/`videofile` | 已验证可行 | 冷启动约 50 秒，加预置配对约 90 秒 |
| 自建系统镜像预置虚拟相机 HAL | 理论可行 | 脱离官方 system-image，`profiles.json` 校验和体系失效 |
| 模拟器刷 Magisk + LSPosed | 理论可行 | 与「固定官方镜像 + 干净快照」冲突 |

**我的判断**：webcam0 现在是最有希望的一条。它不改系统镜像、不需要 root，
对 `scripts/emulator/` 那套可复现基线的冲击最小——只是宿主上多装一个软件，
而这件事可以写进环境准备文档，不污染 AVD 本身。

代价对比很清楚：装虚拟摄像头是**一次性**的宿主配置，之后每次换码接近零开销；
重启换文件是**每次**都要付 90 秒。扫码若在自动化流程里反复出现，前者明显划算。

**但这需要你确认是否接受引入宿主依赖。** 我没有自行安装任何软件。

## 4. 验证方式与范围

| 检查 | 结果 |
| --- | --- |
| `go build ./...` | 通过 |
| `go test ./internal/...` | 全部通过 |
| `gofmt -l internal cmd` | 无输出 |
| `node scripts/check-comment-coverage.mjs` | 通过，覆盖 493 个手写文件 |
| `node protocol/scripts/validate.mjs` | 通过，52 项 |
| MCP setText 端到端 | 见 1.5，真实设备回读确认 |
| MCP schema 守卫 | 见 1.5 |
| webcam0 接入 | 见 2.1，属性 + 双帧 MD5 + 肉眼三重确认 |
| webcam0 运行时换码 | 见 2.2，console 无命令、宿主无虚拟设备 |

**未覆盖**：
- webcam0 端到端换码未验（缺宿主虚拟摄像头软件）；
- `--description` 与 `--match-text` 两种定位方式仍只验了 `--resourceId`；
- emoji 与非 BMP 字符未验；
- API 30/33/34 与 1080×2400 未验（已约定暂缓）。

## 5. 复现方式

```powershell
# MCP setText
.\bin\aactl.exe mcp serve   # 然后按 MCP 协议调用 android_bridge_set_text

# webcam0 通道
& "E:\osdk-data\data\installs\android-sdk\emulator\emulator.exe" `
  -avd osdk-test-35 -camera-back webcam0 -no-snapshot-save
adb -s emulator-5554 shell getprop vendor.qemu.sf.fake_camera   # 应为 none

# 宿主摄像头清单
& "E:\...\emulator.exe" -webcam-list
```

探针脚本在 `E:\tmp\`（非仓库产物）。
