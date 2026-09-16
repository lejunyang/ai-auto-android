# C 端随身 Agent UI 实现追踪

> 本文档是本次 UI/随身 Agent 实现的唯一日常进度入口。
> 实现细节与技术决策留独立 change spec（`.trae/specs/n56-consumer-ui-implementation/`、
> `.trae/specs/n56-consumer-ui-wave2/`），本文不重复长日志。
>
> 状态定义：待做 / 进行中 / 已实现待设备验证 / 已验证（含设备截图证据） / 阻塞
> 最后更新：2026-09-17

## 总目标

将 `docs/ui-prototype/index.html` 确认的四 tab 信息架构落地为真实 Android 体验：
助理、技能、记录、我的。所有状态来自真实运行时，不硬编码就绪/结果/历史。
在技术与授权边界内补齐语音、悬浮、运行通知、会话历史、录制编辑器体验对齐。

## 分项状态

| 模块 | 内容 | 状态 | 验收证据 |
|---|---|---|---|
| 导航骨架 | 底部四 tab + 嵌套导航 + 路由参数 Uri.encode | 已实现，待设备复验 | compileDebugKotlin通过 |
| 主题 | 固定配色（背景#FAFBFC/表面#FFFFFF/主色#2563EB），无动态取色 | 已实现，待设备复验 | Theme.kt固定配色；token层已建 |
| 设计token层 | AppColors/AppShapes/AppSpacing/AppTypography + DesignTokenParityTest | 已实现，待单复验 | AppTokens.kt编译通过；parity测试待运行 |
| 共享组件 | AppComponents.kt + ScriptStoreProducers.kt（IO+错误态+ON_RESUME） | 已实现，待设备复验 | compileDebugKotlin通过 |
| 助理页 | Composer卡片+快捷chips+2列技能网格+语音按钮，首屏压缩中 | 进行中 | v5/v6截图；首屏信息量待对齐原型 |
| 技能页 | 脚本列表+紧凑空态+虚线轻卡teach+灵感分组(明确未实现) | 已实现，待设备复验 | compileDebugKotlin通过 |
| 记录页 | 真实会话历史+全部/已完成/需处理真实筛选+详情弹窗 | 已实现，待有数据设备验证 | compileDebugKotlin通过；test-only fixture待预置 |
| 我的页 | 紧凑布局+52dp头像+真实通知状态(不硬编码已启用) | 已实现，待设备复验 | compileDebugKotlin通过 |
| 第一波修复 | 路由编码、targetPackages响应式、脚本异步IO、文案准确 | 已实现 | compileDebugKotlin通过 |
| 模块A：会话历史持久化 | SessionHistoryRecord+Store(脱敏/有界/可删)+ViewModel终态写入+记录页真实筛选 | 已实现，待单测+设备验证 | compileDebugKotlin通过；保存/读取/删除/有界/损坏测试待补 |
| 模块B：语音输入 | VoiceInputHandler+权限检查+拒绝/无服务降级+外发说明+助理页语音按钮 | 已实现，待设备验证(权限拒绝) | compileDebugKotlin通过 |
| 模块C：前台服务+运行通知 | 重构为真正单一owner：SessionCoordinator创建/启动engine，ViewModel仅订阅，通知/overlay走同一command端口，终态历史由coordinator写 | 已实现，待设备复验 | SessionCoordinatorTest 16测试通过；compileDebugKotlin通过 |
| 模块D：受控悬浮窗 | OverlayController+权限引导+运行状态/结果+收起展开+我的页入口 | 已实现，待设备复验 | OverlayController/OverlayWindow编译通过 |
| 模块E：录制编辑器 | 步骤编排/用一句话表达tab+UpdateDescription领域命令+撤销/保存冲突/高级字段 | 已实现，待设备验证 | compileDebugKotlin通过；tab结构已建 |
| 模块F：语言归纳 | SkillLanguageGenerator+Provider调用+敏感过滤+不静默覆盖+已接入编辑器 | 已实现，待设备验证 | compileDebugKotlin通过；factory读取ProviderConfig/apiKey |
| 设备视觉验证 | 固定AVD(ui-visual-35 1080x2400 420dpi zh-CN)+显式serial+可靠截图+PIL验证 | 已验证（关键路径） | v15四tab+任务输入+语音权限截图PIL verify通过；1080x2400 |
| 全量验证 | make comments + testDebugUnitTest + assembleDebug + lintDebug | 已验证 | testDebugUnitTest 479通过/59跳过；assembleDebug BUILD SUCCESSFUL；APK 76.9MB SHA256:60AAC758... |
| 无用文件清理 | HomeScreen.kt/TaskScreen.kt/SessionViewModelTest.kt/旧截图/一次性脚本已删除 | 已完成 | git status确认删除 |

