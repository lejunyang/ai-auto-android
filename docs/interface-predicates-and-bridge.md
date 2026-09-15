# 界面判据与桥侧对齐：wait/assert、flaky 定位与桥侧实测

本文记录本轮三件事：新增界面等待与断言（`ui.wait` / `ui.assert` 语义）、定位并修复
一个长期存在的 flaky 测试、以及在模拟器上完成 App Bridge 侧 `ui.foreground` 的
端到端验证（闭合前一轮遗留的最大验证缺口）。

前置事实见 `emulator-ui-automation.md` 与 `observe-capability-extension.md`，
本文不重复其内容。本文只陈述已验证事实与明确缺口，不代替 change spec。

**实测环境**：Windows + `aactl`（由 `cmd/aactl` 现场构建），AVD `osdk-test-35`
（`sdk_gphone64_x86_64`，Android 15 / API 35），桥 APK `dev.aiauto.android` 0.1.0
由本轮源码现场构建安装。

## 1. 界面判据：wait 与 assert

### 1.1 三侧能力面

| 能力 | CLI | MCP |
| --- | --- | --- |
| 等待判据成立 | `check wait` | `android_wait` |
| 断言判据成立 | `check assert` | `android_assert` |

两者共用 `internal/adb/predicate.go` 的同一个求值器，差别只在失败语义：
**wait 把"判据未成立"当作正常结果返回，assert 视为错误。**

这个区分是刻意的：自动化脚本里"等不到"通常要继续尝试其它路径，而"断言不成立"
必须让调用方知道当前状态与预期不符。分成两个工具而非一个带 mode 参数，
是为了让模型能预判调用会不会失败。

### 1.2 判据语义与桥侧保持一致

`kind` 与 `operator` 的取值和含义与 App Bridge 侧 `ReplayEngine.await` 完全一致：

| kind | 支持的 operator | 数据来源 |
| --- | --- | --- |
| `node` / `text` | exists、notExists、equals、contains、matches | UIAutomator XML |
| `package` / `window` | equals、contains、matches | 层级最外层节点的 package |
| `uiStable` | stable | 连续两次采样的结构指纹 |

但**数据源不同**：桥侧读无障碍节点树，直连侧解析 `uiautomator dump` 的 XML。
因此两侧能力对齐而不互相依赖 —— 直连模式无需安装任何设备端组件即可做自愈判断。

### 1.3 四处刻意的设计取舍

**不接受任何坐标字段。** 协议层 `target` 含 `recordedBounds`、`relativePoint`、
`normalizedScreenPoint`，但坐标判据在页面重排后会静默命中错误元素，
而断言的价值恰在于捕获这种偏离。MCP schema 层直接拒绝坐标字段（已实测）。

**空选择器被拒。** 全部选择器字段为空会匹配到界面上任意元素，让 `exists` 恒真、
`notExists` 恒假，断言失去意义。服务层与 MCP schema 两处都拦。

**文本与描述用「包含」，resourceId 与 className 用「相等」。**
`uiautomator` 的 text 常带前后空白或省略号，要求精确相等会让大量本该命中的元素落空；
而 resourceId 与 className 是结构性标识，模糊匹配会误命中。

**uiStable 的指纹不含 bounds。** 滚动惯性结束前 bounds 会持续微动，
纳入后 uiStable 几乎不可能成立。与桥侧 `snapshotFingerprint` 取同样的字段组合
（包名、类名、资源 id、文本）。

**复用既有 `ACTION_FAILED` 错误码，不新增。** 错误码是协议层枚举
（`protocol/schema/v1/error.schema.json`），新增会扩大稳定协议面，而断言不成立
本质就是"动作未达到预期效果"。调用方需要区分时看 `details` 里的 kind 与 operator。

### 1.4 assert 失败时返回观察到的实际状态

这是自愈能否成立的关键。实测 assert 失败的错误载荷：

```json
{
  "code": "ACTION_FAILED",
  "message": "The interface assertion did not hold before the timeout.",
  "retryable": true,
  "details": {
    "actualPackage": "com.android.settings",
    "kind": "package", "operator": "equals",
    "matchCount": 0, "polls": 1, "waitedMs": 2135
  }
}
```

只回一个布尔值时，调用方无法区分"立刻不成立"与"等到超时才放弃"，
也拿不到"当前实际在哪"，无法据此纠正。`waitedMs` 与 `polls` 也一并返回，
用于诊断超时是采样太慢还是判据真的不成立。

### 1.5 实测结果（真实设备）

**CLI 侧**：

| 命令 | 结果 |
| --- | --- |
| `check assert --kind package --expected com.android.settings` | satisfied，actualPackage 一致 |
| `check assert --kind package --expected com.nonexistent.app` | ACTION_FAILED，带 actualPackage |
| `check wait` 同上条件 | `ok:true, satisfied:false` |
| `check assert --kind node --operator exists` | satisfied，matchCount=1 |
| `check assert --kind node --operator notExists` | satisfied，matchCount=0 |
| `check wait --kind uiStable --stable-duration-ms 500` | satisfied，polls=3 |
| 空选择器 | INVALID_ARGUMENT，指明需约束哪些字段 |

