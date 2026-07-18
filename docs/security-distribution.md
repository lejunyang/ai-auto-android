# 安全与分发

本文是工程安全基线，不是法律意见，也不构成 Google Play 审核保证。政策结论按
2026-07-18 可访问的官方文档整理；发布前必须重新核对最新全文。

## 安全模型

主要风险包括：控制错误设备、ADB 或 Bridge 被未授权主机使用、模型生成越权
动作、UI/截图泄露敏感数据、录制保存 secret、供应链 APK 被替换，以及把完整
自主无障碍版本误当作 Google Play 合规版本。

### 已实现控制

| 边界 | 当前控制 |
| --- | --- |
| 设备选择 | 每次设备操作显式携带 serial；多设备不猜测目标 |
| ADB | 依赖设备端 RSA 授权；只调用固定 argv，不暴露任意 shell |
| 无障碍 | App 内独立醒目披露、目标包白名单、前台包复核 |
| Bridge 网络 | 默认关闭，只绑定设备 `127.0.0.1:38383`，通过指定设备的 ADB forward 访问 |
| Bridge 会话 | 120 秒一次性码、900 秒随机 token、300 秒 requestId 防重放 |
| Bridge 资源 | 1 MiB 消息、4 个并发请求、8 个连接、请求 deadline |
| Provider 输出 | 严格 JSON、动作白名单、未知字段拒绝、一次只执行一个动作 |
| 风险动作 | 支付、安装、授权、系统设置等阻断；发送、提交、删除等要求当次确认 |
| API Key | Android Keystore 中不可导出的 AES-256/GCM 密钥加密后写入 SharedPreferences |
| UI 数据 | 密码和敏感节点清除文本/内容描述；Provider 只接收有界语义摘要 |
| 本地数据 | App 关闭备份和设备迁移提取；截图文件按当前用户读写权限创建 |

App 清单设置 `android:usesCleartextTraffic="false"`，无障碍元数据明确为
`isAccessibilityTool="false"`。后者符合产品不是残障辅助工具的真实定位，不能
为了规避审核而改为 `true`。

### 仍需按敏感数据处理

- Bridge token 以明文保存在当前用户配置目录；macOS/Unix 使用 `0600`，
  Windows 当前仅依赖用户目录 ACL，不使用系统凭据库。
- 录制脚本未单独加密，只位于 App 私有目录；secret 引用不含明文，但普通输入、
  包名、选择器和界面文本仍可能敏感。
- App 回放 secret 在当前详情页和本次调用内存中存在；电脑端不支持传入 secret。
- UIAutomator XML 没有 App 端敏感节点脱敏；截图可能包含通知、键盘建议和浮层。
- Provider 是用户配置的外部数据处理方。语义摘要会发送到其 Base URL，使用者
  必须评估其隐私政策、数据驻留、日志和保留策略。
- AI 会话 audit 只是在内存状态中保留最近 100 条阶段消息，不是持久化合规审计。
- 本项目不绕过 `FLAG_SECURE`，保护页面为空或不可访问时应失败。

## 操作禁区

不论分发渠道，默认禁止自动执行：

- 支付、购买、转账、银行、钱包和凭据恢复；
- 输入、记录或转发密码、验证码、配对码、token；
- 安装、卸载、授予权限、开启无障碍、设备管理或系统安全设置；
- Root、remount、关闭校验、恢复出厂、修改 ADB 信任；
- 选择器歧义时猜测点击，或绕过 Android 隐私/安全控制；
- 任意 shell 和未列入类型化接口的 ADB 命令。

自动化成功必须由动作后的新观察验证。超时可能表示结果未知，涉及外部副作用时
不得盲目重试。

## Google Play 2026 政策禁区