## 安全约束（不可妥协）

1. **锁屏/授权失效强制暂停，后台不等于暂停**：自动化执行期间控制 App 必然切到目标 App 而进入后台，此时前台服务继续持有会话运行，不暂停。仅在以下情况强制暂停：屏幕锁定（ACTION_SCREEN_OFF）、用户明确接管（通知/悬浮点停止或暂停）、无障碍授权失效。恢复需用户显式继续并重新观察页面。
2. **前台服务为 runtime owner**：AutomationSessionEngine 的生命周期由前台服务持有，Activity 仅为 UI 展示层，绑定/解绑服务不影响会话运行。配置更改或已有会话运行时，禁止启动第二会话（单任务串行）。
3. **历史脱敏**：落盘的任务描述、失败消息、审计摘要必须经过脱敏（移除密码/验证码/敏感文本模式），audit 只保留最近有界条数（上限 20），用户可删除单条或全部。
4. **语音外发透明**：使用系统 SpeechRecognizer，如实告知用户识别可能由系统/云端服务处理，不宣称本地离线。权限拒绝后降级为文本输入。
5. **可观察可停止**：会话运行期间，通知和悬浮窗始终展示状态并提供暂停/停止入口，用户随时可收回控制权。

## 未解决问题与已知缺口

1. **模块E 录制编辑器体验对齐**：现有技术子页面保留，原型的步骤时间线/操作行/工具栏体验对齐待下一轮实现。高级编辑字段、等待/失败规则、撤销/保存冲突已在现有编辑器中保留。
2. **模块F 语言归纳集成**：SkillLanguageGenerator 已实现，待接入录制编辑器（生成技能描述建议，不静默覆盖步骤）。
3. **悬浮窗运行时自动显示**：OverlayController 已实现，我的页有授权入口，但会话运行时自动显示悬浮窗待集成（需用户先授权 SYSTEM_ALERT_WINDOW）。
4. **前台服务运行时设备验证**：通知暂停/停止、锁屏强制暂停需在真实会话运行时设备验证（当前编译通过，我的页显示"运行通知 已启用"）。
5. **真机验证**：当前所有设备验证在模拟器（ui-visual-35）完成，真机兼容性需用户授权后验证。
6. **无用文件清理**：HomeScreen.kt 等旧文件待确认无引用后删除（用户条件授权，需最终确认）。

## 视觉验收标准（机器可判定协议）

### 根因分类
1. **环境失真**：emulator-5554 为 320x640/160dpi（已弃用）；API35 动态取色覆盖原型蓝（已固定配色）。
2. **缺少 token 层**：直接用 Material 3 默认 typography/shape/组件，逐屏微调无法收敛。**这是根因，必须先建 token 层。**
3. **比较方式不可判定**：只有文字化差异，没有测量值和同尺度并排。

