# 下一阶段可执行任务

> 本文件是 N31-N54 唯一执行与状态源，不回写当前 MVP 验收清单。
>
> **当前执行状态（2026-07-25）：** N31、N39、N42 已取得实现、定向测试、构建或
> 设备证据并合入 `main`；N32、N33、N34 已从统一基线 `e7a5716` 创建独立 change
> spec 分支和 worktree，正按互斥目录并行实施。下一阶段任务不回写当前 MVP
> 验收结果。
>
> Task N35 实现已集成到 `main`，但正式验收仍待 macOS、Windows 各 20 次断线恢复
> 与不会串设备的证据，继续保持未完成。N31/N39/N42 的临时分支已在确认补丁等价
> 合入且 worktree 干净后安全清理。
> 执行前必须先阅读根目录 `AGENTS.md`、
> `.trae/specs/build-ai-android-automation-mvp/spec.md` 和
> `docs/alternatives.md`，不得把计划、编译成功或单次演示描述为已交付能力。

## 执行约定

- 每项任务使用独立分支和 worktree；开始前检查 `git status` 与
  `git worktree list --porcelain`。
- 严格按依赖执行。并行任务必须拥有互斥的文件范围；协议清单、公共导航和任务状态
  文件由单一集成任务修改。
- 先写失败测试，再实现最小功能；每项任务通过定向测试后创建单一职责
  Conventional Commit。
- 所有设备操作显式指定 serial。模拟器测试从固定快照开始，结束时清理 App 数据、
  Bridge session、截图、日志和临时凭据。
- 第三方 APK 不自动下载、不提交 Git、不重新分发；只使用用户提供或官方许可来源，
  并在仓库外缓存中校验版本、ABI 和 SHA-256。

## Wave 1：测试基础、无线连接、脚本模型与受控页面

### [x] Task N31：固定 API 30/33/34 Emulator Runner

**目标：** 建立可重复、可并发互斥、可从干净快照启动的本地模拟器控制面。

**具体步骤：**

1. 固定 API 30、33、34 的 system image、ABI、设备规格、分辨率、语言、时区、
   导航模式和 WebView 版本，并记录 SDK package hash。
2. 实现 runner 的 create/start/wait-for-boot/restore-snapshot/stop/delete 生命周期，
   所有命令显式返回 serial 和设备指纹。
3. 增加跨进程设备锁，阻止两个任务复用同一 AVD 或串行号。
4. 为 boot timeout、磁盘不足、镜像缺失、端口冲突和残留 emulator 增加稳定错误码。
5. 在 macOS 本地验证三个 API；为 Windows runner 保留等价路径和 CI smoke。

**依赖与并行：** 无新增任务依赖；可与 N35、N39、N42 并行。

**允许修改范围：** `scripts/emulator/`、AVD 定义、Android 测试根构建配置及专用
CI workflow；本任务是 `android/settings.gradle.kts` 等共享 Gradle 集成文件的唯一
负责人，不得修改生产 Bridge 或 release Manifest。

**验收证据：** 每个 API 连续启动、恢复快照和销毁 10 次；serial 不重复，快照状态
一致，失败后没有残留 emulator 进程或设备锁。

**实现记录（2026-07-25）：** `main` 已包含 `887c1c6` 与端口安全修复
`ee2acc7`。API 30、33、34 各连续完成 10 轮 clean snapshot
启动、dirty marker、恢复和销毁，每个 API 的 10 个 serial 均唯一，30/30
fingerprint 与 marker 一致；最终设备、owned emulator、runtime 和 lease 均为零。
runner 测试 13/13 通过，stop 未确认成功时保留锁，WebView 版本无法解析或漂移时
失败关闭；Windows 使用 native `.exe`/Java 主类路径 smoke，不把它描述为真实
Windows emulator 矩阵。

**失败清理：** 停止本任务创建的 emulator，删除临时 AVD、锁文件和仓库外 SDK
缓存；不得执行共享 `adb kill-server`。

**建议提交：** `test(android): add deterministic emulator runner`

### [ ] Task N32：Debug-only Test Control Plane

**目标：** 在 disposable emulator 中无人值守配置测试服务和 Bridge，同时保证
release 变体完全不包含测试入口。

**具体步骤：**

1. 定义 debug/androidTest 专用的一次性 test token、设备指纹和调用范围。
2. 提供测试服务状态查询、测试无障碍服务启停、Bridge 配对状态生成、无敏感数据脚本
   预置和自检结果读取能力。
3. 每次调用校验 emulator serial、AVD fingerprint、debug 签名和 test-only marker。
4. 为过期 token、真机 serial、release 包、错误签名和重放请求增加拒绝测试。
5. 增加 release APK 静态断言，确认组件、权限、字符串和代码路径均不存在。

**依赖与并行：** 依赖 N31；可与 N33、N34 并行。仅本任务拥有
`android/app/src/debug/`、`android/app/src/androidTest/` 与
`android/test-control/core/`；不得修改 N34 的 reporting 或 artifact 目录。

**允许修改范围：** `android/app/src/debug/`、`android/app/src/androidTest/`、
`android/test-control/core/` 及 `android/test-control/core/build.gradle.kts`；
共享 Gradle 文件由 N31 或后续单一集成任务串行修改，不得由 N32、N34 两个 Agent
并行修改；不得修改 release 行为或普通真机授权。

**验收证据：** API 30/33/34 可自动建立测试 Bridge 并运行一次只读 snapshot；
release APK 检查为零测试入口，真机或非测试签名调用全部失败关闭。

