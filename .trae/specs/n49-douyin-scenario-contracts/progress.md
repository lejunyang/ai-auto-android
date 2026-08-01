# N49 抖音安全兼容场景契约进度

本文件 append-only，记录 RED/GREEN、离线 fixture 证据、安全拒绝和未完成设备边界。

## Round 1

- 独立 worktree `/private/tmp/ai-auto-n49-douyin-contracts` 位于分支
  `n49-douyin-scenario-contracts`，基线 `e804c87`；开工时主工作区与新 worktree
  均干净，现有 N45/N47/N51 worktree 未被修改。
- 已审计 N49 路线、`douyin-39.8.0-arm64.json` 和 N47 三份严格 Schema、安全 runner
  与 unknown commit 语义。N49 被动基线仅有 API 30 hierarchy 与 API 33/34
  hierarchy failure，不构成动作覆盖或连续 10 轮证据。
- 本 change 只实现离线场景策略与兼容报告契约，不操作设备、不运行 ADB、不下载或
  安装 APK、不调用共享 runner，也不修改 manifest、路线图、Android 或 Go。
- 固定安全边界包括七类互动节点零提交、纵向 swipe fresh observation 与内容变化
  验证、长按默认 unsupported、unknown commit 不重试、无账号推荐动态限制，以及
  App 数据/session/artifacts/snapshot 四项清理。

## Round 2

- 先新增场景身份、安全分类、轮次统计、fresh swipe、长按、unknown commit、清理和
  数据最小化测试，不创建实现模块。
- 首次在 `test-lab/scenarios/douyin/` 运行 `npm test`，三个测试文件均以
  `ERR_MODULE_NOT_FOUND` 失败，缺失项明确为 `src/contract-validator.mjs`；这是预期
  RED，未以占位实现或跳过测试伪造通过。

## Round 3

- 新增场景策略与兼容报告两份 draft 2020-12 严格 Schema；递归检查确认全部 object
  都设置 `additionalProperties=false`。身份以 `const` 固定到
  `douyin-39.8.0-arm64.json` 的 package/version/versionCode/ABI/APK SHA-256 和
  签名证书 SHA-256，schema-check 还逐字段读取现有文本 manifest 防止漂移。
- validator 从 runs 重算 fixture/real 轮次、状态、十类分类、内容变化、unknown
  commit 和七类受保护节点提交数。真实报告只能包含有序 iteration `1..10`，fixture
  不得与真实轮次混合，固定 fixture 报告重算为 `fixtureRuns=10`、`realRuns=0` 和
  `completedRealRounds=0`。
- video Surface、visual-only、登录墙、广告、模拟器检测与 API 33/34 hierarchy
  failure 分别绑定固定分类和 error code。观察失败保持零提交；只有
  `visual-only` 可记录 visual 观察分类，仍禁止 action attempt，不从 hierarchy/video
  failure 盲目升级 visual。
- 纵向 swipe 显式绑定抖音 observation package、fresh pre observation、唯一 post
  observation 和变化后的内容 fingerprint；长按固定为互动菜单风险 unsupported；
  unknown commit 固定 `ACTION_COMMIT_UNKNOWN`、一次 attempt、零 retry。
- 点赞、评论、关注、发送、分享、收藏和下载在每轮及重算汇总中恒为零；无账号仍记录
  推荐动态不可控和个性化不可判断。每轮 App 数据、session、artifacts 与 snapshot
  四项清理都必须完成。
- 安全反例拒绝任意 package/APK/action、坐标、selector、secret、路径、URL、原始
  hierarchy/screenshot、OCR、自由异常和陈旧统计；固定 JSON fixtures 不包含 APK、
  截图、日志、账号或真实页面内容。
- GREEN 验证通过：N49 schema-check 验证 2 份 Schema 和 2 个 fixtures，Node 测试
  13/13；N47 离线 smoke 59/59；`make comments` 覆盖 440 个手写文件；
  `git diff --check` 与禁用制品检查通过。
- 全程未操作设备、ADB、抖音 App、APK/cache 或任何 session/snapshot。真实连续
  10 轮仍为零，N49 路线图未修改且必须保持未勾选。
