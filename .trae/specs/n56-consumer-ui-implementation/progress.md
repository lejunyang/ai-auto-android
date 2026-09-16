# N56 C 端 UI 实现进度记录

## 范围

本 change 实现 N56 第一波：四 tab 导航重组与真实状态接通。不实现语音、悬浮、后台队列、
AI 意图归纳等概念功能。

- T0 已完成：原型提交 `docs(ui): add consumer agent prototype`（dfa21c6 on main），
  新建分支 `n56-consumer-ui`，创建本 change spec。
- T1 已完成：导航骨架重构（AiAutoAndroidApp.kt 改为底部四 tab + 嵌套 NavHost）、
  主题微调（冷白/石墨/克制蓝固定回退方案）、共享组件（AppComponents.kt：底部导航、
  段落标题、技能卡片、标签、空态、问候头部、就绪徽章）、字符串资源（consumer_ui_strings.xml）、
  material-icons-extended 依赖。RecordingHost 增加 initialScriptId/startRecordingImmediately 参数，
  SessionScreen 增加 initialTask 参数。
- T2 已完成：助理页（ui/home/AssistantScreen.kt）——任务输入接通 SessionViewModel 导航、
  常用技能与最近活动来自 RecordingScriptStore 真实脚本、就绪状态来自 provider+无障碍真实状态、
  未就绪时任务按钮禁用。无硬编码假数据。
- T3 已完成：技能页（ui/skills/SkillsScreen.kt）——脚本列表来自 RecordingScriptStore.list()、
  筛选（全部/我教的）、录制入口复用 RecordingHost、空态明确。
- T4 已完成：记录页（ui/history/HistoryScreen.kt）——展示已保存脚本作为运行记录、
  筛选（全部/已完成/需处理，不伪造状态字段）、空态明确、隐私边界说明。
- T5 已完成：我的页（ui/settings/SettingsScreen.kt）——助理状态汇总（TagChip）、
  三个设置入口（AI模型→ProviderScreen、应用与权限→AccessibilityScreen、连接电脑→DesktopBridgeScreen）、
  控制权说明。所有状态来自传入参数，无硬编码。

## 验证结果

- `make comments`：通过，520 个手写文件，16 个正反例。
- `make test`：通过，Go 侧全部测试通过（含缓存）。
- `make verify`：通过，协议校验、技能校验、注释覆盖、仓库元数据均通过。
- `make build`：通过。
- `make android-verify`：BUILD SUCCESSFUL，152 tasks（143 executed, 9 up-to-date），
  包含 testDebugUnitTest、assembleDebug、lintDebug；lint 无 Error 级别问题。
  输出中的 deprecation 警告均来自既有文件（AccessibilitySnapshotter.kt、AndroidLanNetwork.kt），
  与本次新增无关。

## 已知边界

- 四个主页面均接通真实状态源，但未在模拟器/真机上进行运行时验证（需设备证据）。
- 会话 audit 仍为内存态，记录页展示已保存脚本而非独立的会话运行历史。
- 语音、系统悬浮、后台队列/触发器、AI 意图归纳与意图回放未实现（N56 后续波次）。
- 旧的 ui/home/HomeScreen.kt 保留未删除，不再被导航引用。
- 本次未提交或 push，所有改动在 n56-consumer-ui 分支上。
