# 下一阶段可执行任务

> 本文件是 N31-N54 唯一执行与状态源，不回写当前 MVP 验收清单。
>
> **当前执行状态（2026-08-01）：** N31、N32、N34、N36、N39、N40、N42、
> N43、N44、N46 已取得对应实现、测试或设备证据并合入 `main`。N37/N38 的持续加密
> Bridge RPC 和 Go/Kotlin 真实 socket 已通过，但跨平台、相机与真机矩阵仍待验收；
> N33 已完成 ListView 重设计与 API 30/33/34 各 20 轮 clean 完整矩阵。下一阶段任务
> 不回写当前 MVP 验收结果。
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
- 本地 SDK/cache 根由 `AACTL_TOOLCHAIN_ROOT` 显式配置，并兼容旧
  `ANDROID_TOOLS_ROOT`；仓库 runner 不绑定 macOS 卷名、Windows 盘符或用户目录。

**跨平台与 CI 修复（2026-08-01）：** GitHub 公共 API 确认远端 `verify.yml`
最新 run `30197184628` 的 Ubuntu/macOS Go、Android、协议、Skills 与 metadata
均成功，Windows 唯一失败在 gofmt；三个附加跨平台 workflow 也仅 Windows 失败。
独立 portability change 固定源码 checkout 为 LF、batch 为 CRLF，增加跨平台格式
脚本、Windows `.exe` fake SDK 验证、N34 当前平台绝对路径/junction 语义，并把
LAN/N43/N46 runner 的机器专属工具根迁移为显式环境变量。远端仍停留在
`5dcc70f`，必须将本地提交 push 后才能取得新的 Actions 结论。

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

**实现记录（2026-07-25）：** `main` 已包含 `b7e9eda` 与端口安全修复
`6604f10`。API 30、33、34 各连续完成 10 轮 clean snapshot
启动、dirty marker、恢复和销毁，每个 API 的 10 个 serial 均唯一，30/30
fingerprint 与 marker 一致；最终设备、owned emulator、runtime 和 lease 均为零。
runner 测试 13/13 通过，stop 未确认成功时保留锁，WebView 版本无法解析或漂移时
失败关闭；Windows 使用 native `.exe`/Java 主类路径 smoke，不把它描述为真实
Windows emulator 矩阵。

**失败清理：** 停止本任务创建的 emulator，删除临时 AVD、锁文件和仓库外 SDK
缓存；不得执行共享 `adb kill-server`。

**建议提交：** `test(android): add deterministic emulator runner`

### [x] Task N32：Debug-only Test Control Plane

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

**实现记录（2026-07-25）：** `main` 已包含 `26865fe`，共享 debug/androidTest
接线位于 `4287344`。core 32/32 通过；release APK 扫描为 0 findings，且
`releaseRuntimeClasspath` 不包含 test-control core。API 30 前两轮分别暴露服务
激活等待和 IPv6 loopback 问题，均明确失败并清理；修复后 API 30
`emulator-5580`、API 33 `emulator-5582`、API 34 `emulator-5584` 各 1/1 通过
只读 Bridge snapshot。每轮绑定 N31 profile/fingerprint、双签名、test marker 与
一次性 token，结束后恢复 Secure settings、关闭 Bridge、删除预置脚本并清零秘密；
最终设备、emulator、runtime 和 lease 均为零。

**失败清理：** 撤销 test token、关闭 Bridge、禁用测试服务并清空测试脚本；保留
脱敏失败报告，不保留 token。

**建议提交：** `test(android): add emulator-only control plane`

### [x] Task N33：扩展 Native Device Fixture

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

**执行记录（设备验收阻塞）：** `main` 已包含代码基线 `7e6cc43` 和手势 follow-up
`b3d21ed` 至 `f732959`；模块单测、Debug/AndroidTest APK、lint 和注释检查通过。
API 30 首次 `fixtureRepeat=20` 在第 1 轮纵向滚动失败，随后使用多个新 clean serial
逐步验证方向、节点可见性、语义 scroll、最新节点 bounds 内 swipe、单层布局和触摸
路由，真实 `VERTICAL_OFFSET` 始终为 0。所有失败均立即停止，没有放宽后置断言；
最终设备、emulator、runtime 和 lease 均为零。API 33/34 与 20 轮矩阵未启动，
N33 保持未完成，后续需重新设计可由平台输入稳定命中的手势 surface。

