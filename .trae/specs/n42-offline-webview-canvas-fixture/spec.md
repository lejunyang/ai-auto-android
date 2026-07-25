# N42 离线 WebView 与 Canvas Fixture 规格

## 目标

建立不依赖公网、可复位、可机器断言的 WebView 和 Canvas 测试 App，为后续语义兼容、
视觉观察和坐标回放提供稳定基线。

## 范围

- 本地 HTML 覆盖按钮、输入、长按、滚动、页面跳转、动态 DOM 和 iframe。
- Canvas 覆盖 tap、long-click、swipe、动画状态和明确结果区域。
- 固定离线字体、资源、viewport、主题和测试数据。
- 提供完整语义、部分语义和单一 Canvas 三种可切换模式。
- 提供测试重置入口和可机器读取的后置状态。
- 使用网络安全配置与运行时拦截禁止公网请求。

## 安全边界

- 不向生产 App 注入 WebView 调试接口。
- 不启用远程调试、JavaScript bridge 任意调用或公网回退。
- fixture 无敏感数据、无登录、无支付或外部副作用。
- N31 未完成前可以完成代码和构建，但不得声明 API 30/33/34 设备验收通过。

## 允许修改

- 新建 `android/web-fixture/`
- 本 change spec

共享 `android/settings.gradle.kts` 由 N31 集成负责人修改，N42 不直接改动。

## 验收

- 离线资源和三种模式可构建、可复位并返回稳定状态。
- 单元测试证明公网请求计数为零并拒绝非本地 URL。
- N31 完成后在 API 30、33、34 验证 hierarchy、截图和状态断言一致。
- 运行 `make comments`、Android 定向测试、构建和 `git diff --check`。
