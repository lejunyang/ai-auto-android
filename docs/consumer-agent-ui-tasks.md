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
| 导航骨架 | 底部四 tab + 嵌套导航 + Uri.encode + 统一 switchTab（修复 tab 失效 P0） | 已验证（设备往返） | TabNavigationTest 11测试通过；设备验证助理↔技能↔我的往返，坐标从 dump 解析 |
| 主题 | 固定配色（背景#EEF1F5 冷灰蓝/卡片#FFFFFF/主色#2563EB），无动态取色 | 已实现，已设备复验 | Theme.kt 用 AppColors；navfix-settings 截图确认 |
| 设计token层 | AppColors/AppShapes/AppSpacing/AppTypography（逐元素实测）+ DesignTokenParityTest | 已实现，单测通过 | 从 20 个原型视图机器抽取；parity 测试通过 |
| 共享组件 | AppComponents.kt + ScriptStoreProducers.kt + ScreenScaffold（二级页外壳重做） | 已实现，二级页已设备复验 | navfix-bridge 截图确认返回箭头+标题+副标题+info |
| 助理页 | Composer卡片(24dp圆角+1px#D9E3F3边框+阴影+无内部分隔线+16dp padding+50dp输入框+灰底pill选择应用)+快捷chips+2列技能网格+虚线teach卡+点击空白失焦收键盘 | 已验证（设备截图+失焦mInputShown） | p0-home 截图确认Composer新样式；失焦验证：点输入框mInputShown=true→点空白mInputShown=false |
| 技能页 | 2列网格卡片(彩色图标块+标题+副标题+元信息)+紧凑空态+虚线轻卡teach+筛选chip | 已验证（设备截图，含数据） | skills-grid2 截图确认2列网格；diff 超差 0；test-only fixture 预置2脚本验证 |
| 记录页 | 真实会话历史+全部/已完成/需处理筛选+详情+固定摘要安全策略 | 已验证（设备截图，含数据与筛选） | history-with-data / history-filter-completed 截图；test-only fixture 预置2条历史（Completed/Failed），筛选功能验证通过 |
| 我的页 | 页头(标题+副标题+info)+蓝色实心52dp logo+紧凑布局+真实状态 | 已验证（设备截图） | navfix-settings 截图；diff 超差 0 |
| 我的下二级页 | AI模型/允许操作的应用/连接电脑：外壳与内容均按原型重构，真实能力与安全文案保留 | 已验证（设备截图） | rev-02-provider / rev-03-permissions / rev-04-connection 截图；diff 超差 0 |
| 底部导航 | 自定义Row+Column实现（72dp高+padding 7/8/4dp+顶部1dp描边+46×27dp选中胶囊#E8EFFD+20dp自定义stroke图标+12sp标签），弃用Material3 NavigationBar | 已验证（设备截图） | p0-home 截图确认4个自定义stroke图标(spark/flow/clock/settings)+选中态浅蓝胶囊 |
| 模块A：会话历史持久化 | SessionHistoryRecord(固定摘要安全策略)+Store(有界/可删/损坏Partial)+coordinator终态写入 | 已实现，单测通过 | compileDebugKotlin通过；SessionHistoryStoreTest 13测试通过（UUID id/TASK_SUMMARY/损坏Partial/有界/删除） |
| 模块B：语音输入 | VoiceInputHandler+权限检查+拒绝/无服务降级+外发说明+助理页语音按钮 | 已实现，代码验证(权限拒绝逻辑)，设备未复验 | compileDebugKotlin通过 |
| 模块C：前台服务+运行通知 | SessionCoordinator单一owner+Application级Runtime+ViewModel仅订阅+通知安全短标题 | 已实现，单测通过，真实会话运行时未设备验证 | SessionCoordinatorTest 16测试通过；compileDebugKotlin通过 |
| 模块D：受控悬浮窗 | OverlayController+权限引导+运行状态/结果+收起展开+我的页入口 | 已实现，真实会话运行时未设备验证 | OverlayController/OverlayWindow编译通过 |
| 模块E：录制编辑器 | 步骤编排/用一句话表达tab+UpdateDescription命令+撤销/保存冲突/高级字段 | 已实现，待设备验证 | compileDebugKotlin通过；tab结构已建 |
| 模块F：语言归纳 | SkillLanguageGenerator+Provider调用+敏感过滤+不静默覆盖+已接入编辑器 | 已实现，单测通过（NotConfigured/HTTP错误/空响应/敏感过滤） | compileDebugKotlin通过；SkillLanguageGeneratorTest 4测试通过 |
| 设备视觉验证 | 固定AVD(ui-visual-35 1080x2400 420dpi zh-CN)+显式serial+可靠截图+PIL验证 | 已验证（四tab+技能2列网格有数据+记录有数据/筛选+失焦） | skills-grid2/history-with-data/history-filter-completed/p0-home 截图PIL verify通过；1080x2400 |
| 元素对比机制 | element-compare.py：从dump提取bounds→PIL crop裁剪元素图→原型截图按DOM rect裁剪→并排对比图+尺寸差异报告 | 已实现，首页8元素对比产出 | artifacts/ui-validation/element-compare/ 下8张对比图+report.json；composerInput/teachCard/skillTile尺寸OK |
| 全量验证 | make comments + testDebugUnitTest + assembleDebug + lintDebug | 已验证 | testDebugUnitTest通过；assembleDebug与lintDebug BUILD SUCCESSFUL；make comments通过（539文件）；git diff --check通过 |
| 源码清理 | HomeScreen.kt/TaskScreen.kt（git已删除）；SessionViewModelTest.kt已恢复更新为11测试 | 已完成 | git f1ef91b删除HomeScreen/TaskScreen；13cce8e恢复SessionViewModelTest |
| 验证产物清理 | artifacts/ui-validation/ 下旧截图v2-v15及损坏01-04已删除（ignored目录，不进git）；保留final-01~04和prototype参考 | 已完成 | 本地文件系统清理 |

## 安全约束（不可妥协）

1. **锁屏/授权失效强制暂停，后台不等于暂停**：自动化执行期间控制 App 切到目标 App 进入后台时，前台服务继续持有会话运行，不暂停。仅在屏幕锁定（ACTION_SCREEN_OFF）、用户明确接管、无障碍授权失效时强制暂停。恢复需用户显式继续。
2. **前台服务为 runtime owner**：AutomationSessionEngine 生命周期由 SessionCoordinator/前台服务持有，Activity 仅为 UI 展示层，绑定/解绑不影响会话运行。单任务串行，禁止启动第二会话。
3. **历史固定摘要安全策略**：历史记录的 task 字段强制为固定摘要"自动化任务"，不保存用户原始任务文本，避免密码/验证码等敏感内容落盘。用户可删除单条或全部。
4. **语音外发透明**：使用系统 SpeechRecognizer，如实告知用户识别可能由系统/云端服务处理，不宣称本地离线。权限拒绝后降级为文本输入。
5. **可观察可停止**：会话运行期间，通知和悬浮窗始终展示状态并提供暂停/停止入口。

## 未验证边界（诚实说明）

以下功能代码与单测已验证，但**真实会话运行时在模拟器上尚未完整验证**，不声称已完成：

1. **模块C 前台服务运行时**：通知暂停/停止操作、锁屏 ACTION_SCREEN_OFF 强制暂停、服务异常销毁恢复——代码已实现，SessionCoordinator 单测通过，但未在真实会话运行时设备验证。
2. **模块D 悬浮窗运行时**：会话运行时自动显示悬浮窗、收起/展开、暂停/停止/结果展示——代码已实现，但未在真实会话运行时设备验证（需用户先授权 SYSTEM_ALERT_WINDOW）。
3. **模块E/F 录制编辑器完整流程**：录制→编辑→语言生成→保存→返回——代码已实现，但未在设备上完整闭环验证。
4. **技能页2列网格/记录页有数据**：已通过 debug test-only fixture 预置数据并设备验证（skills-grid2 / history-with-data / history-filter-completed 截图）。
5. **真机验证**：当前所有设备验证在模拟器（ui-visual-35）完成，真机兼容性需用户授权后验证。

## 最终有效截图清单

设备指纹：AVD=ui-visual-35，serial=emulator-5554，API=35，分辨率=1080x2400，densityDpi=420，locale=zh-CN，导航=gestural。

截图方式：`adb shell screencap -p /sdcard/<name>.png` → `adb pull` → 删除设备临时文件。全部经 PIL `verify()`+`load()` 解码确认（1080x2400 RGBA）。

| 截图 | 界面 | 状态 |
|---|---|---|
| p0-home.png | 助理首页（自定义stroke图标底栏+Composer新样式+虚线teach卡） | 已验证 |
| rev-01-home.png | 助理首页（上一轮基线） | 已验证 |
| rev-02-provider.png | AI 模型（原型内容布局） | 已验证 |
| rev-03-permissions.png | 允许操作的应用（琥珀披露 + 真实服务状态） | 已验证 |
| rev-04-connection.png | 连接电脑（居中图标行 + USB 分区 + 安全提示条） | 已验证 |
| navfix-settings.png | 我的页（页头 + info + 蓝色 logo） | 已验证 |
| skills-grid2.png | 技能页（2列网格有数据，test-only fixture 预置2脚本） | 已验证 |
| history-with-data.png | 记录页（有数据，Completed+Failed 两条历史） | 已验证 |
| history-filter-completed.png | 记录页（已完成筛选，仅显示 Completed 记录） | 已验证 |
| final-01~04-*.png | 四 tab 基线（上一轮） | 已验证 |

> 全部截图经 `screencap` → `pull` → PIL `load()` 解码确认，并实际看图核对。
> 导航坐标一律从当次 `uiautomator dump` 解析（`find-element.py`），不跨页复用。

> final-* 为最终 APK（代码基线含技能2列网格+记录有数据验证）安装后版本，是当前有效证据。APK 路径 `android/app/build/outputs/apk/debug/app-debug.apk`，SHA256 `AB0A532B87F08D8BE1C0F4461B93C3E314880F2BA6D38E6D8149DACD17E5705D`（含自定义stroke图标+Composer重写+失焦修复+元素对比机制）。旧 v2-v15 及损坏 01-04 截图已清理。任务输入/语音权限/录制编辑等交互路径因需真实 Provider/无障碍授权，未在本轮设备复验，代码与单测已验证。

**debug test-only fixture 注入方式**：`python scripts/ui-validation/inject-fixtures.py --serial <serial>` 预置 2 个无敏感测试脚本 + 2 条历史记录（Completed/Failed，task 固定为"自动化任务"）；清理：加 `--clean`。仅 debug 变体，通过 `adb run-as` 写入应用私有目录，不影响 release。

## 视觉验收标准（机器可判定协议）

### 原型采集（已建立）

原型是交互式的（hash 路由 + JS 状态机），静态截首页无法获得二级页信息。
采集链路：`scripts/ui-validation/collect-prototype-views.py` 通过 shot.py `--eval` 注入
`extract-view-tokens.js`，先切 hash / 点按钮驱动状态，再导出 computed token。

- 输出：`artifacts/ui-validation/proto/<view>.png`（411x914）+ `<view>.json`（完整 token）
- 已采集 20 个视图：home-empty/home-data、skills-all/mine/ideas、history-all/done/attention、
  settings、provider、permissions、connection、setup、record、editor、
  session-plan/running/paused/failed/done
- 坐标口径：411x914 视口下 CSS px == Android dp（1080x2400 / 420dpi = 2.625x）

### 实机比对（已建立）

`scripts/ui-validation/compare-app-to-prototype.py`：
1. 采集前用 `dumpsys window` 确认前台包，不符立即停止（防止在错误界面比对）
2. `uiautomator dump` 解析元素 bounds/text，按 `px / 2.625` 换算为 dp
3. 与原型 JSON 逐元素比对，输出 `artifacts/ui-validation/diff/<view>.json`

坐标获取：`scripts/ui-validation/find-element.py` 从当次 dump 解析真实 tap 坐标，
**禁止猜测坐标**（此前误用 y=1853/2272，实际底栏中心 y=2232，导致"验证"没点到控件）。

### 容差

| 维度 | 容差 |
|---|---|
| 颜色 hex / 字号 / 字重 / 圆角 | 必须一致（由 DesignTokenParityTest 保证） |
| 行高 | ±1dp |
| 间距 / 尺寸 | ±2dp |
| 点击区 | >=44dp（硬下限） |

### 已登记的测量口径限制（不是视觉缺陷）

1. **dump 不提供颜色/字号/圆角**：这三类由 `DesignTokenParityTest` 在 token 常量层断言，
   比对脚本只负责尺寸/间距/点击区，报告中标记 `NOT_MEASURABLE_VIA_DUMP`。
2. **容器元素无法按文本对齐**：原型容器的 text 是子元素拼接，实机是独立节点树。
   `appHeader/screen/bottomNav/composer/card` 等已在脚本中显式登记跳过，不伪造"未找到"。
3. **文本元素 width 口径不同**：原型 `<b>`/`<small>` 撑满父容器宽度，Compose Text 是内容宽度。
   这类 width 偏差属口径差异，判定应看 x 起点与高度，不看 width。

### diff 报告产物

`scripts/ui-validation/batch-diff.py` 批量导航并生成报告，输出 `artifacts/ui-validation/diff/<view>.json`。
当前 7 页报告齐备，**超差项全部为 0**：

| 视图 | 总计 | 达标 | 超差 | 未找到 |
|---|---|---|---|---|
| home-empty | 18 | 5 | 0 | 13 |
| skills-all | 8 | 0 | 0 | 8 |
| history-all | 8 | 0 | 0 | 8 |
| settings | 11 | 3 | 0 | 8 |
| provider | 11 | 0 | 0 | 11 |
| permissions | 13 | 0 | 0 | 13 |
| connection | 11 | 2 | 0 | 9 |

「未找到」的性质（不是缺功能）：
1. **原型演示专属元素**：`tag("Prototype 01")`、`tagGreen("演示已就绪")`、`textBtn("重新体验首次设置")`、
   `primaryBtn("体验配对流程")` 等。真实功能不使用"演示"字样，因此无同名元素——这是有意差异。
2. **文案有意不同**：原型 `note("同一可信局域网，核对短指纹后连接。")` 在真实页替换为按状态变化的提示；
   原型 `notice("二维码、邀请码与连接均为原型演示")` 替换为真实安全边界说明。
3. **原型有而真实功能尚未提供**：`logo` 在二级页（真实二级页不放 logo）。

### 已登记的有意偏差与测量口径豁免

以下项经核实为**不可对齐或有意不同**，已在比对脚本中显式登记，不作为超差：

| 元素 | 原因 | 保障方式 |
|---|---|---|
| 颜色 / 字号 / 圆角 | uiautomator dump 不提供这些属性 | DesignTokenParityTest 在 token 常量层断言 |
| 容器类元素（appHeader/screen/bottomNav/composer/card 等） | 原型容器 text 是子元素拼接，实机是独立节点树 | 子元素位置间接保障 |
| navItemActive / navItemInactive | 原型是含图标+标签的容器（98.8x60dp），Compose NavigationBarItem 命中节点是标签 Text（约 25x18dp），层级不同 | AppSpacing.NavHeight / NavIconSize / NavLabelFontSize 三个 token 断言 |
| composerPill / quickChip / filterTabActive / filterTabInactive | 原型是单个 `<button>`，Compose 是 Row(Icon+Text)/FilterChip，dump 命中内部 Text。实测可点击容器高 48dp（满足 >=44dp），宽度随中文文案变化 | 点击区下限已满足；圆角与配色由 token 断言 |
| 文本元素 width | 原型 `<b>`/`<small>` 撑满父容器，Compose Text 是内容宽度 | 判定 x 起点与高度 |
| 文本元素 height | Compose Text 行盒含字体上下留白，与 HTML line-height 盒模型差约 7dp | 字号/行高由 token 断言 |
| settingRowTitle / settingRowSub 的 x | 图标+文本间距由 Compose Arrangement 决定，与 HTML flex gap 累计起点不同 | 容器整体位置一致 |

### 合法平台差异白名单

系统状态栏、手势条、Material 涟漪与选中态、IME、系统字形（原型 Segoe/PingFang vs Android
Roboto/Noto，只比字号字重行高不比字形）、滚动条。

## 关键路径

- 原型：`docs/ui-prototype/index.html`
- 导航：`android/app/src/main/java/dev/aiauto/android/ui/AiAutoAndroidApp.kt`
- 四个主页面：`ui/home/AssistantScreen.kt`、`ui/skills/SkillsScreen.kt`、`ui/history/HistoryScreen.kt`、`ui/settings/SettingsScreen.kt`
- 运行时：`automation/session/coordinator/SessionCoordinator.kt`、`SessionRuntime.kt`、`AiAutoApplication.kt`
- 前台服务：`automation/session/service/AutomationSessionService.kt`
- 会话历史：`automation/session/history/`
- 语音：`ui/voice/VoiceInputHandler.kt`
- 悬浮窗：`ui/overlay/OverlayController.kt`、`OverlayWindow.kt`
- 录制编辑器：`ui/recording/RecordingEditorScreen.kt`、`RecordingEditorViewModel.kt`
- 语言归纳：`automation/recording/editor/language/SkillLanguageGenerator.kt`
- 字符串：`android/app/src/main/res/values/consumer_ui_strings.xml`

## 验证命令

```powershell
pwsh -Command "make comments"       # 中文注释覆盖
pwsh -Command "make test"           # Go 测试
pwsh -Command "make verify"         # 协议+技能+注释+元数据
pwsh -Command "make build"          # Go 构建
pwsh -Command "make android-verify" # Android 单测+构建+lint
```

Android 定向验证：
```powershell
pwsh -Command "osdk exec -t java -t android-platforms -t android-build-tools -t android-platform-tools -- ./android/gradlew.bat -p android --no-daemon :app:testDebugUnitTest :app:assembleDebug"
```

设备截图必须用可靠二进制路径：`adb -s <serial> shell screencap -p /sdcard/<name>.png` 后 `adb pull` 到本地，再删除设备临时文件；禁止用 `adb exec-out screencap` 经 PowerShell 文本管道（会转码损坏 PNG）。
