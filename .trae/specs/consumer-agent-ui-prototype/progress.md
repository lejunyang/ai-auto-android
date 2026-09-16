# UI 原型验证记录

## 范围

本次只交付 C 端产品概念与 HTML 交互原型，不替换 Android UI，不改变运行时和历史 MVP 验收。

- P1 已完成：读取现有 App 导航、首页、Provider、授权、会话、录制编辑源码和最新 N38–N45 记录。
- P2 已完成：`docs/ui-prototype/index.html` 为单文件离线原型，CSS/JS/图标全部内联，不依赖网络或旁边的验证脚本。
- P3 已完成：实际渲染、控件状态回归、注释与仓库验证。
- P4 已完成：文档导航增加原型入口；未来 Android 实施以 N56 未开始条目记录，没有修改既有任务状态。

## 实际验证

- 浏览器页面公开控件回归：1440×900、390×844、320×760 三种视口，各 46 个断言通过。
- 覆盖：空输入、任务计划/暂停/停止/失败/确认/结果、记录筛选、录制暂停/事件捕获、步骤修改/排序/启停/复制/删除、撤销/重做、保存重开、脏数据保护、意图保存/丢弃、变量校验、悬浮收起/展开、处理与待确认边缘光、输入转义、表单校验与人工授权边界。
- 回读首页桌面/移动、编辑器桌面/移动、悬浮移动及处理态截图。各对应报告未发现脚本错误、横向溢出、容器裁切或过小点击区域。
- 检查器的按钮启发式不识别 document 事件委托；通过真实 DOM 控件点击与状态断言验证，不将其当成僵尸按钮。文本 bounding-box 检查会计入滚动容器之外的不可见文本和被悬浮层覆盖的宿主文章；实际截图确认滚动裁切与覆盖符合设计，不宣称原始 lint 报告零告警。
- HTML 文件格式校验通过；单文件可通过 `file://` 打开。
- `make comments`：514 个手写文件、16 个正反例通过（新增 HTML 覆盖与两项反例）。
- `make test`、`make verify`、`make build` 均退出 0；最终文件更新后再次执行 `make comments` / `make verify` / `git diff --check` 通过。
- `make android-verify` 退出 0：`testDebugUnitTest assembleDebug lintDebug`，Gradle 共 152 tasks，23 executed、129 up-to-date。这里包含既有缓存命中，不冒充新设备验收。

## 产物与证据

- 用户入口：`docs/ui-prototype/index.html`，用 Chrome / Edge 双击打开。
- 交互回归定义：`docs/ui-prototype/verify-interactions.js`，用于浏览器页面求值检查，不是生产脚本，HTML 不依赖它。
- 本机验证证据：忽略目录 `artifacts/ui-prototype/` 内的 `preview-report.json`、`narrow-report.json`、`editor-report.json`、`overlay-report.json`、`processing-report.json` 与 `make-*.log` / `android-verify.log`。
- 未访问真机/模拟器，未请求麦克风/相机/悬浮权限，未调用模型，未产生外部账号或网络业务写入。
- 未提交或 push；既有工作区起始干净，只改本 change 允许的原型、文档和注释门禁范围。

## 已知边界

原型仅内存暂存，刷新恢复示例；语音是模拟波形，任务和结果是示例，回放是用户体验演示而非真实脚本执行。自然语言变化不自动覆盖步骤，预检不等于设备可执行。系统级悬浮、后台队列、意图回放、触发器与生命周期仍需独立 Android 实现和设备测试。
