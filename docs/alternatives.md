# 备选技术方案与阶段化落地

本文对应 Task 12.2，评估 MVP 之后可接入的 CLI、设备后端、观察、执行、
录制和人工接管方案。它不是功能承诺，也不改变协议 v1、Agent Skills 或 MCP
当前暴露的动作语义。

## 当前基线与接入原则

### MVP 状态

当前 MVP 已实现 Go `aactl`、类型化 ADB 观察/动作、Android
Accessibility 执行、App Bridge、AI 会话和语义录制回放。以下状态必须与
“已有基础能力”区分：

| 方案 | MVP 状态 |
| --- | --- |
| TypeScript/Node CLI | 未实现；当前 CLI 为 Go |
| Shizuku 后端 | 未实现 |
| Device Owner/DPC 后端 | 未实现 |
| UI Automator/设备农场 Provider | 未实现；仅已有本地直接 ADB 基础能力 |
| MediaProjection + OCR/模板视觉层 | 未实现 |
| ADB `getevent` 高保真录制 | 未实现 |
| 厂商互联、专用蓝牙桥、互联网远程 Provider | 未实现 |
| scrcpy 显示、控制和 OTG 恢复集成 | 未实现 |

尤其需要明确：**Android 和 ADB 没有可供本项目直接启用的通用蓝牙 ADB
transport**。官方 ADB 路线是 USB、模拟器通道和 TCP/IP/Wi-Fi；普通蓝牙配对
不会让设备出现在 `adb devices` 中。蓝牙只能作为厂商私有协议、项目自建专用桥
或 HID 控制链路，且上述能力均未在 MVP 中实现。

### 现有边界

- 协议 v1 的 `action.schema.json` 是上层动作语义源，未知动作和字段必须拒绝；
  `capability.schema.json` 用于运行时能力协商。
- Go 服务层的 `DirectBackend` 是安全、类型化的 ADB 子集；`BridgeBackend`
  只包含已建立 App 会话后的快照、动作和回放；`Automation` 是 CLI 与 MCP
  的共同入口。
- Android AI `AutomationProvider` 只负责“根据提示规划下一动作”和连通性测试，
  它是模型 Provider，不是设备 Provider。
- Android 会话通过 `SessionObserver`、`SessionPlanner`、`SessionExecutor`
  分离观察、规划与执行；录制回放通过 `ReplayGateway` 隔离快照和动作执行。

规格中的 `DeviceProvider`、`Observer`、`ActionExecutor` 是后续演进边界。落地时
应以适配器复用上述现有接口，不把传输、权限或厂商生命周期塞进 AI
`AutomationProvider`：

- `DeviceProvider`：发现设备、读取设备信息和 capabilities，管理连接生命周期；
  设备 ID 对上层是不透明字符串。
- `Observer`：按设备和观察类型返回有界、可脱敏、可校验的观察结果。
- `ActionExecutor`：只接收协议白名单中的类型化动作，执行前仍经过设备选择、
  目标包和风险策略。
- 配对、提权、企业预配、云凭证和信任撤销属于管理面，不暴露为 Agent 动作，
  也不进入通用 MCP 工具。

新增后端必须先报告 capability，再由公共服务层路由。已有 Agent Skills 命令、
CLI JSON 信封、稳定错误码、确认规则和脚本动作保持不变；无法无损映射的底层能力
应保持不可用，而不是扩成任意 shell 或原始远程控制。

## 1. TypeScript/Node CLI 与 Go CLI

**结论：** 保持 Go 为默认 CLI。只有在插件生态、Node 侧 MCP 集成或团队维护效率
带来的收益经原型验证显著高于分发和迁移成本时，才启动 TypeScript 替换；优先考虑
并存的协议客户端或插件进程，而不是一次性重写。

### 适用场景

- 团队主要使用 TypeScript，需要快速复用 JSON Schema、MCP 和 Web 生态。
- Provider 插件变化频繁，进程外扩展比编译进 Go 主程序更重要。
- 产品本来就保证 Node 运行时，或接受为每个平台构建并签名 Node Single
  Executable Application（SEA）。

### 非适用

- 要求无运行时依赖、体积和启动路径可预测的单文件 macOS/Windows CLI。
- ADB 子进程、信号取消、stdio MCP 和跨平台制品已经稳定，重写没有用户收益。
- 仅为共享类型而迁移；协议 Schema 和 fixture 已能跨语言共享，不要求同一语言。

### 权限与安全

- 无论语言如何，都必须以参数数组调用 ADB，禁用 shell 拼接，保留超时、输出上限、
  路径校验和敏感值脱敏。
- TypeScript 方案增加 npm 依赖与 SEA 构建链供应链面；必须锁定依赖、生成 SBOM、
  扫描许可证与漏洞，并分别完成 macOS/Windows 签名。
- SEA 仍处于 active development；原生扩展、动态模块、跨平台代码缓存和快照有额外
  限制，不能把“单可执行文件”视为与 Go 静态制品完全等价。
- Node 权限模型只能作为纵深防御，不能替代动作白名单和公共风险策略。

### 接口接入点

- TypeScript 实现必须消费 `protocol/schema/v1` 和同一 fixture，不复制动作模型。
- 对外保持 `aactl` 命令、JSON 信封和 MCP tool 名称；内部实现等价的
  `DeviceProvider`、`Observer`、`ActionExecutor`。
- 迁移期通过现有 Go `Automation` 建立黑盒契约；App Bridge NDJSON JSON-RPC
  和 Android `AutomationProvider` 不随 CLI 语言变化。
- 若只需要扩展生态，优先新增受版本约束的进程外 Provider RPC，由 Go 核心继续执行
  校验和风险策略。

### 阶段化落地步骤

1. 冻结协议 fixture、CLI/MCP 金丝雀用例、错误码和退出码，记录 Go 基线性能。
2. 用 TypeScript 实现只读的 `devices list`、`device info` 和观察原型，不执行动作。
3. 接入同一 fake ADB 与 Bridge 契约，逐项达到 Go/Node JSON 语义一致。
4. 实现类型化动作、取消、并发限制和 stdio MCP，执行差分测试及恶意参数测试。
5. 构建并签名各平台 SEA，与 Go 制品并行灰度；保留显式回退开关。
6. 只有兼容、分发、性能和维护指标全部达标后才切换默认入口，再按版本窗口退役 Go。

