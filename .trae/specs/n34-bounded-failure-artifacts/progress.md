# N34 Progress

本文件 append-only，记录实现、测试、安全证据和清理结果。

## Round 1

- 已确认 `phase2-n34-failure-artifacts` 从共享 Android 基线 `e7a5716` 开始，worktree
  无本地改动；N31 的 `887c1c6`、`ee2acc7` 已在历史中。
- 已确认 N32、N33 位于独立 worktree，本任务只修改 reporting、artifact、专用
  workflow 和本 change spec。
- 已定义严格结果契约、三层预算、TTL、20 轮分类和 fail-closed 隐私边界。
- 截图没有离线 OCR 时不能由 collector 自证安全，必须提供绑定 SHA-256 的
  `verified-masked` 证明；否则只保留 `REDACTION_UNVERIFIED` 稳定摘要。
- 当前正在编写失败测试；实现、专用验证和提交尚未完成。

## Round 2

- 已先编写 collector、脱敏、严格 Schema 和 20 轮统计测试；首次执行时 4 个测试文件
  均因 `src/collector.mjs`、`redaction.mjs`、`schema-validator.mjs` 和
  `statistics.mjs` 尚不存在而按预期失败。
- 初版实现复验为 32 项中 23 项通过、9 项失败；失败暴露全局正则 `lastIndex` 与跨行
  空白误报，正常 collector 被错误归为 `REDACTION_UNVERIFIED`。修复为每次扫描复位
  状态并只允许水平空白，不放宽敏感键、Bearer 或编码变体过滤。
- 审查补充 source `../`、绝对路径、恶意 run/scenario ID、source/staging symlink
  和 `lstat -> open(O_NOFOLLOW) -> fstat` inode race 测试；路径错误分别保持
  `ARTIFACT_PATH_UNSAFE` 或 `ARTIFACT_SOURCE_UNSAFE`，不归并为脱敏错误。
- 32/32 通过后继续审查输出与清理路径，发现并关闭 `runs`、run 目录以及中间
  `.staging`/run staging symlink；passed、failed 与 TTL 三条清理路径均只解除链接
  本身，不读取或删除 artifact root 外 sentinel。
- 已严格拒绝未知 source 字段、非字符串敏感值、时间线目标包与声明不一致及未知截图
  遮罩器；可信截图 proof 固定为 `android-ui-masker-v1` 与最终 PNG SHA-256。

## Round 3

- 已实现四份严格 JSON Schema、零依赖 Node ESM collector/CLI、结构化递归脱敏、
  单文件/场景/整轮物理预算、原子发布、成功立即清理、TTL 清理和 20 轮聚合。
- timeout 与 assertion failure fake input 均生成设备元数据、动作时间线、测试报告、
  hierarchy、短日志窗和受信任 PNG；摘要 `usedBytes` 与场景目录物理字节一致。
- 20 轮聚合覆盖 passed、product、device、infrastructure 和 flaky，输入倒序后输出
  字节一致；CLI 黑盒覆盖 collect、stats 与 cleanup。
- 专用 workflow 只在 Ubuntu、macOS、Windows 运行 fake-input Node smoke，不启动
  Emulator，不作为 API 30/33/34 或真机矩阵证据。
- 最终 `npm run smoke --prefix test-lab/artifacts` 通过 4 份 Schema 与 44/44 测试；
  `make comments` 通过 216 个手写文件和 14 个自测；`git diff --check` 通过。
- npm 输出现有用户级 `store-dir`、`global-bin-dir`、`global-dir` 弃用警告，但命令
  明确退出 0，不影响本任务验证。
- 当前未运行真实设备或 Emulator 场景；N34 提供 collector 与 fake-input 安全证据，
  后续 N33/N52 设备矩阵集成仍需单独验收。

## Round 4

- 安全复审确认原模型可由 artifact 输入自报 allowlist `processorId` 与最终 PNG
  self-hash，collector 无法证明 masker 实际执行，因此此前提交条件撤销并保持
  worktree 未提交。
- `collectFailureArtifacts` 现只接受调用进程注入的窄
  `trustedScreenshotVerifier(finalPngBytes, proof)`；proof 仍须满足固定方法、PNG
  格式与最终 hash 条件，但只有 verifier 结合进程内独立可信上下文严格返回 `true`
  才能保留截图。verifier 缺失、返回非 `true` 或抛错统一失败关闭为
  `REDACTION_UNVERIFIED`，异常自由文本不进入摘要。
- fake-input verifier 固定校验测试内 run 上下文、processor 身份与预先可信的最终
  hash；新增同一 `sources.json` 无 verifier、自报已知 processor 与匹配 hash、
  verifier 返回 `false` 和 verifier 抛错的回归，均只留下稳定阻断摘要。
- CLI 不加载真实 masker verifier，默认含截图 collect 退出 1 且不保留 PNG；显式
  `--omit-screenshot` 要求 `sources.json` 不含 screenshot 项，并仅收集其余脱敏
  证据。首次修复后 smoke 已通过 4 份 Schema 与 49/49 测试。

## Round 5

- 主线程独立复验 `npm run smoke --prefix test-lab/artifacts`，4 份严格 Schema 和
  49/49 fake-input 测试全部通过；`make comments` 覆盖 216 个手写文件并通过 14 个
  检查器自测，`git diff --check` 通过。
- Git 范围审计确认所有新增文件仅位于 N34 change spec、reporting、artifact 与专用
  workflow 目录；未提交 APK、截图、日志、运行产物、缓存或真实 secret。敏感固定值
  仅存在于 fake-input 测试目录，不进入生产 collector、Schema、workflow 或文档。
- 最终默认 CLI 没有进程内可信 verifier 时拒绝保留 PNG；调用方只能显式省略截图并
  保留其他已脱敏证据，不能以输入中的 processor ID 或 self-hash 建立截图信任。
