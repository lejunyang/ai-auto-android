# N33 Progress

本文件 append-only，记录实现、测试、设备证据、失败和清理结果。

## Round 1

- 已创建独立 change spec，并确认 worktree 位于 `phase2-n33-native-fixture`，
  基线包含 N31 与共享 Android 集成。
- 现有 Fixture 只有单页 toggle，没有 instrumentation、手势目标、三页 Back 栈或
  系统导航状态；当前尚未满足 N33。
- 测试设计拆分为 `core` 与 `system`：前者覆盖业务控件、dialog、多页面和复位，
  后者覆盖 Home、Recents、Settings 应用切换和 Launcher 重入。
- 重复入口使用 instrumentation 参数 `fixtureScenario` 与 `fixtureRepeat`，设备矩阵
  由集成线程使用明确 serial 执行。
- 当前状态：先写失败测试，生产实现尚未修改，设备验收尚未开始。

## Round 2

- RED 阶段先增加 3 个 `FixtureStateTest` 和 UI Automator 场景；JVM 测试按预期因
  `FixtureState` 尚不存在而编译失败，随后才实现生产代码。
- 审查后移除 instrumentation 中全部 `executeShellCommand`、`am force-stop`、
  `pm clear` 和固定 `overview_panel` 依赖；改用 debug-only、不可导出的
  `TestResetActivity` 与应用内 Reset 清理进程状态和任务栈。
- Recents 场景不依赖 SystemUI resource ID：动作前验证 Fixture 前台和 pending
  状态，动作后确认 Fixture 离开前台，并校验稳定前台包属于本轮观察到的 Launcher
  或系统包；Launcher 重入后由独立 `recents_return_state` 验证结果。
- 已实现三页平台 Back 栈、可关闭 dialog、文本输入、点击、长按、独立纵向滚动、
  横向 swipe、Home、Recents、Settings 应用切换和 Launcher 重入。每个动作均有固定
  `contentDescription` 与独立状态节点，原有 `local_toggle` 契约保持兼容。
- 状态仅保存在进程内，不声明 `INTERNET` 或其他权限；Reset 同时清空输入、计数、
  系统 pending/return、滚动位置和 legacy toggle，不保存敏感数据或产生外部副作用。
- `FixtureStateTest` 3/3 通过；`:device-fixture:assembleDebug`、
  `:device-fixture:assembleDebugAndroidTest` 和 `:device-fixture:lintDebug` 通过。
  lint 为 0 error；版本固定和既有 Fixture 发布配置仍有非阻断 warning。
- `make comments` 覆盖 211 个手写文件及 14 个正反例通过；`git diff --check` 和
  shell/SystemUI 固定资源安全扫描通过。
- instrumentation 入口为
  `dev.aiauto.fixture.NativeFixtureDeviceTest#runSelectedFixtureScenario`；参数
  `fixtureScenario=all|core|system`、`fixtureRepeat=1..100`。
- API 30、33、34 的设备 instrumentation 和各 20 轮成功率统计尚未运行，必须由
  集成线程使用明确 serial 和 N31 runner 完成；本轮不将 APK 编译描述为设备验收。

## Round 3

- API 30 首次设备矩阵使用明确 serial `emulator-5576`、N31 fingerprint 和
  `fixtureRepeat=20`；第 1 轮的内层纵向滚动没有产生 `VERTICAL_SCROLL:1`，
  instrumentation 明确以 0/1 失败并停止，后续动作未执行。
- 失败后 N31 runner 正常停止该 serial，`aactl devices list` 返回 0，emulator、
  runtime 和 AVD/port lease 均无残留。该轮不计入 20 次成功率证据。
- 根因包含两层：原根布局不是可语义滚动的外层容器，内层目标可能不完整可见；在顶部
  对内层调用 `Direction.DOWN` 也不会产生正 `scrollY`。修复增加稳定外层
  `fixture_scroll_container`，先通过语义动作使目标完整可见，再使用
  `Direction.UP` 驱动内层真实滚动。
- 内层滚动新增机器可读 `VERTICAL_OFFSET`，测试同时断言节点 `isScrollable`、
  offset 大于零和独立动作状态；横向目标也断言真实 offset，不能仅由命令退出码通过。
