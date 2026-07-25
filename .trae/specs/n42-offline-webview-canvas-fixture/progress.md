# N42 Progress

本文件 append-only，记录实现、构建、设备证据与网络访问结果。

## Round 1

- 已创建独立 change spec。
- 当前状态：实现尚未开始；设备验收等待 N31。

## Round 2

- 已实现独立无网络权限的 Web fixture App，包含完整语义、部分语义和单一 Canvas
  三种模式，以及重置入口、稳定状态节点和拒绝请求计数。
- 完整语义模式覆盖按钮、输入、长按、滚动、详情跳转、动态 DOM 和离线 iframe；
  Canvas 模式覆盖 tap、long-click、横纵 swipe、有限动画和 `aria-label` 结果。
- URL 策略只允许 `file:///android_asset/web/` 下规范路径，并拒绝公网、content URI、
  encoded traversal、双斜线和相似前缀；Manifest 不声明 `INTERNET`，WebView 同时
  禁止网络加载、远程调试、content access 和 universal file URL access。
- `testDebugUnitTest` 4/4 通过，`assembleDebug` 与 `lintDebug` 通过；lint 剩余 13
  个 warning 均来自共享锁定 Gradle/AGP/依赖版本，本模块 warning 为零。
- `make comments` 等价命令覆盖 195 个受管文件通过，`git diff --check` 通过。
- API 30/33/34 hierarchy、截图、重置和网络计数设备验收仍等待 N31，不标记 N42
  完成，也未提交任务 commit。

## Round 3

- API 30 首次设备验收在明确 serial `emulator-5556` 发现 full 模式 hierarchy 仅有
  宿主 `android.webkit.WebView`，没有 HTML 虚拟节点；该轮立即停止，未把宿主节点
  误报为完整语义支持。
- 根因是布局给 WebView 宿主设置 `contentDescription`，平台将虚拟子树折叠到父节点。
  已移除宿主描述，并增加资源契约测试禁止该属性；三 API hierarchy 需要重跑。

## Round 4

- 移除宿主 `contentDescription` 后，API 30 hierarchy 仍仅返回 `NAF` WebView；
  截图 SHA-256 `4fa9bc0ca9a107757d96eabddb464e41246c80edf079fbf93942aa9849acc965`
  且大小 115489 bytes，确认页面已渲染，问题限定为虚拟无障碍 provider 未激活。
- Chromium WebView 依赖宿主参与无障碍；默认 `importantForAccessibility=auto` 对动态
  内容可能保持 no。已显式设置为 `yes`，并增加资源契约测试防止回归；设备复验待执行。

## Round 5

- API 30 首次 `connectedDebugAndroidTest` 在执行测试前崩溃，报告 0 tests；测试 APK
  缺少 `androidx.test.runner.AndroidJUnitRunner`，该轮不计为 WebView 兼容结果。
- 已在 fixture 模块显式固定 `androidx.test:runner:1.6.2`，不再依赖 ext-junit 的
  非契约传递依赖；重新编译并确认测试 APK 包含 runner 后再执行设备测试。

## Round 6

- WebView 虚拟节点由 Chromium 按需提供；短时 `uiautomator dump` 只能看到宿主
  WebView。最终设备验收使用 test APK 内的系统 `UiAutomation` 会话激活 provider，
  不向生产 App 增加 AccessibilityService、调试桥或权限。
- `androidx.test:runner:1.6.2` 已显式进入 test APK；测试 APK DEX 确认包含
  `AndroidJUnitRunner`，随后 API 30、33、34 各运行 3 项 instrumentation，最终
  9/9 通过，0 failure/error/skip。
- full 模式验证按钮、输入、长按、动态 DOM、详情跳转、iframe 和 scroll target
  虚拟节点；partial 模式验证语义控件和单一视觉区域；Canvas 模式只暴露机器可读
  `canvas-result` 目标，不混入 full/partial 控件。
- 每个 API 都验证非空截图、`MODE:<mode>`、`NETWORK_REJECTED:0`。full 模式还通过
  虚拟节点 `ACTION_CLICK` 将状态从 ready 改为 clicked，再点击原生 Reset 回到
  ready，确认重置闭环和网络计数不变。
- 最终执行 serial 为 API 30 `emulator-5568`、API 33 `emulator-5570`、API 34
  `emulator-5572`；对应 N31 fingerprint 均通过。每台测试后均停止，最终
  `aactl devices list --json` 返回 0 台设备，runtime/lease/emulator 无残留。
- 初次像素诊断截图 SHA-256 为
  `4fa9bc0ca9a107757d96eabddb464e41246c80edf079fbf93942aa9849acc965`，
  验收后截图已删除；最终 debug/test APK SHA-256 分别为
  `8c7dda11b19b53283c3864fa64a28376113f2e446106a0eb882cd75ee6aae3d4` 和
  `57b836fd94b907c281a262cf032dd38ba521717719d6ac84b299880edf6d7e5b`，
  仅作为本地证据，不提交 APK。
- 最终 release 与 lintVital 构建通过；release APK SHA-256 为
  `7bd4e1a671c1668b40f30b583233d13afeda779686209ce8b647feb0ebc9b990`，
  DEX/Manifest 均不含 AndroidJUnitRunner、instrumentation 测试类或 INTERNET 权限。
- lint 0 error/15 warning：13 项来自共享锁定工具链/依赖，2 项来自 fixture 显式
  runner 版本和建议移入 version catalog；共享 Gradle 集成由 main 单一负责人串行
  完成，不在 N42 分支越权修改。