**执行记录（2026-07-25）：** 已创建 `phase2-n32-test-control` 独立分支和
`/private/tmp/ai-auto-n32` worktree，独占 core/debug/androidTest 范围；实现、
三 API 设备证据和 release 静态检查完成前保持未勾选。

**失败清理：** 撤销 test token、关闭 Bridge、禁用测试服务并清空测试脚本；保留
脱敏失败报告，不保留 token。

**建议提交：** `test(android): add emulator-only control plane`

### [ ] Task N33：扩展 Native Device Fixture

**目标：** 用受控原生 App 覆盖真实自动化所需的多动作和多页面基础能力。

**具体步骤：**

1. 增加稳定 resource ID 的文本输入、长按、纵向滚动、横向 swipe 和状态断言控件。
2. 增加至少三个页面、可关闭 dialog、返回栈和可复位的进程内状态。
3. 增加 Back、Home、Recents、应用切换和 Launcher 重入场景。
4. 为每个动作提供不随状态变化的 `contentDescription` 和独立后置状态节点。
5. 编写 UI Automator 场景，逐步验证点击、输入、长按、滚动、swipe、多页面和系统
   导航，而不是只检查命令退出码。

**依赖与并行：** 依赖 N31；可与 N32、N34 并行。

**允许修改范围：** `android/device-fixture/` 及 fixture 专用测试；不得把测试控件
加入生产 App。

**验收证据：** API 30/33/34 上每个动作均有前置状态、动作结果和后置状态；完整场景
连续 20 次成功率不低于 95%。

**执行记录（2026-07-25）：** `main` 已包含代码基线 `c50868a`，模块单测 3/3、
Debug/AndroidTest APK、lint 和注释检查通过。API 30 首次以明确 serial
`emulator-5576`、N31 fingerprint 和 `fixtureRepeat=20` 执行时，第 1 轮纵向滚动
未产生 `VERTICAL_SCROLL:1` 后置状态，结果为 0/1 且立即停止；runner 清理后设备、
emulator、runtime 和 lease 均为零。已创建独立 follow-up 修复手势确定性；三个 API
各 20 次完整场景通过前保持未勾选。

**失败清理：** 清除 fixture 数据并恢复 AVD 快照；失败产物交给 N34 管理。

**建议提交：** `test(android): expand native automation fixture`

### [ ] Task N34：失败产物、脱敏与 Flaky 统计

**目标：** 为模拟器任务提供有预算、可诊断、会自动清理的失败证据。

**具体步骤：**

1. 定义 run ID、场景 ID、设备元数据、动作时间线和结果 Schema。
2. 失败时收集限额截图、脱敏 hierarchy、短时间窗日志和测试报告。
3. 对密码、验证码、token、配对码、API Key 和目标 App 敏感文本执行统一过滤。
4. 设置单文件、单场景和整轮预算，以及成功立即清理、失败按 TTL 清理策略。
5. 实现 20 次重复运行统计，区分产品失败、设备失败、基础设施失败和 flaky。

**依赖与并行：** 依赖 N31；可与 N32、N33 并行。仅本任务维护结果 Schema、
artifact collector、`android/test-control/reporting/` 和 `test-lab/artifacts/`；
不得修改 N32 的 core/debug 控制目录。

**允许修改范围：** `android/test-control/reporting/`、`test-lab/artifacts/`、
专用 artifact CI workflow 及其模块内构建文件；共享 Gradle 文件由 N31 或后续单一
集成任务串行修改。

**验收证据：** 故意制造超时和断言失败，确认产物齐全且无敏感值；预算溢出被截断，
TTL 清理后无残留，20 次统计可复现。

**执行记录（2026-07-25）：** 已创建 `phase2-n34-failure-artifacts` 独立分支和
`/private/tmp/ai-auto-n34` worktree，独占 reporting/artifact 范围；故障注入、
预算、脱敏、TTL 和 20 次统计证据完成前保持未勾选。

**失败清理：** 立即删除超预算和未脱敏产物，停止上传；保留仅含错误码的摘要。

**建议提交：** `test: add bounded emulator failure artifacts`

### [ ] Task N35：无线 ADB 生命周期

**目标：** 在 macOS 和 Windows 上支持无线设备发现、配对后重连和可信设备选择。

**具体步骤：**

1. 实现 `devices watch`，观察 USB、emulator、`_adb-tls-pairing._tcp` 和
   `_adb-tls-connect._tcp` mDNS 变化。
2. 保留现有交互式 `adb pair`/`adb connect`，配对码不得进入 argv、日志或档案。
3. 建立不含秘密的可信设备档案，记录设备公认身份、最近 transport 和 capability。
4. 端口或网络变化后通过 mDNS 恢复连接；型号相同、多网卡或身份不一致时停止。
5. 覆盖 VPN、防火墙、mDNS 不可用、伪造服务、断线和多设备冲突。

**依赖与并行：** 无新增任务依赖；可与 N31、N39、N42 并行。

**允许修改范围：** `internal/adb/`、`internal/app/`、`cmd/aactl/` 和 discovery
Skill 及其镜像；不得开放无线配对给默认 MCP 模型调用。

**验收证据：** macOS 与 Windows 各完成 20 次断线恢复，不串设备；配对码和设备
私密凭据不出现在 stdout、日志、配置或崩溃报告。