**追加平台 surface 调查（方案被否定）：** `main` 已包含 `be5bd3b`。临时移除
自定义 intercept/dispatch/touch/`scrollBy` 后，模块 121 tasks 通过；但 API 30
`emulator-5576` 的纯平台 `ScrollView` 在 bounds 内 `UiDevice.swipe` 返回成功后
`VERTICAL_OFFSET` 仍为 0，新 clean `emulator-5578` 的
`UiObject2.scroll(Direction.UP, 0.8f)` 又直接返回 false。两种路线均未伪造状态或
放宽真实 offset 断言，实验实现已完整撤销，fixture 源码与基线一致；最终设备、
runtime、AVD/port lease 和 owned emulator 为零。API 33/34 与重复矩阵未启动，
N33 继续保持未勾选。

**追加重设计记录（API 30 纵向单轮已通过，完整矩阵未完成）：** `main` 已包含
`0e3f8a9`。被否定的自定义/平台 `ScrollView` 已替换为平台 `ListView`，12 个固定
高度列表项提供稳定 resource ID 与 content description；逻辑 offset 由首个可见
位置、首项 top、padding 和真实行高计算，只有 offset 大于 0 才产生
`VERTICAL_SCROLL:1`。API 30 clean `emulator-5562` 使用最新 `ListView` bounds 内
一次向上 swipe 后，vertical-only instrumentation 1/1 通过真实 offset/state 后置；
最终设备/runtime/lease 为零。该证据关闭原纵向 surface blocker，但 API 33/34、
Back/Home/Recents/应用切换和三版本各 20 轮成功率仍未验收，N33 保持未勾选。

**追加最终矩阵（N33 已完成）：** 独立 change `n33-clean-repeat-matrix` 增加固定
profile 的 clean 编排器，每轮仅运行一次完整 `fixtureScenario=all`，严格解析新鲜
JUnit，并绑定 N31 serial/fingerprint。Reset 显式释放输入焦点，避免 Launcher 重入
时 IME 压缩窗口；系统返回使用两阶段握手，Home/Settings 由 Activity 生命周期覆盖，
API 33 Recents overlay 由窗口失焦/恢复覆盖。Recents 后置不再依赖 API 33 会失真的
`currentPackageName`，而是验证类型化全局动作成功、唯一 application window 属于
launcher/system，并在重入后重新观察 `SYSTEM_RETURN:RECENTS`。最终 API 30
`emulator-5578`、API 33 `emulator-5580`、API 34 `emulator-5582` 各 20/20，
总计 60/60、成功率 100%；三份报告为 `0600`，不含 hierarchy、截图或失败 stack。
最终 doctor 健康，设备、runtime、AVD/port lease、owned emulator 和临时目录均为零。

**失败清理：** 清除 fixture 数据并恢复 AVD 快照；失败产物交给 N34 管理。

**建议提交：** `test(android): expand native automation fixture`

### [x] Task N34：失败产物、脱敏与 Flaky 统计

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

**实现记录（2026-07-25）：** `main` 已包含 `dc677b7`。4 份严格 Schema 和
49/49 fake-input 测试通过，覆盖 timeout/assertion 产物、递归与编码变体脱敏、
路径逃逸、symlink、inode race、三层物理预算、成功立即清理、TTL 和 20 轮
product/device/infrastructure/flaky 聚合。截图只有调用进程注入的可信 verifier
明确通过才可保留；输入自报 processor/hash、verifier 缺失、返回 false 或抛错均
失败关闭。默认 CLI 不保留 PNG，只能显式省略截图并收集其他脱敏证据；专用 workflow
仅跑 fake-input smoke，不冒充真实 Emulator 矩阵。

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

**实现记录（2026-07-25）：** `main` 已包含 `6290c36`。协议验证 33 项通过，
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

**实现记录（2026-07-25）：** `main` 已包含 `3209164`，共享模块注册和 version
catalog 集成位于 `581f01c`。API 30、33、34 各运行 full、partial、canvas 三项
instrumentation，最终 9/9 通过；每项均验证非空截图、模式状态和
`NETWORK_REJECTED:0`，full 模式完成 WebView 虚拟节点点击与 Reset 闭环。release
APK 静态检查不含 test runner、instrumentation 类或 `INTERNET` 权限；最终设备、
runtime、lease 和 emulator 均无残留。根 Android 单测、Debug 组装、lint、
AndroidTest APK 和 release 构建共 218 个任务通过。

**失败清理：** 清除 fixture 数据、WebView cache 和截图；恢复 AVD 快照。

**建议提交：** `test(android): add offline webview canvas fixture`

## Wave 2：QR Bridge、编辑器领域、WebView 与视觉观察

