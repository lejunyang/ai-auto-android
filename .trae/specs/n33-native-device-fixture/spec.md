# N33 原生设备 Fixture 规格

## 目标

扩展无网络、无敏感数据的原生测试 App，以稳定语义节点覆盖真实自动化所需的输入、
点击、长按、双向手势、多页面和系统导航能力，并为 API 30、33、34 重复矩阵提供
确定性 instrumentation 入口。

## 范围

- 为每个动作提供稳定 resource ID、固定 `contentDescription` 和独立结果节点。
- 覆盖文本输入、点击、长按、纵向滚动、横向 swipe、dialog 开关和状态断言。
- 提供三个原生页面、平台 Back 栈和可复位的进程内状态。
- 覆盖 Home、Recents、Settings 应用切换及 `MAIN/LAUNCHER` 重入。
- 使用 UI Automator 在每一步校验前置、动作与后置状态，而非只检查命令退出码。
- 通过 runner 参数选择场景与重复次数，支持单轮调试和每 API 20 轮统计。

## 安全边界

- Fixture 不声明网络权限，不保存用户输入，不读取账号或设备敏感数据。
- 系统导航测试只打开 Launcher、Recents 和 Settings 首页，不修改系统设置。
- 不向生产 App、Bridge、共享 Gradle 配置或失败产物模块注入测试入口。
- 设备矩阵必须由集成线程使用明确 serial 执行；本任务不隐式选择设备。

## 允许修改

- `android/device-fixture/`
- `.trae/specs/n33-native-device-fixture/`

## 验收

- JVM 测试覆盖进程内状态转换和完整复位。
- instrumentation 覆盖所有业务动作、三页 Back 栈和系统导航后置状态。
- `fixtureScenario=all|core|system` 与 `fixtureRepeat=N` 可控制重复入口。
- API 30、33、34 每个完整场景连续 20 次成功率不低于 95%。
- 运行模块单测、debug/test APK 构建、lint、`make comments` 和
  `git diff --check`。
