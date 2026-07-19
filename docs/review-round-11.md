# Round 11 设备验收评审

评审日期：2026-07-19

## 结论

- 验收结论：**PASS**。
- API 33 设备自检已证明用户语义交互会停止真实 AI 会话，且停止后不再提交动作。
- 结合 Round 10 的 API 34 真机证据，Task 21、Task 23 和跨 API 触摸清单项已关闭。

## 实现与修复

| 提交 | 内容 |
| --- | --- |
| `d64cca1` | 增加 debug-only 真实 Engine 自检，覆盖自动化归因、用户停止、审计、executor 和 Runtime |
| `dbc7384` | 将 120 秒人工操作窗口与 10 秒断言等待分离，避免验收会话提前超时 |
| `835f931` | 使用 Planner 进入同步点替代固定延时，消除 host 测试竞态 |

自检宿主只存在于 debug source set，不进入 release APK。生产会话停止、风险策略和
无障碍权限模型未被放宽。

## 安全边界

- 无障碍服务必须由用户在系统设置中手动启用；测试不写入 `Settings.Secure`。
- 不启用触摸探索、raw touchscreen motion observer 或
  `FLAG_SEND_MOTION_EVENTS`，避免改变普通触控语义。
- 自动化事件按 session、window、node identity、事件集合、预算和生命周期归因。
- 无法建立唯一语义身份时 fail closed，不用猜测坐标替代安全判断。
- 停止检查要求真实会话为 `Stopped`、停止审计存在、executor 调用为 0，且
  Runtime 注册已清理。

## 设备证据

| 环境 | 结果 |
| --- | --- |
| OPPO Android 14 / API 34 真机 | `AUTOMATION_CLICK_PASS`、`USER_TOUCH_PASS` |
| Android 13 / API 33 模拟器 | `SESSION_STOP_PASS phase=Stopped executorCalls=0` |
| API 33 独立复核 | `aactl` hierarchy 精确复核 PASS 状态，未保存原始 hierarchy |

API 33 自检使用真实 `AutomationSessionEngine`。自动化点击未被误判；用户在目标
节点上的语义点击使会话停止，写入停止审计，停止后 executor 保持为 0，并清理
活动 Runtime。

## 回归结果

| 范围 | 结果 |
| --- | --- |
| 协议 | 10 个 Schema、29 项检查通过 |
| Agent Skills | 3 组发布 Skills 及镜像通过 |
| Go | 全包测试、race、`make test`、`make verify`、`make build` 通过 |
| Android 单测 | 185 个通过，0 failure/error/skip |
| Android lint | 0 error，26 warning |
| Android APK | debug、androidTest、release 构建通过 |
| Harness 稳定性 | 定向测试连续 3 次直接通过 |
| Diff | `git diff --check` 通过 |

Round 11 未重跑 `connectedDebugAndroidTest`。Round 10 已记录 3 个录制 UI 测试
通过，2 个需要手动无障碍授权的用例按设计跳过。

## 残余限制

- 用户触摸中止覆盖能产生普通 View accessibility events 的语义交互，包括点击、
  长按、滚动和文本变化。
- 空白区域、自绘 Canvas 或其他不产生普通 View accessibility events 的触摸仍
  无法在不改变设备触控语义的前提下可靠观察。
- Round 11 的 API 33 AVD 是临时本地验收环境，不属于仓库源码、构建产物或发布制品。