### [x] Task N36：LAN QR Invitation 协议与威胁测试

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

**实现记录（2026-07-26）：** `main` 已包含协议 `a0b66b6`、Go 消费者
`492fdad` 和 Kotlin 消费者 `c4bff51`。Node LAN 测试 10/10、协议全量 52 项、
Go race 测试及 Kotlin N38 定向 33/33 均通过；三端共同消费 1 个合法 invitation、
18 个威胁 fixture 和 fingerprint、transcript、四个 HKDF key、双方 confirmation
及低阶 X25519 向量。过期、重放、错误地址/网卡、篡改与降级均在 socket 或 token
签发前失败关闭。N37/N38 的真实平台集成仍是各自任务，不影响本协议任务完成。

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

**实现记录（正式验收未完成）：** `main` 已包含代码阶段 `492fdad` 和生产 CLI 接线
`ca19d21`、规格证据 `10f1441`。`aactl bridge lan interfaces|listen` 在 ADB 解析前
分流，要求显式网卡 ID 与单地址候选，先流式输出 payload-only invitation，再完成
一次 accept/双方确认并安全关闭验证 session；timeout、cancel、切网、输出失败和认证
失败均关闭 listener 且不泄露 token、私钥、derived key 或 tag。N37 CLI 定向 6 个
顶层测试（含 2 个生命周期子场景）、LAN/CLI race、`make test/verify/build` 通过。
生产单地址 smoke 在 `if-16-en1 / 192.168.31.16` 先输出 invitation，再以
`LAN_ACCEPT_TIMEOUT`/退出码 5 结束，临时端口随后不可达。该证据不替代持续双向 RPC、
macOS/Windows 同 LAN、Windows 防火墙和真实 Android 互操作，任务保持未勾选。

**追加实现记录（持续 RPC 已接，真实跨端互操作未完成）：** `main` 已包含
`ad59f7f`。Go `Session.Call` 在同一认证加密 session 上严格顺序复用
`bridge.request`/`bridge.response`，绑定 frame type、response ID、protocol 和
LAN token；认证违约、响应错配和 `session.close` 均失败关闭。CLI 只增加
`device.info|recording.list`、1 至 20 次的固定只读 probe，不开放任意动作。
真实 `net.Pipe` 双 Framer 多轮 RPC、LAN/CLI race、Go 全量、verify、build 和
comments 已通过。该证据仍不能替代 Go listener 与 Android emulator 的真实 socket
互操作、Windows 同 LAN 和防火墙矩阵，因此 N37 保持未勾选。

**追加设备记录（Go/Kotlin 真实 socket 已通过，跨平台矩阵未完成）：**
独立 change `n37-n38-cross-runtime-rpc` 在 N31 API 30、33、34 clean emulator
上使用生产 Go listener/handshake/framer/`Session.Call` 与生产 Kotlin outbound
session 完成真实 TCP 互操作。每个 API 的同一 socket 连续三次 `device.info` 后
`session.close`，另一个独立 session 验证加密 `AUTH_INVALID` 回包和 Android
致命关闭；最终设备、runtime、lease 和临时目录均为零。该证据闭合 Go/Kotlin
真实 socket 缺口，但不替代 Windows 同 LAN、Windows 防火墙、真实 Wi-Fi/热点/VPN
与切网矩阵，N37 继续保持未勾选。

**追加二维码输出（代码闭环完成，跨平台验收未完成）：** 独立 change
`n37-n38-qr-integration` 为生产 CLI 默认注入纯 Go、无 CGO 的固定 QR Model 2
Version 28-L provider；invitation 事件输出有界 `terminal-utf8-v1`，不调用外部
命令、不打开 GUI、不创建文件。仓库 decoder 与仓库外 `gozxing` 均精确还原 N36
payload；容量不足只降级 `payload-only` 并保留 `manualCode`，取消或内部错误仍
失败关闭并清理 listener/密钥。该证据关闭 N37 二维码生成缺口，但不替代 Windows
同 LAN、防火墙、热点/VPN/切网矩阵，因此 N37 保持未勾选。

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

**实现记录（正式验收未完成）：** `main` 已包含代码阶段 `c4bff51`、生产 socket/
导航接线 `3b57ca2` 和规格证据 `fa98008`。App 现可从桌面 Bridge 页面进入 LAN
配对，手工输入严格 invitation，选择候选与 Android Network 并确认短指纹后，以
`Network.bindSocket` 绑定非 VPN Wi-Fi/以太网并连接 numeric IP literal。NDJSON
握手/加密 frame 有界且严格失败关闭；修正后的 nonce/AAD 与 N37 字节契约一致，固定
ciphertext 断言防止跨端漂移。N38 定向 43/43、App JVM 319/319、debug/release
assemble、lint 和中文注释门禁通过。当前未接入受测扫码 provider，故不声明
`CAMERA` 并明确保留手工路径；真实 N37 同 LAN、持续双向 RPC、相机授权/扫码、切网、
防火墙/OEM 和 API 设备矩阵尚未验收，任务保持未勾选。

