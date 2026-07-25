# N41 Compose 录制脚本编辑器规格

## 目标

在不修改录制领域模型、持久化协议、回放引擎和导航根的前提下，消费 N40
公共编辑接口，为 `AutomationScript 1.1` 提供可嵌入的 Compose 编辑界面和
可测试的 UI 状态适配层。

## 范围

- 提供脚本复制入口、步骤列表、重排、启停、删除和复制操作。
- 提供 selector、归一化坐标、前后等待、重试、失败策略和 notes 表单。
- 展示 Undo、Redo、dirty、未保存离开确认、保存成功和 revision conflict。
- 调用 N40 只读 dry-run，并将失败报告定位到具体步骤。
- 截图点选仅消费调用方传入的已授权、短生命周期 observation view；编辑器
  不申请截图权限，不持久化、不编码 Base64、不记录图像或设备路径。

## 安全与生命周期边界

- 表单先在临时 `ScriptEditorSession` 验证全部 N40 命令，再以单个
  `UpdateStep` 原子写入正式会话，任一字段非法不得产生部分编辑。
- observation view 不进入 `RecordingEditorViewModel`、可恢复状态、日志或
  保存端口；过期 observation 失败关闭，Composable 离开时释放租约。
- 旋转由同一 ViewModel 保留编辑历史和表单；重新创建 observation 容器时，
  旧租约先释放，图像必须由调用方重新授权并传入。
- dry-run 只接收 `ScriptDryRunEngine`，界面不持有 ReplayEngine、shell、
  Accessibility 动作提交或 MediaProjection 权限入口。
- revision conflict 同时展示 expected、attempted 和 current revision，
  不覆盖磁盘新版本，也不清除本地 dirty 状态。

## 允许修改

- `.trae/specs/n41-compose-recording-editor/`
- `android/app/src/main/java/dev/aiauto/android/ui/recording/`
- `android/app/src/test/java/dev/aiauto/android/ui/recording/`
- `android/app/src/androidTest/java/dev/aiauto/android/ui/recording/`
- `android/app/src/main/res/values/recording_strings.xml`

## 禁止修改

- `android/app/src/main/java/dev/aiauto/android/automation/recording/editor/`
- RecordingModels、RecordingScriptStore、协议、ReplayEngine、N44 visual core
- 导航根、共享路线图和 Gradle 配置

## 验收

- JVM 测试覆盖复制、步骤操作、完整表单原子提交和错误保持。
- JVM 测试覆盖 Undo/Redo、dirty、离开确认、保存与 revision conflict。
- JVM 测试覆盖 dry-run 报告和首个失败步骤定位。
- JVM 测试证明 observation 租约过期失败关闭，旋转/离开释放且恢复状态不含
  图像、Base64 或本地路径。
- Compose instrumentation 源码覆盖主要编辑交互；只有真实运行后才可声明
  instrumentation 通过，单纯编译不得替代设备证据。
- N41 定向测试、App JVM 全量、lint、`make comments` 和
  `git diff --check` 通过。