**实现记录（正式验收未完成）：** `main` 已包含 `6872b17` 与审查后修复
`dc3e10e`；定向 Go race 测试覆盖 mDNS 发现、可信档案、身份冲突、IPv6 scope、
watch 边界与恢复路径。

**失败清理：** 移除本任务建立的无线连接和测试档案；不撤销用户其他 ADB 信任，
不停止共享 ADB server。

**建议提交：** `feat(cli): add wireless adb lifecycle`

### [x] Task N39：AutomationScript 1.1

**目标：** 为编辑器和视觉步骤增加可迁移、可并发保护的脚本模型。

**具体步骤：**

1. 在协议中增加 `revision`、`updatedAt`、`provenance`、`enabled`、
   `visualTarget`、`notes` 和兼容的等待/重试字段。
2. 定义 `semantic`、`visual`、`coordinate`、`manual`、`imported` 来源语义。
3. 实现 1.0 到 1.1 迁移、未知 major 拒绝、原子保存和 revision 乐观锁。
4. 实现严格 JSON 导入导出；默认移除 secret、截图和设备本地路径。
5. 增加损坏脚本、并发更新、回滚失败、未知字段和 round-trip 契约测试。

**依赖与并行：** 无新增任务依赖；可与 N31、N35、N42 并行。协议清单文件由本任务
独占，N36 必须在本任务合入后修改同一清单。

**允许修改范围：** `protocol/schema/`、`protocol/fixtures/`、Android recording
model/store/migration 及对应测试。

**验收证据：** 所有 1.0 fixture 无损迁移；1.1 跨 Go/Kotlin round-trip 一致；
revision 冲突不覆盖新数据，导出中无 secret 或截图。

**实现记录（2026-07-25）：** `main` 已包含 `012406b`。协议验证 33 项通过，
Android recording 定向测试 43/43 通过；覆盖 1.0 迁移、严格 1.1 fixture
round-trip、未知 major/字段拒绝、OS 文件锁、revision CAS 冲突、`.bak` 备份、
原子替换回滚和递归脱敏导出。两个独立 store 的同 revision 更新仅一个保存成功，
失败方不会覆盖较新数据。

**失败清理：** 保留原脚本备份并回滚临时文件；迁移失败不得覆盖 1.0 数据。

**建议提交：** `feat(recording): define automation script v1.1`

### [x] Task N42：离线 WebView 与 Canvas Fixture

**目标：** 建立不依赖公网、可复位、可断言的 WebView 和自绘页面测试基线。

**具体步骤：**

1. 增加本地 HTML WebView，覆盖按钮、输入、长按、滚动、页面跳转、动态 DOM 和
   iframe。
2. 增加 Canvas 页面，覆盖 tap、long-click、swipe、动画状态和明确结果区域。
3. 固定离线字体、资源、viewport、主题和测试数据，禁止请求公网。
4. 为语义节点完整、部分节点和单一 Canvas 三种模式提供切换。
5. 增加测试重置入口和可机器读取的后置状态。

**依赖与并行：** 依赖 N31 提供运行环境，但代码可在 N31 同期开发；可与 N35、N39
并行。

**允许修改范围：** 新建 `android/web-fixture/` 及专用测试资源；不得向生产 App
注入 WebView 调试接口。

**验收证据：** API 30/33/34 离线运行一致；三种模式的 hierarchy、截图和状态断言
可重复，网络访问计数为零。

**实现记录（2026-07-25）：** `main` 已包含 `3f5c002`，共享模块注册和 version
catalog 集成位于 `e7a5716`。API 30、33、34 各运行 full、partial、canvas 三项
instrumentation，最终 9/9 通过；每项均验证非空截图、模式状态和
`NETWORK_REJECTED:0`，full 模式完成 WebView 虚拟节点点击与 Reset 闭环。release
APK 静态检查不含 test runner、instrumentation 类或 `INTERNET` 权限；最终设备、
runtime、lease 和 emulator 均无残留。根 Android 单测、Debug 组装、lint、
AndroidTest APK 和 release 构建共 218 个任务通过。

**失败清理：** 清除 fixture 数据、WebView cache 和截图；恢复 AVD 快照。

**建议提交：** `test(android): add offline webview canvas fixture`

## Wave 2：QR Bridge、编辑器领域、WebView 与视觉观察

### [ ] Task N36：LAN QR Invitation 协议与威胁测试

**目标：** 定义不包含长期秘密、可防过期和重放的局域网扫码邀请协议。

**具体步骤：**

1. 定义 invitation Schema：协议版本、invitation ID、地址候选、端口、nonce、TTL、
   X25519 临时公钥和短指纹。
2. 定义 X25519、HKDF transcript、会话确认、capability 协商和错误码。
3. 明确 QR 不包含最终 token、API Key、录制 secret 或长期设备密钥。
4. 增加过期、重放、错误指纹、公网地址、错误网卡、篡改字段和降级攻击 fixture。
5. 更新协议兼容规则，确保 LAN Bridge 与现有 loopback Bridge 会话语义分离。

**依赖与并行：** 依赖 N39 合入共享协议清单；完成后 N37 与 N38 可并行。

**允许修改范围：** 新增 QR/LAN Bridge Schema、fixture 和协议文档；不得修改动作
白名单或降低现有 Bridge 认证。

**验收证据：** 合法 fixture 在 Go/Kotlin 均通过；所有威胁 fixture 在建立 socket
或签发 token 前被拒绝。