**追加实现记录（持续 RPC 服务端已接，真实跨端互操作未完成）：** `main` 已包含
`fccd6b9`。Android 在同一 LAN socket 上严格读取 desktop-to-client 加密 frame，
复用 Bridge request schema、业务 handler、风险门、replay cache 和 deadline，
再返回 client-to-desktop 加密 response；LAN token 与 loopback session 权限隔离。
同一 socket 使用单线程顺序调度，认证致命错误加密回包后关闭，切网、过期、显式关闭
和对端断开均中断阻塞读取。强制定向 64/64、App 全量、lint、debug、
AndroidTest、release、comments 和差异检查通过。Go/Kotlin 真实 socket 多轮 RPC、
致命认证、deadline 与关闭互操作仍待执行，因此 N38 保持未勾选。

**追加设备记录（API 30 crypto 兼容与真实 RPC 已通过）：** 首轮 API 30 设备证据
发现 Android 11 不提供 `KeyPairGenerator("X25519")`，此前 JVM/API 33+ 测试未覆盖
该生产阻塞。实现保持 N36 wire suite 不变：API 33/34 优先 JCA X25519，API 30 在
generator/factory/agreement 缺失时使用 Tink 1.18 的 RFC 7748 字节 API；两条路径
继续拒绝七个低阶点和全零 shared secret，并经双边 shared-secret 测试与 Go 真实
握手验证一致。API 30、33、34 各通过多轮 `device.info`、`session.close` 和独立
`AUTH_INVALID` session。相机扫码、可信系统时钟、切网、真机/OEM、真实无线网络
和 Windows 防火墙仍未验收，N38 继续保持未勾选。

**追加扫码与手工码闭环（代码完成，真机相机待验收）：** App 现可发现并显式启动
F-Droid 构建的 ZXing 4.7.8(108) scanner，且只接受本回合从
`https://f-droid.org/repo/com.google.zxing.client.android_108.apk` 下载并由
Android SDK `apksigner` 验证的唯一 certificate SHA-256
`1f97ed3c5800111d4627d53512bb38102fda0385c45f763b4b92d6341d29f1ad`；
APK SHA-256 为
`2ed4c2661ed0e2e56b2980d59291dacd58040d219cb7e83b3f6db1102d2ed483`。
未知 Play/GitHub signer、错签、缺签、多 current signer、仅历史命中、轮换 history
和签名 API 异常全部失败关闭为 provider unavailable。App 本身不声明 `CAMERA`，
相机权限由外部 scanner 人工处理；拒绝/取消/无相机后手工码仍可用。扫码 JSON 与
N37 `AIAUTO1-` base32 手工码复用同一严格 parser/preflight，解析后只保留安全摘要，
仍需用户选择地址、网卡并核对短指纹才连接。该证据关闭 N38 scanner/provider 与
手工码解析代码缺口，但不替代真机安装、相机权限、OEM、真实无线/切网和 Windows
防火墙验收，因此 N38 保持未勾选。

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

**实现记录（2026-07-25）：** `main` 已包含 `8180ab6`。15/15 editor 定向测试与
214/214 App 全量单测通过；12 类编辑命令均可 Undo/Redo，固定 seed 的 100 步编辑
可完整回退和重做。dry-run 构造器只有 snapshot、selector、coordinate 和 condition
四类只读端口，不存在动作提交能力；revision 冲突保留 attempted/current 双方，
严格导入与默认脱敏导出预览不创建临时文件。

**失败清理：** 放弃事务并恢复最后已保存 revision；删除导入临时文件。

**建议提交：** `feat(recording): add script editor domain`

### [x] Task N43：WebView 语义兼容与多页面回放

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

**实现记录（设备验收未完成）：** `main` 已包含代码基线 `9a21243`。adapter 与
orchestrator 定向测试 20/20、App 全量单测 219/219 通过；只生成类型化语义动作，
节点缺失、歧义、observation 过期及 package/page/API/WebView 漂移均失败关闭。
设备 follow-up 尚未产出可执行 instrumentation 入口；API 30/33/34 各 20 轮和清理
证据均未开始，因此保持未勾选。