**MCP 侧**：工具数 10。`android_assert` / `android_wait` 与 CLI 同条件结果一致；
传入坐标字段 `x`/`y` 被 schema 层 `oneOf` 拒绝；空选择器同样被拒。

### 1.6 途中修正的一处

`aactl check` 独立成命令而非并入 `observe` 或 `action`：它既不是纯观察
（会阻塞轮询直到超时），也不是动作（不改变设备状态）。混进任一侧都会让那一侧的
超时预期与副作用语义变得不一致。

## 2. flaky 测试：定位与修复

### 2.1 现象与量化

`internal/adb` 的 `TestWatchDevicesReportsInitialAndChangedMDNSEvents`
长期间歇失败，报 `ADB calls = 10, watch must remain bounded`。

**连跑 12 次：8 次失败（全部报 10 calls）、4 次通过 —— 失败率 67%。**

### 2.2 根因：断言上界没有余量，不是轮次失控

`WatchDevices` 每轮固定 2 次 ADB 调用（`Devices` + `MDNSServices`）。
测试参数 `Duration=4ms`、`Interval=1ms`，理论 4 轮 = 8 次，**恰好等于旧断言的上界 8**。

循环退出靠 `select` 在 deadline timer 与 ticker 之间竞争。**两者同时就绪时
Go 的 select 随机选一个** —— 选中 ticker 就会多跑一轮，变成 10 次。
这是 select 的语义，不是实现缺陷。

**判定方法**：只把上界从 8 放宽到 12，**参数一字不改**，重跑 8 次 → **8/8 全过**。
这证明是余量问题而非轮次失控。若实现真的失控，放宽上界不会让它稳定通过。

### 2.3 修法

断言改为「理论轮次 + 1 轮余量」= 10，并在注释里写明为何不取理论商、
实测数据是什么、以及超过 10 才说明真的失控。

**修后连跑 12 次：0 次失败。**

## 3. 桥侧 ui.foreground：端到端实测（缺口已闭合）

前一轮遗留的最大缺口是「桥侧只过了编译与单测，没在真实桥连接上调用过」。本轮闭合。

### 3.1 途中发现两处必须同步的白名单

`ui.foreground` 加进 `AndroidBridgeMethods` 的 `capabilities()` 与 `handle()` 后
仍然报 `PROTOCOL_ERROR: The requested bridge method is not supported.`，
即使 `bridge info` 已经正确列出该 capability。

**真因是方法名分散在两处白名单**：

| 位置 | 作用 | 本轮改动 |
| --- | --- | --- |
| `BridgeDispatcher.routeMethod` 的 `when` 分支 | 决定哪些方法可路由到 handler | 加 `METHOD_UI_FOREGROUND` |
| `DebugTestControlPlane.READ_ONLY_BRIDGE_METHODS` | debug 测试桥的只读白名单 | 加 `"ui.foreground"` |

「capability 已声明」与「方法可路由」是两件事，前者不含后者。
这一点在加任何桥方法时都要同时处理。

### 3.2 完整的桥连接链路（实测走通）

```
1. adb root + settings put secure enabled_accessibility_services  → 服务被系统 Bound
2. App 内「无障碍执行」页：填目标包名 + 勾选同意 + 点确认        → 披露已接受
3. App 内「桌面桥」页：点「开启桌面桥」                          → 显示 6 位一次性码
4. adb forward tcp:38383 + aactl bridge open --code <码>          → 会话建立（15 分钟）
5. aactl bridge foreground --device <serial>                      → 返回活动界面
```

第 1 步的关键判据是 `dumpsys accessibility` 的
`Bound services:{Service[label=AI 安卓自动化执行服务...]}` 与 `Crashed services:{}`，
以及 App 进程被拉起。**这同时闭合了上一轮"写 secure settings 后服务是否真被绑定"
的未知项：确实会绑定，无需人工点击系统设置。**

第 2 步不可跳过：系统层面服务已 Bound，但 App 要求先接受知情披露，
否则所有无障碍方法（含既有的 `ui.snapshot`）都返回
`PERMISSION_DENIED: The in-app accessibility disclosure has not been accepted`。
这是既有设计的用户主权环节，不是本轮引入。

### 3.3 验证结果

| 检查 | 结果 |
| --- | --- |
| `bridge info` capabilities | 含 `ui.foreground`，`available:true`，`permission:granted` |
| 前台是 App 自己 | `dev.aiauto.android` / `FrameLayout` / `source=activeWindow` |
| 切到设置后再读 | `com.android.settings` —— **实时的，不是缓存** |
| 同一时刻直连通道 | `com.android.settings` —— 两侧一致 |
| `ui.snapshot` 仍可用 | `packageName=com.android.settings`，children=1 |

