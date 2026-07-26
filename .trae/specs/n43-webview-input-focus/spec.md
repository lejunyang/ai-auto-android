# N43 WebView 显式聚焦输入规格

## 目标

修复 API 30 固定 WebView 上输入虚拟节点初始不暴露 `ACTION_SET_TEXT` 的设备阻塞。
测试与生产语义保持一致：先把同一唯一输入节点上的 `ACTION_CLICK` 作为显式步骤提交，
重新观察页面，再把新鲜节点上的 `ACTION_SET_TEXT` 作为第二个显式步骤提交。

## 安全语义

- click/focus 与 setText 是两个独立动作提交，不能伪装为一个原子动作。
- 每步前重新获取 `UiAutomation.rootInActiveWindow`，唯一匹配名称、目标包和动作能力。
- click 后必须重新观察同名节点；只有新节点明确暴露 `ACTION_SET_TEXT` 才能输入。
- 任一步骤缺失、歧义、页面变化或动作失败立即停止，不重试已提交动作。
- 不使用坐标、手势、剪贴板、IME shell、DOM/JavaScript 注入或直接文本 ADB 回退。
- 测试值固定为非敏感 ASCII，报告只记录动作能力 ID 和稳定失败，不记录设备内容。

## 允许修改

- `.trae/specs/n43-webview-input-focus/`
- `android/web-fixture/src/androidTest/java/dev/aiauto/webfixture/N43SemanticReplayDeviceTest.kt`
- N43 WebView semantic adapter 的专用 JVM 测试；仅在证明需要时修改其专用生产目录
- `.trae/specs/n43-webview-semantic-replay/progress.md` 的 append-only 设备证据

## 禁止修改

- 共享 Accessibility action/router/backend、recording core、Bridge 协议和 N42 fixture 页面
- WebView DOM、JavaScript、Gradle 依赖、release 行为和公共路线图
- 坐标输入、剪贴板输入、raw ADB、shell、任意命令或授权绕过

## 验收

- RED 设备入口在 API 30 初始输入节点缺少 `ACTION_SET_TEXT` 时稳定失败。
- 编译后的 instrumentation 明确执行 click、重新观察、setText，且无回退路径。
- 固定 API 30 clean AVD 单轮通过后，再决定是否运行 20 轮；失败时停止并清理。
- API 30/33/34 各 20 轮设备矩阵仍由 N43 主任务验收，未运行的 profile 不勾选。
- Web fixture instrumentation 编译、相关 JVM 测试、comments 与 diff-check 通过。