**实现记录（跨实现验收进行中）：** `main` 已包含协议提交 `9d5017e`。LAN 定向
测试 10/10、协议全量 13 个 Schema、13 个合法 fixture、35 个非法 fixture 和
4 个兼容检查共 52 项通过；覆盖 TTL、重放、地址/网卡、指纹、X25519 低阶点、
HKDF、transcript、双方确认和禁止降级。Go/Kotlin 消费者正由 N37/N38 使用同一
18 个威胁 fixture 和密码向量实现；两端证据完成前本任务保持未勾选。

**失败清理：** 删除临时密钥和 invitation；测试日志只保留指纹和错误码。

**建议提交：** `feat(protocol): define lan bridge invitations`

### [ ] Task N37：Go LAN QR Listener

**目标：** 让桌面端临时监听可信局域网连接，并向手机展示可验证的二维码邀请。

**具体步骤：**

1. 实现临时 listener、地址候选枚举、明确网卡选择和端口生命周期。
2. 生成 N36 invitation 和二维码，并提供不依赖摄像头的手工邀请码输出。
3. 完成临时密钥交换、HKDF、指纹确认和加密 framed RPC。
4. 诊断 VPN、热点、IPv4/IPv6、错误路由和 macOS/Windows 防火墙。
5. 复用现有 capability、风险门、目标包、超时和 session close 语义。

**依赖与并行：** 依赖 N36；可与 N38 并行。

**允许修改范围：** `internal/bridge/lan/`、`cmd/aactl/`、CLI 服务层和对应 Go 测试；
不得把 listener 绑定到公网或默认所有网卡。

**验收证据：** macOS/Windows 同 LAN 建连成功；过期、重放、错误网卡和指纹变化
失败关闭；listener/session 结束后端口和临时密钥均清理。

**失败清理：** 关闭 listener 和连接，清零临时私钥，撤销 invitation，删除二维码
临时文件和防火墙测试规则。

**建议提交：** `feat(bridge): add desktop lan invitation listener`

### [ ] Task N38：Android 扫码与出站加密连接

**目标：** App 扫描桌面 invitation 后主动出站建立 LAN Bridge，并提供手工码备选。

**具体步骤：**

1. 增加扫码页、摄像头权限说明、二维码解析和手工邀请码输入路径。
2. 校验 N36 Schema、TTL、地址类型、短指纹和用户当前选择。
3. 主动连接桌面候选地址，完成 X25519/HKDF 和 transcript 绑定。
4. 展示连接网卡、桌面指纹、session 过期时间和明确停止入口。
5. 为权限拒绝、无摄像头、地址不可达、重放、切网和进程恢复增加测试。

**依赖与并行：** 依赖 N36；可与 N37 并行，集成测试在两者合入后串行执行。

**允许修改范围：** Android Bridge LAN 模块、扫码 UI、摄像头权限和对应测试；
不得修改无障碍授权流程或在 release 中静默连接。

**验收证据：** 扫码和手工码均可建立短期 session；无摄像头仍可使用手工路径；
切网或过期后停止新动作且不降级到明文。

**失败清理：** 关闭出站 socket，清零临时密钥和解析结果，撤销 session；不保留
二维码图像。

**建议提交：** `feat(android): connect lan bridge by qr`

### [x] Task N40：录制编辑器领域层

**目标：** 提供可测试的步骤编辑事务，不依赖 Compose UI。

**具体步骤：**

1. 实现脚本复制以及步骤 insert/delete/duplicate/reorder/enable/update。
2. 实现 selector、coordinate、wait、retry、failure policy 和 notes 编辑。
3. 实现 Undo/Redo、dirty state、revision 冲突和未保存变更保护。
4. 实现 dry-run：只做快照、选择器匹配、坐标预览和条件检查，不提交动作。
5. 实现导入预览、严格校验和导出脱敏。

**依赖与并行：** 依赖 N39；可与 N43 并行，不能与 N41 同时修改 editor domain。

**允许修改范围：** 仅限
`android/app/src/main/java/dev/aiauto/android/automation/recording/editor/` 与对应
`android/app/src/test/.../automation/recording/editor/`。不得修改 N39 所有的
recording core models、repository、迁移或协议文件，也不得修改 Compose 页面或
真实动作执行器。

**验收证据：** 每个编辑命令有反向操作测试；100 步随机编辑后 Undo 回到原脚本；
dry-run 动作提交数为零，revision 冲突保留双方数据。

**实现记录（2026-07-25）：** `main` 已包含 `2314b06`。15/15 editor 定向测试与
214/214 App 全量单测通过；12 类编辑命令均可 Undo/Redo，固定 seed 的 100 步编辑
可完整回退和重做。dry-run 构造器只有 snapshot、selector、coordinate 和 condition
四类只读端口，不存在动作提交能力；revision 冲突保留 attempted/current 双方，
严格导入与默认脱敏导出预览不创建临时文件。

**失败清理：** 放弃事务并恢复最后已保存 revision；删除导入临时文件。

**建议提交：** `feat(recording): add script editor domain`

### [ ] Task N43：WebView 语义兼容与多页面回放

**目标：** 验证 WebView 虚拟 Accessibility 节点，并在语义不足时明确降级。

**具体步骤：**

1. 记录 N42 各页面在 API/WebView 版本上的节点、事件和动作矩阵。
2. 用现有语义动作完成点击、输入、长按、滚动、跳转和 Back。
3. 录制跨页面流程，保存、复位并确定性回放。
4. 动态 DOM 或 iframe 节点缺失时返回稳定 `SEMANTIC_UNAVAILABLE`，不得猜坐标。
5. 输出完整语义、混合、仅视觉、不支持四级兼容结果。

