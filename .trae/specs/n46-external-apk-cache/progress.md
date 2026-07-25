# N46 Progress

本文件 append-only，记录实现、测试、安全证据、未完成设备项和清理结果。

## Round 1

- 已确认独立 worktree `/private/tmp/ai-auto-n46` 位于分支
  `phase2-n46-external-apk`，基线为 `7974815`，启动时无本地改动。
- 根 main 已前进但不属于本任务；N44 使用独立 worktree，本任务不读取其未提交内容，
  不回退或吸收并行变更。
- 已审计 MVP、路线图 N46、N31 runner 和 N34 collector。N46 只提供离线验证与
  类型化 runner 接口，不下载、不自行安装、不执行任意 shell。
- 当前正在编写失败测试；实现、专用验证、真实设备安装和提交尚未完成。

## Round 2

- 已先创建 manifest、cache verifier、lifecycle 和 repository scan 四组测试；首次
  执行 4/4 测试文件因实现模块不存在而按预期失败。
- 已实现零依赖 Node ESM 严格 JSON parser，拒绝重复字段、`__proto__`、未知字段、
  非 HTTPS、userinfo、query、fragment 和含 URL 的 `user-provided` 来源。
- cache 根必须显式传入仓库外绝对路径；词法路径和 `realpath` 均与仓库隔离，摘要
  目录及 artifact 拒绝 symlink。artifact 通过
  `lstat -> open(O_NOFOLLOW) -> fstat` 流式校验 identity、size 和 SHA-256。
- inspector 是调用方注入的类型化端口，只接收固定 artifactPath、sizeBytes 和
  sha256；返回值严格匹配 package、version、versionCode、ABI、minSdk 与签名摘要。
  本任务没有实现或执行 `aapt`、`apksigner`、shell 或网络下载。
- verified descriptor 由进程内私有 provenance 签发，复制或手工构造的同形对象
  不能进入 runner。clean snapshot 恢复后、`installVerified` 前再次校验原
  dev/inode identity、size 与 SHA-256；不同字节替换和相同字节原子替换均保持安装
  调用数为零，并执行最终 snapshot 恢复。
- 已核对指定 SDK Platform-Tools 37.0.0 的 `adb help`，其安装语法为
  `install [options] PACKAGE`，未声明独立 `--`；固定 argv 因此为
  `-s <serial> install --no-streaming <absolute-path>`，不传 shell 或任意参数。

## Round 3

- `npm run smoke --prefix scripts/test-lab` 通过 32/32 测试；repository scan 报告
  工作区和全部 Git 历史 APK/APKS/AAB/XAPK 路径均为 0。
- `make comments` 通过 307 个手写文件及检查器 14 个正反例；`git diff --check`、
  Node 语法检查、JSON 可解析性和允许目录范围审计通过。
- 测试只在系统临时目录写入无 APK 扩展名的非 APK 字节；未下载、生成、复制、安装
  或提交 APK，未启动 ADB server、Emulator、真实 inspector 或网络请求。
- 当前只完成离线 manifest/cache 验证和 fake runner 生命周期契约。真实 APK 的
  aapt/apksigner 检查、API 30/33/34 clean AVD 安装与场景结束恢复尚未接入，不作为
  本轮设备证据；应由后续持有合法仓库外制品的 runner/场景任务显式验收。
