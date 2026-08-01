# N48 哔哩哔哩场景契约进度

本文件 append-only，记录 RED/GREEN、契约验证和真实十轮证据边界。

## Round 1

- 独立 worktree `/private/tmp/ai-auto-n48-bilibili-contracts` 位于分支
  `n48-bilibili-scenario-contracts`，基线 `e804c87`；开工时主工作区与新
  worktree 均干净，现有 N45/N47/N51 worktree 未被修改。
- 已审计 N46 被动基线：B站 `9.5.0` 在 API 30 仅观察到 consent/login 信号；
  API 33 hierarchy 不可用；API 34 hierarchy 可用但 target package 不可见。这些
  结果只作为固定 blocker 分类来源，不构成任何动作或十轮验收证据。
- 本轮只实现离线 N48 场景计划和报告契约；不运行设备、ADB、N47 production adapter，
  不修改 manifest/runner/路线图，不下载 APK，也不勾选 N48。

## Round 2

- RED 先增加固定 identity/十轮计划、安全页面零提交、公开搜索输入不保留、公开结果
  click 白名单、长按四类副作用证明、系统导航/跨 fixture 前台包复核、动作后观察、
  unknown commit 不重试、API 33/34 稳定 blocker、五类清理、统计重算和泄密拒绝测试。
- 首次运行四个测试文件均因缺少 `src/report-validator.mjs` 以
  `ERR_MODULE_NOT_FOUND` 失败，确认测试先于实现进入 RED。

## Round 3

- 两份严格 Schema 固定 B站 verified manifest identity、API/profile、十轮计划、
  15 个动作 ID、四个 package、route、状态、blocker、提交和五类清理字段；所有
  object 均 `additionalProperties:false`，没有输入值、selector、坐标、路径、URL、
  raw hierarchy、screenshot 或自由异常字段。
- validator 从 rounds 重算成功率、fixture/真实轮次、状态、route、分类、blocker、
  action commit、unknown commit、清理轮次和真实十轮完整性。fixture 只接受固定
  fake ID；真实形状要求每轮唯一 N47 run UUID、run-report SHA-256、profile
  fingerprint SHA-256 与已验证 APK SHA-256。测试中的 real-device 对象只验证形状，
  仓库固定报告仍全部标记为 `fixture`，不形成设备证据。
- consent/privacy/permission/login/update/advertisement/unknown 只能在当前步骤
  零尝试零提交停止；公开搜索输入无值字段，内容点击仅有 `click-public-result`；
  长按证明任一点赞/收藏/下载/分享边界不足时 `unsupported`。所有已提交动作和
  unknown commit 均要求 post observation，unknown 最多一次且必须停止后续步骤。
- API 33 全十轮 fixture 固定为 `BILIBILI_API33_HIERARCHY_UNAVAILABLE`，API 34
  分类测试固定为 `BILIBILI_API34_PACKAGE_INVISIBLE`，二者均无动作步骤。专用
  schema-check 还直接读取现有 manifest 复核 fixture identity。
- GREEN 最终为 N48 schema-check 2 Schema/2 fixture、Node 20/20；N47 Runner
  smoke 59/59；`make comments` 覆盖 441 个手写文件且检查器自测 14 项通过；
  `git diff --check` 与禁止二进制/越权路径扫描通过。
- 全程未操作设备、ADB、APK/cache、Android/Go、共享 Runner/Schema、路线图或
  N49-N52。真实固定版本连续十轮仍为零，N48 与路线图勾选保持未完成。