- 自定义 `DeterministicScrollView` 只在内层真实触摸期间阻止父容器截获，不直接设置
  后置状态；状态仍仅由 `scrollY` 变化监听器更新。
- follow-up 模块单测、Debug/AndroidTest APK 和 lint 共 78 个任务通过；中文注释与
  差异检查在提交前复验。API 30/33/34 各 20 轮设备矩阵仍由主线程执行，设备项保持
  未勾选。
- 修复后的 API 30 单轮 smoke 首次仍失败：在 1080x2400 屏幕上页面内容已完整可见，
  因此外层容器合法地不暴露滚动动作；测试却在检查目标可见性前强制
  `container.isScrollable`。该轮未执行内层手势，runner 停止 `emulator-5554` 后
  设备、runtime 和 lease 再次为零。
- reveal 逻辑改为先验证目标边界；目标已完整可见时直接使用，只有目标被裁剪时才要求
  外层提供语义滚动并执行对齐。内层真实 offset 和状态断言保持不变。
- 下一次 API 30 单轮 smoke 已进入内层目标，但平台节点把自定义
  `DeterministicScrollView` 报告为 `isScrollable=false`，测试在手势前再次停止。
  `UiObject2.scroll/swipe` 仍可对明确对象边界注入类型化手势，因此移除不可靠的元数据
  硬门；验收继续要求真实 offset 大于零和独立状态为 1，动作无效仍会失败关闭。
- 移除元数据硬门后的单轮已实际调用 `UiObject2.scroll`，但 `VERTICAL_OFFSET` 仍为
  0，证明该语义 helper 在嵌套自定义 ScrollView 上没有注入有效位移；runner 停止
  `emulator-5558` 后再次确认零残留。
- 下一路由从每轮最新 `UiObject2.visibleBounds` 计算目标内部 swipe 起止点，纵向由
  下向上、横向由右向左，坐标不持久化也不跨越目标边界。测试同时要求输入注入返回
  true、真实 offset 大于零和独立状态为 1，任何一个条件不满足都失败。
- 目标内部坐标 swipe 已成功注入但内层 `scrollY` 仍为 0，确认根因在嵌套 View 的
  触摸路由而非目标定位。`DeterministicScrollView` 现只根据真实
  `MotionEvent.ACTION_MOVE` 的 y delta 调用 `scrollBy`；它不写测试状态，状态仍由
  Activity 的真实 `scrollY` 变化监听器产生。
- 即使自定义 View 处理 MOVE，双 ScrollView 结构下 API 30 的内层 offset 仍为 0。
  固定 1080x2400/420dpi profile 能完整容纳全部控件，因此移除无必要的外层
  ScrollView，恢复单层页面，让事件直接命中内层纵/横目标。
- 单层测试不再尝试调整页面位置；每轮要求目标直接存在、达到固定高度并完整位于最新
  屏幕边界内，再执行目标内 swipe 和 offset/state 双重验证。固定 profile 下目标
  不可见会明确失败，不用猜测页面坐标。
- 单层结构下真实 swipe 仍未进入内层 `onTouchEvent`，offset 保持 0，说明子内容先
  接收了 dispatch。专用纵向 surface 内没有可交互子控件，因此
  `DeterministicScrollView.onInterceptTouchEvent` 明确截获该 surface 内触摸，再由
  MOVE delta 更新真实 `scrollY`；后置仍由 offset/state 客观验证。
- 最后一次 API 30 单轮使用明确 serial `emulator-5566` 和固定 N31 fingerprint；
  单层布局与触摸截获均已生效，但纵向目标仍返回 `VERTICAL_OFFSET:0`，测试明确失败
  并在首个手势后停止。该结果证明当前 UiAutomator 注入路径不能满足 N33 的真实位移
  验收，不能靠放宽断言或直接写状态继续推进。
- runner 已停止 `emulator-5566`，最终设备、emulator、runtime 与 lease 均为零。
  本轮停止继续试错，API 33/34 与各 20 轮矩阵未启动；N33 保持未完成，后续需独立
  设计可由平台输入路由稳定命中的手势 surface，再从 clean snapshot 重新验收。
