# N46 Android APK 离线 Inspector 规格

## 目标

在既有 N46 仓库外 cache verifier 与安装生命周期之前，提供真实调用 Android
`aapt2` 和 `apksigner.jar` 的离线 inspector。调用方必须显式信任一个具体
`build-tools/<version>` 目录和固定 Java，或同时提供三个固定工具绝对路径；本任务不下载、
安装、生成、复制或提交真实 APK。

## 工具信任边界

- 配置只允许 `repositoryRoot`、`javaPath` 加具体 `buildToolsDirectory`，或
  `repositoryRoot` 加固定 `aapt2Path`、`apksignerJarPath`、`javaPath` 两种互斥形式。
- 工具路径必须绝对、位于仓库外、名称固定、为非 symlink 普通可执行文件；具体
  build-tools 目录同样不能是 symlink。
- 不接受 executable、command、shell、argv、额外参数、环境脚本或 manifest
  提供的工具配置。
- 每次调用前后核对工具 identity，检测到 inode、大小、时间或权限变化时失败关闭。
- 不执行 `apksigner` shell wrapper；仅通过 `execFile` 执行固定参数：
  `aapt2 dump badging <artifact>` 与
  `java -Xmx256M -jar <fixed-apksigner.jar> verify --print-certs <artifact>`，
  明确设置 `shell: false`。

## 输出与解析

- 每个进程使用固定超时和输出上限；超时、超预算、工具失败、工具变化和解析失败
  映射为稳定错误码，不把工具输出、路径或底层错误文本放入公开错误。
- `aapt2 dump badging` 必须恰好提供一个 package、version、versionCode、minSdk
  和 native-code 集合；只接受 N46 manifest 允许的 ABI。
- `apksigner verify --print-certs` 必须恰好提供 Signer #1 的一个 SHA-256 证书摘要；
  缺失、重复、格式错误或多个 signer 均失败关闭。
- 成功结果只包含 package、version、versionCode、ABI、minSdk 和
  signingCertificateSha256，并交给既有 verifier 与 manifest 严格比较。

## 允许修改

- `.trae/specs/n46-android-apk-inspector/`
- `scripts/test-lab/` 的 inspector 实现与对应测试
- `test-lab/apps/README.md`

## 禁止事项

- 不修改 Android、Gradle、workflow、共享路线图、N41 或 N45。
- 不下载、安装、生成、复制或提交真实 APK，不发起网络或设备操作。
- 不开放任意 executable、argv 或 shell，不接受仓库内 executable 或 symlink。
- 不把 fake executable、注入 `execFile` 或非 APK fixture 描述为真实 APK 验收。

## 验收

- 先记录实现模块缺失的 RED 测试，再完成最小实现。
- fake `execFile` 证明两组 argv 固定，artifact 中 shell 元字符不能改变参数边界。
- 覆盖 timeout、output budget、工具失败、工具替换、symlink、仓库内 executable、
  未知/重复/缺失元数据和多签名歧义。
- 既有 verifier 在 descriptor 产生和安装调用之前拒绝 metadata mismatch。
- `npm run smoke --prefix scripts/test-lab`、`make comments`、`git diff --check` 和
  修改范围审计通过。
- 真实 APK、真实 Android 工具输出和 API 30/33/34 安装仍明确保留为未验收项。
