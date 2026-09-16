# C 端 Agent UI 概念原型

## 目标与边界

将现有配置导向界面重组为任务导向体验，交付 `docs/ui-prototype/index.html` 单文件可交互原型。仅设计，不修改 Android、Go、协议、设备授权或已完成 MVP 状态；原型不连接设备、不访问模型、不采集麦克风。示例数据与未来能力必须显式标记。

## 事实基线

- 现有导航、首页与会话：`android/app/src/main/java/dev/aiauto/android/ui/` 中的 `AiAutoAndroidApp.kt`、`home/HomeScreen.kt`、`session/SessionScreen.kt`。
- 现有录制与编辑：`ui/recording/RecordingEditorScreen.kt`、`res/values/recording_strings.xml`，以及 `docs/next-phase-tasks.md` 的 N39/N40/N41/N45；旧 `docs/recording.md` 是 MVP 口径，不能据此否认后来已实现的编辑能力。
- 模型、授权与连接：`ui/provider/ProviderScreen.kt`、`ui/accessibility/AccessibilityScreen.kt`，N38 的最新追加记录。
- 语音、系统级悬浮、后台编排、自然语言技能及条件触发是概念，不冒充现有 Android 功能。

## 设计约束

- 四个 App 主入口：助理、技能、记录、我的。模型、权限、目标 App 和电脑连接沉入设置。
- 冷白、石墨、克制蓝色；深色悬浮场景；统一细线图标，不使用 emoji。
- 展示任务启动、计划预览、运行、暂停、停止、待确认、失败与结果。
- 录制体现 App/行为时间线，支持步骤改名、移动、启停、复制、删除、撤销/重做和保存。
- 自然语言不是唯一执行依据：保留结构化步骤、参数与成功条件；页面变化需重新观察，无法验证时停下，不宣称无限自愈。
- 后台待命不等于锁屏执行或多个 App 并发抢占；前台操作单任务串行，敏感操作交回用户。
- 所有入口有反馈；桌面提供设计导览，窄屏优先真实 App 体验；尊重减少动态效果设置。

## 执行任务与所有权

1. P1：读取现有功能及约束，确定信息架构。证据为源码与规格映射。
2. P2：制作单文件原型及页内设计说明。依赖 P1；独占 `docs/ui-prototype/`，无并行写入。
3. P3：检查实际桌面/移动渲染、关键交互、中文注释与 Git 差异。依赖 P2；允许最小修改注释门禁以覆盖原型 HTML。
4. P4：交付 HTML，并将未来实施工作加入 `docs/next-phase-tasks.md` 独立未开始条目；共享文档与本 change 状态仅由本任务维护。

允许范围：本 change 目录、`docs/ui-prototype/`、`docs/README.md`、`docs/next-phase-tasks.md`、`scripts/check-comment-coverage.mjs`。不创建并行实现 worktree，因为本次为单一串行设计任务；不自动提交、push 或切换用户分支。建议后续单独提交 `docs(ui): add consumer agent prototype`。

## 验证与清理

- 原型：桌面与移动实际截图，静态/运行错误与关键状态检查。
- 仓库：`make comments`、`make test`、`make verify`、`make build`、`make android-verify`；结果按实际记录，未执行项不得描述通过。
- 失败只回改本次新增原型，不改变生产实现和历史验收。截图、缓存与验证日志留在忽略目录，不提交。
- 真实无障碍、相机、麦克风、系统悬浮授权无法由 HTML 安全替代，本次不触发；未来实现需遵循系统人工授权流程。
