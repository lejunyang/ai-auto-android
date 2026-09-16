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
| 主题 | 固定配色（背景#FAFBFC/表面#FFFFFF/主色#2563EB），无动态取色 | 已实现，待设备复验 | Theme.kt固定配色；AppTokens.kt已建 |
| 设计token层 | AppColors/AppShapes/AppSpacing/AppTypography + DesignTokenParityTest | 已实现，单测通过 | AppTokens.kt编译通过；DesignTokenParityTest 31测试通过（Argb常量比较） |
| 共享组件 | AppComponents.kt + ScriptStoreProducers.kt（IO+错误态+ON_RESUME） | 已实现，待设备复验 | compileDebugKotlin通过 |
| 助理页 | Composer卡片+快捷chips+技能列表+语音按钮 | 已实现，待设备复验 | final-01首页截图 |
| 技能页 | 脚本列表+紧凑空态+虚线轻卡teach | 已实现，待设备复验 | final-02技能页截图 |
| 记录页 | 真实会话历史+全部/已完成/需处理筛选+详情 | 已实现，待有数据设备验证 | compileDebugKotlin通过；test-only fixture待预置 |
| 我的页 | 紧凑布局+52dp头像+真实通知状态(不硬编码已启用) | 已实现，待设备复验 | final-04我的页截图 |
| 模块A：会话历史持久化 | SessionHistoryRecord(固定摘要安全策略)+Store(有界/可删/损坏Partial)+coordinator终态写入 | 已实现，单测通过 | compileDebugKotlin通过；SessionHistoryStoreTest 14测试通过（UUID id/TASK_SUMMARY/损坏Partial/有界/删除） |
| 模块B：语音输入 | VoiceInputHandler+权限检查+拒绝/无服务降级+外发说明+助理页语音按钮 | 已实现，代码验证(权限拒绝逻辑)，设备未复验 | compileDebugKotlin通过 |
| 模块C：前台服务+运行通知 | SessionCoordinator单一owner+Application级Runtime+ViewModel仅订阅+通知安全短标题 | 已实现，单测通过，真实会话运行时未设备验证 | SessionCoordinatorTest 13测试通过；compileDebugKotlin通过 |
| 模块D：受控悬浮窗 | OverlayController+权限引导+运行状态/结果+收起展开+我的页入口 | 已实现，真实会话运行时未设备验证 | OverlayController/OverlayWindow编译通过 |
| 模块E：录制编辑器 | 步骤编排/用一句话表达tab+UpdateDescription命令+撤销/保存冲突/高级字段 | 已实现，待设备验证 | compileDebugKotlin通过；tab结构已建 |
| 模块F：语言归纳 | SkillLanguageGenerator+Provider调用+敏感过滤+不静默覆盖+已接入编辑器 | 已实现，单测通过（NotConfigured/HTTP错误/空响应/敏感过滤） | compileDebugKotlin通过；SkillLanguageGeneratorTest 5测试通过 |
| 设备视觉验证 | 固定AVD(ui-visual-35 1080x2400 420dpi zh-CN)+显式serial+可靠截图+PIL验证 | 已验证（UI四tab） | final-01~04四tab截图PIL verify通过；1080x2400 |
| 全量验证 | make comments + testDebugUnitTest + assembleDebug | 已验证 | testDebugUnitTest 486通过/1跳过（旧RecordingScriptStore基线ignore）；assembleDebug BUILD SUCCESSFUL；make comments通过；git diff --check通过 |
| 源码清理 | HomeScreen.kt/TaskScreen.kt（git已删除）；SessionViewModelTest.kt已恢复更新为12测试 | 已完成 | git f1ef91b删除HomeScreen/TaskScreen；13cce8e恢复SessionViewModelTest |
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
4. **记录页有数据状态**：真实历史筛选/详情——代码已实现，但需 debug test-only fixture 预置数据后设备验证。
5. **真机验证**：当前所有设备验证在模拟器（ui-visual-35）完成，真机兼容性需用户授权后验证。

## 最终有效截图清单

设备指纹：AVD=ui-visual-35，serial=emulator-5554，API=35，分辨率=1080x2400，densityDpi=420，locale=zh-CN，导航=gestural。

截图方式：`adb shell screencap -p /sdcard/<name>.png` → `adb pull` → 删除设备临时文件。全部经 PIL `verify()`+`load()` 解码确认（1080x2400 RGBA）。

| 截图 | 界面 | 状态 |
|---|---|---|
| final-01-home.png | 助理首页 | 已验证 |
| final-02-skills.png | 技能页 | 已验证 |
| final-03-history.png | 记录页（空态） | 已验证 |
| final-04-settings.png | 我的页 | 已验证 |

> final-* 为最终 APK（代码基线 commit 13cce8e）安装后版本，是当前有效证据。APK 路径 `android/app/build/outputs/apk/debug/app-debug.apk`，SHA256 `3859679693FC750F49409D9BE796F9E0D133175703560217D1ED0FF35FC6A385`。旧 v2-v15 及损坏 01-04 截图已清理。任务输入/语音权限/录制编辑等交互路径因需真实 Provider/无障碍授权，未在本轮设备复验，代码与单测已验证。

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
