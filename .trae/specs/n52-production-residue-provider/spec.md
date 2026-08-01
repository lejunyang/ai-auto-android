# N52 Production Residue Provider 规格

## 目标

在既有 N52 production ports 上实现八类 concrete residue inspector 的当前安全子集。
所有结果绑定 N31 固定 profile、明确 emulator serial、device fingerprint 和
`clean` snapshot。无法从可信类型化能力证明的类别必须在 capability 阶段返回
`PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE`，不得用常量零、raw shell、设备发现或任意
路径检查伪造成功。

## 固定边界

- profile 仅允许 `api-30`、`api-33`、`api-34`，其 API、AVD 名和 `clean` snapshot
  必须来自仓库内 N31 profiles。
- package 仅允许 `dev.aiauto.android`、`dev.aiauto.fixture` 和
  `dev.aiauto.webfixture`；调用方不能增加 package、serial、路径或检查类型。
- state root 来自既有 production config，report root 固定为
  `stateRoot/reports`，staging root 固定为
  `stateRoot/staging/n52-production-matrix`。三者必须位于仓库外，且不能通过 symlink
  或路径替换逃逸。
- `appData`、`bridgeSessions`、`testServices` 和 `ownedProcesses` 只接受窄类型
  provider。provider 缺失、身份不可验证或检查竞态时 capability unavailable 或在
  stop 后稳定失败。
- `screenshots`、`logs`、`runtimeFiles` 和 `leases` 只检查 N31/N52 固定布局；目录、
  普通文件、JSON 和遍历均有深度、条目、字节预算。

## 安全语义

- 所有 inspector 逐字回显 `profileId`、`apiLevel`、`serial`、
  `deviceFingerprint`、`clean` snapshot、固定 kind 和非负整数 count。
- runtime 与 lease 存在时必须校验 N31 profile、AVD、serial、port、固定 lock/log
  路径和进程候选身份；旧 binding 返回 `PRODUCTION_BINDING_DRIFT`。
- staging profile 目录存在时必须有严格 `binding.json`；截图和日志只能在其固定子目录
  中计数。
- 每层目录执行 `lstat` 与 `realpath`；symlink、特殊文件、路径漂移、inode/mtime
  变化、超预算、畸形 JSON 或检查前后身份变化均返回
  `PRODUCTION_RESIDUE_INSPECTION_FAILED`。
- inspector 只读且不清理任何文件、锁或进程。它不执行 ADB、shell、kill、设备发现、
  emulator 启动或任意用户输入路径。

## 当前能力边界

当前主线没有可供 N52 host production CLI 使用并能在 stop 后证明 App 数据、
Bridge session、测试服务和进程身份的完整类型化 provider。因此默认 residue
capability 必须保持 unavailable，production preflight 的 emulator 启动数为零。
固定文件系统 inspector 仍作为 concrete 安全实现交付，并由 fake typed providers
验证；这不代表真实 N52 设备矩阵已完成。

## 允许修改

- `.trae/specs/n52-production-residue-provider/`
- `test-lab/matrix/local/src/production-adapters.mjs`
- `test-lab/matrix/local/src/` 下必要新增 residue 模块
- `test-lab/matrix/local/test/`

## 禁止修改

- N47 runner、Android、Go、workflow、`docs/next-phase-tasks.md`
- SDK、AVD、cache、设备或 emulator 状态
- 既有 N52 路线图、MVP checklist 或真实设备验收勾选

## 验收

- RED 先证明 concrete residue 模块缺失。
- fake 覆盖八类零值与非零值、固定 package、symlink、目录身份竞态、预算、stale
  runtime/lease/process、截图、日志和 binding/fingerprint 漂移。
- 保留既有 N52 41 项测试，并运行 N47、N34 回归、`make comments`、
  `git diff --check`、`make test`、`make verify` 和 `make build`。
- 不操作设备、不启动 emulator、不运行真实 300 轮，不勾选 N52。
