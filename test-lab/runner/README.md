# 通用场景 Runner

本目录提供 N47 的严格场景 DSL、类型化执行内核、N34 结果映射、兼容率聚合和固定
production fixture CLI。CLI 只接受 `api-30|33|34`，不接受 serial、package、APK、
task、场景或动作；production 端口使用固定 N31、fixture APK、aactl 和无坐标
`android_visual_action_execute` 契约。

## 验证

```bash
npm run smoke --prefix test-lab/runner
```

smoke 会验证三份严格 Schema、两个 fixture 场景、N34 `scenario-result` 映射以及
runner/聚合安全测试。fixture 动态测试共运行 18 步，覆盖：

- `launch`、`tap`、`input`、`long-click`、`scroll`、`swipe`
- `back`、`home`、`recents`、`switch-app`
- `semantic`、`hybrid`、`visual` route

## Production CLI

```bash
npm run production-fixture --prefix test-lab/runner -- --profile api-33
```

当前仓库缺少可由 host 安全调用的 debug/test-only Bridge 与 Accessibility 动作授权
入口，因此 CLI 会在 N31 start、APK 安装和动作之前稳定返回
`PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE`。这不是跳过：capability 缺失必须零
启动、零动作。未来只有注入绑定 N31 serial/fingerprint 且具备 teardown 的类型化授权
provider 后，production port factory 才会执行固定 native、WebView、Canvas 场景。

## 端口边界

生产接线必须显式提供以下窄端口：

- `observer.observe`：返回短期 observation、前台包和页面分类。
- `conditions.verify`：只读验证 pre/post condition。
- `router.resolve`：返回绑定 observation 的 route、score、attempts 和不透明 token。
- `executor.execute`：只执行一次类型化动作，并明确返回 `committed: true/false`。
- `values.resolve`：仅在 input 动作调用栈内解析 `valueRef`。
- `artifacts.collect`：接收已通过 N34 Schema 的失败 run。
- `lifecycle`：停止场景、关闭 Bridge、清除 App 数据和恢复快照。

executor 异常时提交状态为未知，报告使用 `actionCommits: null` 和
`ACTION_COMMIT_UNKNOWN`，不得伪报零提交或重放动作。

## 未完成边界

当前没有 API 30/33/34 的 production fixture 设备闭环证据；fake 只证明固定 argv、
stdin、身份门、提交语义和清理编排。真实第三方 App 仍要求用户提供合法、固定身份的
仓库外 APK，并通过 N46 verifier/lifecycle；登录、发送、购买、验证码、生物识别和
系统安全设置不属于自动场景。