### 对齐协议
1. **统一坐标系**：验证 AVD 用 1080x2400/420dpi（2.625x），dp 视口为 411x914。原型用 411x914 视口截图，原型 px 直接当 Compose dp 用。
2. **抽取机器可读 token**：从原型 `getComputedStyle` 导出 `artifacts/ui-validation/tokens.json`，包含颜色（sRGB hex）、圆角、间距、文字角色（font-size/weight/line-height）、关键控件盒模型。
3. **Compose token 层 + parity 单测**：新增 `AppColors/AppShapes/AppSpacing/AppTypography` 常量，单测断言等于 tokens.json，漂移由 `make android-verify` 拦住。固定配色为默认，动态取色最多显式可选项。
4. **状态矩阵双端同名截图**：原型与 App 相同状态，文件名成对（如 `home.empty.html.png` / `home.empty.app.png`），覆盖助理首页（空/有数据）、四 tab、任务输入与计划、运行/暂停/失败/结果、录制与步骤编辑、我的与二级页、深色与大字号。
5. **同尺度并排对照**：App 截图按 411dp 宽等比缩放，生成并排对照图放 `artifacts/ui-validation/pairs/`，逐项给测量值。不做整图像素 diff，按区域测量判定。
6. **容差与合法差异白名单**：颜色/圆角/字号完全一致；行高 ±1dp；间距 ±2dp；点击区 ≥44dp。允许平台差异：系统状态栏/手势条、Material 涟漪与选中态、IME、系统字形（只比字号字重行高不比字形）、滚动条。
7. **无法对齐项二选一**：用自定义 composable 重做样式，或在本文档显式登记为已接受偏差，不允许沉默不一致。

### 执行顺序
复位/新建正常分辨率 AVD → 抽 token → 建 Compose token 层与 parity 单测 → 逐屏截图并排对比 → 超差回改后复验。截图须先 PIL verify+load 确认可解码再看图。

## 关键路径

- 原型：`docs/ui-prototype/index.html`
- 导航：`android/app/src/main/java/dev/aiauto/android/ui/AiAutoAndroidApp.kt`
- 共享组件：`android/app/src/main/java/dev/aiauto/android/ui/components/`
- 四个主页面：`ui/home/AssistantScreen.kt`、`ui/skills/SkillsScreen.kt`、`ui/history/HistoryScreen.kt`、`ui/settings/SettingsScreen.kt`
- 会话历史（模块A）：`android/app/src/main/java/dev/aiauto/android/automation/session/history/`
- 字符串：`android/app/src/main/res/values/consumer_ui_strings.xml`
- Change spec：`.trae/specs/n56-consumer-ui-implementation/spec.md`、`.trae/specs/n56-consumer-ui-wave2/spec.md`

## 验证命令

```powershell
pwsh -Command "make comments"       # 中文注释覆盖
pwsh -Command "make test"           # Go 测试
pwsh -Command "make verify"         # 协议+技能+注释+元数据
pwsh -Command "make build"          # Go 构建
pwsh -Command "make android-verify" # Android 单测+构建+lint
```

设备验证：固定 AVD 启动后，用显式 serial 执行 `adb install`、`adb shell` 操作。截图必须用可靠二进制路径：`adb shell screencap -p /sdcard/<name>.png` 后 `adb pull` 到本地，再删除设备临时文件；禁止用 `adb exec-out screencap` 经 PowerShell 文本管道（会转码损坏 PNG）。截图存 `artifacts/ui-validation/`，需经 PIL `verify()`+`load()` 解码确认后 Read 看图。

## 设备指纹（UI 视觉验证专用 AVD）

| 项目 | 值 |
|---|---|
| AVD 名 | ui-visual-35 |
| serial | emulator-5556 |
| API | 35 (android-35, google_apis x86_64) |
| 分辨率 | 1080x2400 |
| densityDpi | 420 |
| locale | zh-CN |
| 导航模式 | gestural (hw.navigation=none) |
| 来源 | 复制 osdk-test-35 后修改 config.ini（hw.lcd.width/height/density + persist.sys.locale），未改动 profiles.json 既有 api-* 回归基线 |