[Google Play AccessibilityService API 政策](https://support.google.com/googleplay/android-developer/answer/10964491)
当前明确禁止使用 Accessibility API 让 App **自主发起、规划并执行动作或决策**。
政策说明允许窄目的、静态人类定义脚本的确定性规则自动化，但这不构成对具体 App
的预先批准。

Play 的
[敏感权限与 API 政策](https://support.google.com/googleplay/android-developer/answer/16558241)
还列出以下禁区：

- 未经用户许可改变设置，或阻止用户禁用/卸载应用；
- 绕过 Android 内建安全、隐私控制和通知；
- 以欺骗方式改变或利用 UI；
- 将 Accessibility API 用于远程通话录音；
- 不是真实残障辅助工具却声明 `isAccessibilityTool=true`。

非 Accessibility Tool 还必须：

- 在正常使用路径、请求权限之前提供独立的 App 内醒目披露；
- 说明读取/收集的数据以及用途和分享方式；
- 通过明确肯定操作取得同意，返回或离开页面不能算同意；
- 完成 Play Console Accessibility 声明并提供演示视频；
- 维护隐私政策和 Data safety 声明。

**对当前仓库的结论**：App 内“观察 -> AI 规划 -> 本地执行 -> 验证”闭环属于上述
自主能力。仓库当前只有一个 application variant，没有移除自主闭环的 Play
变体，因此完整 APK 不应被描述为可公开上架 Google Play。

一个可能提交审核的 Play 变体至少需要在构建产物中移除自主规划与连续执行，只
保留建议、用户逐步选择和每步明确确认；还需单独完成披露、声明和数据安全工作。
该变体当前未实现，不能从文档推定其合规。

## 分发渠道

| 渠道 | 适用性 | 要求与边界 |
| --- | --- | --- |
| Google Play 公开版 | 当前完整版不适用 | 必须先实现并验证裁剪变体；审核结果不可保证 |
| 签名 APK 受控侧载 | 适合开源、高级用户和内部测试 | 发布签名、SHA-256、可信 HTTPS 来源、用户主动安装、升级保持同一签名 |
| ADB 开发安装 | 仅开发和测试 | 官方 2026 FAQ 说明 ADB 安装不要求开发者验证；本项目 `aactl` 不提供安装命令 |
| Managed Google Play 私有 App / EMM | 适合受管企业设备 | 组织管理员审批、限定可见范围、托管更新和撤销；不是所有 EMM 都支持同一发布方式 |
| 企业自有制品库/MDM | 适合受控终端 | 组织签名、设备合规、访问控制、审计、回滚和应急撤销 |

[Managed Google Play 私有 App](https://support.google.com/googleplay/work/answer/9146439)
可通过支持的 EMM 发布 AAB 或 APK，通常只对指定组织可见。私有发布和企业管理员
审批不等于自动豁免所有 Play 条款、隐私法律或组织安全要求。

本 App 不是 Device Policy Controller，没有静默安装、托管配置或设备所有者
能力。企业分发只能管理当前普通 App，不能把未实现的 DPC 能力写入方案。

## 侧载与 2026 开发者验证

Android 官方
[备选分发指南](https://developer.android.com/distribute/marketing-tools/alternative-distribution)
说明 Android 8.0 及以上由用户按安装来源单独允许“安装未知应用”。不要引导用户
全局降低安全设置，也不要自动点击安装或授权界面。

[Android 开发者验证 FAQ](https://developer.android.com/developer-verification/guides/faq)
截至本文日期说明：

- 2026-09-30 的首阶段执行仅针对指定地区和参与商店，直接侧载暂不受该阶段影响；
- 计划在 2027 年扩大全球执行，建议非 Play 分发也提前验证身份并注册包名；
- ADB 开发安装不受验证和高级流程等待期影响；
- 组织商店向受管设备分发的企业 App 不要求该验证，但从其他来源安装时仍建议注册；
- 未验证开发者的高级侧载流程计划包含开发者模式、风险确认、重启认证和等待期。

这些规则处于分阶段上线状态，不能将“当前可侧载”解释为长期不需要注册。

## 当前制品状态

本地 Android 定向验证和构建：

```bash
make android-test
make android-lint
make android-build
```

输出为：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

当前发布脚本可打包 macOS arm64/amd64、Windows amd64、Linux amd64 CLI、
debug APK 和 `SHA256SUMS`：

```bash
make release VERSION=0.1.0
```

输出目录默认为 `dist/`。Release workflow 可在 `v*` tag 或手动触发时验证
校验和并发布 GitHub Release。

这仍是工程测试制品流水线：APK 使用调试密钥，不能作为生产发布制品。当前
`release` build type 没有发布签名配置，流水线也不产出发布签名 APK。不要因为
文件进入 GitHub Release 就把 debug APK 描述为适合公开侧载或企业生产部署。

## 发布制品最低要求

生产分发前必须完成：

1. 在受控密钥管理系统中保管发布私钥；不提交 keystore、密码或签名配置。
2. 使用稳定 application ID 和同一签名证书发布后续升级。
3. 对 APK 验证签名并记录证书摘要：

   ```bash
   apksigner verify --verbose --print-certs release.apk
   ```

4. 分别生成并发布 SHA-256：

   macOS：

   ```bash
   shasum -a 256 release.apk
   ```

   Windows PowerShell：

   ```powershell
   Get-FileHash -Algorithm SHA256 .\release.apk
   ```

5. 在与 APK 不同的可信通道公布预期 SHA-256 和签名证书摘要。
6. 从干净环境复验 APK、版本号、包名、最低 API、权限、网络安全配置和
   `isAccessibilityTool=false`。
7. 对每个渠道记录可见范围、升级、回滚、撤销、隐私联系人和事件响应负责人。

`apksigner` 的签名必须在最终 APK 修改完成后验证；签名后改动 APK 会使签名失效。
参见官方 [apksigner 文档](https://developer.android.com/tools/apksigner)。

## Secret 与日志清单

不得进入 Git、命令参数、聊天、发布日志或工单：

- Provider API Key、发布私钥和 keystore 密码；
- ADB 无线配对码、Bridge 一次性码和 session token；
- 密码、验证码、支付数据和回放 secret；
- 原始截图、完整 UIAutomator XML 和未脱敏语义树；
- 可识别个人的设备 serial、Wi-Fi endpoint 和消息内容。

优先让 `aactl devices pair` 和 `aactl bridge open` 从 stdin 读取码。虽然
`bridge open --code` 语法存在，但会把码放入进程参数和 shell 历史，不应用于
正常流程。

## 参考

- [Google Play AccessibilityService API 政策](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Google Play User Data 政策](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Android 开发者验证](https://developer.android.com/developer-verification)
- [Managed Google Play 私有 App](https://support.google.com/googleplay/work/answer/9146439)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)
