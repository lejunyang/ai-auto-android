# N51 小程序分类契约进度

本文件 append-only，记录 RED/GREEN、契约验证和真实宿主证据边界。

## Round 1

- 独立 worktree `/private/tmp/ai-auto-n51-miniapp-contracts` 位于分支
  `n51-miniapp-classification-contracts`，基线 `0fecfe1`；开工时主工作区与新
  worktree 均干净，现有 N45 worktree 未被修改。
- 已审计 `docs/next-phase-tasks.md` N51：微信 `8.0.76` 与支付宝
  `12.12.10.8000` 只有未进入小程序的被动启动基线，不产生任何四级真实样例。
- 本轮只实现离线分类契约。禁止设备操作、APK 下载、账号登录、deep link 猜测和原始
  hierarchy/screenshot 持久化，N51 与真实宿主验收保持未完成。

## Round 2

- RED 先增加分类、统计和安全测试；首次运行三个测试文件全部因缺少
  `src/report-validator.mjs` 以 `ERR_MODULE_NOT_FOUND` 失败，随后才实现生产代码。
- 严格 Schema 绑定 sample、宿主 package/version、page/sample identity 与页面
  fingerprint。hierarchy/screenshot 只允许限额摘要和 SHA-256，二者
  `retainedBytes` 固定为零。
- validator 从探针重算 `full-semantic`、`hybrid`、`visual-only`、`unsupported`，
  再重算总数、fixture/真实样例数、宿主数、四级统计和 fail-closed 数；陈旧 decision
  或统计均拒绝。
- 页面未知、低置信度、sample/宿主/page/fingerprint 漂移、重复 probe 类型均分类为
  `unsupported`；低置信度与未验证 probe 的 action commit 必须为零。
- 固定 `dev.aiauto.webfixture` 报告仅声明 `N42_N43` fixture。测试中的微信/支付宝
  sample ID 使用 `contract-*`，只验证 package/version 契约，不形成真实页面证据。
- 专用 schema-check 通过 1 个 Schema 与 1 个 fixture；Node 定向测试 17/17 通过。
  N47 离线 smoke 59/59、N52 离线 smoke 21/21，`make comments` 覆盖 426 个手写文件。
- 全程未操作设备、未下载 APK、未登录账号、未搜索或猜测 deep link；微信/支付宝
  真实小程序四级样例均为零，N51 与路线图勾选保持未完成。

## Round 3

- 分类契约实现提交为 `44cb22d`，提交范围仅含本 change spec 与
  `test-lab/scenarios/miniapps/`，提交后 worktree 干净。
- 实现提交 author 与 committer 均为 `lejunyang <lejunyang@qq.com>`；消息末尾
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>` 恰好一次。
- 本轮只记录已经验证的离线契约证据，不新增真实宿主样例；N51 与路线图勾选继续保持
  未完成。
