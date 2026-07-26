# N43 WebView 语义能力只读探针规格

## 目标

在 API 30/33/34 固定 AVD 与 WebView profile 上，只读采集离线 Web fixture 关键虚拟
节点的 class、editable/focusable/focused 和 Accessibility action ID，区分“动作未暴露”
与“动作返回成功但 Web DOM 未生效”，为 N43 full/hybrid/unsupported 决策提供证据。

## 探针范围

- 固定节点：click button、text input、long-press target、scroll result/container。
- 每个节点必须名称唯一、package 为 `dev.aiauto.webfixture`、可见且启用。
- 仅读取 action list 和状态；不得调用 `performAction`、坐标、截图、剪贴板、IME、
  DOM/JavaScript、ADB text 或 shell。
- 输出只保留 API、WebView 版本、节点别名、class、布尔状态和排序后的 action ID；
  不保留 bounds、页面文本、hierarchy、截图或设备内容。

## 允许修改

- `.trae/specs/n43-webview-capability-probe/`
- `android/gradle/libs.versions.toml` 与 `android/web-fixture/build.gradle.kts` 的
  AndroidX Activity 测试宿主依赖接线，不升级共享版本
- `android/web-fixture/src/androidTest/java/dev/aiauto/webfixture/`
- `android/web-fixture/src/test/java/dev/aiauto/webfixture/` 的静态安全契约测试
- `android/web-fixture/src/main/` 的原生 page generation 与 WebView Back 测试接线；
  不修改页面 HTML/JavaScript 的动作语义
- `.trae/specs/n43-webview-semantic-replay/progress.md` 的 append-only 证据

## 禁止修改

- fixture HTML/JavaScript、生产 App WebView、recording core、Accessibility backend
- raw ADB、shell、坐标动作、剪贴板、IME 注入、DOM/JavaScript 或权限绕过
- 公共路线图；集成后由单一 owner 更新

## 验收

- JVM 静态检查证明探针无动作提交 API。
- AndroidTest APK 编译通过。
- API 30/33/34 固定 profile 各运行一次，记录 action matrix 与清理结果。
- 场景分类只能单次提交动作；后置语义不可见时报告 hybrid-required，不得重放或猜坐标。
- 设备执行失败时立即停止当前 profile；每个 profile 结束设备/runtime/lock/lease 为零。
- 该探针不等于 N43 完整场景通过，不勾选 20 轮设备验收。
