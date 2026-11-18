# 完整包与精简包发布规格（N60.2）

## 目标与范围

在 `virtual-display-screen-off` 的 `d565282` 后交付可解压使用的 CLI/helper 包，默认完整包附官方 ADB，另提供不附 ADB 的精简包。只修改任务书允许的发布脚本及新增辅助脚本/测试、ADB locator、CLI 诊断、Makefile、release workflow、五份指定文档、路线图 N60.2、固定清单及本规格。不修改三道风险门、MCP、App、Bridge 或 displayd 源码；不 push，不触碰其它 worktree。项目无 LICENSE，不选择或编写许可证。

## 发布契约

- 四目标：darwin/arm64、darwin/amd64、windows/amd64、linux/amd64；每目标完整/精简各一份，Windows zip，其它 tar.gz。顶级目录与归档去掉扩展名后的名称相同。
- 完整包：aactl[.exe]、同版 aactl-displayd.apk、README.txt、platform-tools/adb[.exe]、Windows 两个官方 DLL、platform-tools/NOTICE.txt。只提取运行 ADB 必需的白名单文件；不把其它 SDK 工具放入包。
- 精简包：aactl[.exe]、aactl-displayd.apk、README.txt；用户自备官方 Platform-Tools。BUNDLES=slim 不下载 Platform-Tools，不要求本项许可变量。
- App debug APK 单独发布；SHA256SUMS 覆盖八归档及 App APK。不再单独发布裸 CLI。
- README.txt 使用中文三步说明、项目/版本、App 发布资产指引、ADB 来源/冲突代价与 v1→v2 升级顺序，不附自拟许可文本。
- 使用 :displayd:assembleDebug 的 displayd-debug.apk：与 CLI 来自同一提交，helper 仅作 app_process CLASSPATH，不安装到设备，调试签名不影响该用途。App 使用 :app:assembleDebug。
- 固定排序、目录 0755、可执行文件 0755、其它文件 0644；时间取 SOURCE_DATE_EPOCH，否则取提交时间。zip 受 DOS 两秒精度约束；APK/编译器自身可复现性不作超出实测的保证。
- 全部构建、校验、提取和归档在专属 staging 中进行；任何失败清理 staging，不发布半成品，不破坏上一次成功输出。

## Platform-Tools 供应链与许可

版本必须匹配 osdk.toml 的 37.0.1。JSON 固定清单逐系统记录版本、官方归档名、字节数、官方 repository2-3.xml 的 SHA-1，以及在核对 size/SHA-1 后从实际下载计算的 SHA-256；来源、日期和原始值记入 append-only progress.md。macOS 两架构共用官方通用归档。

仅从 https://dl.google.com/android/repository/ 获取二进制；拒绝其它源及跨源重定向。完整包在下载或使用缓存前必须设置 AACTL_ACCEPT_ANDROID_SDK_LICENSE=android-sdk-license。下载缓存放仓库外或忽略目录，三系统 size/SHA-1/SHA-256 全部通过后才提取；任何不符停止并清理损坏缓存。提取前逐条验证归档路径，拒绝绝对路径、..、反斜杠、重复项、符号链接及其它非常规条目，按固定白名单读取到新文件，禁止通用解压逃逸。

负责人已知悉 Android SDK 许可再分发风险并决定打包；文档如实说明发布者自行评估，本文不作法律判断，显式许可开关不得绕过。

## ADB 定位与诊断

顺序：AACTL_ADB_PATH → 解析 os.Executable() 符号链接后的同目录 platform-tools/adb[.exe] → PATH → ANDROID_HOME / ANDROID_SDK_ROOT → 默认 SDK。工作目录不参与随包查找。随包候选存在但不可用时返回 ADB_NOT_FOUND，明确重新解压或用 AACTL_ADB_PATH 覆盖，禁止静默回退。候选必须常规文件，并通过既有执行权限校验。

doctor 的普通格式与 --json 都展示实际 ADB 路径及来源（explicit / bundled / path / sdk），不新增命令，不修改 MCP 工具面。保留 Resolve 调用兼容性，增加可注入 executable 路径和来源诊断接口。

## 验收与授权边界

先失败测试再实现：定位顺序、随包损坏、符号链接、CWD 无关；离线假清单/归档覆盖成功、摘要/大小不符、路径逃逸、符号链接、无许可、slim 不联网、失败无半成品。

本机真实完整构建后核对所有 SHA256SUMS、内容和大小；仓库外隔离解压 Windows 两包，清除 AACTL_ADB_PATH/ANDROID_HOME/ANDROID_SDK_ROOT 并移除含 adb 的 PATH，完整包 doctor 命中 bundled，slim 返回 ADB_NOT_FOUND。隔离默认用户 SDK 目录的影响须记录。

只启动指定 disposable AVD osdk-test-35：emulator -avd osdk-test-35 -read-only -no-snapshot -no-boot-anim -no-audio，只操作 emulator-5554。每步先观察身份、说明动作/预期/验证，再执行及复核；多设备、离线、身份变化立即停。仅用解压完整包完成 devices、一次截图、无 --helper 的 display start、create、close、stop；记录指纹与环境，清理临时文件、关闭模拟器并确认无残留。不操作真机授权或安全设置。

全量 make comments、make test、make verify、make build、make android-verify。其它三平台仅结构/摘要检查，无运行结论。任何范围外修复、产品矛盾、官方元数据不符或许可/安全越界停止报告。

## 后续项

仅更新路线图 N60.2：登记项目自身许可证待负责人决定、linux/arm64 官方 ADB 缺口、macOS 公证/隔离属性、macOS/Linux 实机运行验证、包管理器分发，不改其它子项结论。
