# N46 Android APK Inspector Progress

本文件 append-only，记录实现轮次、失败证据、验证结果与真实 APK 未验收项。

## Round 1

- 已确认独立 worktree `/private/tmp/ai-auto-n46-inspector` 位于分支
  `phase2-n46-apk-inspector`，基线为 `e3d62ee`，启动时无本地改动。
- Git 仓库身份为 `lejunyang <lejunyang@qq.com>`；本任务修改范围与 N41 独立
  worktree 互斥，不修改 Android、Gradle、workflow、共享路线图、N41 或 N45。
- 已审计既有 N46 verifier：inspector 只接收 artifactPath、sizeBytes、sha256，
  返回的六项元数据会与 manifest 比较，成功后才签发进程内 provenance descriptor。
- 已先新增 `apk-inspector.test.mjs`。首次定向执行因
  `scripts/test-lab/src/apk-inspector.mjs` 不存在而以
  `ERR_MODULE_NOT_FOUND` 失败，确认 RED 后才开始生产实现。
- 测试 fixture 只创建无 `.apk` 扩展名的文本 artifact 和 fake executable；未下载、
  生成、安装、复制或提交真实 APK，未调用真实 Android 工具、ADB、设备或网络。

## Round 2

- 已实现 `createAndroidApkInspector`，配置严格限制为具体
  `build-tools/<version>` 绝对目录，或成对固定 `aapt2Path`、`apksignerPath`；
  unknown 配置、任意 executable、argv、混合模式和错误工具名均被拒绝。
- 工具及整条父路径必须位于仓库外、没有 symlink，且工具为普通可执行文件。创建时
  固定 canonical path、dev/inode、size、mode、mtime 与 ctime，每次工具执行前后
  重新核对；调用期间替换 `aapt2` 时停止后续 `apksigner`，返回稳定错误码。
- 安全审查发现 SDK `apksigner` 是会从 PATH 查找 `java` 的 shell wrapper；实现已
  改为固定并校验 `javaPath` 与 `lib/apksigner.jar` identity，完全不执行 wrapper。
  仅通过 `execFile` 执行 `aapt2 dump badging <artifact>` 和固定
  `java -Xmx256M -jar ... verify --print-certs <artifact>`，使用 `shell: false`、15 秒超时和
  256 KiB 输出上限。artifact 路径中的 shell 元字符保持为单个 argv，不接受额外
  参数；底层路径、stderr 和错误文本不会进入公开错误。
- badging 严格要求唯一 package、version、versionCode、minSdk 与非空已知 ABI；
  签名输出严格要求 Signer #1 的唯一 SHA-256 摘要。缺失、重复、未知 ABI、多 signer
  或格式歧义均返回 `APK_INSPECTOR_OUTPUT_INVALID`。
- 六项真实解析结果的 mismatch 均由既有 verifier 在 descriptor 签发和任何安装
  调用之前以对应稳定错误码拒绝。

## Round 3

- 定向测试增加 Java/jar 替换拒绝后为 8/8；完整
  `npm run smoke --prefix scripts/test-lab` 为 40/40，repository scan 的工作区与
  Git 历史 APK/APKS/AAB/XAPK 路径均为 0。comments、语法和 diff-check 通过。
  工作区和全部 Git 历史 APK/APKS/AAB/XAPK 路径均为 0。
- `make comments` 通过 330 个手写文件和检查器 14 个正反例；两个新增 Node 文件的
  语法检查与 `git diff --check` 通过，修改文件均在本 change spec 允许范围。
- 现有真实 SDK 路径
  `/Volumes/aigo S7 Media/SDK/android-tools/android-sdk/build-tools/36.0.0`
  及固定 Temurin Java 已通过 inspector 创建阶段的路径、文件类型、可执行位、
  symlink、仓库边界和 aapt2/Java/apksigner jar identity 检查；该检查没有执行
  工具，也没有读取、生成或安装 APK。
- 真实 APK 的 `aapt2`/`apksigner` 输出兼容性、签名方案差异、API 30/33/34 clean
  AVD 安装与场景恢复仍未验收。完成这些证据必须由用户提供合法仓库外制品，并遵循
  既有 N46 manifest、cache verifier、显式 serial 与 clean snapshot 边界。
- 主实现提交为 `01b1bde`，author/committer 均为
  `lejunyang <lejunyang@qq.com>` 且 trailer 恰好一次；提交只包含 follow-up
  spec、`scripts/test-lab` inspector/测试和仓库外 App README，worktree clean。