**追加调查记录（API 30 聚焦输入候选被否定）：** `main` 已包含调查证据
`1ad06aa`。固定 API 30 clean AVD `emulator-5570` 的 fingerprint 与 WebView
91.0.4472.114 均符合 N31 profile；单轮 instrumentation 证明输入虚拟节点初始不仅
缺少 `ACTION_SET_TEXT`，也不暴露可用于显式聚焦的 `ACTION_CLICK`，因此第一步即以
`semantic action 16 unavailable` 零提交停止，未执行后续动作，也未尝试坐标、
剪贴板、IME shell、DOM/JavaScript 或直接 ADB 文本降级。被设备证据否定的代码实验
已完整撤销；runner stop 后设备数、runtime、lock 和 lease 均为零。API 33/34 与
二十轮矩阵未运行，任务继续保持未勾选。

**追加能力矩阵（输入结论已更正，完整验收未完成）：** `main` 已包含
`9cc8db6`。API 30/33/34 的只读 matrix 一致：唯一 WebView `EditText` 均为
editable，并暴露 `ACTION_CLICK=16` 与 `ACTION_SET_TEXT=2097152`；此前输入失败是
`aria-label` selector 错误，不是 API 30/Android 11 或 WebView 91 缺少完整语义输入。
三版本单轮均完成 input、dynamic DOM、scroll、跨页导航和 AndroidX dispatcher
Back；long-click 不暴露 `ACTION_LONG_CLICK=32`，iframe/详情 click 返回成功但语义
后置不可见，均明确归为 hybrid-required。安全降级只允许 N45 授权视觉 long-click
或对已提交语义 click 做视觉后置验证，不重复点击；无可信 observation 时失败关闭。
迁移 dispatcher 后 API 30/33 各 1/1，通过；API 34 首轮基础 click 后置未更新，新
clean snapshot 复跑通过，实际为 1/2。每版本 20 轮、95% 成功门和 N45 真实混合闭环
仍未完成，因此任务继续保持未勾选。

**追加重复矩阵（60/60 通过，混合闭环仍未完成）：** 独立 change
`n43-clean-repeat-matrix` 固定每轮先恢复 N31 clean snapshot，再运行一次 N43
instrumentation 并解析新鲜 JUnit。初始 API 34 为 17/20，复现 click 返回 true 但
后置仍 ready 的波动；修复前报告保留。提交前增加同一 native generation 下四次
只读 ready/唯一 click/action 稳定观察，不重放已提交动作。最终代码在 API
30/33/34 各 20/20，总计 60/60，三份 `0600` 报告均 `cleaned:true`，最终
设备/runtime/lease 为零。long-click 仍无语义 action，iframe/detail 后置仍为
hybrid-required；N43 已按目标完成兼容分级、语义流程、失败关闭和多页面回放，因此
本任务现勾选完成。该勾选不代表 N45 授权视觉动作或真实混合闭环已经交付。

**失败清理：** 清除 WebView 数据、脚本和产物，恢复快照。

**建议提交：** `test(android): validate webview semantic replay`

### [x] Task N44：视觉 Observation 与 AI 候选

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

**实现记录（App 用户路径完成，桌面公开路径未完成）：** `main` 已包含服务层
`ccb801d`、App 会话集成 `1682806` 及规格证据 `5b8bffe`、`d86cfde`。Android
visual/会话定向、App 全量单测、Go `internal/visual` 与独立 MCP adapter
race 测试通过；覆盖可信 verifier、同 ID 防漂移、图片副本清零、0/90/180/270
crop 映射、会话代次、稳定 hierarchy、自然屏幕/crop 几何、低置信度和歧义失败
关闭。显式授权 App AI 会话已能把同 observation 元数据与最小 PNG 传给 Provider，
结束后撤销并清零；独立 adapter 仍未注册到共享 MCP server，因为桌面端尚无能同时
提供可信 PNG/hierarchy 的采集端口，因此主任务保持未勾选。

