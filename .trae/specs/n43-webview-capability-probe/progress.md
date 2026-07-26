# N43 WebView 能力探针进度

本文件 append-only，记录固定 API/WebView action matrix、失败边界和设备清理。

## Round 1

- worktree `/private/tmp/ai-auto-n43-probe` 位于
  `phase2-n43-capability-probe`，基线 `6e16a2a`，创建时主 worktree 干净。
- API 30/WebView 91 既有证据显示输入节点初始不暴露 `ACTION_SET_TEXT` 或 click。
- API 33/WebView 109 与 API 34/WebView 113 新增单轮均在首个 button 步骤失败：
  虚拟节点 `ACTION_CLICK` 返回成功，但后置 DOM state 保持 ready。两轮均停止并清理。
- 完整场景失败发生在输入前，故不能据此推断 API 33/34 input action list；下一步只读
  采集关键节点能力，不执行动作。

## Round 2

- 新增只读 `N43WebViewCapabilityProbeTest`，输出仅包含 API、WebView、节点 alias、
  match count、class、editable/focusable/focused 和排序后的 action ID。JVM 静态契约
  证明 probe 不调用 `performAction`、坐标、截图、剪贴板、IME、shell 或 DOM/JS，
  也不输出 bounds、页面文本、hierarchy 或截图。
- API 30/33/34 固定 profile 的 matrix 一致。input 均为唯一
  `android.widget.EditText`，`editable=true`，同时暴露 `ACTION_CLICK=16` 和
  `ACTION_SET_TEXT=2097152`；因此 Round 1 的输入缺失假设由设备证据更正为 selector
  错误，完整语义输入并非高 API 独有。
- 三版本 long-click 目标均为 `android.widget.Button`，action list 不含
  `ACTION_LONG_CLICK=32`；它不能通过纯语义 route 完成。scroll container 有两个
  候选，只有 `android.webkit.WebView` 暴露 `ACTION_SCROLL_FORWARD=4096`，场景测试
  使用该能力完成滚动和后置点击。
- 原完整场景首个 click 的失败来自 reset 等待复用了旧页面的 ready 状态。fixture
  增加原生 `LOAD_GENERATION`，只在可信本地 `onPageFinished` 后递增；reset、导航
  和 Back 均等待 generation 增长，避免对陈旧虚拟节点提交动作。

## Round 3

- API 30/33/34 各使用 N31 clean snapshot、明确 serial、固定 fingerprint 和对应
  WebView 91/109/113 运行一次场景。三轮均通过 click、唯一 editable input 的
  `ACTION_SET_TEXT`、动态 DOM、语义 scroll、详情导航和 typed global Back。
- long-click 没有语义 action，稳定报告 `N43_HYBRID_REQUIRED:longClick`。iframe
  button 和详情 action 的 `ACTION_CLICK` 均只提交一次；动作返回成功但更新后的
  页面文本没有进入新语义树，分别报告
  `N43_HYBRID_REQUIRED:iframePostcondition` 与
  `N43_HYBRID_REQUIRED:detailPostcondition`，没有盲目重放。
- 安全降级固定为：long-click 仅在 N45 授权 observation、唯一候选、可信 lease 和
  可验证后置下执行 typed visual long-click；iframe/详情保持原语义 click，仅用同次
  授权视觉 observation 验证后置，不再点击。无授权、歧义、低置信度、secure 页面或
  后置不明确时归为 unsupported/人工介入，动作提交数为零或不追加第二次提交。
- fixture 宿主原先未把系统 Back 转给 WebView 历史，导致返回 Launcher；迁移到
  AndroidX `OnBackPressedDispatcher` 并接入 `canGoBack/goBack` 后，三版本均用
  generation 与主页面语义状态验证返回，也覆盖 target API 36 的预测性返回契约。
  这属于 fixture 导航接线，不是 API 30 或 WebView 91 的能力缺失。
- JVM/AndroidTest 编译 53 tasks 通过；三版本场景与只读 probe 均各 1/1 通过。
  最终 `aactl` 为 0 devices，N31 runtime、AVD/port lease 和 owned emulator 为零。
  这些是单轮能力证据，不是每版本 20 轮成功率验收，N43 仍保持未完成。

## Round 4

- 模块全量 lint 首轮拒绝 legacy `Activity.onBackPressed()`，错误为
  `GestureBackNavigation`。未增加 suppression 或 baseline；fixture 复用项目锁定的
  AndroidX Activity 版本，迁移到 lifecycle-aware `OnBackPressedDispatcher`，
  `test + lint + build` 最终 88 tasks 全通过，覆盖 debug、androidTest 与 release。
- dispatcher 迁移后的场景复验中，API 30 `emulator-5568` 与 API 33
  `emulator-5570` 各 1/1 通过。API 34 `emulator-5572` 首轮在基础 click 后失败：
  `ACTION_CLICK` 返回成功，但语义树保持 `Fixture state ready`，未出现
  `Fixture state clicked`；结果未知，因此未在同一页面重试。
- runner 停止失败设备后，从新的 clean snapshot 启动 API 34
  `emulator-5574`，相同场景 1/1 通过。故迁移后 API 34 的真实统计为 1/2，而不是
  确定性通过；该波动与此前 WebView 113 首 click 偶发无后置一致，进一步证明
  action 返回值不能替代新观察，也不能跳过每版本 20 轮与 flaky 统计门。
- 最终再次确认 0 devices、0 N31 runtime、0 AVD/port lease、0 owned emulator。
  N43 保持未完成，不能因最后一次 clean run 成功而覆盖前一次失败。

## Round 5

- 实现与设备证据提交为 `b3e66b8`，作者和提交者均为
  `lejunyang <lejunyang@qq.com>`，要求的 co-author trailer 恰好一次；主分支等价
  集成提交为 `9cc8db6`。
- 提交前 `make comments`、`git diff --check`、Web fixture 完整
  `test + lint + build` 及设备/N31 零残留检查均通过。公共路线图只追加更正与剩余
  缺口，N43 仍未勾选。