### 验收指标

- 协议合法/非法 fixture、CLI/MCP 契约和安全测试 100% 与 Go 基线一致。
- 未知动作、额外字段和恶意参数 100% 在启动 ADB 子进程前被拒绝。
- macOS arm64/amd64、Windows amd64 制品可重复构建、签名和校验，目标机无需预装
  Node。
- 典型命令冷启动 p95 不超过 Go 基线的 2 倍，空闲内存有明确预算且无失控子进程。
- 双栈灰度期间同一输入的稳定字段无差异，回退演练可在一个发布周期内完成。

### 主要来源

- [Node.js Single executable applications](https://nodejs.org/api/single-executable-applications.html)
- [Go 构建目标与 GOOS/GOARCH](https://go.dev/doc/install/source#environment)
- [项目协议兼容规则](../protocol/README.md)

## 2. Shizuku 高级用户后端

### 适用场景

- 侧载或高级用户环境，希望在不永久 root 的设备上获得 ADB shell 身份可用的部分
  系统 API。
- 需要把少量已审计的包管理、进程控制或系统查询封装成类型化能力，且用户愿意安装、
  启动并授权 Shizuku。
- Android 11 及以上设备可由用户通过无线调试启动服务；旧版本可接受电脑 ADB 启动。

### 非适用

- 面向普通消费者的零配置体验、Google Play 默认分发或不能要求开发者选项的环境。
- 需要稳定开机自启的无人值守设备；非 root 启动通常在重启后需要重新执行。
- 把 Shizuku 当成 root、系统签名或权限绕过。实际权限取决于启动身份、系统版本和
  厂商限制，不保证每个系统 API 可用。

### 权限与安全

- App 必须显式请求 Shizuku 授权，并显示当前 Binder、UID、启动方式和能力探测结果；
  撤销授权或 Binder 死亡后立即使 capability 失效。
- 非 root Shizuku 通常运行在 ADB `shell` 身份；root/Sui 是更高风险的独立部署，
  不得自动升级或静默回退。
- 只允许逐项审计的 Binder/API 调用或固定命令模板，禁止向 Agent 暴露 Shizuku
  user service、`rish` 或任意 shell。
- 对包安装、权限、系统设置等高风险能力默认不注册；若企业版本确需使用，应在管理面
  单独审批、审计，并与普通自动化动作隔离。
- 厂商可能额外限制 ADB 权限或后台运行，应失败关闭并给出可操作原因，不能尝试绕过。

### 接口接入点

- 在 Android App 内实现 Shizuku 版 `SessionObserver`/`SessionExecutor` 适配器，
  保留现有 `AutomationRiskPolicy` 和 `ProviderActionParser`。
- 经 App Bridge 只暴露能力协商后可用的类型化动作；Go 侧继续走 `BridgeBackend`，
  不需要知道 Binder 细节。
- 若 Shizuku 只补充设备信息或截图，则作为 `Observer` 能力；若补充动作，则实现
  `ActionExecutor`，不得改变协议 v1 动作含义。
- AI `AutomationProvider` 不直接依赖 Shizuku；规划器只能看到经脱敏的观察和可用
  capability。

### 阶段化落地步骤

1. 加入仅诊断的 Shizuku 状态页，探测安装、Binder、授权、UID 和重启失效。
2. 实现一个只读能力原型，与 Accessibility/ADB 结果做兼容性和厂商矩阵测试。
3. 增加最小类型化动作适配器，复用现有解析、目标包约束、确认和审计。
4. 在 Bridge hello 中发布细粒度 capability、权限状态和不可用原因。
5. 覆盖 Binder 死亡、授权撤销、系统重启、厂商限制和版本升级的恢复流程。
6. 通过侧载高级版灰度；不把 Shizuku 变成 MVP 或普通用户的硬依赖。

### 验收指标

- 授权撤销或 Binder 死亡后 500ms 内停止提交新动作，并将能力标为不可用。
- 支持矩阵中的设备重启、重新授权和 Shizuku 升级流程均可恢复，失败不回退任意 shell。
- 类型化动作与 Accessibility/ADB 后端的成功结果、错误码和审计字段满足同一契约。
- 未授权调用、额外参数、目标包逃逸和高风险动作测试 100% 被拒绝。
- 日志、崩溃报告和 Bridge 响应不包含配对码、token、输入明文或 Binder 敏感数据。

### 主要来源

- [Shizuku 用户启动与厂商限制指南](https://shizuku.rikka.app/guide/setup/)
- [Shizuku API 开发指南](https://github.com/RikkaApps/Shizuku-API)
- [Android ADB](https://developer.android.com/tools/adb)

## 3. Device Owner/DPC 企业专用设备后端

### 适用场景

- 企业自有、工位机、展陈、仓储或 kiosk 等 fully managed/dedicated devices。
- 设备可在首次设置或恢复出厂后纳管，需要统一应用部署、锁定任务模式、托管配置、
  网络/更新策略和合规状态。
- 组织已有 EMM、设备注册、管理员身份、密钥轮换、远程擦除和审计体系。

### 非适用

- 已投入个人使用且不能清除数据的 BYOD；Device Owner 不是普通运行时权限，不能由
  用户在已配置设备上随手开启。
- 期望 DPC 直接完成任意第三方 App UI 操作。DPC 管理设备政策和应用生命周期，
  普通 UI 观察/动作仍需 Accessibility、UI Automator 或受控应用接口。
- 没有组织管理面、设备所有权、退出纳管和数据处置流程的消费者产品。

### 权限与安全

- 预配必须验证组织身份和注册令牌；测试用 `dpm set-device-owner` 不作为生产部署
  流程。
- Device Owner 权限影响整台设备，政策变更采用管理员 RBAC、双人审批、签名配置、
  审计和可回滚版本。
- Agent 动作面不得直接暴露安装、卸载、授权、擦除、证书、VPN 或系统更新 API。
  这些操作只属于企业管理面。
- 设备退役必须撤销凭证、清理企业数据并验证退出状态；禁止留下可复用的 Bridge
  token 或云控制密钥。
- DPC 仅降低环境漂移，不取消目标包白名单、高风险确认和紧急停止。

### 接口接入点

- `DeviceProvider` 负责返回纳管 ID、合规状态和 DPC capabilities；凭证与 EMM
  生命周期留在管理面。
- DPC 先负责准备确定性环境，再由现有 Android `SessionObserver`/
  `SessionExecutor` 或 `ReplayGateway` 执行协议 v1 UI 动作。
- 政策状态可作为 `Observer` 的只读前置条件，但不得伪装成 UI 节点或交给模型自由
  修改。
- App Bridge 仍只监听 loopback；互联网 EMM 通道与本地 Bridge 是两个信任域。

### 阶段化落地步骤

1. 用 Test DPC/测试 DPC 建立恢复出厂、二维码或零接触预配实验环境。
2. 定义最小政策基线：目标 App、锁定任务、更新窗口、网络和证书，不接入 AI 会话。
3. 实现 `DeviceProvider` 合规观察和 capability 映射，验证离线、过期政策和退役。
4. 在专用设备上接入现有 Accessibility 执行和录制回放，保持动作与确认语义不变。
5. 接入 EMM RBAC、审计、凭证轮换、远程停用和灾难恢复。
6. 先在单一设备型号和单一业务流程试点，再扩展 API 级别与 OEM 矩阵。

### 验收指标

- 新设备从恢复出厂到合规可用的预配成功率不低于 99%，失败有可恢复状态。
- 非管理员和 Agent 无法调用政策变更；管理面敏感操作审计覆盖率 100%。
- 断网时按明确的离线策略运行，超过租约后停止新自动化而不是无限信任旧政策。
- 退出纳管演练可清除组织凭证和数据，设备不会残留可用控制通道。
- 同一业务脚本在纳管设备矩阵上的成功率和错误码满足既有回放基线。

### 主要来源

- [构建设备政策控制器 DPC](https://developer.android.com/work/dpc/build-dpc)
- [专用设备](https://developer.android.com/work/dpc/dedicated-devices)
- [DevicePolicyManager API](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)

## 4. ADB/UI Automator 测试与设备农场后端

### 适用场景

- 发布前在多 API、分辨率、语言和 OEM 设备矩阵上运行确定性回归。
- 需要跨应用或系统界面的测试代码，且允许安装 instrumentation/test APK。
- 云设备农场提供测试矩阵、实体/虚拟设备、日志、截图、视频和 CI 接口。
- 本地实验室需要统一调度 USB/Wi-Fi ADB 设备并隔离测试任务。

### 非适用

- 消费者设备上的常驻生产自动化；UI Automator 首先是 instrumentation 测试框架。
- 需要毫秒级交互的公网云真机。排队、启动、上传和取件使其更适合批处理。
- 假设设备农场允许 root、Device Owner、长期 Bridge、任意入站端口或持久状态；
  各供应商限制必须逐项探测。

### 权限与安全

- 本地 ADB 沿用显式 serial、RSA 授权、参数数组、动作白名单和测试结束清理。
- 测试 APK 只使用测试所需权限；签名密钥、云凭证和被测账号进入 CI secret，
  不写入 APK、脚本或测试产物。
- 上传云端前对截图、层级、日志和视频分级脱敏，设置保留期、区域和删除验证。
- 多租户设备必须在任务前后恢复快照或清除数据；发现上一个租户残留立即隔离设备。
- UI Automator 能操作系统 UI，但仍不得自动确认支付、真实授权或生产账号高风险动作。

### 接口接入点

- 本地实验室可直接以现有 `DirectBackend` 适配 `DeviceProvider`、`Observer` 和
  `ActionExecutor`。
- UI Automator runner 实现测试侧 `Observer`/`ActionExecutor`，动作仍映射到协议
  v1 的 click、setText、scroll、wait 等语义。
- 云设备农场通常是异步 job Provider：`DeviceProvider` 管理 job/device ID，
  `Observer` 读取产物；只有供应商支持实时会话时才注册在线 `ActionExecutor`。
- 对不支持实时动作的农场，将 `AutomationScript` 编译成 instrumentation 测试批次，
  不伪装成交互式 MCP 设备。

### 阶段化落地步骤

1. 把协议 fixture 和核心回放脚本转为本地 Emulator UI Automator 冒烟测试。
2. 建立本地设备池 Provider，完成独占租约、健康检查、隔离和清理。
3. 选择一个设备农场完成单设备单脚本上传、运行、产物和稳定错误映射。
4. 增加 API/OEM/语言/方向矩阵、分片、重试预算和 flaky 分类。
5. 若供应商提供实时控制，再单独验证会话租约、观察延迟和动作幂等；否则保持批处理。
6. 接入 CI 门禁和成本配额，定期验证产物删除、凭证轮换与设备污染。

### 验收指标

- 同一脚本在本地 Emulator、至少一台实体设备和云矩阵中使用同一动作语义。
- 设备租约互斥率 100%，任务后账号、文件、App 数据和 Bridge 会话清理率 100%。
- 关键流程连续 20 次运行成功率不低于 95%；flaky 用例能区分产品、设备和基础设施
  原因。
- 超时、排队失败、设备离线和测试断言映射为稳定错误，不触发无限重试。
- 截图、日志、视频和层级产物均有保留期、访问审计和删除验证。

### 主要来源

- [Android UI Automator](https://developer.android.com/training/testing/other-components/ui-automator)
- [Firebase Test Lab](https://firebase.google.com/docs/test-lab)
- [Android ADB](https://developer.android.com/tools/adb)

## 5. MediaProjection + OCR/模板匹配视觉层

### 适用场景

- Canvas、游戏、视频流、WebView 或自绘控件无法提供可靠 Accessibility 节点。
- 需要用 OCR 找文字区域，或用受版本控制的模板定位稳定图标。
- 视觉只作为语义树缺失时的观察补充，并允许低置信度时请求人工接管。

### 非适用

- Accessibility 已提供稳定 resource ID、文本和节点动作的普通表单；视觉成本更高且
  更脆弱。
- `FLAG_SECURE`、DRM、受保护窗口或组织政策禁止捕获的内容。
- 期望 OCR 理解任意 UI 语义，或用单一模板跨主题、语言、密度、动画和 OEM 稳定工作。
- 无法接受每次 MediaProjection 会话由用户授权和前台通知的无人值守消费者场景。

### 权限与安全

- MediaProjection 每个会话都必须取得用户同意；Android 14 及以上按一次 token/
  一次 `createVirtualDisplay()` 管理，并声明 `mediaProjection` 前台服务类型。
- 投影状态、停止入口和 `onStop()` 资源释放必须对用户可见；不得规避系统捕获提示。
- 默认端侧 OCR，帧只保存在内存；如需云 OCR，必须另行同意、加密、最小化上传并设置
  保留期。
- 密码、验证码、支付、通知和其他敏感区域在进入模型、日志或产物前遮罩；安全窗口返回
  空白时不得尝试绕过。
- 视觉结果只生成带置信度和坐标变换证据的候选；最终动作仍经过目标包、风险策略和
  屏幕边界校验。

### 接口接入点

- 视觉实现为 `Observer` 装饰层：先消费 MediaProjection/ADB screenshot，再输出
  OCR 块、模板候选和当前显示元数据。
- 协议 v1 不新增“视觉点击”动作。候选最终映射为已有 `normalizedScreenPoint` 或
  `recordedBounds + relativePoint`，保持 `ui.click`/`ui.tap` 语义。
- 在 Android 会话中补充 `SessionObservation.uiSummary`；在 Bridge 中用细粒度
  `vision.ocr`、`vision.template` capability 控制可用性。
- 不在 `AutomationProvider` 内直接做截图或 OCR，便于替换视觉引擎并独立执行隐私
  过滤和性能预算。

### 阶段化落地步骤

1. 使用已有 ADB screenshot 建离线标注集，先评估 OCR/模板，不引入运行时权限。
2. 实现端侧 OCR，只输出文字、边界、语言和置信度，接入统一遮罩。
3. 增加模板注册表，记录来源、版本、主题、密度、缩放范围和淘汰策略。
4. 在 Android App 接入 MediaProjection、前台服务、旋转/尺寸回调和显式停止。
5. 与 Accessibility 结果融合：语义优先，视觉回退，歧义或低置信度失败关闭。
6. 最后才允许视觉候选驱动类型化动作，并先在只读或测试账号流程灰度。

### 验收指标

- 标注集分别报告 OCR 字符/词准确率和目标框 precision/recall，不以单一成功案例验收。
- 支持设备上单帧视觉观察 p95 小于 500ms，内存有上限，积压时丢旧帧而非无限排队。
- 坐标在旋转、缩放、系统栏和应用窗口共享下误差不超过目标短边的 10%。
- 低于阈值或多个候选接近时 100% 不执行；高风险页面不发生自动视觉点击。
- 停止投影、锁屏、权限撤销和进程回收后无残留 VirtualDisplay、Surface 或敏感帧。

### 主要来源

- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [ML Kit Android 文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [OpenCV Template Matching](https://docs.opencv.org/4.x/de/da9/tutorial_template_matching.html)
- [WindowManager FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)

## 6. ADB `getevent` 高保真录制插件

### 适用场景

- 实验室对多指、压力、触点轨迹、物理按键和精确时序进行驱动或手势回归。
- 语义 Accessibility 事件不能还原底层输入，但设备型号、系统镜像和触摸驱动固定。
- 录制结果用于诊断、离线分析，或可降级转换成协议 v1 的 tap/swipe 动作。

### 非适用

- 普通非 root 消费者设备、云真机或不能控制 SELinux 策略的环境。
- 跨型号直接回放原始 event code。`/dev/input/event*` 编号、轴范围、多点触控协议和
  驱动行为都可能变化。
- 需要语义选择器、文本输入意图或等待条件；原始事件不知道用户点击了哪个业务控件。
- 将原始 `sendevent` 暴露给 Agent 或通用 MCP。

### 权限与安全

- AOSP 文档示例使用 `adb shell su -- getevent`。部分设备可能允许 shell 读取，
  但不能作为产品保证；直接读写 event 节点和可靠 `sendevent` 回放通常需要 root
  或定制系统权限。
- SELinux 对 root 进程同样执行强制访问控制；Linux UID 为 0 不代表目标域可读写
  `/dev/input/event*`。必须同时探测 DAC 权限和 SELinux denial。
- 捕获进程只读打开节点；禁止 `chmod 666`、全局 permissive 或修改量产设备策略。
- 原始事件可包含键盘、扫码枪等敏感输入，必须按设备过滤、加密存储、短期保留并在
  导出前脱敏。
- 原始注入可绕过 UI 级确认。回放仅限隔离测试设备、显式设备指纹和人工启动，
  默认不进入自动化动作面。

### 接口接入点

- `getevent` 作为录制 sidecar，不扩展 `DirectBackend` 为任意 shell；启动参数、
  event 节点和输出格式都由固定模板控制。
- 解析器将时间戳、设备描述、轴范围和事件帧写入版本化原始产物；能可靠识别的单指
  手势再转为现有 `RecordedAction`。
- 可表示的 tap/swipe 继续通过现有 `ReplayGateway`/`ActionExecutor` 回放；多指、
  压力或设备专属事件保持“仅原始产物”，不伪造成协议 v1 动作。
- 若未来确需原始回放，应建立独立、不可由 Agent 枚举的实验室管理接口和 capability，
  不能复用 `action.execute`。

### 阶段化落地步骤

1. 在 root Emulator/工程机上实现只读 capability 探测和 `getevent -pl` 设备清单。
2. 采集 `-lt` 流，按 `SYN_REPORT` 分帧，保存单调时钟、轴范围、方向和设备指纹。
3. 为单指 tap/swipe 建立规范化转换，与屏幕录制和 Accessibility 事件对时。
4. 增加多点触控解析，但先只做可视化和离线验证，不做注入。
5. 在固定镜像实验室评估受控 `sendevent`；每次回放前复核设备/驱动指纹和 SELinux。
6. 只有原始回放有不可替代价值时才保留插件，否则生产路线只使用转换后的语义动作。

### 验收指标

- 长时间录制事件解析丢帧率低于 0.1%，`SYN_REPORT` 和时间戳单调性可验证。
- 标注手势的触点数、轨迹和持续时间重建准确率不低于 99%。
- event 节点或轴范围变化时 100% 阻止原始回放，不按旧编号猜测。
- 捕获模式不产生任何 event 节点写操作；无 root/SELinux 权限时返回明确、稳定错误。
- 转换后的 tap/swipe 通过协议 fixture 和现有风险策略，原始数据不进入模型或普通日志。

### 主要来源

- [AOSP getevent 工具](https://source.android.com/docs/core/interaction/input/getevent)
- [Linux 多点触控协议](https://docs.kernel.org/input/multi-touch-protocol.html)
- [Android SELinux](https://source.android.com/docs/security/features/selinux)

## 7. 厂商互联、专用蓝牙桥与互联网远程 Provider

### 适用场景

- 厂商为特定设备提供受支持的跨设备 SDK、企业控制 API 或硬件配件协议。
- 专用蓝牙硬件桥只需低带宽控制、状态或故障恢复，并能同时部署两端固件/应用。
- 互联网远程设备实验室、门店或企业设备需要中心调度、租约、审计和区域路由。
- 希望在不改变 Agent Skills 动作语义的情况下替换底层设备发现、观察和执行通道。

### 非适用

- 把普通蓝牙配对当作 ADB。**蓝牙没有通用 ADB transport**，不能复用
  `adb devices`、ADB RSA 授权或 App Bridge 的 ADB forward。
- 用 BLE 承载持续高帧率屏幕流、完整 UI 树或大文件；带宽、MTU、后台限制和丢包使其
  不适合作为通用视觉通道。
- 把只支持 HID 的桥声明为完整 Provider；没有观察能力时不能进行自主闭环。
- 将当前只监听 loopback、依赖短期 token 的 App Bridge 端口直接暴露到公网。

### 权限与安全

- 厂商 SDK 必须做许可证、版本、数据出境、停服和供应链评估；运行时 capability
  探测不能只看品牌或版本号。
- 蓝牙系统配对不是应用层授权。专用桥还需要双向认证、会话密钥、重放保护、固件签名、
  丢失设备撤销和物理绑定。
- Android 12 及以上蓝牙扫描/连接使用细分运行时权限；拒绝权限时 Provider 必须不可用。
- 远程 Provider 使用设备身份、短期租约、mTLS 或等价双向认证、最小 RBAC、速率限制、
  幂等键和完整审计；租约到期立即停止新动作。
- 任何远程通道都必须在服务端和设备端重复执行动作白名单、目标包和风险校验，不能只
  信任云端模型。

### 接口接入点

- 每种实现独立适配 `DeviceProvider`、`Observer`、`ActionExecutor`，使用命名空间
  capability，例如 `vendor.snapshot`、`bluetooth.hid`、`remote.action`。
- Provider 返回自己的不透明设备 ID，由公共层映射为 CLI `--device`；不得伪造 ADB
  serial 或向 `DirectBackend` 注入非 ADB 参数。
- 专用蓝牙桥若只有 HID 控制，只注册受限 `ActionExecutor`，不注册 `Observer`；
  上层因此不得启动自主会话，只允许人工恢复或预验证脚本。
- 互联网路线通过网关转发版本化 Provider RPC；网关不能绕过本地 App 的
  `SessionExecutor`、`ReplayGateway` 和能力协商。

### 阶段化落地步骤

1. 定义 Provider conformance suite：发现、能力、观察、动作、取消、租约和错误映射。
2. 先选一个厂商 SDK 做只读发现/观察原型，验证升级、离线和停服降级。
3. 用开发板完成专用蓝牙桥概念验证，只做固定按键/HID 和应用层双向认证。
4. 建立互联网网关，先开放只读设备信息和观察，再加入短租约和审计。
5. 逐项启用类型化动作，执行设备端二次校验、幂等和断线停止测试。
6. 完成密钥轮换、设备遗失、租户隔离、区域故障和供应商退出演练后再扩大部署。

### 验收指标

- Provider conformance suite 100% 通过；缺少观察能力的蓝牙桥无法启动 AI 自主会话。
- 蓝牙重放、伪造对端、降级和丢失设备测试均失败关闭；固件升级支持签名和回滚。
- 远程动作端到端 p95 延迟、可用性和断线策略按业务 SLO 验收，租约过期后无新动作。
- 多租户设备、凭证、日志和产物隔离率 100%，所有控制动作可关联操作者和请求 ID。
- 厂商 SDK 不可用或停服时返回 capability unavailable，不静默切换到低安全通道。

### 主要来源

- [Android ADB：USB 与 Wi-Fi/TCP/IP 连接](https://developer.android.com/tools/adb)
- [Android 蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Bluetooth Core Specification](https://www.bluetooth.com/specifications/specs/core-specification/)
- [NIST Zero Trust Architecture](https://csrc.nist.gov/publications/detail/sp/800-207/final)

## 8. scrcpy 显示、控制与 OTG 恢复

### 适用场景

- 开发、演示、故障排查和高风险步骤的人工接管，需要低延迟显示并使用电脑键鼠。
- ADB 已授权时，需要跨 macOS、Windows、Linux 的临时镜像、控制和录屏工具。
- USB 调试不可用但设备可通过 USB 接入时，用 OTG/AOA HID 进行盲操或恢复准备；
  该模式只提供键鼠/手柄控制。

### 非适用

- 把 scrcpy 当作语义观察器或确定性回放引擎。画面和人工输入本身没有节点选择器、
  等待条件或业务断言。
- 期望 OTG 同时镜像屏幕。scrcpy OTG 模式不使用 ADB，但会禁用视频和音频，且只在
  USB 连接下工作。
- 屏幕完全不可见、设备未解锁且没有可验证恢复路径时依赖盲操执行敏感操作。
- 用未经固定版本和校验的第三方 scrcpy 分发包进入发布制品。

### 权限与安全

- 普通模式依赖 ADB USB/TCP/IP 调试授权；部分厂商控制输入还要求单独的
  “USB debugging (Security Settings)”。
- 只从 Genymobile 官方仓库取得并固定版本，校验发布签名/哈希和许可证；子进程使用
  固定参数数组，不经 shell。
- 显示、剪贴板、录屏和音频都可能泄露敏感数据，默认关闭非必要转发，人工接管期间
  显示醒目标识并写入审计。
- OTG/AOA 表现为物理 HID，可能触发真实按键和指针动作；仅允许本地人工启动，不作为
  Agent 的隐式降级路径。
- scrcpy 退出、ADB 断开或会话停止后必须清理子进程、临时文件和操作员租约。

### 接口接入点

- 显示功能是 Operator Sidecar，放在 `DeviceProvider` 生命周期旁，不替代
  `Observer`。只有经过明确设计的帧采集器才可向视觉 `Observer` 提供画面。
- 普通控制如需程序化接入，必须映射到已有类型化 `ActionExecutor`；默认方案仍是
  现有 ADB/Accessibility 执行，不解析 scrcpy GUI 输入作为 Agent 动作。
- OTG 只发布 `operator.recovery`/`usb.otg.hid` 类受限 capability，不注册完整
  `Observer` 或自动回放能力。
- App Bridge 和 AI `AutomationProvider` 不依赖 scrcpy；人工接管通过会话状态机暂停
  自动执行，归还控制权后重新观察。

### 阶段化落地步骤

1. 先编写人工运行和安全清理说明，固定官方 scrcpy 版本，不改 CLI。
2. 增加可选进程管理器：显式设备、显示参数、健康检查、退出码和无孤儿进程清理。
3. 与会话状态机联动人工接管：暂停、启动显示、操作员确认归还、重新观察。
4. 按需加入录屏产物，但默认关闭音频/剪贴板并实施保留期和脱敏。
5. 单独验证 OTG/AOA 恢复手册，覆盖无 USB 调试、多个 USB 设备和 Windows 驱动问题。
6. 只有确有收益时才评估帧流视觉接入；不把 scrcpy 控制协议变成新的上层动作模型。

### 验收指标

- 支持平台和设备矩阵中镜像启动成功率不低于 99%，交互画面延迟 p95 小于 150ms。
- 指定设备错误、多设备、ADB 未授权和进程崩溃均有明确错误，测试后无孤儿进程。
- 人工接管期间自动执行提交数为 0；归还后必须重新观察，不能沿用旧 UI 快照。
- OTG 验收明确证明“无需 USB 调试、仅 USB、无视频/音频”，且不能被 AI 会话自动选择。
- 产物、剪贴板和音频默认最小化，开启时有用户提示、审计和删除验证。

### 主要来源

- [scrcpy 官方仓库与功能说明](https://github.com/Genymobile/scrcpy)
- [scrcpy 官方 OTG 文档](https://github.com/Genymobile/scrcpy/blob/master/doc/otg.md)
- [Android Open Accessory 2.0 HID](https://source.android.com/docs/core/interaction/accessories/aoa2)
- [Android ADB](https://developer.android.com/tools/adb)

## 下一阶段优先级与新增方案

本节是尚未实现的下一阶段路线图，不代表当前产品能力或完成承诺。实施时继续复用
协议 v1、显式设备选择、类型化动作、风险门和脱敏审计；不得为提高覆盖率而开放
任意 shell、绕过系统授权或降低选择器置信度阈值。

### A. 优先级

| 优先级 | 方案 | 决策与原因 |
| --- | --- | --- |
| P0：现在做 | UI Automator 与固定 AVD 控制面 | 自动运行跨 App、系统导航和无障碍场景，优先减少人工验证；固定 API、镜像、分辨率、语言、导航模式和快照 |
| P0：现在做 | 无线 ADB 生命周期 | 补齐 Android 11+ 配对发现、`pair`/`connect`、mDNS、可信设备档案、端口变化和断线重连；继续沿用官方 ADB 信任边界 |
| P0：现在做 | 录制编辑器 | 允许人工修正语义选择器、坐标、等待、重试、顺序和失败策略，是 WebView、Canvas 和动态真实 App 可维护回放的前提 |
| P0：现在做 | WebView/Canvas fixture | 用离线、可复位、可断言的受控页面建立语义、混合和纯视觉测试基线 |
| P0：现在做 | 视觉 `Observer` | 在语义树不可用时提供受限截图、候选区域、置信度和坐标证据；实际执行仍走现有类型化动作和风险策略 |
| P0：现在做 | 真实 App 兼容矩阵 | 用固定版本的哔哩哔哩、抖音和小黑盒验证多页面、多动作与动态 UI，但不作为完全确定性的 CI 门禁 |
| P1：条件推进 | App LAN QR Bridge | 当无线 ADB 向导仍不能满足低门槛连接时，实现 App 主动出站的同局域网加密 Bridge；先完成威胁模型和跨平台网络诊断 |
| P1：条件推进 | 设备农场 Provider | 本地 API 矩阵稳定后接入一个设备农场，扩大 OEM 和系统版本覆盖；复用同一脚本、错误码和结果模型 |
| P1：条件推进 | scrcpy sidecar | 仅用于人工接管、演示和恢复，不作为语义观察器、录制器或 Agent 默认执行内核 |
| P2：按部署模型 | Shizuku | 仅高级侧载版确需额外系统能力时实施；不能变成普通用户依赖，也不能向 Agent 暴露任意 shell |
| P2：按部署模型 | Device Owner/DPC | 仅企业自有、专用或 kiosk 设备具备预配和管理面时实施；不适用于普通个人设备 |
| P2：按业务模型 | 互联网远程 Provider | 只有门店或实验室出现明确租约、多租户、区域和审计需求后建设 |
| P3：暂缓 | TypeScript CLI 重写 | 当前 Go CLI、MCP、跨平台制品和安全边界已稳定，重写没有近期用户收益 |
| P3：暂缓 | `getevent`/`sendevent` | 依赖 root、固定驱动和 SELinux 策略，仅适合隔离实验室，不能进入通用 Agent 动作面 |
| P3：暂缓 | 厂商互联与专用蓝牙桥 | 需要明确 OEM SDK 或专用硬件场景；普通蓝牙不是通用 ADB transport |

### B. 无线连接与扫码的两层设计

#### 第一层：官方无线 ADB

近期先完善 Android 11+ 官方无线调试路线：

1. 通过 `_adb-tls-pairing._tcp` 与 `_adb-tls-connect._tcp` mDNS 服务发现配对和连接
   端点。
2. 使用现有类型化 `adb pair` 与 `adb connect`，从交互式 stdin 读取系统显示的
   配对码，禁止把配对码放入 argv、日志或任务文档。
3. 配对后校验设备身份、transport 和 capabilities，保存不含秘密的可信设备档案；
   端口变化或短暂断网后通过 mDNS 恢复，禁止按模糊型号串错设备。
4. 在 macOS 和 Windows 覆盖多网卡、VPN、mDNS 被禁、网络切换、防火墙和多设备
   冲突；无法恢复时返回可操作错误，不自动降级到未加密 `adb tcpip 5555`。

本项目不逆向、不依赖，也不承诺兼容 Android Studio 的 QR 配对私有实现。系统无线
调试页提供的配对码与连接端点是稳定基础；未来若 Android SDK 提供受支持的通用 QR
协议，再通过独立 capability 接入。

#### 第二层：App 主动出站的 LAN QR Bridge

若要提供“电脑展示二维码、手机扫码后连接”的产品体验，推荐新建独立的 LAN Bridge，
而不是把当前设备 loopback Bridge 直接暴露到局域网：

```text
电脑临时 listener 展示 QR
  -> App 扫码并校验邀请
  -> App 主动向电脑候选地址发起连接
  -> 临时密钥协商与指纹确认
  -> 复用版本化 JSON-RPC、capability、风险门和短期会话
```

QR invitation 只包含：

- 协议版本和 invitation ID；
- 桌面局域网地址候选与临时端口；
- 一次性 session nonce、签发时间和短 TTL；
- 桌面 X25519 临时公钥及供人工核对的短指纹。

QR 不包含最终 session token、API Key、长期设备密钥或录制 secret。双方使用
X25519 交换临时密钥，经 HKDF 派生会话密钥，并绑定 invitation、nonce、双方临时
公钥、协议版本和过期时间。实现必须拒绝过期邀请、nonce 重放、指纹变化、公网或
错误网卡地址、未协商 capability 和静默降级。

桌面端应明确选择监听网卡，检测 VPN、热点、IPv4/IPv6 和防火墙阻断；二维码地址均
不可达时，回退为可人工输入的一次性邀请码和地址，不回退为无认证端口。App 使用
摄像头扫码需要用户授权，也必须提供不依赖摄像头的手工邀请码路径。

扫码只简化应用层发现与会话建立，不能绕过 App 安装、Android 无障碍服务启用、
MediaProjection、运行时权限、USB/Wi-Fi ADB RSA 或 OEM 安全确认。

### C. 真实 App 实验室

哔哩哔哩、抖音和小黑盒应作为兼容性矩阵运行，而不是替代可重复 fixture：

- 为每个 APK 建立仓库内文本 manifest，固定 package、`versionName`、
  `versionCode`、ABI、`minSdk`、许可来源和 SHA-256。
- APK 保存在仓库外受控 cache，不提交、不重新分发，也不自动从商店或非授权站点
  抓取；只接受用户提供或官方许可来源。
- 每轮从干净 AVD 快照开始，校验 hash 后安装；场景结束清除 App 数据、Bridge
  session、截图和测试账号状态。
- 使用未登录、无外部副作用的公开内容，覆盖文本输入、点击、长按、上下滚动、横向
  swipe、至少两级页面、Back、Home、Recents 和跨 App 任务切换。
- 每个动作后重新抓取 hierarchy 或截图并验证后置条件，不能把命令退出码当作 UI
  成功。
- 广告、A/B 实验、地区差异、Play Integrity、模拟器检测、ABI 限制和服务端变化
  单独分类；真实 App 结果报告兼容率和失败原因，不作为完全确定性的提交门禁。

三个 App 的适配场景可由独立子 Agent 并行开发，但共享 runner、APK manifest 和
结果 Schema 的文件必须由单一负责人维护，避免并行覆盖。

### D. WebView 与小程序的分级支持

WebView 首先使用其暴露的虚拟 Accessibility 节点。受控 fixture 应包含本地 HTML
按钮、文本框、长按、滚动、页面跳转、动态 DOM 和 iframe，并按 WebView 版本记录
节点、事件和动作矩阵。

小程序运行在宿主 App 包和运行时中，本项目通常不能直接读取通用 DOM 或注入
JavaScript。WebView、小程序和混合页面统一按以下等级报告：

| 等级 | 可见能力 | 支持结论 |
| --- | --- | --- |
| 完整语义 | 关键控件均有稳定虚拟节点和事件 | 可使用语义录制与确定性回放 |
| 混合 | 部分节点稳定，部分区域仅可截图定位 | 使用语义步骤与显式视觉步骤混合回放 |
| 仅视觉 | 页面主要表现为单一 Canvas、Surface 或不可分解节点 | 只允许截图候选和显式坐标步骤 |
| 不支持 | `FLAG_SECURE`、DRM、敏感限制或无法验证后置条件 | 失败关闭或请求人工接管 |

某个宿主或小程序的成功案例不能声明为通用 DOM、小程序或 WebView 支持。宿主私有
调试接口、远程调试端口和 DOM 注入不作为生产通用方案。

### E. Canvas、视觉与可编辑录制

生产路线采用以下受控闭环：

```text
语义快照
  -> 无稳定节点
  -> 安全截图
  -> OCR/模板/视觉模型提出候选区域、点位和置信度
  -> 归一化坐标与屏幕元数据校验
  -> 目标包和风险门
  -> 单个类型化 tap/long-click/swipe
  -> 重新截图或获取语义树验证
```

视觉候选至少记录来源、归一化点或边界、置信度、屏幕宽高、旋转、前台包、
observation ID、图像 SHA-256 和生成时间。原始截图不写入脚本；执行时必须确认当前
观察仍有效，并重新映射旋转、分辨率、系统栏和窗口偏移。低置信度、多候选接近、
前台包变化或无法验证结果时不执行。

不能把以下能力误写为通用触摸录制：

- MediaProjection 只提供画面，不提供用户触点或触摸时间线；
- 透明 overlay 若捕获触摸，会先改变或截断目标 App 的原始交互语义；
- Accessibility raw motion 依赖 API 和 ROM，已有设备证据表明相关 flag 可能破坏
  正常触控；
- `getevent`/`sendevent` 通常要求 root、定制 SELinux 和固定驱动，只能留在隔离
  实验室插件。

因此生产方案是“语义录制 + 录制编辑器 + 显式视觉/坐标步骤”。编辑器应支持脚本
revision、步骤新增/删除/复制/排序/启停、selector 与坐标互转、tap/long-click/
swipe、等待、重试、失败策略、截图点选、Undo/Redo、导入导出和 dry-run。导出不
包含 secret 或截图；dry-run 只做匹配、坐标预览和条件检查，不提交动作。

桌面 Skill 和 MCP 应把同一 observation ID 下的 PNG `ImageContent` 与屏幕尺寸、
旋转、前台包和脱敏 hierarchy 一起交给视觉模型。模型只能提出候选，实际动作继续
通过 `android_action_execute`，并执行“截图 -> 候选 -> 确认/策略门 -> 单动作 ->
再观察”。

### F. Emulator-first 自动验收

下一阶段先建设测试控制面，再扩大第三方 App 数量：

1. 固定 API 30、33、34 的 system image、AVD 配置、分辨率、语言、导航模式和
   WebView 版本；每个场景从 clean snapshot 启动。
2. 使用 UI Automator 2.4 的跨 App、系统导航、稳定等待、截图和结果报告能力，覆盖
   文本、长按、滚动、手势、多页面、Back、Home、Recents 和任务切换。
3. debug/test 变体可以通过一次性测试 token 预置无敏感数据脚本、生成 Bridge
   配对状态和读取自检结果；这些端点、测试服务和 shell 配置能力不得进入 release。
4. 只在 disposable emulator 且校验 serial、AVD fingerprint、debug 签名和
   test-only marker 后，使用测试 shell 身份启用测试无障碍服务；禁止把同一路径用于
   普通真机。
5. 失败产物限额收集脱敏截图、hierarchy、短时间窗日志、设备元数据和动作时间线，
   设置保留期并在成功后自动清理。
6. 核心 fixture 在 API 矩阵连续运行 20 次并报告成功率、耗时、重试和 flaky 分类；
   真实 App 单独报告兼容率。

即使 emulator 可以无人值守运行，真机首次 USB/Wi-Fi ADB RSA、无障碍服务启用与
醒目披露、MediaProjection、安装确认、Shizuku、Device Owner、敏感运行时权限、
生物识别、CAPTCHA、支付和 OEM 安全设置仍不得自动绕过，也不能用模拟器结果宣称
真机授权已自动化。

### G. 主要来源

- [Android ADB：无线调试、配对、mDNS 与端口转发](https://developer.android.com/tools/adb)
- [Android UI Automator 2.4+ 指南](https://developer.android.com/training/testing/other-components/ui-automator)
- [AndroidX Test UI Automator 发布说明](https://developer.android.com/jetpack/androidx/releases/test-uiautomator)
- [Android Accessibility 自动与分析测试](https://developer.android.com/guide/topics/ui/accessibility/testing)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android WebView 中构建可访问 Web 内容](https://developer.android.com/guide/webapps/webview)
- [WindowManager `FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)
- [AOSP `getevent`](https://source.android.com/docs/core/interaction/input/getevent)

## 推荐演进顺序

1. **P0：本地测试控制面。** 保持 Go、协议 v1 和现有安全边界，先落地 UI
   Automator 与固定 AVD 本地矩阵，在 API 30/33/34 建立可重复、可清理且不增加
   生产权限的回归基线。
2. **P0：无线 ADB 生命周期。** 完成官方 Android 11+ 配对发现、mDNS、可信设备
   档案、端口变化和断线恢复，并在 macOS 与 Windows 验证不会串错设备。
3. **P0：脚本 1.1 与录制编辑器。** 先实现 revision、provenance、
   `visualTarget`、迁移和原子保存，再完成步骤编辑、Undo/Redo、截图点选和 dry-run，
   为动态页面提供可维护的人工修正路径。
4. **P0：WebView/Canvas fixture 与语义兼容。** 建立离线、可复位 fixture，先验证
   WebView 虚拟节点与多页面录制回放，再明确小程序和 Canvas 的语义、混合、仅视觉
   与不支持边界。
5. **P0：视觉 `Observer` 与坐标回放。** 在语义树不可用时提供有界截图、候选区域、
   置信度和 observation 证据，支持受控的规范化 tap、long-click 和 swipe；始终
   保持低置信度、多候选和无法验证结果时失败关闭。
6. **P0：真实 App 兼容矩阵。** 在上述稳定基础上，用固定版本、ABI、来源和
   SHA-256 的哔哩哔哩、抖音和小黑盒验证多动作、多页面及任务切换，并独立报告
   动态内容、广告、A/B 实验和模拟器检测造成的失败。
7. **P1：LAN QR Bridge。** 只有无线 ADB 向导仍不能满足连接体验时，才实现 App
   主动出站、临时密钥协商和短期邀请的同局域网扫码连接。
8. **P1：设备农场与 scrcpy。** 本地 API 矩阵稳定后，条件接入一个设备农场
   Provider 扩大 OEM 覆盖；scrcpy 只作为人工接管、演示和恢复 sidecar，不进入
   自动执行内核。
9. **P2：按部署模型选择。** 仅在明确的高级侧载、企业专用设备或远程实验室需求下，
   分别评估 Shizuku、Device Owner/DPC 和互联网远程 Provider，不将它们变成通用
   MVP 依赖。
10. **P3：继续暂缓。** TypeScript CLI 重写、厂商互联、专用蓝牙桥和
    `getevent`/`sendevent` 原始回放只有出现不可替代价值时才立项；原始输入方案
    继续限制在 root、固定驱动和定制 SELinux 的隔离实验室。

任何阶段都不得通过新增 Provider 放宽动作白名单、目标设备显式选择、目标包限制、
高风险确认、紧急停止、敏感数据过滤或审计要求。
