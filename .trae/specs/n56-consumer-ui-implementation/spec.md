# N56 C 端 Agent UI 实现（第一波：导航与真实状态映射）

## 目标与边界

将 `docs/ui-prototype/index.html` 确认的四 tab 信息架构落地为真实 Android Compose 界面：
助理、技能、记录、我的。所有状态来自真实运行时（Provider、无障碍、录制脚本存储、会话引擎），
不使用硬编码就绪、假历史、示例步骤或静态结果。

本 change 仅覆盖 N56 第一波：UI 导航重组与真实状态接通。以下为概念功能，不在本 change 实现：
语音输入、系统级悬浮窗、后台队列/触发器、AI 意图归纳与意图回放、"用一句话表达"技能编辑器。
这些入口如出现在界面上，必须明确标记为未实现或不展示。

## 事实基线

- 导航根：`android/app/src/main/java/dev/aiauto/android/ui/AiAutoAndroidApp.kt`，当前为扁平 NavHost。
- 首页：`ui/home/HomeScreen.kt`，当前展示运行状态卡片 + 新建任务/录制按钮。
- 会话：`ui/session/SessionScreen.kt` + `SessionViewModel.kt`，真实状态机（Idle/Observing/Planning/
  AwaitingConfirmation/Executing/Verifying/Paused/Completed/Failed/Stopped），支持暂停/恢复/停止/确认。
- 录制：`ui/recording/RecordingHost.kt` + `RecordingViewModel.kt`，内部管理 LIST/SESSION/DETAIL/EDITOR
  四个目的地；`RecordingScriptStore.list()` 返回真实持久化脚本摘要。
- 录制编辑器：`ui/recording/RecordingEditorScreen.kt` + `RecordingEditorViewModel.kt`，已有步骤编辑、
  排序、启停、复制、删除、撤销/重做、保存、预检（dry run）、未保存保护。
- Provider：`ui/provider/ProviderScreen.kt` + `ProviderConfigRepository`，API Key 加密存储。
- 无障碍：`ui/accessibility/AccessibilityScreen.kt` + `AccessibilitySettingsRepository`，
  `AccessibilityServiceStatus.isEnabled(context)` 检测服务启用。
- 桌面桥/LAN：`ui/bridge/DesktopBridgeScreen.kt` + `ui/bridge/lan/LanPairingHost.kt`。
- 主题：`ui/theme/Theme.kt`，当前使用 Material 动态取色 + 固定回退方案。
- 原型设计：冷白/石墨/克制蓝；四个主入口为助理、技能、记录、我的；模型、权限、目标 App 和电脑连接
  沉入"我的"二级页面。

## 设计约束

- 底部 NavigationBar 四个 tab：助理（spark）、技能（flow）、记录（clock）、我的（settings）。
- 每个 tab 内部有独立的嵌套导航栈；子页面（Provider、无障碍、桌面桥、LAN、会话详情、录制流程）
  保留返回按钮，不显示底部导航。
- 助理页：任务输入框 + 选择目标应用 + 常用技能（来自真实脚本）+ 最近活动 + 就绪状态徽章。
  任务启动后进入会话详情页，接通真实 SessionViewModel。
- 技能页：脚本列表（来自 RecordingScriptStore.list()），筛选（全部/我教的），录制新技能入口。
  点击脚本进入详情/编辑，复用现有 RecordingHost。
- 记录页：展示已保存脚本及其元数据（创建时间、步骤数、目标包），空态明确说明暂无记录。
  不造假历史；会话 audit 为内存态，不持久化展示为历史记录。
- 我的页：助理状态汇总（Provider 已配置？无障碍已启用？目标应用已选？），三个设置入口：
  AI 模型（→ ProviderScreen）、应用与权限（→ AccessibilityScreen）、连接电脑（→ DesktopBridgeScreen）。
- 主题：在现有 Material 主题基础上微调为冷白/石墨/克制蓝，保留动态取色能力。不引入自定义设计系统。
- 所有新增手写代码必须包含中文注释，纳入 `make comments`。

## 执行任务与所有权

1. T1（集成负责人，串行）：导航骨架重构 + 主题微调 + 共享组件 + 字符串资源。
   独占 `ui/AiAutoAndroidApp.kt`、`ui/theme/`、`ui/components/`、`res/values/strings.xml`。
2. T2（并行，依赖 T1）：助理页重构。独占 `ui/home/`。
3. T3（并行，依赖 T1）：技能页新建。独占 `ui/skills/`。
4. T4（并行，依赖 T1）：记录页新建。独占 `ui/history/`。
5. T5（并行，依赖 T1）：我的页新建。独占 `ui/settings/`（注意：与现有 `ui/provider/`、
   `ui/accessibility/`、`ui/bridge/` 互斥，我的页只做入口聚合，不修改那些子页面）。
6. T6（集成负责人，串行，依赖 T2-T5）：集成验证、构建、修复导航衔接问题。

共享导航图（AiAutoAndroidApp.kt）由 T1 定义路由常量和嵌套结构，T2-T5 只在各自 tab 的 NavGraph 内
添加目的地，不修改其他 tab 的路由。

## 允许修改范围

- `android/app/src/main/java/dev/aiauto/android/ui/`（全部子目录）
- `android/app/src/main/res/values/strings.xml`（新增字符串，不删除既有）
- `android/app/src/main/res/values/` 其他样式文件（如需）
- 本 change 规格目录 `.trae/specs/n56-consumer-ui-implementation/`

禁止修改：
- `android/app/src/main/java/dev/aiauto/android/accessibility/`、`automation/`、`bridge/`、
  `provider/`、`security/`、`observe/`（领域层和运行时，本 change 不改动）
- `protocol/`、`internal/`、`cmd/`（Go 侧）
- 既有 MVP 规格和 N31-N55 的 tasks/checklist/progress
- `docs/next-phase-tasks.md`（N56 状态在本 change 完成后由集成负责人更新）

## 验证与清理

- 编译：`make android-verify`（testDebugUnitTest assembleDebug lintDebug）必须通过。
- 注释：`make comments` 必须通过，新增文件纳入检查。
- Go 侧：`make test`、`make verify`、`make build` 不受影响，应继续通过。
- 导航：手动验证四个 tab 切换、子页面返回、任务启动、录制入口、设置入口均可达。
- 真实状态：Provider 未配置时助理页显示待配置；无障碍未启用时任务按钮禁用；
  无脚本时技能页和记录页显示空态；不出现硬编码的"已就绪"或假数据。
- 失败只回改本 change 新增/修改的 UI 文件，不改变领域层和历史验收。
- 本 change 不提交或 push，完成后由用户审查；保留原型提交在 main 上。