**追加桌面公开路径（N44 已完成）：** 独立 change
`n44-desktop-visual-observation` 提供生产
`device -> hierarchy -> screenshot -> hierarchy -> device` 稳定门，前后设备、
hierarchy、前台包或 rotation 漂移均零候选失败关闭。PNG、自然方向 screen、完整
capture crop 与脱敏 UIAutomator tree 绑定同一短生命周期 observation；password
节点及后代不返回 label。共享 MCP 现注册只读
`android_visual_target_propose`，生产 provider 从唯一 role/label 节点生成
`template` 候选，`actionCommitCount` 固定为 0。transport 在 visual response 写出
成功或失败后按 observation ID 清零服务端图片，connection/server 关闭也清理 pending。
API 34 clean `emulator-5584` 的生产 MCP smoke 返回同 observation PNG、
`dev.aiauto.fixture`、rotation 0、脱敏 hierarchy 和唯一候选，未执行动作；最终
doctor 健康，设备、runtime、lease 与构建 APK 均为零。N44 的 App 用户授权路径和
桌面公开路径均已闭合，因此本任务现勾选完成；该结论不代表 N41 截图编辑或 N45
历史视觉步骤 rebind 已完成。

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

**实现记录（详情入口完成，真实截图点选和设备验收未完成）：** `main` 已包含可嵌入
编辑器组件 `9151360`、详情入口 `1f11fc7` 及规格证据 `71a4465`、`647550d`。
JVM 定向、App 全量单测、AndroidTest APK 编译、lint、comments 和 diff-check
通过；覆盖脚本复制、步骤重排/启停/删除/复制、完整表单原子提交、Undo/Redo、
dirty/离开确认、revision conflict、dry-run 首失败定位和短生命周期 observation
点选。详情页现在使用 keyed ViewModel、真实 store revision CAS 和只读
Accessibility dry-run；无活跃授权时明确不显示截图 surface。真实授权 screenshot
provider 与 Compose instrumentation 设备执行尚未验收，因此保持未勾选。

**追加设备记录（真实详情入口通过，截图生产接线缺口已明确）：** `main` 已包含
N41 设备 instrumentation `6084c8a` 与 ignored RED 修复 `86ffbe8`。API 34 clean
`emulator-5568` 的 JUnit XML 为 6 tests、0 failure/error、3 skipped：真实详情入口
两项与 debug-only 合成截图 provider 生命周期一项通过，覆盖步骤操作、Undo/Redo、
dirty 离开确认、脚本复制、revision conflict 和 dry-run 定位。三项 skipped 合约
分别锁定真实 Host 授权 provider 注入、point 保存 observation/hash 和 normalized
bounds 框选缺口，不把未实现能力伪报为通过。最终设备/runtime/lease 为零；N41
仍缺这三项生产接线与对应设备转绿，因此保持未勾选。

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

**实现记录（安全执行内核完成，生产端口与设备矩阵未完成）：** `main` 已包含
`982919f` 与规格证据 `a648582`。N45 定向 22/22、App 全量单测、lint、comments
和 diff-check 通过；覆盖三分辨率、四旋转、crop/window/inset/density 映射、
observation/candidate/package/screen/expiry/secure 前置门、类型化 tap/long-click/
swipe、只读后置 verifier 及提交后不重试。ReplayEngine 对 visual/coordinate/manual
缺端口时明确 `VISUAL_REPLAY_UNAVAILABLE`，不会回落旧像素或 semantic selector。
生产 UI/Bridge 尚未注入 observation/candidate/current-screen/verifier 端口，且
API 30/33/34 fixture 未实际命中，因此保持未勾选。

**追加实现记录（App 授权视觉 production port 已接，设备矩阵未完成）：**
`main` 已包含 `383c60a` 和规格证据 `eee8d7f`。App AI 会话的视觉 tap、
long-click、swipe 现在必须携带严格 `visualTarget`，绑定目标包、observation ID、
PNG SHA-256、model candidate UUID、>=0.70 置信度及 screenshot crop-local
point/bounds；Planner 复用 N44 `VisualProposalService` 完成 crop/rotation 映射，
再复用 N45 preflight/coordinate planner。source/pre/post 三阶段授权截图在 risk、
人工确认、执行前复核、typed action 和后置验证期间存活，所有成功/拒绝/失败/停止/
超时/取消路径撤销并清零。Accessibility dispatch 再次绑定实际 source package；
后置验证失败明确为已提交一次且不重放。App JVM 337/337、lint 0 error/28 warning、
AndroidTest APK、N47 59/59、N52 21/21、仓库 test/verify/build 和 Android
test/lint/build 均通过。N47 desktop visual route、历史脚本 rebind 及 API 30/33/34
多分辨率/旋转设备命中尚未验收，因此 N45/N47/N52 主任务继续保持未勾选。

**失败清理：** 停止当前回放，清除 observation 和截图，恢复 fixture 状态。

**建议提交：** `feat(recording): replay explicit visual coordinates`

### [x] Task N46：第三方 APK Manifest 与仓库外缓存

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

