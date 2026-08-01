# N50 小黑盒安全兼容场景契约规格

## 目标

为固定版本小黑盒建立离线、失败关闭、机器可复核的场景与兼容报告契约。契约绑定
`xiaoheihe-1.3.392-arm64.json`，固定总计 10 轮并支持 API 30、33、34，从每轮观察、
步骤结果、route、安全阻塞和清理内容重算兼容结论与全部统计。

本 change spec 只交付 Schema、validator、fake fixture、测试和说明，不运行设备、
N47 Runner、APK 或 Bridge，也不产生 N50 真实轮次证据。

## 固定身份与矩阵

- manifest 名、package、version、versionCode、ABI、APK SHA-256 和签名证书 SHA-256
  必须与仓库已有 `xiaoheihe-1.3.392-arm64.json` 完全一致。
- 报告固定恰好 10 轮，iteration 必须按顺序完整覆盖 1 至 10；每轮 API 只能是
  30、33 或 34，不能缺失、重复、扩展或由调用方改写为其他 API。
- fixture 报告必须显式声明 `fixture`，可验证契约和统计器，但
  `statistics.realRuns`、真实兼容数和真实兼容率均不得计入 fixture。
- 只有 10 个完整且身份一致的 `real` run 才能形成真实十轮矩阵；本 change spec 不提供
  这类设备证据。

## 场景与 route

- 固定步骤覆盖公开搜索输入与提交、公开详情、无副作用长按、列表滚动、列表 swipe、
  Back、Home、Recents 和跨 App 返回。
- 动作只以固定枚举标签存在，不包含 selector、坐标、输入内容、任意 package、命令、
  deep link 或可执行参数。
- App 内容 route 只能是 `native-accessibility` 或 `webview-accessibility`，并必须与
  最新 pre observation 的 `native`/`webview` surface 一致；不存在 hybrid、DOM 或
  JavaScript route。
- Home、Recents 和跨 App 返回使用独立 `system-navigation` route，不与 native 或
  WebView 兼容统计混合。
- 公开搜索、公开详情、Back、Home、Recents 和跨 App 返回必须有 post observation；
  契约对全部已提交步骤采用更强约束：提交后必须重新观察。

## 安全与兼容判定

1. 任一 observation 命中 consent 或 permission 时，该 run 的动作提交数必须为零，
   decision 必须不兼容；API 30、33、34 均适用，阻塞不能算兼容成功。
2. 长按只能使用 `long-press-safe-content`，全局禁止登录、关注、收藏、评论、发帖、
   下载和分享；报告不得携带这些操作的目标或参数。
3. dynamic content、advertisement 和 network 使用三个独立字段与独立统计，不允许用
   一个笼统页面分类互相覆盖。广告、网络失败/未知或动态内容未知均失败关闭。
4. `actionCommits=null` 表示提交状态未知；该步骤 attempts 必须为 1、retryCount 必须
   为 0，使用稳定错误码并立即停止后续步骤，禁止隐式重试。
5. compatible run 必须完整执行固定十一步，每步恰好一次提交、route 与 surface 匹配、
   post observation 存在，且所有清理项成功。
6. validator 不信任报告自带 decision 或 statistics；必须从 run 内容重算 reason
   codes、兼容数、真实/fixture 数、三 API 计数、route、动作覆盖和独立分类统计。

## 数据最小化与清理

- observation 只允许有界 ID、前台类别、surface 与固定分类，不保存截图、hierarchy、
  OCR、DOM、页面文本、异常、URL、路径或原始内容。
- Schema 和 validator 拒绝 unknown keys、任意 package/APK/action、坐标、selector、
  secret、token、cookie、路径、URL、raw content、DOM 和 JavaScript 字段。
- 每轮无论兼容、阻塞或失败，都必须清除搜索历史、App 数据、Bridge、session、
  artifacts，并恢复 snapshot；任一项失败均不得兼容。

## 允许修改

- `.trae/specs/n50-xiaoheihe-scenario-contracts/`
- `test-lab/scenarios/xiaoheihe/`

## 禁止修改

- N47 共享 Runner、Schema、聚合器和 production adapter
- `docs/next-phase-tasks.md`、manifest、APK/cache、其他任务规格
- Android、Go、设备、模拟器、ADB、Bridge 或任何真实 App 状态

## 验收

- 先运行缺失 validator 的 Node 测试取得 RED，再实现严格 Schema、validator 和 fixture。
- 专用 schema-check 递归确认所有 object 都拒绝未知字段，并覆盖固定身份、任意动作、
  坐标、secret、路径和 raw content 反例。
- 定向 Node/schema 测试覆盖固定 10 轮、三 API 阻塞、route 分离、post observation、
  长按副作用、独立分类、unknown commit、全清理、fixture 排除和统计重算。
- 运行 N47 离线 smoke 回归、`make comments` 与 `git diff --check`。
- 不操作设备、不提交 APK/cache、不 push、不勾选 N50。