> 旧 AVD osdk-test-35 (emulator-5554) 配置为 320x640/160dpi，属极小屏，其截图不作为视觉结论。

## 视觉验证证据（v2，正常分辨率）

截图路径：`artifacts/ui-validation/v2-0{1..4}-*.png`，全部经 PIL verify 通过（1080x2400 RGBA）。

| 截图 | 界面 | 状态 |
|---|---|---|
| v2-01-home.png | 助理首页 | 已获取，视觉差异修复中 |
| v2-02-skills.png | 技能页 | 已获取，视觉差异修复中 |
| v2-03-history.png | 记录页 | 已获取，视觉差异修复中 |
| v2-04-settings.png | 我的页 | 已获取，视觉差异修复中 |

### 逐屏对比结论（原型 → 实机 → 差异 → 修法）

**助理首页：**
- 问候区：原型「你好，今天想做点什么」小字 + 「有什么，可以交给我？」大标题 + 右侧 auto 旋转图标 → 实机「你的随身自动化助理」subtitle + 「有什么，可以交给我？」title + 「待配置」文字徽章 → 差异：缺「你好」问候语，右侧是文字徽章而非图标 → 修法：GreetingHeader 添加 hello 小字行，就绪徽章用图标+圆点。
- 任务输入区：原型 textarea + 底部「选择应用」pill + 语音 icon-btn + 发送 icon-btn → 实机 OutlinedTextField + 下方「选择应用」outlined 按钮 + 「开始任务」禁用按钮 → 差异：无语音按钮，发送是文字按钮，布局偏松 → 修法：添加语音按钮（模块B集成），发送按钮用 icon 形式。
- 快捷提示：原型有「整理稍后阅读」「提取页面重点」chips → 实机无 → 修法：添加 quick chips（可选，MVP 可延后）。
- 常用技能：原型 2 列 skill-grid 卡片 → 实机空态文字 + teach-card → 差异：有数据时应用 grid → 修法：有脚本时用 2 列网格布局。
- 背景色：原型 `#eef1f5` 冷灰蓝 → 实机偏白 → 修法：Theme background 调为冷灰蓝。

**技能页：**
- 筛选：原型 3 个（全部/我教的/灵感）→ 实机 2 个（全部/我教的）→ 差异：缺「灵感」→ 修法：MVP 可暂留 2 个，或添加「灵感」空态。
- teach-card 图标：原型 record 图标 + chevron → 实机圆点图标 → 修法：换为录制/播放图标。

**记录页：**
- 标题顺序：原型「运行记录」大标题在上 + 「只留下有用的结果」subtitle 在下（setHeader 后 page-heading） → 实机 GreetingHeader subtitle 在上 title 在下 → 差异：顺序反了 → 修法：记录页不用 GreetingHeader，直接用 page-heading 布局。
- 空态：原型「没有符合条件的记录」小字 → 实机大时钟图标 + 「暂无运行记录」 → 差异：空态过重 → 修法：简化空态。

**我的页：**
- 状态标签色：原型 amber `#9b6419` 琥珀色 → 实机粉色 → 差异：警告色不对 → 修法：TagChip WARNING tone 用琥珀色。
- 「随时唤起」区：原型有悬浮入口/运行通知 switch（标记未来概念） → 实机无 → 差异：缺悬浮/通知入口 → 修法：模块D实现后添加。
- 整体：logo + 状态卡 + 连接与能力 + 控制权说明，基本对齐原型。

**全局：**
- 圆角：原型 22px → 实机 Material3 默认偏小 → 修法：自定义 Shapes 增大圆角。
- 卡片描边：原型 `--line:#e5eaf1` 细描边 → 实机卡片多为纯阴影无描边 → 修法：卡片添加细描边。
- 主色：原型 `#2563eb` → 实机基本一致（固定配色已生效，无动态取色偏差）。
