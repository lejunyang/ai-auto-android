# N52 Production Matrix Ports 与固定 CLI 规格

## 目标

在既有 N52 纯编排内核之上增加 production 组装层与唯一固定 CLI，把 N31 lifecycle、
N47 scenario provider 和八类 residue inspector 映射为窄类型端口。当前没有 concrete
provider 的能力必须在任何 emulator 启动前稳定失败关闭，禁止以 fake 或 skip 冒充
production。

## 固定入口

- CLI 不接受任何参数、环境变量覆盖或配置文件覆盖；任意 argv 均返回
  `PRODUCTION_CLI_ARGUMENT_FORBIDDEN`。
- 工具根固定为 `/Volumes/aigo S7 Media/SDK/android-tools`，报告固定写入
  `emulator-state/reports/n52-production-matrix.json`。
- profile、scenario、iteration 只允许既有 API 30/33/34 × native/WebView/Canvas/
  recording editor/recording replay × 20 轮，共 300 轮。
- CLI 不接受 serial、task、scenario、action、iteration、skip、报告路径或工具路径；
  serial 只能由 N31 clean lifecycle 返回。

## Production Contract

- 注入的 adapter factory 只创建 `lifecycle`、`scenario`、`residue` 三类 adapter 和
  有界 `close`。
- 三类 adapter 对每个固定 profile 先执行 capability probe；任一 provider 缺失、
  能力缺失、身份漂移或畸形返回均在 `startClean` 调用数为零时失败。
- lifecycle 只映射 `startClean` 与 `stop`。启动结果必须绑定固定 profile/API、明确
  serial、SHA-256 fingerprint 和 `clean` snapshot；stop 必须回显同一绑定。
- scenario 只暴露五个固定方法，由组装层按固定 scenario ID 分派。结果必须回显同一
  serial/fingerprint/snapshot 绑定并携带严格 N47 report。
- residue 只暴露 `appData`、`bridgeSessions`、`testServices`、`screenshots`、`logs`、
  `ownedProcesses`、`runtimeFiles`、`leases` 八个固定 inspector。每项必须回显同一
  绑定和非负整数，不接受自由检查名或设备内容。
- 任意 provider 只可返回稳定错误码；组装层不得降级到 raw ADB、shell、任意 action、
  默认 serial、设备发现或 fake。

## 当前接线边界

- 接入现有 N31 `EmulatorRunner` lifecycle：固定外置 SDK/AVD/state/Java 根，从 clean
  snapshot 启动，读取 marker，返回 serial/fingerprint，并以明确 profile 停止。
- 当前主线没有可覆盖五场景的 N47 production fixture provider，默认 factory 返回
  `PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE`。
- 当前主线没有可证明八类残留全零的 production residue provider，默认 factory 返回
  `PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE`；不得硬编码零。
- recording editor/replay 的设备入口与 N47 visual/recording production route 尚未
  完整接线，因此 production CLI 当前预期在 capability 阶段零启动失败。

## 报告与清理

- 只有 N52 内核返回严格报告且 adapter session 成功关闭后才发布报告。
- 报告使用同目录临时文件、`0600`、文件同步和原子 rename；失败必须删除临时文件，
  不覆盖已有报告。
- N31 `startClean` 在已启动后发生 marker、fingerprint 或返回校验失败时必须尝试
  stop；stop 失败不得伪报清理成功。
- 不运行真实 300 轮，不把 fake 300 轮测试描述为设备证据。

## 允许修改

- `.trae/specs/n52-production-matrix-ports/`
- `test-lab/matrix/local/` 下的 source、test、package 与必要 Schema

## 禁止修改

- workflow、`docs/next-phase-tasks.md`、N47 runner/scripts、N48-N51
- Android、Go、SDK、AVD、cache、设备和 emulator 状态
- 既有 N52 完成勾选与真实设备验收结论

## 验收

- RED 先证明 production CLI/ports 模块缺失。
- 定向 fake 测试覆盖无参数 CLI、固定配置、capability 零启动、五场景分派、八类
  residue 顺序、300 轮计划、binding/fingerprint drift、unknown commit、stop/
  residue fail-fast、N31 start 错误清理和报告原子发布/临时文件清理。
- 既有 N52 Schema 与 21 项测试、N47 59 项、N34 49 项及
  `make comments test verify build` 通过。
- 不操作设备、不运行真实 300 轮；N52 与真实设备 checklist 保持未勾选。