**依赖与并行：** 依赖 N42；可与 N40 并行，视觉降级集成依赖 N44/N45。

**允许修改范围：** 仅限
`android/app/src/main/java/dev/aiauto/android/automation/recording/webview/`、对应
`android/app/src/test/.../automation/recording/webview/`，以及 N42 明确预留的
WebView fixture 适配目录。不得修改 N39 的 core models/repository、N40 的
`recording/editor/`，也不得使用宿主私有 DOM 注入作为通用能力。

**验收证据：** API 30/33/34 完整语义场景连续 20 次成功率不低于 95%；语义缺失
场景动作提交数为零并给出明确降级等级。

**实现记录（设备验收未完成）：** `main` 已包含代码基线 `27be2fe`。adapter 与
orchestrator 定向测试 20/20、App 全量单测 219/219 通过；只生成类型化语义动作，
节点缺失、歧义、observation 过期及 package/page/API/WebView 漂移均失败关闭。
API 30/33/34 各 20 轮真实虚拟节点回放入口正在独立 follow-up 中实现，60 轮与清理
证据完成前保持未勾选。

**失败清理：** 清除 WebView 数据、脚本和产物，恢复快照。

**建议提交：** `test(android): validate webview semantic replay`

### [ ] Task N44：视觉 Observation 与 AI 候选

**目标：** 把截图、语义树和屏幕元数据绑定为同一次观察，并让 AI 只提出候选。

**具体步骤：**

1. 定义 observation ID、前台包、屏幕宽高、旋转、裁剪映射、时间和图像 SHA-256。
2. 将安全 PNG `ImageContent` 与脱敏 hierarchy 作为同一 MCP/Skill 观察返回。
3. 定义 OCR/template/model/manual 候选的归一化点、边界、置信度和来源。
4. 实现 `android_visual_target_propose` 或等价服务层，只返回候选，不执行动作。
5. App AI 会话接入用户授权的最小图像上下文，并复用截图裁剪、预算和清零策略。
6. 拒绝过期 observation、前台包变化、低置信度和候选接近情况。

**依赖与并行：** 依赖 N31、N34；协议清单修改必须在 N36 后由单一协议负责人集成。
可与 N40、N43 并行开发非协议部分。

**允许修改范围：** Android 侧仅限
`android/app/src/main/java/dev/aiauto/android/observe/visual/` 及对应测试，Go 侧仅限
`internal/visual/`，协议侧仅限独立 visual schema，MCP 侧仅限独立 visual adapter
文件。不得修改 N39/N40 的 recording core/editor、N43 的
`recording/webview/`，也不得把原图写入录制脚本。

**验收证据：** 同 observation 的 PNG、尺寸、旋转和 hierarchy 可关联；过期或歧义
候选动作提交数为零；截图内存和临时文件按预算清零。

**失败清理：** 清零图片字节、删除临时 PNG 和候选，撤销 observation ID。

**建议提交：** `feat(observe): add visual target proposals`

## Wave 3：编辑器 UI、坐标回放与真实 App 实验室

### [ ] Task N41：Compose 录制编辑器与截图点选

**目标：** 让用户在 App 中安全调整脚本步骤和视觉坐标。

**具体步骤：**

1. 实现脚本复制、步骤列表、拖动排序、启停、删除和复制 UI。
2. 实现 selector、tap、long-click、swipe、等待、重试和失败策略表单。
3. 显示最新授权截图，支持点选或框选并保存规范化坐标与 observation 元数据。
4. 增加 Undo/Redo、dirty state、离开确认和 revision 冲突 UI。
5. 增加 dry-run 报告、失败步骤定位和回放前差异预览。

**依赖与并行：** 依赖 N40、N44；完成领域接口后可由 UI 子 Agent 单独实现。

**允许修改范围：** Android Compose recording editor UI、导航、资源和 UI 测试；
不得修改 editor domain 或协议模型。

**验收证据：** Compose 单测和 instrumentation 覆盖所有编辑命令、旋转恢复、冲突、
截图点选及 dry-run；UI 自动测试不依赖人工点击。

**失败清理：** 丢弃未保存事务和截图缓存，恢复已保存 revision。

**建议提交：** `feat(android): add recording step editor`

### [ ] Task N45：显式坐标与视觉步骤回放

**目标：** 安全执行没有 selector 的显式坐标 tap、long-click 和 swipe。

**具体步骤：**

1. 允许 provenance 为 visual/coordinate/manual 的步骤跳过语义 selector 预检。
2. 实现 normalized tap、long-click、swipe 及持续时间、相对区域参数。
3. 处理旋转、分辨率、density、系统栏、窗口偏移和裁剪坐标逆映射。
4. 执行前校验 observation、前台包、屏幕元数据、边界和风险级别。
5. 每个动作后重新截图或抓语义树验证；无法验证时明确失败。
6. 保持低置信度、多候选和 `FLAG_SECURE` 页面失败关闭。

**依赖与并行：** 依赖 N39、N42、N44；与 N41 可并行，但动作模型共享文件必须由
N45 单独拥有。

**允许修改范围：** recording replay、coordinate transformer、类型化动作和对应
测试；不得开放原始 `sendevent` 或任意 shell。

**验收证据：** API 30/33/34 在至少三种分辨率和两种旋转下准确命中 fixture；
低置信度和过期观察动作提交数为零。

**失败清理：** 停止当前回放，清除 observation 和截图，恢复 fixture 状态。

