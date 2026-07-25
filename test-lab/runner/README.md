# 通用场景 Runner

本目录提供 N47 的严格场景 DSL、类型化执行内核、N34 结果映射和兼容率聚合。当前实现
只通过 fake 端口运行仓库内 native/Web/Canvas fixture，不直接执行 ADB、安装 APK、
发现设备、读取截图或调用 Accessibility。

## 验证

```bash
npm run smoke --prefix test-lab/runner
```

smoke 会验证三份严格 Schema、两个 fixture 场景、N34 `scenario-result` 映射以及
runner/聚合安全测试。fixture 动态测试共运行 18 步，覆盖：

- `launch`、`tap`、`input`、`long-click`、`scroll`、`swipe`
- `back`、`home`、`recents`、`switch-app`
- `semantic`、`hybrid`、`visual` route

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

当前没有 production observer/router/executor adapter，也没有在 emulator 上运行该
runner。真实第三方 App 仍要求用户提供合法、固定身份的仓库外 APK，并通过 N46
verifier/lifecycle；登录、发送、购买、验证码、生物识别和系统安全设置不属于自动
场景。
