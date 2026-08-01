# N43 Clean Snapshot 重复矩阵进度

本文件 append-only，记录编排设计、RED/GREEN、设备统计、清理和集成证据。

## Round 1

- 现有 `N43SemanticReplayDeviceTest` 支持 `n43Repeat=1..100`，但同一次
  instrumentation 的多 iteration 只调用 fixture reset，不会恢复 N31 clean
  snapshot；因此不能直接用 `n43Repeat=20` 勾选“每轮 clean snapshot”验收。
- 历史单轮设备证据为 API 30/33 各通过，API 34 迁移后 1/2；每一步都重新观察，
  对 action 返回 true 但后置未变化会失败关闭且不重放。当前需要主机编排 20 个独立
  clean round，而不是修改 N43 动作实现。
- 本 change 固定每轮 `n43Repeat=1`、固定 test class 和固定 Gradle task，解析本轮
  JUnit XML；报告不保存 stack、stdout/stderr、hierarchy、页面文本或截图。

## Round 2

- runner fake 测试 5/5 通过：固定 profile 参数、严格唯一 JUnit、19/20=95%、
  18/20=90%、每轮 restore、serial 漂移 fail-fast 和 stop 锁保留单次重试。
- Web fixture 首轮完整 `test/lint/debug/androidTest/release` 共 119 tasks 通过；
  runner comments 与 diff-check 通过。
- 初始代码的 clean 矩阵结果为 API 30 20/20、API 33 20/20、API 34 17/20。API 34
  三轮均为 product-failure、零 infrastructure-failure，与历史 WebView 113 首 click
  返回 true 但后置仍 ready 的波动一致；每轮失败后都先 restore clean，未在同页面
  重放 click。修复前 API 34 报告以 `n43-semantic-matrix-api-34-before-stability.json`
  保留为 `0600` 外置证据。

## Round 3

- 为第一步 click 增加动作提交前的只读稳定观察门：同一 native `LOAD_GENERATION`
  下，WebView ready 状态、唯一 click 节点和 `ACTION_CLICK` 必须连续四次组合一致，
  才提交唯一一次 click。该门不执行动作、不延长动作后置等待，也不修改
  long-click/iframe/detail 的 hybrid-required 分类。
- 首个实现错误地要求 native generation 与 WebView 虚拟节点出现在同一 tree
  traversal，API 34 单轮在 click 前以 `generation=-1` 失败，动作提交数为零并清理。
  修正为每次 observation 先读既有 native generation，再独立读取 WebView 节点；
  定向单测、AndroidTest 编译与 API 34 单轮随后通过。
- 修复后 API 34 clean 矩阵 20/20，成功率 100%；修复前 17/20 失败证据未覆盖。
  最终代码仍需重新跑 API 30/33 二十轮后，才能勾选三版本共同验收。

## Round 4

- 最终代码重新运行 API 30、33、34，各 20/20，总计 60/60；每轮均在同一明确
  profile/serial/fingerprint 上先 restore clean，再执行一次固定 N43 test。
- 三份最终报告 `n43-semantic-matrix-api-{30,33,34}.json` 与修复前 API 34
  `before-stability` 报告权限均为 `0600`；最终报告不含 stack、stdout/stderr、
  XML、hierarchy 或截图。
- runner fake 5/5、Web fixture `test/lint/debug/androidTest/release` 119 tasks、
  comments 与 diff-check 通过。最终 doctor 健康，设备、runtime、AVD/port lease
  与 runner temp 均为零。

## Round 5

- 单一职责提交 `9da0827` 已以等价集成提交 `fae0014` 落入 `main`，stable
  patch-id 均为 `933cdf757e9d3af1bc4c83fbb9e4ed84f2b454f7`。Author 与
  Committer 均为 `lejunyang <lejunyang@qq.com>`，提交末尾恰好一次要求的 trailer。
- 临时 worktree 干净且已非强制移除，随后执行 `git worktree prune` 并删除已等价
  集成的临时分支；修复前与最终外置报告均保留。
