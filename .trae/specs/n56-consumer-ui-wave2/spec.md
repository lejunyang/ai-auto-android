# N56 C 端 UI 实现（第二波：运行时陪伴、历史持久化、语音、悬浮、编辑器体验、语言归纳）

## 目标与边界

在第一波四 tab 导航基础上，补齐技术与授权边界内可完成的功能：
- 真正的会话历史/结果持久化（不再用录制脚本冒充运行记录）
- 按需语音输入（权限拒绝/无服务降级为文本）
- 前台服务 + 运行通知（暂停/停止/结果展示）
- 受控悬浮窗（SYSTEM_ALERT_WINDOW 用户授权后可用，收起/展开，不宣传永久后台/锁屏执行）
- 生命周期处理（锁屏/服务失效时暂停，状态同步）
- 录制编辑器体验对齐原型（保留高级字段、等待/失败规则、撤销/保存冲突）
- 语言归纳（调用 Provider 生成技能自然语言描述，不静默覆盖步骤，差异可检查）

## 明确不做

- 不替代真机授权（无障碍、悬浮窗、麦克风、通知权限均由用户手动授予）
- 不宣传永久后台、锁屏自动执行或任意 App 自愈
- 不实现触发器/定时任务/后台队列（N56 后续波次）
- 不修改 Go 侧、协议层、领域层安全策略
- 不在 release 变体包含 test-only 入口

## 模块划分与目录所有权（并行互斥）

### 模块 A：会话历史持久化 + 记录页重构
- 独占目录：`automation/session/history/`（新建）、`ui/history/`
- 内容：
  - `SessionHistoryRecord` 数据模型（id、task、targetPackage、phase、completionSummary/failureMessage、startTime/endTime、audit 摘要、stepCount）
  - `SessionHistoryStore` 文件持久化（JSON，原子写入，类似 RecordingScriptStore）
  - 修改 `SessionViewModel`：会话进入 Completed/Failed/Stopped 终态时写入历史
  - 重构 `HistoryScreen`：展示真实历史记录，支持筛选（全部/已完成/需处理），点击查看详情
  - 历史详情：任务描述、状态、结果摘要、时间、步骤数
- 共享修改：`SessionViewModel.kt`（仅添加历史写入回调，不改变现有逻辑）

### 模块 B：语音输入
- 独占目录：`ui/voice/`（新建）
- 内容：
  - `VoiceInputHandler` 封装 Android SpeechRecognizer
  - 权限检查（RECORD_AUDIO），运行时请求
  - 权限拒绝/无 SpeechRecognizer 服务时降级为文本输入，不崩溃
  - 接入助理页任务输入框的语音按钮
  - 录音状态可视化（监听中/识别中/错误）
- 共享修改：`AndroidManifest.xml`（添加 RECORD_AUDIO 权限，由集成负责人统一处理）、
  `AssistantScreen.kt`（添加语音按钮调用，模块 B 负责）

### 模块 C：前台服务 + 运行通知 + 生命周期
- 独占目录：`automation/session/service/`（新建）
- 内容：
  - `AutomationSessionService` 前台服务，与 `AutomationSessionEngine` 绑定
  - 通知渠道（运行中/已完成/失败），通知带暂停/停止 Action
  - 服务启动/停止与会话生命周期联动
  - 前台服务为 runtime owner，持有 AutomationSessionEngine 生命周期；Activity 仅为 UI 层，绑定/解绑不影响会话
  - 锁屏（ACTION_SCREEN_OFF）、用户明确停止/暂停、无障碍授权失效时强制暂停；切到目标 App 导致控制 App 后台时不暂停，会话继续运行
  - 单任务串行：已有会话运行时禁止启动第二会话
  - `AutomationSessionServiceController` 管理绑定/解绑
- 共享修改：`AndroidManifest.xml`（FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE、POST_NOTIFICATIONS 权限 + service 声明，集成负责人处理）、
  `SessionViewModel.kt`（可选：通过服务启动会话）

### 模块 D：受控悬浮窗
- 独占目录：`ui/overlay/`（新建）
- 内容：
  - `OverlayController` 管理悬浮窗生命周期（WindowManager）
  - SYSTEM_ALERT_WINDOW 权限检查，未授权时引导用户到系统设置
  - 悬浮窗 UI：运行状态/结果展示，收起为小图标/胶囊，展开为面板
  - 暂停/停止按钮，结果展示
  - 仅在会话运行时显示，不常驻
  - 尊重"减少动态效果"设置