**建议提交：** `feat(recording): replay explicit visual coordinates`

### [ ] Task N46：第三方 APK Manifest 与仓库外缓存

**目标：** 合法、可重复地准备真实 App 兼容性测试制品。

**具体步骤：**

1. 定义文本 manifest：package、版本、versionCode、ABI、minSdk、来源、许可备注和
   SHA-256。
2. 实现仓库外 cache 查找、hash/ABI 校验和缺失制品提示。
3. 禁止自动商店抓取、未知来源下载、Git 提交和重新分发 APK。
4. 安装前恢复 clean AVD，安装后验证 package/version/signing 摘要。
5. 场景结束清除 App 数据并恢复快照。

**依赖与并行：** 依赖 N31、N34；可与 N41、N45 并行。

**允许修改范围：** `test-lab/apps/` 文本 manifest、`scripts/test-lab/` 和测试；
APK 路径必须位于 `.gitignore` 覆盖的仓库外 cache。

**验收证据：** 正确 APK 可安装；错误 hash、ABI、版本和来源缺失在安装前失败；
Git 历史和工作区不包含 APK。

**失败清理：** 卸载或清除第三方 App、删除未验证 cache 条目并恢复快照。

**建议提交：** `test(lab): add verified external app manifests`

### [ ] Task N47：通用真实 App 场景 Runner

**目标：** 用统一动作和后置条件运行未登录、无副作用的真实 App 流程。

**具体步骤：**

1. 定义场景 DSL：启动、点击、输入、长按、滚动、swipe、多页面、Back、Home、
   Recents 和应用切换。
2. 每一步绑定目标包、前置观察、动作、后置条件、超时和可接受动态区域。
3. 支持语义、混合和视觉路径，并记录实际 route、score、attempts 和 observation。
4. 增加广告、更新提示、A/B 页面、模拟器检测和网络失败分类，不盲目关闭弹窗。
5. 输出兼容率和失败分类，不把第三方动态场景作为确定性提交门禁。

**依赖与并行：** 依赖 N33、N43、N45、N46；完成后 N48、N49、N50 可并行。

**允许修改范围：** `test-lab/runner/`、场景 Schema、结果报告和测试；不得包含具体
第三方 APK 或账号凭据。

**验收证据：** 使用 native/Web fixture 完整跑通所有动作；失败后证据符合 N34，
高风险或未知页面动作提交数为零。

**失败清理：** 停止场景、关闭 Bridge、清除 App 数据和产物并恢复快照。

**建议提交：** `test(lab): add cross-app scenario runner`

### [ ] Task N48：哔哩哔哩未登录兼容场景

**目标：** 验证固定版本哔哩哔哩在未登录状态下的多动作和多页面兼容性。

**具体步骤：**

1. 使用 N46 manifest 校验用户提供或官方许可来源的 APK。
2. 定义无发送、无购买、无账号操作的公开内容浏览和搜索场景。
3. 覆盖输入、结果点击、长按无副作用内容、滚动、swipe、两级页面和 Back。
4. 覆盖 Home/Recents 和切换到 fixture 后返回。
5. 记录语义、混合或视觉 route，以及广告/A-B/模拟器限制。

**依赖与并行：** 依赖 N47；可与 N49、N50 并行，独占
`test-lab/scenarios/bilibili/`。

**允许修改范围：** 哔哩哔哩 manifest、场景和兼容报告；不得提交 APK、账号或抓取
商店内容。

**验收证据：** 固定版本连续运行 10 次并报告成功率、route 和失败分类；无外部副作用。

**失败清理：** 清除 App 数据、搜索历史、截图和场景缓存，恢复快照。

**建议提交：** `test(lab): add bilibili compatibility scenario`

### [ ] Task N49：抖音未登录兼容场景

**目标：** 验证固定版本抖音在未登录状态下的手势、页面和任务切换兼容性。

**具体步骤：**

1. 使用 N46 manifest 校验用户提供或官方许可来源的 APK。
2. 定义公开内容浏览场景，不点赞、不评论、不关注、不发送。
3. 覆盖纵向 swipe、长按无副作用区域、搜索输入、页面跳转和 Back。
4. 覆盖 Home/Recents 和跨 App 返回。
5. 分类视频 Surface、广告、登录墙、模拟器检测和仅视觉区域。

**依赖与并行：** 依赖 N47；可与 N48、N50 并行，独占
`test-lab/scenarios/douyin/`。

**允许修改范围：** 抖音 manifest、场景和兼容报告；不得提交 APK 或执行互动动作。

**验收证据：** 固定版本连续运行 10 次并报告成功率；遇到登录、广告或不可验证页面
停止，不产生互动副作用。

**失败清理：** 清除 App 数据、缓存、截图和 session，恢复快照。

**建议提交：** `test(lab): add douyin compatibility scenario`

### [ ] Task N50：小黑盒未登录兼容场景

**目标：** 验证固定版本小黑盒在未登录状态下的列表、搜索和详情页兼容性。

**具体步骤：**

1. 使用 N46 manifest 校验用户提供或官方许可来源的 APK。
2. 定义公开资讯或游戏资料浏览场景，不登录、不发帖、不评论。
3. 覆盖搜索输入、点击、长按无副作用内容、滚动、swipe 和多页面返回。
4. 覆盖 Home/Recents 与跨 App 切换。
5. 记录 WebView、原生节点、广告和动态内容的 route 分类。

**依赖与并行：** 依赖 N47；可与 N48、N49 并行，独占
`test-lab/scenarios/xiaoheihe/`。

