# N49 抖音安全兼容场景契约规格

## 目标

为抖音 `39.8.0` arm64 未登录兼容场景建立严格、离线、机器可读的安全策略与报告
契约。契约固定绑定 N46 manifest `douyin-39.8.0-arm64.json`，定义连续 10 轮真实
验收的计数规则，但本 change 只交付 fake fixture、Schema、validator 和测试，不操作
设备，也不产生任何真实轮次。

## 身份与轮次

- manifest 绑定固定 `com.ss.android.ugc.aweme`、`39.8.0`、`390801`、
  `arm64-v8a`、APK SHA-256 和签名证书 SHA-256；报告不得接受调用方传入其他
  package、APK、来源或路径。
- 真实兼容报告必须恰好包含 iteration `1..10` 的 10 轮，重复、缺失、乱序或额外轮次
  均拒绝。
- fixture 只验证契约和分类逻辑，`fixtureRuns` 可以非零，但 `realRuns` 与
  `completedRealRounds` 必须为零，不能伪装成真实设备证据。
- 无账号运行仍必须记录有界遥测状态，并明确标记个性化不可判断、推荐内容动态且不可
  复现；不得由无账号 fixture 推导推荐质量结论。

## 动作与安全边界

- 能力目录仅允许公开 feed 观察、纵向 swipe、搜索输入、页面跳转、Back、Home、
  Recents、返回抖音和长按探针；目录不是可执行 action，不携带 selector、输入值、
  坐标或命令。
- 纵向 swipe 只有在 fresh observation 绑定目标 package 后才可尝试一次；提交后必须
  取得不同内容 fingerprint 的 post observation，才能记为
  `content-change-verified`。内容未变化或无法验证时不得伪报兼容。
- 长按可能打开互动菜单，默认 `unsupported`，attempt、retry 与 action commit
  必须为零。
- 点赞、评论、关注、发送、分享、收藏和下载节点在每轮与汇总中都必须保持零提交。
- executor 提交状态未知时只允许 `ACTION_COMMIT_UNKNOWN`、一次 attempt、零 retry，
  立即停止，禁止自动重试。

## 分类与视觉边界

以下结果必须使用不同固定分类，不得合并为自由文本：

- `video-surface`
- `visual-only`
- `login-wall`
- `advertisement`
- `emulator-detected`
- `hierarchy-failure-api33`
- `hierarchy-failure-api34`

另有 `content-change-verified`、`long-press-unsupported` 和
`action-commit-unknown`，分别表示可验证纵向内容变化、长按安全拒绝和提交状态未知。
`visual-only` 只允许显式分类证据和零 action commit；视频 Surface、API 33/34
hierarchy failure 或其他观察失败都不得盲目升级到 visual action。

## 报告与清理

- validator 不信任报告自带统计，从 runs 重算 fixture/real 轮次、状态、分类、
  已验证内容变化、unknown commit 和受保护节点提交数。
- 每轮只保留固定枚举、计数、observation ID、内容 fingerprint 和清理状态；拒绝
  unknown key、任意 action/package/APK、坐标、secret、路径、URL、原始 hierarchy、
  screenshot、OCR、日志或页面内容。
- 每轮无论分类或失败都必须记录 App 数据已清除、session 已清除、artifacts 已清除和
  snapshot 已恢复；任一清理项缺失或失败均不满足契约。

## 实施波次

1. **Wave 1，规格所有权：** 仅修改本 change spec，冻结身份、轮次、分类、清理和
   不可交互边界。
2. **Wave 2，测试所有权：** 在 `test-lab/scenarios/douyin/test/` 增加 RED 测试，
   不创建实现模块。
3. **Wave 3，契约所有权：** 增加两份严格 Schema、validator、固定 scenario/report
   fixture 与 README，测试转 GREEN。
4. **汇合点：** 运行 N49 定向 Node/schema、N47 离线回归、`make comments` 和
   `git diff --check`；审查修改范围后单提交。

上述波次修改目录互斥但按依赖串行执行，不派发 Sub-Agent，不与共享 Runner/Schema
并行写同一文件。

## 允许修改

- `.trae/specs/n49-douyin-scenario-contracts/`
- `test-lab/scenarios/douyin/`

## 禁止修改

- N47 共享 runner、Schema、adapter 与 fixture
- `docs/next-phase-tasks.md`、manifest、其他 change spec 和其他场景任务
- Android、Go、APK/cache、账号、设备、session、snapshot 或真实运行产物

## 验收证据

- 先记录缺少实现模块导致的 RED，再实现 Schema、validator、fixture 和测试进入
  GREEN。
- 定向测试覆盖 manifest 固定绑定、真实 10 轮、fixture 排除、七类受保护节点零提交、
  分类隔离、fresh swipe、内容 fingerprint 变化、长按 unsupported、unknown commit
  不重试、无账号遥测限制和四项清理。
- 安全反例覆盖任意 package/APK/action、坐标、secret、路径、URL 与原始内容。
- 两份 Schema 的全部 object 都设置 `additionalProperties=false`。
- N49 Node/schema、N47 离线回归、`make comments` 与 `git diff --check` 通过。
- 本 change 不产生真实 10 轮设备证据，不勾选 N49。

## 提交与回滚

建议单一职责提交为 `test(lab): add douyin scenario contracts`，提交消息末尾保留且
仅保留一次 `Co-authored-by: TRAE CLI <noreply@bytedance.com>`。失败时只回滚本
change spec 与 `test-lab/scenarios/douyin/`；本任务不创建需要额外清理的 App 数据、
session、artifacts、snapshot、APK 或设备状态。
