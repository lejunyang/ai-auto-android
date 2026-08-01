# N50 小黑盒安全兼容场景契约进度

本文件 append-only，记录 RED/GREEN、离线 fixture、安全拒绝与真实设备证据边界。

## Round 1

- 独立 worktree `/private/tmp/ai-auto-n50-xiaoheihe-scenario-contracts` 位于分支
  `n50-xiaoheihe-scenario-contracts`，基线 `e804c87`；创建前主工作区干净，现有
  N45、N47、N51 worktree 均未占用本任务允许目录。
- 已审计 N50 路线和 N46 被动基线：小黑盒 `1.3.392` 在 API 30/33/34 均稳定命中
  consent/permission 信号，历史 runner 未点击或授权，因此这些轮次不能作为兼容成功。
- 本轮只实现离线代码与 fake 契约。禁止设备、ADB、APK/cache、manifest 修改、共享
  Runner/Schema 修改、Android/Go 代码和路线图更新，N50 保持未勾选。

## Round 2

- 先增加固定身份/矩阵、安全阻塞、route/post observation、独立分类、unknown commit、
  清理、统计重算与数据最小化测试；首次 `npm test` 的 4 个测试文件均因缺少
  `src/report-validator.mjs` 以 `ERR_MODULE_NOT_FOUND` 失败，随后才开始生产实现。
- GREEN 实现两份严格 JSON Schema、fail-closed validator、固定安全场景和
  consent/permission 阻塞 run 模板；schema-check 从模板生成 API 30/33/34 各 10 轮，
  并确认 30 个 fixture 的真实轮次、真实兼容数和真实成功率均不成立。
- validator 从 observation/step/cleanup 内容重算逐轮 decision，再重算三 API、route、
  动作覆盖、动态内容、广告、网络和清理统计。阻塞轮次零提交，unknown commit 首次
  尝试后停止，全部已提交动作都要求 post observation。
- N50 专用 smoke 通过 2 份 Schema、1 个固定场景、1 个阻塞模板、30 个生成 fixture
  run 与 18/18 Node 测试；全程未读取 APK、未连接设备、未运行 N47 Runner 或 Bridge。

## Round 3

- 交叉审计并行 N49 契约后纠正 Round 2 的轮数解释：路线图与本任务用户要求都是报告
  总计固定 10 轮，不是 API 30/33/34 各 10 轮。最终 Schema 只接受按序 iteration
  1 至 10，每轮 API 严格限于 30、33、34。
- 三 API consent/permission 规则仍分别由完整 10 轮报告验证；另用包含三个 API 的
  10 轮 fixture 重算逐 API 阻塞统计。fixture 仍不计真实轮次或真实成功率。
- 最终契约补充固定 route policy、real/fixture 不混用和跨轮 observation ID freshness；
  N50 smoke 通过 2 份严格 Schema、10 个生成 fixture run 与 20/20 Node 测试。
- N47 离线 smoke 通过 Schema 检查与 59/59 测试；`make comments` 覆盖 441 个手写
  文件并通过 14 个检查器自测。允许目录内未发现 APK、截图、hierarchy、日志、压缩包、
  路径、secret、坐标、DOM/JavaScript 或可执行动作参数。