**允许修改范围：** 小黑盒 manifest、场景和兼容报告；不得提交 APK 或账号数据。

**验收证据：** 固定版本连续运行 10 次并报告成功率、动作覆盖和失败分类。

**失败清理：** 清除 App 数据、搜索历史、产物和 session，恢复快照。

**建议提交：** `test(lab): add xiaoheihe compatibility scenario`

### [ ] Task N51：小程序分级探索矩阵

**目标：** 评估不同宿主和渲染模式的小程序，而不承诺通用 DOM 支持。

**具体步骤：**

1. 选择至少两个宿主和多个公开、无登录、无副作用的小程序页面。
2. 记录前台包、Accessibility 树、截图、WebView/Canvas 特征和可验证动作。
3. 分别尝试语义点击/输入/滚动与视觉候选，不使用宿主私有调试接口。
4. 按完整语义、混合、仅视觉、不支持四级输出结果。
5. 记录宿主版本、页面版本、限制和失败证据。

**依赖与并行：** 依赖 N43、N44、N45、N46；可与 N48-N50 并行。

**允许修改范围：** `test-lab/scenarios/miniapps/` 和探索报告；不得注入 JavaScript、
抓取私有 DOM 或提交宿主 APK。

**验收证据：** 每类至少一个可复查样例；仅视觉和不支持场景不会伪装成语义支持，
低置信度动作提交数为零。

**失败清理：** 清除宿主 App 数据、截图、缓存和 session；恢复快照。

**建议提交：** `test(lab): classify miniapp automation support`

## Wave 4：本地矩阵收口与条件扩展

### [ ] Task N52：本地 API 30/33/34 自动矩阵收口

**目标：** 在本地固定 AVD 上收口核心自动化场景，不依赖设备农场即可独立完成和
验收 API 矩阵。

**具体步骤：**

1. 在 API 30/33/34 固定 AVD 上运行 native、WebView、Canvas、编辑器和回放场景。
2. 每个核心场景连续运行 20 次，统计成功率、耗时、重试次数和 flaky 分类。
3. 验证每轮从 clean snapshot 开始，结束后通过 App 数据、Bridge session、测试服务、
   截图、日志和进程残留检查。
4. 汇总各 API 的 capability、动作路由、失败错误码和兼容差异，不因单一 API 失败而
   隐式跳过。
5. 生成本地矩阵报告和机器可读结果，作为后续第三方 App 与可选设备农场的稳定基线。

**依赖与并行：** 依赖 N31-N34、N43-N47；可与 N48-N51 的兼容场景并行运行，但
本任务独占本地矩阵 workflow 和聚合报告。

**允许修改范围：** `test-lab/matrix/local/`、本地 API matrix workflow、结果聚合
和本地矩阵文档；不得增加云供应商 SDK、凭据或远程 job 逻辑。

**验收证据：** 每个 API 的核心 fixture 连续 20 次成功率不低于 95%，残留清理率
100%；报告包含耗时、重试、flaky 分类和可复查失败证据。

**失败清理：** 停止本任务创建的本地 AVD，释放设备锁，删除临时快照和超预算产物；
不得执行共享 `adb kill-server`。

**建议提交：** `test(lab): finalize local android api matrix`

### [ ] Task N53：条件性设备农场 Provider

**目标：** 仅在 N52 本地矩阵稳定后接入一个设备农场，扩大 OEM 和系统版本覆盖，
同时保持本地结果模型、错误语义和安全边界不变。

**具体步骤：**

1. 以 N52 的成功率、残留清理率和结果 Schema 作为启用条件，未达标时不得开始接入。
2. 定义异步 job Provider、设备租约、上传制品、结果轮询、取消和稳定错误码。
3. 将远程截图、hierarchy、日志和视频映射到 N34 的脱敏、预算、TTL 和删除策略。
4. 通过 CI secret 注入供应商凭据，禁止将凭据、任意供应商命令或配对能力暴露给
   通用 MCP。
5. 选择一个供应商运行最小 API/OEM 矩阵，并验证超时、取消、设备离线、租约释放和
   远程产物删除。

**依赖与并行：** 条件任务，依赖 N52；本地矩阵稳定后可与 N54 并行，但独占设备
农场 Provider 与供应商 workflow。

**允许修改范围：** `test-lab/providers/device-farm/`、供应商专用 workflow、凭据
接入说明和 Provider 测试；不得修改 N52 本地矩阵 runner。

**验收证据：** 远程结果可无损映射到本地结果模型；取消和失败均释放租约，远程产物
删除验证通过，凭据只来自 CI secret。

**失败清理：** 取消全部云 job，释放设备租约，删除远程产物并验证删除；撤销测试
凭据，不影响 N52 本地矩阵。

**建议提交：** `test(lab): add optional device farm provider`

### [ ] Task N54：可选 scrcpy 人工接管

**目标：** 在自动化暂停时提供受控人工显示和恢复，不改变默认执行内核。

**具体步骤：**

1. 固定官方 scrcpy 版本、来源、hash、许可证和 macOS/Windows 安装诊断。
2. 实现显式 serial 的 sidecar 生命周期、健康检查和孤儿进程清理。
3. 自动化进入人工接管前暂停，接管期间动作提交数保持为零。
4. 归还控制后重新观察，不复用旧 snapshot 或视觉坐标。
5. 默认关闭剪贴板、音频和录屏；开启时要求明确提示和产物策略。

