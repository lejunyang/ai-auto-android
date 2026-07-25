# N47 通用跨 App 场景 Runner 规格

## 目标

为 native、WebView、Canvas fixture 和后续用户提供的合法真实 App 建立统一、严格且
失败关闭的场景 DSL。Runner 只通过类型化观察、路由、动作、产物和清理端口运行未登录、
无外部副作用流程，不内置 ADB、shell、坐标猜测、账号或第三方 App 数据。

## 场景 DSL

- 场景固定目标包 allowlist、无副作用声明、步骤上限和默认超时。
- 动作只允许 `launch`、`tap`、`input`、`long-click`、`scroll`、`swipe`、`back`、
  `home`、`recents` 和 `switch-app`。
- 每一步固定目标包、前置条件、动作、后置条件、超时、允许 route、最低 score、
  可接受页面分类和归一化动态区域。
- 原始输入文本只在执行调用栈内传给类型化 executor，不写入报告、timeline 或错误。

## 执行边界

- 每步动作前必须取得最新 observation，验证前台包、条件、页面分类和时效。
- route resolver 必须返回绑定同一 observation 的 `semantic`、`hybrid` 或 `visual`
  结果；route 不在步骤 allowlist 或 score 低于阈值时动作提交数为零。
- executor 只能执行一次类型化动作，返回 route、score、attempts 和 observation ID；
  runner 不对已提交动作做隐式重试。
- 动作后必须重新观察并验证后置条件。验证失败明确记录已有提交，不重放动作。
- `advertisement`、`update-prompt`、`emulator-detected`、`network-failure`、
  `login-wall`、`unknown` 默认停止且不自动点击；`ab-variant` 仅在步骤显式接受时继续。
- 前台包漂移、旧 observation、未知 route、动态区域越界、高风险/未知页面和端口异常
  均使用稳定错误码，禁止自由异常文本进入报告。

## 结果与产物

- 运行报告记录每步实际 route、score、attempts、pre/post observation ID、页面分类、
  状态和稳定错误码，不记录 selector、输入文本、坐标或设备内容。
- 同时生成符合 N34 `scenario-result` 的 timeline/outcome，失败时交给注入的 artifact
  collector；runner 本身不读取截图、hierarchy、日志或 secret。
- 聚合器按场景生成通过率、步骤兼容率、route 计数、页面分类、失败类别和错误码计数；
  输入顺序不影响结果。

## 清理

- 成功、失败和取消均依次调用停止场景、关闭 Bridge、清除目标 App 数据和恢复快照。
- 所有清理动作都尝试执行；任一失败使用 `SCENARIO_CLEANUP_FAILED`，不得吞掉残留风险。
- fixture 测试使用 fake 端口；本 change spec 不安装 APK、不启动模拟器、不伪造真实
  App 或设备验收。

## 允许修改

- `.trae/specs/n47-cross-app-scenario-runner/`
- `test-lab/runner/`
- 必要的 N47 场景/结果 Schema 和 fixture 场景

## 禁止修改

- Android 生产 App、N33/N42 fixture 实现、N34 collector、N45 replay 内核
- 第三方 APK、账号、凭据、真实搜索历史或运行产物
- 公共路线图和其他 change spec；由 main 在实现提交集成后统一更新

## 验收

- RED 测试先证明严格 Schema、runner、聚合器和 fixture 场景尚不存在。
- native/Web fixture 定义覆盖全部 10 类动作和三种 route。
- fake fixture 运行覆盖成功、包漂移、低 score、未知页面、广告/更新/A-B、模拟器检测、
  网络失败、后置失败、端口异常、产物调用和全路径清理。
- 高风险或未知页面动作提交数为零；后置失败提交数为一且不重放。
- N34 结果 Schema 验证、N47 smoke、`make comments` 和 `git diff --check` 通过。