**实现记录（2026-08-01）：** `main` 已包含离线 verifier `725d628`、固定工具
inspector `4d41fd6`、现代 badging 修复 `ab0e901`、N31 verified install runner
`9414b9c` 及对应规格证据。
N46 全量 49/49 零依赖 Node 测试通过，覆盖严格文本 manifest、仓库外 cache、
symlink/inode/hash/size 防替换、类型化 inspector、固定 ADB argv、clean/final
snapshot 生命周期和安装前二次校验。inspector 固定并校验仓库外 `aapt2`、Java
和 `apksigner.jar` identity，只以 `shell:false` 固定 argv 执行并严格解析六项
元数据；现代 `minSdkVersion` 与旧 `sdkVersion` 严格二选一，微信约 490 KiB 合法
warning 在有界 1 MiB 工具输出预算内通过。

五个固定 arm64 制品均从厂商官网或官网当前脚本声明的官方 CDN 来源取得：B站
`9.5.0`、抖音 `39.8.0`、小黑盒 `1.3.392`、微信 `8.0.76`、支付宝
`12.12.10.8000`。仓库只保存 package、版本、ABI、minSdk、来源、许可备注、
SHA-256 和签名摘要 manifest；完整 APK 以 `0600` 保存在
`AACTL_ANDROID_APK_CACHE` 的仓库外 SHA-256 cache，staging 为空，工作区与全部
Git 历史的 APK/APKS/AAB/XAPK 路径均为零。

API 30、33、34 × 五包共 15/15 唯一组合完成 clean snapshot 安装、设备
`base.apk` 拉回同 size/SHA-256、六项 metadata 与签名复核、数据清理、clean
restore 和 stop。API 34 微信首次因 5 分钟 pull 预算失败，该轮零残留；增加独立
10 分钟有界预算并在新 clean serial 重跑成功，总计 16 次尝试。最终设备、runtime、
AVD/port lease、owned emulator 和临时安装目录均为零。该结论只完成 N46 制品准备
与安装验收，不代表 N48-N51 动态场景或登录能力完成。

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

**实现记录（Runner 安全内核完成，production adapter/设备验收未完成）：**
`main` 已包含 N47 安全内核 `cdc7797` 和规格证据 `1da17db`。三份严格 Schema
覆盖场景 DSL、运行报告和兼容汇总；native/Web/Canvas 两个 fixture 场景经 fake
类型化端口动态执行 18 步，联合覆盖 10 类动作和 semantic/hybrid/visual 三种
route。Runner 每步绑定最新 observation、前后台包、页面分类、pre/post condition、
timeout、route/score/attempts 和动态区域；广告、更新、模拟器检测、网络失败、
登录墙和 unknown 默认零提交，A-B variant 仅在步骤显式接受时继续。明确拒绝记
0 次提交，明确成功记 1 次，executor 异常记 `ACTION_COMMIT_UNKNOWN` 和
`actionCommits:null`，不伪报零或重放。失败 run 在 cleanup 前通过 N34 Schema 后
交给 artifact 端口，四步清理在成功/失败/取消均执行。N47 smoke 25/25、N34
artifacts 49/49、comments 和 diff-check 通过。本轮未下载/安装 APK；production
observer/router/executor adapter、真实 emulator 与第三方 App 验收尚未实现，
任务保持未勾选。

**追加实现记录（semantic production adapter 已接，input/visual/设备验收未完成）：**
`main` 已包含固定 aactl adapter `aa67e64` 和规格证据 `f83d57b`。Adapter 固定仓库外
`aactl` identity（path/dev/inode/mode/time/size/SHA-256），每次执行前复核，以
`execFile`、固定 argv、`shell:false`、显式 serial、输出预算和步骤剩余 deadline
调用。严格 envelope 拒绝重复 key、尾随 JSON、成功 stderr 和字段冲突；合法非零
失败 envelope 保留稳定 code。semantic snapshot 有界校验节点、状态、动作、包与
maxDepth，condition catalog 不接受 callback；唯一可见、启用、非敏感节点才能生成
一次性 route token。tap/long-click/scroll/Back/Home/Recents 走固定 Bridge action，
launch/switch-app 走 direct launch；明确拒绝/成功/异常映射提交 false/true/unknown。
Runner 经 adapter 的 snapshot→action→post snapshot→condition→cleanup 端到端 fake
通过，全包 53/53、N34 49/49、comments/diff-check 通过。visual/hybrid 和
visual-state 因 N45 production port 缺失失败关闭；input 因现有 CLI `--action` 会把
明文放入 argv 而返回 `INPUT_ADAPTER_UNAVAILABLE`，等待固定 stdin action 模式。
真实 emulator/Bridge 仍未运行，任务保持未勾选。

