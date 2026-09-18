# C 端随身 Agent UI 实现追踪

> 本文档是本次 UI/随身 Agent 实现的唯一日常进度入口。
> 实现细节与技术决策留独立 change spec（`.trae/specs/n56-consumer-ui-implementation/`、
> `.trae/specs/n56-consumer-ui-wave2/`），本文不重复长日志。
>
> 状态定义：待做 / 进行中 / 已实现待设备验证 / 已验证（含设备截图证据） / 阻塞
> 最后更新：2026-09-18

## 总目标

将 `docs/ui-prototype/index.html` 确认的四 tab 信息架构落地为真实 Android 体验：
助理、技能、记录、我的。所有状态来自真实运行时，不硬编码就绪/结果/历史。
在技术与授权边界内补齐语音、悬浮、运行通知、会话历史、录制编辑器体验对齐。

## 分项状态

| 模块 | 内容 | 状态 | 验收证据 |
|---|---|---|---|
| 导航骨架 | 底部四 tab + 嵌套导航 + Uri.encode + 统一 switchTab（修复 tab 失效 P0） | 已验证（设备往返） | TabNavigationTest 11测试通过；设备验证助理↔技能↔我的往返，坐标从 dump 解析 |
| 主题 | 固定配色（背景#EEF1F5 冷灰蓝/卡片#FFFFFF/主色#2563EB），无动态取色 | 已实现，已设备复验 | Theme.kt 用 AppColors；navfix-settings 截图确认 |
| 设计token层 | AppColors/AppShapes/AppSpacing/AppTypography（逐元素实测）+ DesignTokenParityTest | 已实现，单测通过 | 从 20 个原型视图机器抽取；parity 测试通过 |
| 共享组件 | AppComponents.kt + ScriptStoreProducers.kt + ScreenScaffold（二级页外壳重做） | 已实现，二级页已设备复验 | navfix-bridge 截图确认返回箭头+标题+副标题+info |
| 助理页 | Composer卡片(24dp圆角+1px#D9E3F3边框+阴影+无内部分隔线+16dp padding+50dp输入框+灰底pill选择应用+自定义phone/mic/up图标)+问候标题强制两行(27sp/620/-1/37.8)+右上tiny-orbit(42dp浅蓝圆角方块rotate-7°内蓝色Auto三角竖线，替换原"△"字符占位)+快捷chips灰底小胶囊(#E8EDF4/#5D6C82/10dp/44dp/11sp/自动换行)+常用技能两列等高网格(静态Column+Row，tile固定112.5dp)+虚线teach卡+点击空白失焦收键盘 | 已验证（四页37元素0超差+设备截图+失焦） | v3-home-empty 截图；element-compare skillTile 180.6x112.4≈原型180.5x112.5；失焦mInputShown true→false |
| 技能页 | 单列宽卡列表(原型skills-all)：页头info+区块标题"教会它的事"(24sp/700)+加号+三filter-tab(全部/我教的/灵感，选中ink底白字)+技能卡(左上AppIconBlock真实launcher图标，包不存在回退描边Book/Note+已保存绿色TagChip+标题14sp+目标包名描述+步数手动触发+chevron)+灵感未来概念卡(点击弹"规划中"，不可执行)+teach虚线轻卡 | 已验证（0超差+设备截图+交互11/11） | v3-skills-all 截图；灵感卡弹规划中对话框、info弹"关于技能"、+进入录制流程均设备验证；test-only fixture预置脚本 |
| 记录页 | 单列宽卡(原型history-all)：页头info+区块标题"做过的，都在这里。"(24sp/700)+三filter-tab(全部/已完成/需处理，选中ink底白字)+记录卡(状态tag已完成绿/需处理琥珀，统一TagChip约25.9dp+chevron+标题固定"自动化任务"TASK_SUMMARY脱敏+状态说明+元信息目标包名·时间·N步)+只读详情对话框(目标应用/开始/结束/N步/隐私说明)+隐私note | 已验证（0超差+设备截图+交互） | v3-history-all 截图；点记录卡弹只读详情、info弹"关于运行记录"、已完成筛选仅显示Completed均设备验证 |
| 我的页 | 页头(标题18sp+副标题12sp+info)+蓝色实心52dp logo(白色Auto三角竖线，非spark)+助理状态卡(白底+1px line边框+18dp圆角+琥珀"未完成配置"tag+AI模型/允许操作的应用子状态)+连接与能力三行setting-row(无图标底块+20dp自定义spark/shield/monitor描边图标#68809e+min-height63dp+行间border-bottom+右侧状态+chevron)+随时唤起(悬浮入口/运行通知真实授权状态"需授权>") | 已验证（0超差+设备截图+交互） | v3-settings 截图；info弹"关于这一页"设备验证；logo为白Auto、setting-row为自定义描边图标 |
| 我的下二级页 | AI模型/允许操作的应用/连接电脑：外壳与内容均按原型重构，真实能力与安全文案保留 | 已验证（设备截图） | rev-02-provider / rev-03-permissions / rev-04-connection 截图；diff 超差 0 |
| 底部导航 | 自定义Row+Column实现（68dp内容区+navigationBarsPadding手势条+weight(1f)均分宽度+padding 7/8/4dp+顶部1dp描边+46×27dp选中胶囊#E8EFFD+20dp自定义stroke图标(spark/flow/clock/settings)+12sp标签），弃用Material3 NavigationBar；settings横线选中跟随tint变蓝；flow节点7×5横向矩形 | 已验证（设备截图+dump点击中心坐标） | p1-home/p1-skills/p1-settings 截图确认4个自定义stroke图标+选中态浅蓝胶囊+手势条不压标签；点击各tab中心(135/405/675/945)均正确导航 |
| 模块A：会话历史持久化 | SessionHistoryRecord(固定摘要安全策略)+Store(有界/可删/损坏Partial)+coordinator终态写入 | 已实现，单测通过 | compileDebugKotlin通过；SessionHistoryStoreTest 13测试通过（UUID id/TASK_SUMMARY/损坏Partial/有界/删除） |
| 模块B：语音输入 | VoiceInputHandler+权限检查+拒绝/无服务降级+外发说明+助理页语音按钮 | 已实现，代码验证(权限拒绝逻辑)，设备未复验 | compileDebugKotlin通过 |
| 模块C：前台服务+运行通知 | SessionCoordinator单一owner+Application级Runtime+ViewModel仅订阅+通知安全短标题 | 已实现，单测通过，真实会话运行时未设备验证 | SessionCoordinatorTest 16测试通过；compileDebugKotlin通过 |
| 模块D：受控悬浮窗 | OverlayController+权限引导+运行状态/结果+收起展开+我的页入口 | 已实现，真实会话运行时未设备验证 | OverlayController/OverlayWindow编译通过 |
| 模块E：录制编辑器 | 步骤编排/用一句话表达tab+UpdateDescription命令+撤销/保存冲突/高级字段 | 已实现，待设备验证 | compileDebugKotlin通过；tab结构已建 |
| 模块F：语言归纳 | SkillLanguageGenerator+Provider调用+敏感过滤+不静默覆盖+已接入编辑器 | 已实现，单测通过（NotConfigured/HTTP错误/空响应/敏感过滤） | compileDebugKotlin通过；SkillLanguageGeneratorTest 4测试通过 |
| 设备视觉验证 | 固定AVD(ui-visual-35 1080x2400 420dpi zh-CN)+显式serial+可靠截图(screencap到设备再pull)+PIL验证+MainAgent逐张目检 | 已验证（四页0超差+交互11/11） | v3-home-empty/v3-skills-all/v3-history-all/v3-settings 四张最终截图PIL verify并实际看图；interact/ 下对话框与录制页截图 |
| 元素对比机制 | element-compare.py（节点树版）：uiautomator dump 解析 bounds→叶子/edittext/clickable/LCA/聚类多策略匹配→PIL crop 并排裁剪→尺寸差异；tag 只比高度、容器类按口径豁免 | 已验证，四页37元素0超差 | home-empty 0/13、skills-all 0/9、history-all 0/9、settings 0/6；产物 artifacts/ui-validation/element-compare/ |
| 交互设备验证 | 灵感筛选/未来概念卡/规划中对话框、三页info说明、记录只读详情、+进入录制并返回、筛选切换 | 已验证 11/11 | verify_interactions.py 全 PASS；ia-02-idea-dialog/ib-01-history-detail/ia-04-record 截图目检 |
| 全量验证 | make comments + testDebugUnitTest + assembleDebug + lintDebug | 已验证 | testDebugUnitTest 525通过/0失败/1跳过(既有)；lintDebug、assembleDebug BUILD SUCCESSFUL；make comments通过（540文件）；git diff --check通过 |
| 源码清理 | HomeScreen.kt/TaskScreen.kt（git已删除）；SessionViewModelTest.kt已恢复更新为11测试 | 已完成 | git f1ef91b删除HomeScreen/TaskScreen；13cce8e恢复SessionViewModelTest |
| 验证产物清理 | artifacts/ui-validation/ 下旧截图v2-v15及损坏01-04已删除（ignored目录，不进git）；保留final-01~04和prototype参考 | 已完成 | 本地文件系统清理 |

## 本轮逐元素复核修正（2026-09-18，MainAgent 亲手设备验收）

organizer 交付三页对齐后，MainAgent 不采信"报告完成"，自行装 APK、dump、裁剪、看图，
按原型采集 token 逐项核对并修正，最终四页 37 个元素 **0 超差**、交互 11/11。

1. **图标体系统一为自定义描边图标（AppIcons.kt）**：以原型 `paths` 对象为准，用
   `ImageVector.Builder + addPathNodes`（stroke-width 1.65、round cap/join）重绘，弃用造型不符的
   Material 实心图标。品牌符号 **Auto = 三角形+中竖线**（`M5 17L12 5l7 12H5`+`M12 5V20`），
   用于问候右上 tiny-orbit、我的页 logo（蓝底白 Auto）、悬浮球；底栏助理仍是四角星 spark。
   新增 Book/Note 描边图标；首页 tile 与最近活动由 Material MenuBook 改为描边 Book。
2. **真实 launcher 图标（AppIconBlock.kt，新建）**：技能卡左上按 `targetPackages.distinct().take(2)`
   加载应用真实图标（PackageManager 先验存在再取图标，Dispatchers.IO 不阻塞主线程，toBitmap 后
   clip 11dp 圆角、无底色）；包不存在（如 fixture 的 com.example.*）回退浅蓝底描边 Book /
   薄荷底 Note。真实已安装应用的双图标圆角加载路径已具备，待真机/真实包名目检。
3. **标签配色与高度（TagChip）**：记录"已完成"=绿、"需处理"=琥珀、技能"已保存"=绿
   （原先误用蓝色，且技能页是内联 Surface 未复用组件，已统一改为 TagChip SUCCESS）。
   高度由 33.9dp 修到约 25.9dp（对齐原型 tagGreen 26.8dp）：`lineHeight=16.sp` 且
   `PlatformTextStyle(includeFontPadding=false)` 必须放进 `style.copy`，不能作 Text() 顶层参数。
4. **标题字号层级**（以原型采集 JSON font 字段为准，曾整体反掉）：页头标题 18sp/650/行高24/字距-0.7、
   副标题 12sp；区块大标题（"教会它的事"/"做过的，都在这里。"）24sp/700/行高36/字距-0.8；
   我的页分区标题 16sp/650（通用 SectionHeader 同步）。
5. **首页 skillTile 高度**：根因是 LazyColumn 内嵌同方向 `LazyVerticalGrid` 并写死 `height(100dp)`，
   把 tile 固定的 112.5dp 压到 100.2dp。改为非滚动静态 `Column + Row(chunked(2)) + Box(weight(1f))`，
   奇数补 Spacer，高度由 tile 112.5dp 自然撑开；复验 180.6x112.4 达标。
6. **fixture 幂等**：inject-fixtures.py 原先每次随机 UUID 且不清理，多轮运行累积出重复卡与大量
   0 字节 `.<id>.json.lock`。`--clean` 现在同时删 `*.json`、`*.lock`、`.*.lock`；run_views.py
   注入前先 clean 再注入，结果恒定 2 脚本 + 2 历史，不重复。
7. **冷启动 ANR（结论：非代码死锁）**：debug 包冷启动首帧 RenderThread 积压（Displayed≈7s、
   Skipped 203 frames）期间，uiautomator 反复 dump 注入焦点事件，5s 未被处理即触发
   "Input dispatching timed out (FocusEvent)" 系统 ANR 对话框，但背后首页已完整渲染，无 FATAL、
   主线程堆栈停在 performDraw→HardwareRenderer.setStopped→future.get 等 RenderThread。
   对照实验：force-stop→am start 后静候约 18s 不注入任何事件即正常。run_views.py 已固化
   "静候 18s + 低频轮询首页文本（4s 间隔、最多 6 次）后才导航"，复跑稳定无 ANR。

## 安全约束（不可妥协）

1. **锁屏/授权失效强制暂停，后台不等于暂停**：自动化执行期间控制 App 切到目标 App 进入后台时，前台服务继续持有会话运行，不暂停。仅在屏幕锁定（ACTION_SCREEN_OFF）、用户明确接管、无障碍授权失效时强制暂停。恢复需用户显式继续。
2. **前台服务为 runtime owner**：AutomationSessionEngine 生命周期由 SessionCoordinator/前台服务持有，Activity 仅为 UI 展示层，绑定/解绑不影响会话运行。单任务串行，禁止启动第二会话。
3. **历史固定摘要安全策略**：历史记录的 task 字段强制为固定摘要"自动化任务"，不保存用户原始任务文本，避免密码/验证码等敏感内容落盘。用户可删除单条或全部。
4. **语音外发透明**：使用系统 SpeechRecognizer，如实告知用户识别可能由系统/云端服务处理，不宣称本地离线。权限拒绝后降级为文本输入。
5. **可观察可停止**：会话运行期间，通知和悬浮窗始终展示状态并提供暂停/停止入口。

## 未验证边界（诚实说明）

以下功能代码与单测已验证，但**真实会话运行时在模拟器上尚未完整验证**，不声称已完成：

1. **模块C 前台服务运行时**：通知暂停/停止操作、锁屏 ACTION_SCREEN_OFF 强制暂停、服务异常销毁恢复——代码已实现，SessionCoordinator 单测通过，但未在真实会话运行时设备验证。
2. **模块D 悬浮窗运行时**：会话运行时自动显示悬浮窗、收起/展开、暂停/停止/结果展示——代码已实现，但未在真实会话运行时设备验证（需用户先授权 SYSTEM_ALERT_WINDOW）。
3. **模块E/F 录制编辑器完整流程**：从技能页 + 进入"录制新流程"页（脚本名/目标包名/敏感字段 secret 提示/步骤预览）已设备验证；但录制→捕获步骤→编辑→语言生成→保存→返回的完整闭环未在设备走通（需无障碍/投影授权）。
4. **技能/记录有数据态与筛选**：已通过 debug test-only fixture 预置恒定 2 脚本 + 2 历史并设备验证（v3-skills-all / v3-history-all，记录"已完成"筛选仅显示 Completed，灵感 tab 未来概念卡与规划中对话框、记录只读详情均验证）。
5. **真机验证**：当前所有设备验证在模拟器（ui-visual-35）完成，真机兼容性需用户授权后验证。
6. **真实 launcher 图标圆角加载**：AppIconBlock 的双真实应用图标组合仅在包存在时出现，本轮 fixture 用不存在的 com.example.* 只验了 Book/Note 回退；真机或已安装真实包名下的圆角图标待目检。

## 最终有效截图清单

设备指纹：AVD=ui-visual-35，serial=emulator-5554，API=35，分辨率=1080x2400，densityDpi=420，locale=zh-CN，导航=gestural。

截图方式：`adb shell screencap -p /sdcard/<name>.png` → `adb pull` → 删除设备临时文件。全部经 PIL `verify()`+`load()` 解码确认（1080x2400 RGBA）。

| 截图 | 界面 | 状态 |
|---|---|---|
| verify-round6/v3-home-empty.png | 助理首页（两行问候+蓝Auto orbit+composer+灰chips+两等高112.4 tile描边Book+虚线teach+最近活动+自定义底栏） | 已验证，0超差 |
| verify-round6/v3-skills-all.png | 技能页（区块24sp+三筛选深色选中+单列宽卡+AppIconBlock回退Book/Note+绿已保存TagChip，不重复） | 已验证，0超差 |
| verify-round6/v3-history-all.png | 记录页（区块24sp+绿已完成/琥珀需处理TagChip+固定"自动化任务"+状态说明+元信息+隐私note） | 已验证，0超差 |
| verify-round6/v3-settings.png | 我的页（蓝底白Auto logo+琥珀未完成配置+白底状态卡+spark/shield/monitor描边setting-row） | 已验证，0超差 |
| verify-round6/interact/ia-01-ideas.png | 灵感 tab：两张未来概念卡（蓝"未来概念"tag） | 已验证 |
| verify-round6/interact/ia-02-idea-dialog.png | 点灵感卡弹"规划中，当前不可执行"对话框 | 已验证 |
| verify-round6/interact/ia-04-record.png | + 进入"录制新流程"页（脚本名/目标包名/secret 提示/步骤预览） | 已验证 |
| verify-round6/interact/ib-01-history-detail.png | 记录卡只读详情（状态tag+N步+目标应用+开始/结束+隐私说明） | 已验证 |
| rev-02-provider.png / rev-03-permissions.png / rev-04-connection.png | AI模型 / 允许操作的应用 / 连接电脑（二级页原型内容布局） | 已验证 |

> 全部截图经 `screencap` → `pull` → PIL `load()` 解码确认，并实际看图核对。
> 导航坐标一律从当次 `uiautomator dump` 解析（`find-element.py`），不跨页复用。

> v3-* 为当前有效证据，对应当前 main 工作区代码（含自定义描边图标系统 AppIcons/AppIconBlock、Auto 品牌符号、
> TagChip 配色与高度、标题字号层级、首页静态网格 tile 112.5dp、四页 37 元素 0 超差、交互 11/11）。
> APK 路径 `android/app/build/outputs/apk/debug/app-debug.apk`，大小 76,889,541 字节，
> SHA256 `5635DB3ABBD987CBFD794B9EAC5350DCC7BB0D3A044EFD04E6C4DE9B94ED72B0`。
> 冷启动须静候约 18s 再 dump/tap（见上文 ANR 结论）。任务输入/语音权限/真实会话运行时（前台服务、悬浮窗）、
> 录制编辑完整闭环因需真实 Provider/无障碍/悬浮授权，未在本轮设备复验，代码与单测已验证。

**debug test-only fixture 注入方式**：`python scripts/ui-validation/inject-fixtures.py --serial <serial>` 预置 2 个无敏感测试脚本 + 2 条历史记录（Completed/Failed，task 固定为"自动化任务"）；清理：加 `--clean`。仅 debug 变体，通过 `adb run-as` 写入应用私有目录，不影响 release。

## 视觉验收标准（机器可判定协议）

### 原型采集（已建立）

原型是交互式的（hash 路由 + JS 状态机），静态截首页无法获得二级页信息。
采集链路：`scripts/ui-validation/collect-prototype-views.py` 通过 shot.py `--eval` 注入
`extract-view-tokens.js`，先切 hash / 点按钮驱动状态，再导出 computed token。

- 输出：`artifacts/ui-validation/proto/<view>.png`（411x914）+ `<view>.json`（完整 token）
- 已采集 20 个视图：home-empty/home-data、skills-all/mine/ideas、history-all/done/attention、
  settings、provider、permissions、connection、setup、record、editor、
  session-plan/running/paused/failed/done
- 坐标口径：411x914 视口下 CSS px == Android dp（1080x2400 / 420dpi = 2.625x）

### 实机比对（已建立）

`scripts/ui-validation/compare-app-to-prototype.py`：
1. 采集前用 `dumpsys window` 确认前台包，不符立即停止（防止在错误界面比对）
2. `uiautomator dump` 解析元素 bounds/text，按 `px / 2.625` 换算为 dp
3. 与原型 JSON 逐元素比对，输出 `artifacts/ui-validation/diff/<view>.json`

坐标获取：`scripts/ui-validation/find-element.py` 从当次 dump 解析真实 tap 坐标，
**禁止猜测坐标**（此前误用 y=1853/2272，实际底栏中心 y=2232，导致"验证"没点到控件）。

### 容差

| 维度 | 容差 |
|---|---|
| 颜色 hex / 字号 / 字重 / 圆角 | 必须一致（由 DesignTokenParityTest 保证） |
| 行高 | ±1dp |
| 间距 / 尺寸 | ±2dp |
| 点击区 | >=44dp（硬下限） |

### 已登记的测量口径限制（不是视觉缺陷）

1. **dump 不提供颜色/字号/圆角**：这三类由 `DesignTokenParityTest` 在 token 常量层断言，
   比对脚本只负责尺寸/间距/点击区，报告中标记 `NOT_MEASURABLE_VIA_DUMP`。
2. **容器元素无法按文本对齐**：原型容器的 text 是子元素拼接，实机是独立节点树。
   `appHeader/screen/bottomNav/composer/card` 等已在脚本中显式登记跳过，不伪造"未找到"。
3. **文本元素 width 口径不同**：原型 `<b>`/`<small>` 撑满父容器宽度，Compose Text 是内容宽度。
   这类 width 偏差属口径差异，判定应看 x 起点与高度，不看 width。

### diff 报告产物

> 口径更新（2026-09-18）：早期 `compare-app-to-prototype.py`/`batch-diff.py` 按文本匹配，
> 对容器/图标/带图标按钮误报多。**当前以节点树版 `element-compare.py` 为准**，四个主页面
> 37 个元素逐项比对**超差全部为 0**：home-empty 0/13、skills-all 0/9、history-all 0/9、settings 0/6
> （INFO 为实机无独立容器节点的口径项，不计超差；tag 只比高度，宽度差异源于脱敏文案长短）。
> 下表为早期 7 页文本匹配 diff，保留作历史，不作为最终视觉结论：

`scripts/ui-validation/batch-diff.py` 批量导航并生成报告，输出 `artifacts/ui-validation/diff/<view>.json`。
早期 7 页报告超差均为 0（"未找到"主要是原型演示专属元素，见下）：

| 视图 | 总计 | 达标 | 超差 | 未找到 |
|---|---|---|---|---|
| home-empty | 18 | 5 | 0 | 13 |
| skills-all | 8 | 0 | 0 | 8 |
| history-all | 8 | 0 | 0 | 8 |
| settings | 11 | 3 | 0 | 8 |
| provider | 11 | 0 | 0 | 11 |
| permissions | 13 | 0 | 0 | 13 |
| connection | 11 | 2 | 0 | 9 |

「未找到」的性质（不是缺功能）：
1. **原型演示专属元素**：`tag("Prototype 01")`、`tagGreen("演示已就绪")`、`textBtn("重新体验首次设置")`、
   `primaryBtn("体验配对流程")` 等。真实功能不使用"演示"字样，因此无同名元素——这是有意差异。
2. **文案有意不同**：原型 `note("同一可信局域网，核对短指纹后连接。")` 在真实页替换为按状态变化的提示；
   原型 `notice("二维码、邀请码与连接均为原型演示")` 替换为真实安全边界说明。
3. **原型有而真实功能尚未提供**：`logo` 在二级页（真实二级页不放 logo）。

### 已登记的有意偏差与测量口径豁免

以下项经核实为**不可对齐或有意不同**，已在比对脚本中显式登记，不作为超差：

| 元素 | 原因 | 保障方式 |
|---|---|---|
| 颜色 / 字号 / 圆角 | uiautomator dump 不提供这些属性 | DesignTokenParityTest 在 token 常量层断言 |
| 容器类元素（appHeader/screen/bottomNav/composer/card 等） | 原型容器 text 是子元素拼接，实机是独立节点树 | 子元素位置间接保障 |
| navItemActive / navItemInactive | 原型是含图标+标签的容器（98.8x60dp），Compose NavigationBarItem 命中节点是标签 Text（约 25x18dp），层级不同 | AppSpacing.NavHeight / NavIconSize / NavLabelFontSize 三个 token 断言 |
| composerPill / quickChip / filterTabActive / filterTabInactive | 原型是单个 `<button>`，Compose 是 Row(Icon+Text)/FilterChip，dump 命中内部 Text。实测可点击容器高 48dp（满足 >=44dp），宽度随中文文案变化 | 点击区下限已满足；圆角与配色由 token 断言 |
| 文本元素 width | 原型 `<b>`/`<small>` 撑满父容器，Compose Text 是内容宽度 | 判定 x 起点与高度 |
| 文本元素 height | Compose Text 行盒含字体上下留白，与 HTML line-height 盒模型差约 7dp | 字号/行高由 token 断言 |
| settingRowTitle / settingRowSub 的 x | 图标+文本间距由 Compose Arrangement 决定，与 HTML flex gap 累计起点不同 | 容器整体位置一致 |

### 合法平台差异白名单

系统状态栏、手势条、Material 涟漪与选中态、IME、系统字形（原型 Segoe/PingFang vs Android
Roboto/Noto，只比字号字重行高不比字形）、滚动条。

## 关键路径

- 原型：`docs/ui-prototype/index.html`
- 导航：`android/app/src/main/java/dev/aiauto/android/ui/AiAutoAndroidApp.kt`
- 四个主页面：`ui/home/AssistantScreen.kt`、`ui/skills/SkillsScreen.kt`、`ui/history/HistoryScreen.kt`、`ui/settings/SettingsScreen.kt`
- 运行时：`automation/session/coordinator/SessionCoordinator.kt`、`SessionRuntime.kt`、`AiAutoApplication.kt`
- 前台服务：`automation/session/service/AutomationSessionService.kt`
- 会话历史：`automation/session/history/`
- 语音：`ui/voice/VoiceInputHandler.kt`
- 悬浮窗：`ui/overlay/OverlayController.kt`、`OverlayWindow.kt`
- 录制编辑器：`ui/recording/RecordingEditorScreen.kt`、`RecordingEditorViewModel.kt`
- 语言归纳：`automation/recording/editor/language/SkillLanguageGenerator.kt`
- 字符串：`android/app/src/main/res/values/consumer_ui_strings.xml`

## 验证命令

```powershell
pwsh -Command "make comments"       # 中文注释覆盖
pwsh -Command "make test"           # Go 测试
pwsh -Command "make verify"         # 协议+技能+注释+元数据
pwsh -Command "make build"          # Go 构建
pwsh -Command "make android-verify" # Android 单测+构建+lint
```

Android 定向验证：
```powershell
pwsh -Command "osdk exec -t java -t android-platforms -t android-build-tools -t android-platform-tools -- ./android/gradlew.bat -p android --no-daemon :app:testDebugUnitTest :app:assembleDebug"
```

设备截图必须用可靠二进制路径：`adb -s <serial> shell screencap -p /sdcard/<name>.png` 后 `adb pull` 到本地，再删除设备临时文件；禁止用 `adb exec-out screencap` 经 PowerShell 文本管道（会转码损坏 PNG）。

## 运行时推进（会话执行闭环）

### 完成状态

| 模块 | 状态 | 设备验证证据 |
|---|---|---|
| LocalDemoProvider | ✅ 已实现 | `provider/LocalDemoProvider.kt`，step 计数器，app.launch→task.finish |
| ProviderType 枚举 | ✅ 已实现 | `ProviderConfig.kt` 新增 `enum class ProviderType { OPENAI, LOCAL_DEMO }` |
| Factory 分支 | ✅ 已实现 | `AndroidSessionAdapters.kt` 根据 config.type 分支创建 Provider |
| 就绪判断适配 | ✅ 已实现 | `AiAutoAndroidApp.kt` providerReady 从 hasApiKey 改为 isConfigured |
| 悬浮窗崩溃修复 | ✅ 已修复 | `OverlayController.kt` 反射设置 ViewTreeLifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner |
| 会话启动→执行→停止 | ✅ 设备验证 | deskclock 会话"已停止"，历史记录写入 |
| 会话历史持久化 | ✅ 设备验证 | 记录页显示 4 条历史（deskclock 已完成 + settings 需处理×2 + 旧记录） |
| 前台服务通知渠道 | ✅ 设备验证 | `automation_session` 渠道已创建，importance=2 |
| 悬浮窗系统通知 | ✅ 设备验证 | "AI 安卓自动化正在其他应用上层显示内容" |

### 关键技术决策

**悬浮窗 ComposeView 崩溃根因**：`OverlayController` 用 `applicationContext` 创建 `ComposeView`，未设置 `ViewTreeLifecycleOwner`/`ViewTreeViewModelStoreOwner`/`ViewTreeSavedStateRegistryOwner`，ComposeView attach 时抛 `IllegalStateException`。

**修复方案**：在 `OverlayController` 内部实现 `OverlayLifecycleOwner`（实现 `LifecycleOwner`+`ViewModelStoreOwner`+`SavedStateRegistryOwner`），通过**反射**调用 `androidx.lifecycle.ViewTreeLifecycleOwner.set()`、`ViewTreeViewModelStoreOwner.set()`、`androidx.savedstate.ViewTreeSavedStateRegistryOwner.set()` 设置 owner。用反射的原因：lifecycle KMP 架构下这些类在 api 制品中，编译 classpath 可能不包含，但运行时 classpath 一定有。

**LocalDemoProvider**：无需 API key、不发网络请求，第一次返回 `app.launch`（目标包从 prompt.uiSummary 正则提取），第二次及以后返回 `task.finish`。用于无真实 AI Provider 时验证会话运行时 UI/流程。

**安全边界正常工作**：`com.android.settings` 被本地策略校验拦截（"Payment, package installer, and system settings targets are not allowed"），会话标记为"需处理"；`com.google.android.deskclock` 正常执行，会话标记为"已完成"。

### 验证命令

```powershell
# root 授权无障碍（必须完整类名）
adb -s emulator-5554 shell settings put secure enabled_accessibility_services dev.aiauto.android/dev.aiauto.android.accessibility.AiAutomationAccessibilityService
adb -s emulator-5554 shell settings put secure accessibility_enabled 1

# root 授权悬浮窗
adb -s emulator-5554 shell appops set dev.aiauto.android android:system_alert_window allow

# root 写 provider 配置（LOCAL_DEMO 模式，无 API key）
adb -s emulator-5554 push provider-config.xml /data/data/dev.aiauto.android/shared_prefs/provider-config.xml

# 完整会话测试：输入→发送→计划确认→开始安全会话→观察悬浮窗/前台服务/历史
```

### 未验证边界（诚实说明）

1. **真实 AI Provider 会话**：当前用 LocalDemoProvider 验证流程，真实 OpenAI 兼容 Provider 需用户配置 API key 后验证。
2. **悬浮窗交互**：悬浮窗已显示（系统通知确认），但收起/展开/暂停/停止/结果的完整交互未在设备上逐项验证。
3. **前台服务通知操作**：通知暂停/停止按钮的交互未在设备上验证。
4. **锁屏 SCREEN_OFF 强制暂停**：代码已实现，未在设备上触发锁屏验证。
5. **BridgeAutomationProvider**：用户提到"app bridge 操控相当于一种 provider"，长期应实现通过桌面桥协议转发规划请求的 Provider，当前用 LocalDemoProvider 替代。
6. **Provider 类型选择 UI**：我的页 AI 模型行（ProviderScreen）尚未添加 OPENAI/本地演示的类型选择界面，当前只能通过 root 写 SharedPreferences 设置。
7. **会话页视觉对齐原型**：SessionScreen 功能完整但视觉是 Material 默认风格，原型已有 session-plan/running/paused/failed/done 五个视图，尚未按原型对齐。
8. **录制编辑器完整流程**：录制→捕获步骤→编辑→语言生成→保存→返回的完整闭环未在设备走通。

## 本轮 P0/P1/P2 全局修复与页面重构（2026-09-18）

### P0 全局修复（已提交并设备验证）

1. **底栏双层涟漪**：AppBottomNavigation 的 tab Column 加 `indication = null`，去掉满格灰涟漪，只保留浅蓝选中胶囊。
2. **背景色修正**：AppColors.BackgroundArgb 从 `#EEF1F5`（原型桌面 body 背景）改为 `#FAFBFC`（手机屏幕 paper 近白），同步更新 DesignTokenParityTest 断言。设备截图 p2-home2 确认背景近白、不再灰蓝。
3. **全局输入框失焦**：新建 `ui/components/FocusClear.kt`，提供 `Modifier.clearFocusOnTapOutside()`（detectTapGestures onTap → clearFocus + hideKeyboard），应用到 ScreenScaffold 根 Column 和 AiAutoAndroidApp NavHost。子元素消费点击不触发，不误吞按钮。
4. **AGENTS.md**：末尾新增"UI 交互规范"三条（失焦/弹窗/背景色）。

### P1 通用底部弹窗组件（已提交并设备验证）

新建 `ui/components/ProtoModalSheet.kt`，完全自绘（不用 Material3 ModalBottomSheet）：
- scrim 半透明深蓝 `#16233755`，点遮罩关闭
- 顶部把手 35x4dp 圆角9 `#d1d9e5`
- 白底 `#FAFBFC` 圆角28dp 底部面板
- slide up/down + fade in/out 动画（280ms）
- 标题20sp Bold + 右上关闭 X + imePadding + 内容区 verticalScroll

设备验证：助理页"选择应用"pill 点击弹出"这次使用哪些应用"底部弹窗（scrim+把手+应用列表+蓝色按钮），截图 p2-app-picker.png 确认。

### P2 页面重构（三个子代理并行，目录互斥）

| 页面 | 改动 | 设备验证 |
|---|---|---|
| ProviderScreen | BasicTextField 自绘输入框（白底+1dp描边+13dp圆角+50dp高+16sp垂直居中），修复文字截断 | 编译通过 |
| AccessibilityScreen | 整体重写：删手动包名输入框/撤销按钮，改 PackageManager 应用列表+Switch（每行图标+应用名+包名+Switch），"打开无障碍配置"按钮仅在未开启时显示，琥珀色安全披露 NoticeBar 精简 | p2-accessibility.png 确认应用列表（Chrome/Gmail/Maps等）+Switch+按钮已隐藏 |
| DesktopBridgeScreen | 配对码改 ProtoModalSheet 底部弹窗（标题"核对电脑邀请"+指纹卡+蓝色配对按钮+取消），新码自动弹出 | p2-bridge6.png 确认手机→电脑图标行+蓝色"开启桌面桥"按钮+USB分区+安全说明卡；p2-pair-modal.png 确认"等待电脑配对"状态切换 |
| LanPairingScreen | 全面对齐原型设计语言，自绘邀请码输入框，补扫码按钮（"扫码能力规划中"诚实反馈），保留全部真实逻辑 | 编译通过 |
| RecordingEditorScreen | 整体按原型重构：步骤时间轴rail+点步骤弹ProtoModalSheet「编辑步骤」+技能名称弹窗+AI语言归纳（仅建议用户确认后应用），保留选择器/坐标/等待/重试/失败策略/secret/revision冲突/dirty保护 | 编译通过 |
| AssistantScreen | "选择应用"pill改弹ProtoModalSheet「这次使用哪些应用」，候选来自真实targetPackages，多选草稿确认后回显 | p2-app-picker.png 确认弹窗正常 |

### 构建环境

- JDK21：`$env:JAVA_HOME="E:\osdk-data\data\installs\java\21.0.12.1+1"`
- 全量：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` BUILD SUCCESSFUL
- lint 抑制：QUERY_ALL_PACKAGES（AccessibilityScreen 查询全部已安装应用所需）
- 设备：emulator-5554（ui-visual-35，1080x2400/420dpi/zh-CN）

### Orange 5.0 模型说明

create_agent schema 无 model 参数，无法显式指定 Orange 5.0，本轮子代理使用默认模型。

## 本轮 P0 修复（2026-09-18，commit 9d79034 + a9cd977）

### P0-A ProtoModalSheet 改 Dialog 承载（commit 9d79034）

根因：ProtoModalSheet 内联 AnimatedVisibility+Box(fillMaxSize) 挂在 ScreenScaffold 的
垂直 Column 中，弹窗被当作第二个流式子项无法全屏覆盖（助理页正常因根是 Box）。

修复：改用 androidx.compose.ui.window.Dialog 承载，DialogProperties
(usePlatformDefaultWidth=false, decorFitsSystemWindows=false) 使其成为独立浮层窗口，
任何页面挂载都能全屏遮罩。visible=false 时先播退出动画(280ms)再 dismiss。

设备验证（verify-round8/）：
- r8-bridge-modal.png：开启桌面桥后立即弹出配对码弹窗（scrim遮罩+把手+标题"核对电脑邀请"
  +6位码490672+配对/取消按钮）
- r8-bridge-closed.png：关闭弹窗后出现"查看配对码"入口

### P0-B 录制新流程页视觉重构（commit a9cd977）

RecordingSessionScreen.kt 重写为原型 #record 设计语言：
- 大标题"照常操作，剩下的交给我。"+描述（替换原 Material 默认标题）
- 蓝色 NoticeBar（#EEF4FF/#2563EB）替换原紫色状态卡，诚实标注"语义录制已可用，自动意图归纳为规划中"
- 脚本名 label 外置 + 自绘 BasicTextField（白底圆角 50dp，无 Material 聚焦蓝框）
- 目标应用改为"只记录这些应用"卡片 + 右侧"更改>"链接（替换原多行包名输入框）
- 底部安全说明中性文字（不记录密码/验证码/支付，未知操作标待补充）
- 开始录制用自绘 ProtoPrimaryButton（48dp 高 14dp 圆角），未满足时灰色禁用态
- 步骤预览卡片化

设备验证：r8-record.png 确认大标题+蓝色NoticeBar+label外置输入框+应用卡+安全说明+禁用态按钮

### 验证结果
- :app:testDebugUnitTest + :app:lintDebug + :app:assembleDebug BUILD SUCCESSFUL
- make comments 通过（543 文件）
- APK SHA256: 716DBA69BBDCBCEA6929A0C36A21167E79C16B13A2D605F5DCC69324B74AE3A2

### 未验证边界
- 录制全流程（填名称→选应用→开始录制→捕获步骤→编辑→保存）未在设备走通（需真实无障碍授权）
- 我的页间距逐元素复核（P1-C）未完成
- 弹窗动画连续截图（P1-D）未完成

## P0 修复：录制页应用多选弹窗（2026-09-18，commit 19c2f2c）

### 问题
录制页"更改"按钮是死按钮（onChange(packages) 原样写回），用户既不能手输包名也不能弹窗选择，目标应用完全无法设置。

### 修复
- 录制页加载已安装可启动应用列表（produceState 后台查询，复用无障碍页同款 ACTION_MAIN+CATEGORY_LAUNCHER 逻辑）
- 点更改弹出 ProtoModalSheet 应用多选（真实图标+应用名+包名+勾选指示器）
- 确认后把所选包名 join 为字符串回写 ViewModel
- 已选应用区展示真实图标+应用名列表（不再是裸包名字符串）

### 设备验证（verify-round9/）
- r9-picker-open.png：弹窗正常（Chrome/Gmail/Maps/时钟等真实应用+真实图标）
- r9-selected.png：勾选时钟确认后"已选 1 个应用"+时钟图标+应用名正确显示

### 验证结果
- :app:testDebugUnitTest + :app:lintDebug + :app:assembleDebug BUILD SUCCESSFUL
- make comments 通过（543 文件）
- APK SHA256: 62C6478DB689DA0D25E62C6571BA13D60D1CCD45651D4345A7C144FDE6C996BF

### 未验证边界
- 录制全流程（填名称→开始录制→捕获步骤→编辑→保存）未在设备走通（需真实无障碍事件捕获）
- P1-C 我的页间距逐元素复核未完成
- P1-D 弹窗动画连续截图未完成

## P0 修复：录制中NoticeBar颜色+诚实文案（2026-09-18，commit 56f9d23）

### 问题
- 录制中NoticeBar为红色危险色调（#FDE8E8/#B63E49），录制是正常进行态不应红色
- NoticeBar宣称"语义录制已可用"，但实际捕获步骤为裸动作(ui.click/ui.wait)，无控件语义描述，名不副实

### 修复
- 录制中状态NoticeBar改为蓝色info（#EEF4FF/#2563EB），与录制前同色系
- 文案改为"动作录制可用，语义识别与意图归纳优化中"，诚实反映当前能力

### 设备验证
- r9-notice.png：蓝色底+蓝字+"动作录制可用，语义识别与意图归纳优化中"

### 验证结果
- testDebugUnitTest + lintDebug + assembleDebug BUILD SUCCESSFUL
- make comments 通过（543文件）
- APK SHA256: 9BBC82C89C29A1C3C6932B05C52A906CC5E65BD4BCA607179AAB1423F3180E13

### 未验证/未完成边界
- 语义录制提取控件语义（点击"闹钟"而非裸ui.click）需改录制引擎架构，本轮未做
- 纯等待噪声抑制/合并相邻wait需改录制引擎，本轮未做
- P1-C 我的页间距逐元素复核未完成
- P1-D 弹窗动画连续截图未完成

## StepHumanizer步骤可读化（2026-09-18）

### 修复
- 新建 automation/recording/StepHumanizer.kt（纯Kotlin可单测）：
  从selectorCandidates按contentDescription>text>resourceId提取可读目标名，
  ui.click→"点击 闹钟"、ui.scroll→"向下滑动"、ui.wait→"等待 应用 打开"
- RecordingStepCard主标题用humanize()+灰色techBadge小字
- StepRail(RecordingEditorScreen)主标题用humanize()
- 录制中NoticeBar蓝色(#EEF4FF/#2563EB)，文案改回"语义录制可用，自动意图归纳规划中"

### 验证结果
- testDebugUnitTest + lintDebug + assembleDebug BUILD SUCCESSFUL
- make comments通过（544文件）
- APK SHA256: 6F7F263439BEC26B81B6F77F99FE5057F6D607DAEBDA4BBEEBE951F0FAC4A8D3

### 未验证边界
- 录制全流程设备走通（切到时钟应用点tab→捕获步骤→可读名显示）未在本轮设备走通
- wait噪声去重（WINDOW_CHANGED映射）未做
- StepHumanizer单元测试未补
- P1-C我的页间距/P1-D弹窗动画未完成

## StepHumanizer 7个缺陷修复（2026-09-18）

### 修复
1. wait从params[expected]读取包名（原误读target.packageName）→"等待 时钟 打开"
2. scroll direction从params[direction]读取→"向上/下/左/右滑动"
3. techBadge优先显示contentDescription/text（原恒显resourceId）
4. RecordingEventMapper.role()无语义类名返回空（消除FrameLayout噪声）
5. 补StepHumanizerTest 11个测试全部通过
6. humanize支持packageLabel映射参数

### 验证结果
- StepHumanizerTest 11/11通过
- testDebugUnitTest + lintDebug + assembleDebug BUILD SUCCESSFUL
- make comments通过（545文件）
- APK SHA256: 0BF88A33B22B032D17A78E91EA2F03E131E994B479FAD60E5DAAA70124710B60

### 未完成边界
- 应用选择弹窗确认按钮固定底部未改
- wait噪声去重未做
- P1-C我的页间距/P1-D弹窗动画未完成

## A+B 弹窗按钮固定+wait去重（2026-09-18）

### A. 应用选择弹窗确认按钮固定底部
- AppPickerList改LazyColumn weight(1f)+heightIn(max=400dp)
- 确认按钮固定弹窗底部，30+应用无需滑到底

### B. wait噪声去重
- RecordingStateMachine.append()加wait去重：
  - 相邻同类ui.wait合并（保留前一条）
  - 录制开始首个窗口变化不记

### 验证结果
- testDebugUnitTest + lintDebug + assembleDebug BUILD SUCCESSFUL
- make comments通过（545文件）
- APK SHA256: A53FE2A1B86012B4EFF1BF9A44E3FB65E4CA68E625407BCC460066E960177486

### 未完成边界
- C. 我的页间距逐元素复核未做
- D. 弹窗动画连续截图未做
- packageLabel三处UI传入未验证
