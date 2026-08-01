# N44 桌面可信视觉 Observation 规格

## 目标

为共享 MCP 增加只读 `android_visual_target_propose`，在一次明确设备调用中绑定
稳定前台包、UIAutomator hierarchy、PNG、屏幕旋转与短生命周期 observation，再
返回视觉候选而不执行任何动作。

## 数据与安全边界

- 输入只接受明确 `device`、`expectedPackage`；不接受坐标、动作、shell、路径或
  任意命令。
- 采集顺序固定为设备身份、hierarchy、PNG、hierarchy、设备身份；前后设备、
  hierarchy 摘要、前台包或 rotation 任一漂移即失败关闭。
- PNG 尺寸来自已验证图片，UIAutomator rotation 映射到自然方向屏幕；crop 固定为
  完整 capture plane，不猜测系统栏或窗口偏移。
- hierarchy 只保留 role、最小 label、归一化 bounds、sensitive 和 children；
  password/sensitive 节点及后代不返回 label。
- 可信 verifier 必须消费由稳定设备与双 hierarchy 生成的一次性 attestation，
  不能只比较 PNG self-hash。
- 生产 provider 只从同 observation 的非敏感 hierarchy 生成 template 候选；
  没有候选、低置信度或歧义时零提交失败关闭。
- MCP 返回的图片只在调用响应期间存在；成功、失败、取消和 server 关闭均撤销
  observation 并清零 registry、adapter 与 MCP 图片副本。

## 允许修改

- `.trae/specs/n44-desktop-visual-observation/`
- `internal/visual/`
- `internal/mcpserver/`
- `internal/cli/` 的 MCP 生产接线与对应测试
- N44 相关 README、架构、协议说明与现有 append-only progress
- `skills/android-device-observation/`、对应 `.trae` 镜像与 `scripts/skills.mjs`
- `docs/next-phase-tasks.md` 仅由主线程单点更新

## 禁止事项

- 不修改 Android App、recording、N41/N45、公共动作协议或 Bridge 认证。
- 不执行动作，不自动点击候选，不开放 raw ADB、shell、任意文件路径或外部 provider。
- 不持久化 PNG、hierarchy、候选或 observation，不提交测试图片和运行产物。
- 不把单元测试或 MCP 注册描述为真机兼容矩阵。

## 验收

- RED/GREEN 覆盖稳定采集、设备/包/hierarchy/rotation 漂移、敏感节点、预算与清零。
- 共享 MCP 恰好公开六个严格 Schema 工具，visual 工具为 read-only 且无 execute 字段。
- fake ADB 证明固定 argv 和调用顺序；N31 emulator 单次只读调用验证同 observation
  PNG、package、screen、rotation、hierarchy 与候选关联。
- Go 定向与 race、`make test`、`make verify`、`make build`、comments 和 diff-check
  通过，最终设备与临时产物为零。