**追加实现记录（stdin input 已安全接线）：** `main` 已包含固定 stdin action
`e676a6a` 和规格证据 `259bbbc`。`aactl bridge action --stdin` 与 `--action`
严格互斥，stdin 最多 1 MiB，只接受 UTF-8 单 JSON object，递归拒绝重复 key、
尾随值、NUL、数组/标量和读取错误；action byte slice 在 RPC 后清零，错误不回显
输入。Adapter 的 input route 现在通过 child stdin 写入固定 `ui.setText` JSON，
argv 不含明文/action；stream 缺失/错误、进程异常和成功 data 畸形均记提交状态
未知，明确拒绝/成功映射 false/true，步骤 deadline 过期则零进程调用。Runner input
端到端 fake 覆盖 values→route→stdin→post snapshot→condition→cleanup。N47
全包 59/59、N34 49/49、Go race/全量测试、verify/comments/diff-check 通过。
真实 emulator input、visual/hybrid adapter 和第三方 App 仍未验收，任务保持未勾选。

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

**追加被动基线（动作场景未开始）：** 独立 change `n46-passive-app-smoke`
复用 N46 verified descriptor 与 N31 clean lifecycle，只执行 package-only launch
和一次 UIAutomator hierarchy，不点击隐私、权限、登录或页面内容。B站 `9.5.0`
在 API 30 target package 可见并出现 consent/login 信号；API 33 hierarchy 不可用；
API 34 有 hierarchy 但 target package 不可见。三轮均完成安装同字节/签名复核、
`pm clear`、clean restore 和 stop。该证据只证明启动观察基线，不满足搜索、点击、
长按、滚动、系统导航或连续 10 次验收，N48 保持未勾选。

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

**追加被动基线（动作场景未开始）：** 抖音 `39.8.0` 在 API 30 完成 target
package 可见的单次 hierarchy，未命中固定登录/广告/网络/模拟器信号；API 33/34
均在 hierarchy 阶段返回稳定 `SCENARIO_FAILED` 并完成清理。API 30 另有一次设备
`base.apk` 复核失败，后续 clean 轮成功，失败未被改写为通过。全过程不点赞、评论、
关注、发送、点击或 swipe；N49 的动作覆盖和连续 10 次仍未开始，任务保持未勾选。

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

**追加被动基线（动作场景未开始）：** 小黑盒 `1.3.392` 在 API 30/33/34 三个
clean profile 均完成 target package 可见的单次 hierarchy，并稳定命中
consent/permission 信号；runner 未点击或授权。三轮均完成 verified install、数据
清理和 restore。搜索、详情、长按、滚动、swipe、多页面与十轮统计尚未执行，N50
保持未勾选。

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

**追加宿主被动基线（未进入小程序）：** 微信 `8.0.76` 在 API 30/34 target
package 可见，API 33 hierarchy 不可用；支付宝 `12.12.10.8000` 在 API 30/33/34
均 target package 可见，并稳定命中 consent/permission/network failure 信号。
runner 未点击同意、权限、登录或任何宿主页面，也未进入、搜索或识别小程序。因此本
证据只确认两个固定宿主可安装并被动启动，不产生完整语义/混合/仅视觉/不支持的任何
小程序样例，N51 保持未勾选。

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

**实现记录（编排与报告内核完成，真实设备矩阵未完成）：** `main` 已包含 N52
内核 `502a5a1` 和规格证据 `064fe96`。固定计划恰好为 API 30/33/34 ×
native/WebView/Canvas/编辑器/回放 × 20 轮，共 300 轮；capability 在零启动阶段
严格校验，单轮绑定显式 serial、API、fingerprint、clean snapshot 和 N47 报告。
成功、失败和取消均执行类型化 stop/residue 补偿清理，生命周期、残留和 fingerprint
风险 fail-fast。严格 Schema 与语义 validator 从 runs 重算 N34 统计、N47
route/page/error、95% 成功率、100% 清理率和未知提交门，并拒绝重复轮次、陈旧聚合、
自由异常及任意内容键。N52 21/21、N47 59/59、N34 49/49 和 comments/diff-check
通过，跨平台 Node 24 workflow 仅运行离线 fake smoke。N32 test-only Bridge 仍只读，
N47 visual production route 与 emulator 动作授权链尚无设备验收，真实
3 × 5 × 20 矩阵未运行，因此主任务保持未勾选。

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
