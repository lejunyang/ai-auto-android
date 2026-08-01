# 小黑盒安全兼容场景契约

本目录提供 N50 当前可自动交付的离线场景 Schema、报告 Schema、validator、fake
fixture 和测试。它不操作设备、不运行 N47 Runner、不读取 APK，也不代表小黑盒已完成
真实兼容验收。

## 固定绑定

场景和每轮报告必须完整绑定：

- manifest：`xiaoheihe-1.3.392-arm64.json`
- package：`com.max.xiaoheihe`
- version/versionCode：`1.3.392` / `1114`
- ABI：`arm64-v8a`
- 固定 APK 与签名证书 SHA-256
- 总计 10 轮，iteration 严格为 1 至 10；每轮 API 只允许 30、33、34

Schema 不接受调用方传入其他 package、APK 身份、API、轮数或 iteration。fake fixture
可验证契约，但 `realRuns`、`realCompatibleRuns`、`realSuccessRate` 和
`realMatrixComplete` 均不会把 fixture 当作真实证据。

## 安全场景

固定步骤覆盖公开搜索、公开详情、无副作用长按、列表滚动/swipe、Back、Home、
Recents、切到固定 fake peer 和跨 App 返回。步骤只保存固定动作标签，不保存搜索内容、
selector、坐标、命令、deep link 或任意执行参数。

- 内容 route 仅允许 `native-accessibility` 和 `webview-accessibility`，并绑定最新
  observation 的 `native`/`webview` surface。
- Home、Recents 和跨 App 步骤使用独立 `system-navigation` route。
- 不存在 hybrid、DOM 或 JavaScript route；场景固定 `domInjection=false` 和
  `javascriptInjection=false`。
- 全部已提交动作必须重新观察；公开搜索、详情、Back/Home/Recents/跨 App 返回均由
  固定步骤显式要求 post observation。
- 长按只允许 `long-press-safe-content`。登录、关注、收藏、评论、发帖、下载和分享均
  不可表示。

## 失败关闭

- 任一 observation 命中 consent/permission 时整轮零提交且不兼容，三 API 规则一致。
- dynamic content、advertisement、network 使用三组独立枚举、reason code 和统计。
- 前台/surface 未知、广告存在或未知、网络失败或未知、动态内容未知均不兼容。
- `actionCommits=null` 仅表示提交状态未知，必须首次尝试后立即停止且禁止重试。
- 每轮都必须清搜索历史、App 数据、Bridge、session、artifacts 并恢复 snapshot。
- validator 从内容重算逐轮 decision、API/route/action/classification 统计和兼容率；
  报告自带的陈旧值会被拒绝。

## 数据最小化

observation 只保存 ID、前台类别、surface 和固定分类枚举。严格 Schema 拒绝 unknown
keys、路径、URL、secret、token、cookie、原始截图/hierarchy、OCR、DOM、JavaScript、
自由文本异常、任意动作与坐标。

## 离线验证

```sh
cd test-lab/scenarios/xiaoheihe
npm run smoke
```

该命令只读取本目录 JSON 和代码，并复用 N47 的纯离线 Schema validator。不会发现或
选择设备、启动 App、连接 Bridge、下载 APK、访问网络或修改真实搜索历史。
