# N52 本地矩阵进度

本文件 append-only，记录 RED/GREEN、300 轮 fake 证据、残留门与真实设备边界。

## Round 1

- worktree `/private/tmp/ai-auto-n52-matrix` 位于 `phase2-n52-local-matrix`，基线
  `d675d13`，开工时干净。
- 已审计 N31 AVD lifecycle、N32 debug control plane、N34 统计、N47 Runner 与
  native/Web/Canvas/app instrumentation。N31 已具备 clean snapshot/serial/
  fingerprint/lease，N32 的 test-only Bridge 明确只读。
- 真实 N47 动作矩阵目前缺少已验收的 emulator production action 授权链和 visual
  production route，因此本轮先实现矩阵 orchestration/schema/aggregation；能力
  缺失必须零启动失败，不能借 N32 只读控制面伪造通过。
- 固定计划为 3 API × 5 核心场景 × 20 轮，共 300 轮，清理残留八类全零。

## Round 2

- RED 先确认 `test-lab/matrix/local/src/matrix.mjs` 与 `report.mjs` 缺失；首轮实现后
  发现失败轮 residue 证据、基础设施错误分类和 fail-fast 状态存在跨字段漂移，均由
  严格报告 validator 拒绝后修复。
- 已实现固定 300 轮 plan、三 profile capability 零启动前置门、显式 serial/API/
  fingerprint/clean snapshot 校验、N47 run-report 绑定、stop/residue 补偿清理和
  N34 二十轮统计。无可信 serial 时不调用清理端口，具备可信 serial 但其他启动元数据
  无效时仍尝试 stop/residue。
- 报告 Schema 只允许固定 profile、scenario、route、page class、错误码和八类残留
  计数；语义层从 runs 重算 profile 与 scenario 统计、清理率、route/page/error、
  fingerprint 稳定性和成功门。畸形输入只返回稳定校验错误，不抛出原始异常。
- fake 测试覆盖 capability 缺失/额外能力、完整 300 轮、产品/设备/取消、场景端口
  异常、无效子报告、start/stop/residue 失败、无可信 serial、未知 action commit、
  capability/fingerprint 漂移、重复轮次、陈旧聚合和自由字段拒绝；N52 为 17/17。
- 后续边界复审补齐输入/端口/时钟、probe 异常、畸形 stop/residue、90% 验收失败和
  多步骤计数上界，冻结测试为 21/21；route/page 汇总按 N47 每轮最多 200 步设置
  有界 Schema，并从 run 结果重算 profile 与 scenario 两层计数。
- 定向验证通过：N47 59/59、N34 49/49、`make comments` 覆盖 365 个手写文件，
  `git diff --check` 通过。新增跨平台 Node 24 离线 workflow 串行执行 N34、N47、
  N52 smoke，明确不替代真实 Emulator 验收。
- 真实 API 30/33/34 的 3 × 5 × 20 设备矩阵未运行。N32 test-only Bridge 仍只读，
  N47 visual production route 与 emulator 动作授权链没有设备验收，因此 N52 主任务、
  真实设备 checklist 和路线图勾选保持未完成。

## Round 3

- 编排内核提交为 `502a5a1`，提交范围仅含 N52 change spec、专用离线 workflow 和
  `test-lab/matrix/local/`。提交后 worktree clean。
- author 与 committer 均为 `lejunyang <lejunyang@qq.com>`；提交消息末尾
  `Co-authored-by: TRAE CLI <noreply@bytedance.com>` 恰好一次。