- 共享修改：`AndroidManifest.xml`（SYSTEM_ALERT_WINDOW 权限，集成负责人处理）、
  导航（可选：从会话页进入悬浮模式）

### 模块 E：录制编辑器体验对齐
- 独占目录：`ui/recording/`（修改现有文件，不改变领域层）
- 内容：
  - 检查现有 `RecordingEditorScreen` 视觉，对齐原型的步骤编排布局
  - 步骤时间线（步骤编号 + 连接线），步骤卡片（标题、应用、类型、启用状态）
  - 步骤操作行（编辑、启停、上移、下移、复制、删除）
  - 顶部工具栏（撤销、重做、添加步骤、预检）
  - 底部操作栏（预检、保存）
  - 保留所有现有高级字段：等待规则、失败策略、定位选择器、参数、secret 引用
  - 保留撤销/重做、保存冲突（revision 检查）、未保存保护
  - 不改变 `RecordingEditorViewModel` 的数据模型和逻辑
- 共享修改：无（仅 ui/recording/ 内文件）

### 模块 F：语言归纳（技能描述生成）
- 独占目录：`automation/recording/editor/language/`（新建）
- 内容：
  - `SkillLanguageGenerator` 调用现有 Provider（OpenAI 兼容）生成技能的自然语言描述
  - 输入：脚本步骤（类型、目标、参数摘要，不含敏感值）、脚本名称
  - 输出：自然语言描述草稿
  - 不静默覆盖步骤：生成结果只作为描述建议，用户可编辑/拒绝
  - 差异提示：如果用户修改了描述，标记"意图已改，步骤尚未同步"
  - Provider 未配置或调用失败时降级：不生成，保留手动输入
  - 接入录制编辑器的"用一句话表达"标签页（如果模块 E 预留了位置）
- 共享修改：`ProviderConfigRepository` 只读使用，不修改；编辑器 UI 接入由模块 E 或 F 协调

## 共享文件所有权（单一集成负责人）

以下文件由集成负责人（本 Agent）统一修改，子代理不得直接修改：
- `AndroidManifest.xml` — 所有权限和服务声明
- `AiAutoAndroidApp.kt` — 新路由和导航注入
- `res/values/consumer_ui_strings.xml` — 新字符串（子代理可建议 key，集成负责人统一添加）
- `SessionViewModel.kt` — 历史写入和服务集成（模块 A/C 提供代码，集成负责人合并）

## 验证标准

### 单元测试（每个模块必须）
- 历史存储：保存/读取/列表/删除/冲突
- 语音：权限拒绝降级、无服务降级
- 服务：通知构建、生命周期状态转换
- 悬浮：权限检查、显示/隐藏
- 编辑器：布局不破坏现有功能（现有测试继续通过）
- 语言归纳：Provider 未配置降级、敏感值不发送

### 构建
- `make android-verify`（testDebugUnitTest + assembleDebug + lintDebug）通过
- `make comments` 通过
- `make test` / `make verify` / `make build` 通过

### 设备测试（固定模拟器）
- 启动固定 AVD（android-35 google_apis x86_64）
- 安装 debug APK
- 导航闭环：四个 tab 切换、子页面返回
- 任务闭环：启动任务（可用 test-only 或 mock Provider）、暂停、停止
- 录制闭环：开始录制、结束、编辑、保存
- 授权拒绝：拒绝麦克风/悬浮窗权限后功能降级不崩溃
- 悬浮：授权后显示运行状态，暂停/停止可用
- 生命周期：按 home 键/锁屏后服务状态，返回后状态同步
- 截图回读：每个关键状态截图保存

### 不替代真机
- 无障碍服务授权、悬浮窗授权、麦克风授权在模拟器上可自动授权（debug-only），
  但真机结论需用户手动授权，不宣传模拟器结果等于真机兼容。

## 失败清理
- 每个模块独立可回退（删除新增目录 + 还原共享文件）
- 设备测试产物（截图、日志）留在忽略目录，不提交
- 不改变第一波已提交的导航骨架和四个主页面