**依赖与并行：** 条件任务；依赖现有会话暂停/停止能力、N34 产物规则和 N52 本地
矩阵，可与 N53 并行，且不依赖设备农场。

**允许修改范围：** `internal/operator/`、CLI sidecar 命令、文档和测试；不得把
scrcpy GUI 输入解析为 Agent 动作，不得自动选择 OTG 降级。

**验收证据：** macOS/Windows 明确设备启动、暂停、归还和异常退出均无孤儿进程；
接管期间自动执行提交数为零。

**失败清理：** 终止 sidecar、删除临时文件和操作员租约，保持自动化暂停并要求重新
观察。

**建议提交：** `feat(cli): add optional scrcpy operator handoff`

## 并行执行波次

1. **Wave 1A：** N31、N35、N39、N42 可并行，分别拥有测试控制、ADB、录制协议、
   Web fixture 目录。N39 合入前其他任务不得修改协议清单。
2. **Wave 1B：** N32、N33、N34 在 N31 后并行；目录所有者分别为
   `android/test-control/core/` 与 debug/androidTest、`android/device-fixture/`、
   `android/test-control/reporting/` 与 `test-lab/artifacts/`。共享 Gradle 文件
   已由 N31 负责；若仍需集成修改，必须暂停并行并交给单一集成 Agent。
3. **Wave 2A：** N36 在 N39 后串行修改协议；完成后 N37 与 N38 并行。
4. **Wave 2B：** N40、N43、N44 可并行，目录所有权分别限定为
   `recording/editor/`、`recording/webview/` 和 `observe/visual/` +
   `internal/visual/` + 独立 visual schema/MCP adapter。三者不得修改共享导航、
   Gradle 根配置、recording core models/repository 或协议聚合清单；这些共享文件
   在三项提交完成后，由一个单一 integration owner 暂停并行、串行集成和验证。
5. **Wave 3A：** N41、N45、N46 可并行；分别拥有 editor UI、replay executor、
   test-lab artifact。
6. **Wave 3B：** N47 集成通用 runner；完成后 N48、N49、N50、N51 使用独立场景
   目录并行。
7. **Wave 4：** N52 独立收口本地 API 30/33/34 矩阵，不等待设备农场。
8. **Wave 5：** 本地矩阵稳定后，N53 设备农场与 N54 scrcpy 是两个互不依赖的条件
   任务，可由目录互斥的 Sub-Agent 并行执行。

任何两个 Sub-Agent 都不得同时修改同一文件。`protocol/fixtures/manifest.json`、
`docs/README.md`、Gradle 根配置、公共导航和任务状态文件必须由单一集成 Agent
串行修改。

## Sub-Agent Prompt 模板

```text
实现 docs/next-phase-tasks.md 的 Task NXX。

前置阅读：
- AGENTS.md
- .trae/specs/build-ai-android-automation-mvp/spec.md
- docs/alternatives.md
- docs/next-phase-tasks.md 的 Task NXX

依赖状态：
- 已完成：[列出提交 hash]
- 未完成依赖：[必须停止，不得自行绕过]

允许修改：
- [精确目录/文件]

禁止修改：
- 其他 Agent 拥有的目录
- 公共协议清单/导航/任务状态（除非本任务明确为集成负责人）
- release 安全边界、动作白名单和真机授权流程

执行要求：
1. 先写失败测试并报告预期失败。
2. 实现最小功能。
3. 运行：[精确测试命令]。
4. 运行 git diff --check、make comments 和任务相关构建。
5. 核对无密钥、APK、截图、缓存或生成物。
6. 只提交允许范围，提交信息使用：[建议 Conventional Commit]。

完成时报告：
- commit hash
- 修改文件
- 测试数量与结果
- 未覆盖风险
- worktree 是否干净
```

Sub-Agent 30-60 秒无输出时先查询状态；确认卡死后再停止。Agent 完成或失败后立即
关闭，不重复派发仍在运行的相同任务。

## 人工授权边界

模拟器可以在 debug/test 变体、test-only marker、明确 AVD fingerprint 和一次性
测试 token 约束下自动预置测试无障碍服务与 Bridge。以下真机步骤仍必须由用户完成：

- USB 或 Wi-Fi ADB RSA 与系统无线调试配对；
- 首次启用无障碍服务和确认醒目披露；
- MediaProjection、摄像头、安装、未知来源和敏感运行时权限；
- OEM“USB 调试安全设置”、Shizuku、Device Owner 预配；
- 登录、密码、验证码、CAPTCHA、生物识别、支付、购买、发送和系统安全设置。

模拟器自动通过不得描述为真机授权可绕过。真实 App 场景仅使用未登录、非敏感、
无发送、无购买、无外部副作用的流程。

## 完成定义

一项任务只有同时满足以下条件才能勾选：

1. 依赖已完成，代码与文档位于允许范围；
2. 定向测试、注释检查、格式检查和相关构建通过；
3. 设备任务有明确 serial、前置观察、动作和后置证据；
4. 失败路径和清理路径实际验证；
5. release 不包含 debug/test 控制能力；
6. 没有提交 APK、密钥、token、截图、缓存或构建产物；
7. Conventional Commit 单一职责，worktree 干净；
8. 动态第三方 App 只报告兼容率，不夸大为确定性产品保证。
9. N52 可在不实施 N53/N54 时独立完成；N53 和 N54 只有满足各自启用条件并取得实际
   验收证据后才勾选，未实施时保持条件性待办而不阻塞本地矩阵。
