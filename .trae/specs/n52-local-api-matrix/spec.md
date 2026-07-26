# N52 本地 API 30/33/34 矩阵规格

## 目标

在 N31 固定 AVD 上编排 native、WebView、Canvas、录制编辑器和回放五类核心场景，
每个 API/场景连续运行 20 次，生成严格机器报告、兼容统计和可审计残留清理结论。
本 change spec 先实现并验证 orchestration 内核；真实 emulator 只有满足生产动作授权
与场景 adapter 前置条件后才能运行。

## 固定矩阵

- Profile 恰好为 `api-30`、`api-33`、`api-34`，API level 分别为 30、33、34。
- 场景恰好为 `native-fixture`、`webview-fixture`、`canvas-fixture`、
  `recording-editor` 和 `recording-replay`。
- 每个 profile/scenario 恰好 20 个连续 iteration，总计 300 轮；不得隐式 skip。
- 每个场景声明所需 capability，开始矩阵前一次性验证全部 profile 能力；任一缺失时
  设备启动数为零。

## 单轮契约

1. lifecycle `startClean` 必须返回显式 serial、profile/API、N31 fingerprint、
   snapshot=`clean` 和可用 capability。
2. 场景端口接收该短期 context，返回符合 N47 `run-report` 的结果；scenario/profile/
   iteration 必须与计划逐字一致。
3. 无论场景成功、失败或取消都调用 `stop`；stop 失败后不得继续后续轮次。
4. stop 后 residue 端口必须报告 App 数据已清、Bridge session/测试服务/截图/日志/
   owned process/runtime/lease 均为零。
5. residue 不干净时清理率失败并立即停止，不能把有残留的后续结果加入成功率。

## 结果

- 每个 API/场景输出 20 轮 N34 统计、N47 route/page/error 汇总、成功率、耗时和重试。
- 每个 API 汇总 capability、通过/失败/取消、route 和稳定错误码差异。
- 矩阵通过条件：计划 300 轮全部实际执行；每个核心场景成功率不低于 95%；清理率
  100%；无未知 action commit；所有 profile fingerprint 和 snapshot 一致。
- 报告拒绝自由异常、设备内容、截图、hierarchy、日志、selector、坐标和 secret。

## 安全边界

- Orchestrator 不运行 shell、raw ADB、任意 Gradle task 或未声明命令。
- lifecycle/scenario/residue 均为类型化注入端口；orchestrator 不发现设备、不选择
  默认 serial、不修改真机授权。
- N32 test-only Bridge 当前只读，不能作为动作场景通过证据。真实矩阵必须使用明确
  test-only 动作端口或用户授权的 production Bridge，缺失时稳定失败而非跳过。
- 设备农场、scrcpy、第三方 APK、账号和公网不属于本任务。
- fake 300 轮证明编排/报告语义，不等于 API 设备矩阵验收。

## 允许修改

- `.trae/specs/n52-local-api-matrix/`
- `test-lab/matrix/local/`
- N52 专用 workflow 与测试

## 禁止修改

- N31 runner、N32 控制面、N34 collector、N47 Runner、Android 生产/fixture 代码
- 云 Provider、凭据、第三方 APK、设备运行产物和公共路线图

## 验收

- RED 测试先证明矩阵模块缺失。
- fake 300 轮覆盖完整成功、capability 缺失零启动、单轮 product/device/
  infrastructure/cancel、未知提交、stop 失败、残留失败和错误报告拒绝。
- 同一输入顺序变化后聚合字节一致；计划中任何缺轮、重复轮或 skip 失败关闭。
- N52 smoke、N47/N34 Schema、comments 和 diff-check 通过。
- 真实 3 API × 5 场景 × 20 轮设备证据保持未勾选，直到实际运行。
