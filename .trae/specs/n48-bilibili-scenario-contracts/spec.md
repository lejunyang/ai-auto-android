# N48 哔哩哔哩安全兼容场景契约规格

## 目标

为哔哩哔哩 `9.5.0` 未登录公开内容场景建立严格、离线、机器可读的计划与报告契约。
本 change 只交付固定计划、JSON Schema、validator、fake fixture 和测试，不执行设备、
ADB、N47 production adapter 或第三方 App 动作，也不形成 N48 真实十轮验收证据。

## 身份与计划

- 制品身份逐字段绑定现有 `bilibili-9.5.0-arm64.json`：manifest 名称、package、
  version、versionCode、ABI、size、APK SHA-256 和签名证书 SHA-256 均固定。
- 报告不得携带 APK、下载来源、manifest 路径、任意 package、serial、账号或凭据。
- 计划固定为单一 profile 下连续 `1..10` 共十轮；报告最多覆盖这十轮，禁止缺号伪装
  完成、重复轮次或额外轮次。
- 固定动作只覆盖 launch、进入公开搜索、临时输入公开查询、点击公开搜索结果、公开
  结果滚动/swipe、受安全证明约束的长按、两级 Back、Home、Recents、从 Recents
  返回、切到 native fixture 及返回。
- 输入值只存在于运行期内存，不进入计划结果或报告；报告中不存在 query、text、
  value、selector 或输入摘要字段。

## 安全状态机

1. 每步先观察并复核前台包；动作被明确提交或提交状态未知后必须再次观察。
2. consent、privacy、permission、login、update、advertisement 和 unknown 页面均
   立即失败关闭，本步零尝试、零提交且不执行后续步骤。
3. 唯一内容点击为固定的公开搜索结果点击，不接受任意 tap、deep link 或动作参数。
4. 长按只有在执行前已证明不会点赞、收藏、下载或分享时才允许一次提交；证明不足时
   分类为 `unsupported`、零尝试、零提交。
5. Back、Home、Recents、从 Recents 返回、切到 fixture 和从 fixture 返回均逐步复核
   固定前台 package 角色；任一漂移失败关闭。
6. 每个动作最多一次尝试。`ACTION_COMMIT_UNKNOWN` 必须保留 `actionCommits:null`，
   仍执行动作后观察，不重试且不执行后续步骤。
7. API 33 的 hierarchy unavailable 固定分类为
   `BILIBILI_API33_HIERARCHY_UNAVAILABLE`；API 34 的 target package invisible 固定
   分类为 `BILIBILI_API34_PACKAGE_INVISIBLE`，两者均零动作提交。

## 报告与统计

- 报告只允许固定 identity、profile、evidence kind、十轮内容和重算统计。
- fixture 每轮只能绑定固定 fake ID；真实轮次必须绑定唯一 N47 run UUID、run-report
  SHA-256、profile fingerprint SHA-256 与已验证 APK SHA-256。
- validator 从 rounds 重算通过/失败/unsupported、fixture/真实轮次、action commit、
  unknown commit、cleanup、route、页面、安全分类与 blocker 统计。
- fake fixture 可以覆盖完整十轮契约，但 `realRounds` 必须为零且
  `tenRoundRealEvidenceComplete` 必须为 `false`。
- 真实十轮完成仅在 `evidenceKind=real-device`、十轮齐全、全部清理完成且无 unknown
  commit 时才能由内容推导，离线 fixture 不得提升为真实证据。
- Schema 与 validator 拒绝 unknown key、任意 package/APK/action、坐标、secret、
  路径、URL、raw hierarchy、screenshot、自由错误文本和陈旧统计。

## 清理

每个已报告轮次都必须记录 App 数据、搜索历史、session、artifacts 清除和 clean
snapshot 恢复状态。任一项未完成时分类为 cleanup failure，不得计入完成的真实十轮。

## 允许修改

- `.trae/specs/n48-bilibili-scenario-contracts/`
- `test-lab/scenarios/bilibili/`

## 禁止修改

- `docs/next-phase-tasks.md`、共享 N47 Runner/Schema、manifest 与 APK/cache
- N49-N52、Android、Go、设备、账号与任何第三方 App 状态
- 不下载、安装、启动或操作哔哩哔哩，不生成真实 hierarchy/screenshot

## 验收

- 先增加计划、安全状态机、统计和泄密边界测试，并以缺失实现取得 RED。
- 实现严格 plan/report Schema、fail-closed validator、固定 fake fixtures 和
  schema-check 后取得 GREEN。
- 运行 N48 定向 Node、专用 schema-check、N47 Runner 回归、`make comments` 与
  `git diff --check`。
- 最终只提交离线契约，不勾选 N48，不把 fixture 描述为真实十轮设备证据。
