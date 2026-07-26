# N47 aactl Adapter 进度

本文件 append-only，记录 RED/GREEN、CLI identity、动作安全边界与未完成设备证据。

## Round 1

- worktree `/private/tmp/ai-auto-n47-adapters` 位于
  `phase2-n47-production-adapters`，基线 `6755e81`，开工时干净。
- 已审计 Go `service`、`aactl bridge snapshot/action`、direct launch、N47 Runner
  端口和 N31 emulator runner。现有 CLI 能安全提供 semantic snapshot/action 和
  explicit launch，但不能提供 N45 visual production observation。
- Adapter 因此只实现 semantic production route；hybrid/visual 和 visual-state
  明确失败关闭，不以 hierarchy/screenshot 坐标猜测替代。
- Bridge open/Accessibility 授权和 emulator lifecycle 由调用方预先完成或注入，
  adapter 不处理配对码、授权、设备发现或系统设置。

## Round 2

- 先增加 executable identity、strict envelope、semantic snapshot、condition、
  route、executor 和 lifecycle 测试；首次运行三个测试文件均因
  `adapters/aactl.mjs` 不存在而有效 RED。
- aactl 创建时要求仓库外绝对普通可执行文件，拒绝 symlink chain，绑定 canonical
  path、dev/inode/mode/ctime/mtime/size 和 SHA-256；每次执行前完整复核，并限制
  executable 最大 128 MiB。
- 所有调用使用 `execFile`、固定 argv、`shell:false`、显式 serial、1 MiB 输出预算
  和步骤剩余 deadline。stdout 只接受单个严格 envelope，拒绝重复 key、尾随 JSON、
  成功 stderr、字段冲突和超限；非零退出若携带合法失败 envelope 则保留稳定 code，
  否则记为 `AACTL_EXEC_FAILED`。
- observer 通过 `bridge snapshot` 读取严格语义树，限制 100 深度、10000 节点、
  节点字段/actions/state/bounds 和请求 maxDepth；有界保留最近两次 observation，
  post condition 必须绑定不同且仍有效的 pre observation。

## Round 3

- condition catalog 只接受声明式 package/target/operator/expected，不接受 callback。
  foreground、semantic-state、node-present/absent 可用；敏感节点不被误报 absent；
  visual-state 明确 `VISUAL_ADAPTER_UNAVAILABLE`。
- router 只提供 semantic route。目标节点必须唯一、目标包一致、可见、启用、非
  password/sensitive 且具备 click/setText/longClick/scroll action；visual/hybrid、
  swipe 和能力缺失均在 executor 前失败。
- route token 是一次性内存对象，绑定 action、observation 和包，消费后不可重试。
  tap/long-click/scroll/Back/Home/Recents 构造固定 protocol v1 action；
  launch/switch-app 只使用 direct launch argv。
- 安全审查确认现有 `aactl bridge action --action` 会把 input 明文放入进程 argv，
  因此 production input adapter 明确返回 `INPUT_ADAPTER_UNAVAILABLE` 且进程调用数
  为零。后续必须先增加固定 stdin action 模式，禁止 argv 或剪贴板降级。
- executor 只有严格成功 data 才返回 `committed:true`；CLI 明确动作拒绝返回
  `committed:false`；进程异常或成功 data 畸形抛稳定错误，由 Runner 记录 unknown。
  deadline 在进程调用前过期明确返回 false 并消费 token。
- 明确配置 launcher/SystemUI 包只用于 Home/Recents 后观察与 switch-app 恢复，
  其他未知包不被接受；页面分类覆盖广告、更新、A-B、登录墙、模拟器和网络失败。

## Round 4

- adapter 定向及 N47 全包最终 53/53 通过，其中包含 Runner 经 adapter 完成
  snapshot -> action -> post snapshot -> condition -> cleanup 的端到端 fake 测试。
- N34 artifacts 49/49 通过；Node 语法、`make comments` 和 `git diff --check`
  通过。范围仅包含本 change spec、adapter 和对应测试，无 APK、截图、日志、raw
  ADB、shell 或设备产物。
- 当前尚未在真实 emulator 上建立 Bridge 并运行场景；N45 visual production
  adapter 也未接入，input 因缺少安全 stdin action 模式保持失败关闭。不能把 fake
  execFile 证据描述为设备验收，N47 路线图仍保持未完成。

## Round 5

- 实现提交 `680c9db`（`test(lab): connect scenario runner adapters`）包含 adapter
  change spec、固定 aactl adapter、README 和全部测试。
- 提交 author/committer 均为 `lejunyang <lejunyang@qq.com>`，消息含且仅含一次
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>`；实现提交后 worktree 干净。

## Round 6

- 独立 change spec `n47-stdin-action-input` 为 CLI 增加固定 `bridge action --stdin`
  模式，input action JSON 不再进入进程 argv；adapter 通过 child stdin 写入并清零
  自身 Buffer。
- 原 `INPUT_ADAPTER_UNAVAILABLE` 缺口已由独立实现安全关闭；visual/hybrid 与真实
  emulator 验收仍未完成，不改变本规格此前设备边界。