**桥侧与直连侧的 `source` 字段不同**（`activeWindow` vs `resumedActivity`），
这是刻意的：调用方据此判断结果来源与可信度。

### 3.4 桥侧只加 foreground，Go 侧要同时补一条通道

`bridge.Service` 原先只有固定方法（`Snapshot` 发 `ui.snapshot`、
`Action` 发 `action.execute`），**没有调用任意桥方法的通道**。
因此本轮同时新增：

- `internal/bridge/service.go` 的 `Foreground(ctx, device, targetPackage)`
- `BridgeBackend` 接口加同名方法
- CLI `aactl bridge foreground`

## 4. 验证方式与范围

| 检查 | 结果 |
| --- | --- |
| `go build ./...` | 通过 |
| `go test ./internal/...` | **全部通过（含此前 flaky 的那条）** |
| 新增单测 | 判据求值 6 个 + 校验/匹配/指纹 3 个，全部通过 |
| flaky 复测 | 修前 8/12 失败；修后 12/12 通过 |
| `gofmt -l internal cmd` | 无输出 |
| `node scripts/check-comment-coverage.mjs` | 通过，覆盖 488 个手写文件 |
| `node protocol/scripts/validate.mjs` | 通过，52 项 |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL |
| CLI 端到端 | 见 1.5，真实设备 |
| MCP 端到端 | 见 1.5，真实设备，含 schema 层拒绝 |
| **桥侧端到端** | **见 3.3，真实桥连接** |

### 4.1 一处既有 lint 错误，与本轮无关

`:app:lintDebug` 报
`AndroidManifest.xml:24: Error: MainActivity must extend android.app.Activity [Instantiatable]`。

`git diff --stat HEAD -- '*MainActivity*' 'android/app/src/main/AndroidManifest.xml'`
**输出为空** —— 本轮未触碰这两处；本轮 Android 侧改动仅 3 个桥相关文件共 50 行。
**未处理。**

## 5. 待补与待确认

### 5.1 已知缺口（未实现）

| 项 | 说明 |
| --- | --- |
| `ui.longClick` / `ui.scroll` 暴露到 MCP | 协议层已定义，MCP 未暴露 |
| 中文文本输入 | 限制在 `internal/mcpserver/schemas.go` 与 `internal/adb/direct.go`，协议层无此限制 |
| 扫码运行时换码 | `imagefile` 覆盖不生效、`virtualscene-image` 取景不可控；`videofile:` 与 `webcam0` 未验证 |
| 证书注入 | 仅调研，本轮未实现（按要求）。结论见 `observe-capability-extension.md` 第 3 节 |
| 自动接受无障碍披露 | 本轮用 UI 自动化点过去，未做成可复用的预置脚本 |

### 5.2 待确认的设计决策

1. **判据轮询间隔取 400ms 是否合适。** `uiautomator dump` 单次实测数百毫秒，
   间隔更短会让采样比 dump 还快。但这也意味着 uiStable 的最小可检测静止时间受此约束。
2. **`check` 是否该支持组合判据。** 当前一次只能验一个判据。
   真实自愈可能需要"包名对且某元素存在"，现在要调两次。
3. **桥侧是否也提供 wait/assert。** 当前判据只在直连侧。桥侧有无障碍事件流，
   理论上能做到事件驱动而非轮询，但需要新的协议方法与事件通道。
4. **自动预置脚本放哪一层。** 无障碍授权 + 披露同意 + 开桥这条链本轮是手工脚本，
   若要纳入 `scripts/emulator/`，需要先定「哪些步骤允许自动化」的边界。

## 6. 复现方式

```powershell
# 判据（直连，无需桥）
.\bin\aactl.exe check assert --device emulator-5554 --kind package `
  --operator equals --expected com.android.settings
.\bin\aactl.exe check wait --device emulator-5554 --kind uiStable `
  --operator stable --stable-duration-ms 500 --timeout-ms 8000

# flaky 复测
for ($i=1; $i -le 12; $i++) {
  go test ./internal/adb/ -run TestWatchDevicesReportsInitialAndChangedMDNSEvents -count=1
}

# 桥侧（需先完成 3.2 的五步）
.\bin\aactl.exe bridge foreground --device emulator-5554
```

探针脚本在 `E:\tmp\`（非仓库产物）：`probe_predicate_e2e.py`、
`probe_watch_flaky.py`、`probe_watch_bound.py`、`probe_bridge_final.py`、
`probe_consent.py`。

**PowerShell 注意事项**（本轮各踩一次）：内联 `python -c` 会吞掉引号与反引号，
需要引号的一律写 `.py` 文件；`go test -run 'A|B'` 的 `|` 会被当管道，需用单引号包裹。
