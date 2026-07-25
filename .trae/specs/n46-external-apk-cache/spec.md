# N46 第三方 APK Manifest 与仓库外缓存规格

## 目标

为真实 App 兼容性测试提供严格、离线且可审计的制品验证入口。仓库只保存文本
manifest、Schema、验证脚本和测试；APK 必须由用户或许可来源在仓库外准备，本任务
不下载、不安装、不提交也不重新分发 APK。

## Manifest 契约

- manifest 固定记录 package、version、versionCode、ABI、minSdk、来源、许可备注、
  文件大小、APK SHA-256 和签名证书 SHA-256。
- 来源只能是无 userinfo、query、fragment 的 HTTPS 来源说明或明确的
  `user-provided` 说明；manifest 不包含下载、商店抓取、认证参数或任意命令。
- manifest 及嵌套 source 对象拒绝未知字段；摘要使用小写 64 位十六进制。

## 仓库外缓存与验证

- cache 根必须由调用方显式传入绝对路径；词法路径和真实路径均不得位于 Git 仓库内。
- cache 按 `sha256/<前两位>/<完整摘要>/artifact` 查找，不依赖 APK 文件名或扩展名。
- cache 根、摘要目录和 artifact 的符号链接、路径逃逸、非普通文件及打开前后 inode
  变化全部失败关闭。
- 在任何安装入口之前校验文件 identity、大小与 SHA-256，再通过调用方注入的类型化
  inspector 校验 package、version、versionCode、ABI、minSdk 和签名摘要。
- inspector 只能接收固定字段并返回严格元数据；manifest、cache 或 inspector 结果
  均不能提供 shell、可执行文件或任意 argv。
- 成功只返回不可变的 `verified-external-app` descriptor，不执行安装或网络访问。

## Runner 生命周期边界

- 默认缺少 runner 时只返回 verified descriptor，安装调用次数必须为零。
- 后续 runner 必须实现 restore-clean、install-verified、inspect-installed、
  clear-app-data 和 restore-final 五个类型化端口，并显式绑定 serial 与 snapshot。
- 固定 argv provider 只生成允许的 ADB install 参数，不能接收额外命令或 shell 字符串。
- clean snapshot 前置恢复失败时不得安装；已进入生命周期后，无论场景结果如何都要
  清理 App 数据并恢复 snapshot，任一步失败均失败关闭。
- 本任务不接入真实设备；真实安装与 clean AVD 证据留给使用 N46 descriptor 的后续
  runner 任务。

## 允许修改

- `.trae/specs/n46-external-apk-cache/`
- `test-lab/apps/` 下文本 manifest、Schema 和 README
- `scripts/test-lab/` 下零依赖 Node ESM 实现与测试
- 必要时新增 N46 专用 workflow

## 禁止事项

- 不修改 Android 生产代码、Gradle、共享 workflow、路线图或 N44 目录。
- 不下载、生成、复制、安装或提交 APK，不访问应用商店，不保存账号或下载凭据。
- 不开放任意 shell、任意 executable、任意 argv 或隐式设备选择。
- 不把 fake inspector、接口测试或无设备验证描述为真实 APK 安装证据。

## 验收

- 先观察实现缺失导致测试失败，再实现最小功能。
- 错误 hash、size、ABI、version、versionCode、package、minSdk、签名或来源均在
  runner 安装调用前失败。
- 仓库内 cache、相对 cache、路径穿越和符号链接逃逸均被拒绝。
- 默认路径不安装；fake runner 验证 clean 前置和最终清理失败关闭。
- `.gitignore` 覆盖 APK，工作区和全部 Git 历史的 APK 扩展名扫描为零。
- 专用测试、`make comments`、`git diff --check` 和范围审计通过。
