# N43 WebView 语义兼容与多页面回放规格

## 目标

在不读取私有 DOM、不注入 JavaScript 且不猜测坐标的前提下，使用 WebView 暴露的
Accessibility 虚拟节点规划并编排点击、输入、长按、滚动、页面跳转和 Back。语义
能力不足时必须在动作提交前失败关闭，并输出可机器判断的兼容等级和错误码。

## 范围

- 定义 API、WebView 版本、页面和语义能力的兼容矩阵。
- 定义 `full semantic`、`hybrid`、`visual-only`、`unsupported` 四级结果。
- 通过可注入端口保存跨页面流程快照、复位 fixture 并确定性回放。
- 每一步绑定前置 package、page、semantic observation 与可验证后置状态。
- 提供包装现有 `ReplayGateway` 的最小 adapter，设备 instrumentation 可注入页面身份、
  API 和 WebView 版本元数据。
- 使用纯单元测试覆盖 N42 full、partial、canvas、动态 DOM、iframe 和多页面场景。

## 安全边界

- 只提交现有 `RecordedAction` 类型化语义动作，不提交 tap、swipe 或任意坐标动作。
- 节点缺失、动作能力缺失或动态 DOM/iframe 未暴露时返回
  `SEMANTIC_UNAVAILABLE`。
- 节点歧义、package 漂移、页面变化或 observation 过期时失败关闭，不尝试其他节点。
- 视觉等级只是能力报告；N44/N45 完成前 N43 不执行视觉候选或坐标降级。
- 不修改 recording core models/store/repository，不向生产 WebView 注入调试接口。

## 允许修改

- `.trae/specs/n43-webview-semantic-replay/`
- `android/app/src/main/java/dev/aiauto/android/automation/recording/webview/`
- `android/app/src/test/java/dev/aiauto/android/automation/recording/webview/`
- 如设备接入确需新增文件，仅使用 N42 预留的专用 adapter 测试目录，不改 N42 文件。

## 验收

- 四级能力分类与每项缺失原因有纯单元测试。
- 点击、输入、长按、滚动、跳转和 Back 均通过现有类型化动作端口规划和验证。
- 保存、reset、跨页面 replay 的任一步失败都停止后续步骤。
- 所有失败关闭场景断言动作提交数为零或停止后不再增长。
- API 30、33、34 固定 AVD 的完整语义流程各连续运行 20 次，成功率不低于 95%。
- 运行定向 app 单测、相关 app 单测、`make comments` 和 `git diff --check`。

## 设备验收边界

纯单元测试和 adapter 构建不能替代设备结论。API 30、33、34 各 20 轮矩阵由主线程
在显式 serial、固定 WebView 版本和 clean snapshot 下执行；完成前不得勾选设备项，
不得宣称 N43 完成。
