# N44 授权视觉会话集成规格

## 目标

把已有 Automation Session 的显式截图授权、同轮脱敏 Accessibility hierarchy 和
N44 visual observation 绑定起来，让 Provider 接收可审计的 observation 元数据与
短生命周期最小 PNG，而不新增权限入口或视觉动作执行能力。

## 安全边界

- 只在 `screenshotsAllowed=true` 且同一会话代次仍活跃时采集；关闭或替换会话后
  原授权不得复用。
- hierarchy 必须来自当前目标包，截图后重新观察并与规划输入完全一致；漂移时
  Provider 调用数为零。
- 截图仍由现有 `AccessibilityScreenshotController` 拒绝 sensitive/secure window、
  包变化、超预算和授权撤销；本任务不新增 MediaProjection 或系统权限入口。
- visual store 的可信 verifier 必须同时验证独立会话代次，不接受 self-hash 作为
  脱敏证明。
- Provider 只借用图片副本；成功、失败、取消和会话停止后，截图原字节、store
  owned bytes 与 Provider borrowed bytes 全部清零并撤销 observation。
- Provider prompt 只增加 observation ID、包、尺寸、crop、时间和 PNG hash/size；
  不把原图、Base64 或本地路径写入日志、状态、脚本或结构化结果。

## 允许修改

- `.trae/specs/n44-visual-session-integration/`
- Android `automation/session/` 的视觉会话 adapter、models 和测试
- Android `provider/AutomationProvider.kt`、`OpenAICompatibleProvider.kt` 及测试
- 现有手工截图 capability test 的 observation 输入适配

## 禁止事项

- 不修改截图系统授权、无障碍 disclosure、N44 visual core、recording、N41/N45、
  协议 manifest、共享路线图或 Gradle。
- 不注册桌面 MCP visual tool；桌面端没有同 observation 的可信采集端口前必须保持
  未公开。
- 不执行视觉候选，不把候选转换为动作，不降低现有 Provider action 风险门。

## 验收

- 截图开启时 Provider 收到同 observation 的元数据、脱敏 hierarchy summary 与 PNG。
- 截图关闭保持原纯语义流程且不创建 observation。
- hierarchy 缺失/漂移、包变化、授权代次失效时 Provider 调用数为零。
- 成功、Provider 抛错和取消路径图片全部清零，store 中 observation 撤销。
- Android 定向与 App 全量单测、lint、comments、diff-check 通过。
