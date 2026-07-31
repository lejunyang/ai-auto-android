# N46 现代 APK Badging 进度

本文件 append-only，记录真实输出、测试、实现、制品验证和集成证据。

## Round 1

- 官方 B站 APK 的 package、ABI 和单签名均可由固定工具读取，但现代 `aapt2`
  输出 `minSdkVersion:'24'`；现有 parser 只接受旧 `sdkVersion:'N'`，因此错误返回
  `APK_INSPECTOR_OUTPUT_INVALID`。
- 修复只扩展严格字段名兼容，不增加 manifest 猜测或 xmltree fallback，也不改变
  工具路径、argv、identity 复核和签名歧义拒绝。

## Round 2

- parser 现在要求 `sdkVersion` 或 `minSdkVersion` 恰好出现一个；双字段、重复、缺失、
  非正整数和超过 100 继续失败关闭。现代、旧式和歧义测试均通过。
- 微信 `aapt2` stdout 实测仅 9,470 字节，但合法资源 warning stderr 为 489,718
  字节，超过旧 256 KiB 总预算。统一固定工具输出预算调整为 1 MiB，并增加约
  500 KiB warning 可接受、超过 1 MiB 仍返回 `APK_INSPECTOR_OUTPUT_LIMIT` 的回归；
  非零退出、timeout 和工具替换规则不变。
- N46 全量零依赖测试 42/42、仓库/历史 APK 路径扫描、comments 和 diff-check
  通过。
- B站 `9.5.0`、小黑盒 `1.3.392`、微信 `8.0.76` 的官方 arm64 APK 已下载到仓库外
  SHA-256 cache，并由正式 verifier 二次确认 package、version/versionCode、ABI、
  minSdk、size、SHA-256 与单签名证书摘要。仓库只新增严格文本 manifest，不包含
  APK。
- 抖音 `39.8.0` 从当前官网 `douyinstatic` 主脚本 Android 下载面板唯一解析
  `ugapk.com` 64 位短链；支付宝 `12.12.10.8000` 从当前 `d.alipay.com` 官方
  unified landing 脚本 x64 分支解析 `tfs.alipayobjects.com` APK。两包同样归档到
  外置 SHA-256 cache 并经正式 verifier 二次通过。
- 五个 artifact 均为 `0600`，staging 为空；完整 APK 未进入工作区或 Git 历史。
  当前完成的是 N46 verified descriptor 准备，不等于安装、第三方 App 场景或 N51
  小程序探索已验收。

## Round 3

- 单一职责提交 `cba821f` 已以等价集成提交 `ab0e901` 落入 `main`。集成提交的
  Author 与 Committer 均为 `lejunyang <lejunyang@qq.com>`，提交末尾恰好一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`。
- 五包三 API 的安装验收由独立 N31 install runner change 承担；本 change 的提交、
  身份、trailer、集成和干净 worktree 状态已闭合。
