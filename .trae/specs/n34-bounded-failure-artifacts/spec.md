# N34 有界失败产物、脱敏与 Flaky 统计规格

## 目标

为固定 Android Emulator 场景提供可诊断、可复现且自动清理的失败证据。collector
只处理调用方明确提供的文件，不发现设备、不执行 ADB、不上传网络，也不改变生产
App、Bridge 或真机授权行为。

## 结果契约

- `runId`、`scenarioId`、设备元数据、动作时间线、结果、产物清单、预算和保留状态均
  写入严格 JSON Schema，所有对象拒绝未知字段。
- 单轮失败类别只允许 `product`、`device` 或 `infrastructure`；20 轮聚合在通过与
  失败交替或失败类别变化时标记为 `flaky`。
- timeout 与 assertion failure 使用稳定错误码，不把原始异常消息写入摘要。
- 固定输入必须生成字节稳定的统计结果；聚合报告不包含当前时间或随机字段。

## 脱敏边界

- hierarchy、日志和测试报告先按结构递归过滤，再扫描密码、验证码、token、配对码、
  API Key、授权头、显式目标 App 敏感文本及其 URL、JSON、Base64、Base64URL 和
  Hex 编码变体。
- 调用方必须声明目标敏感文本清单完整；声明缺失、文本编码不可判定或过滤后仍命中
  敏感模式时，删除该场景全部暂存产物。
- collector 不包含 OCR，不能自行证明截图安全。PNG 只有在 SHA-256 等输入条件
  成立，且调用进程注入的 `trustedScreenshotVerifier` 结合独立可信上下文明确返回
  `true` 时才可保留；输入 `processorId`、self-hash 或 allowlist 不构成信任锚。
- 默认 CLI 不注入真实 masker verifier，因此含 PNG 输入必须失败关闭；调用方可
  显式省略截图并收集其余证据，但不能由 artifact 输入指定代码或伪造生产信任。
- 失败关闭时只保留严格 Schema 约束的 `REDACTION_UNVERIFIED` 等稳定错误码摘要，
  `uploadAllowed` 固定为 `false`，不保留自由文本、设备内容或原始异常。

## 预算与清理

- 默认单文件 512 KiB、单场景 2 MiB、整轮 16 MiB，调用方可以在受限范围内收紧。
- 文本按 UTF-8 边界确定性截断；不能安全截断的 PNG 直接丢弃并记录稳定原因。
- 场景和整轮预算按固定优先级保留摘要、设备元数据、动作时间线、测试报告、
  hierarchy、日志和截图，任何物理文件都计入预算。
- 成功结果立即删除同场景目录并复核无残留；失败与阻断摘要按 TTL 清理，损坏或无法
  验证保留元数据的目录按隐私优先策略删除。
- 写入使用同一 run 目录下的临时目录与原子重命名；collector 不跟随符号链接。

## 20 轮统计

- 输入必须恰好包含 20 个唯一且连续的 iteration。
- 全部通过为 `passed`；全部失败且类别一致时保持
  `product`、`device` 或 `infrastructure`；通过/失败混合或失败类别混合为
  `flaky`。
- 输出成功率、耗时分位数、重试计数、分类计数和按错误码排序的计数，不执行隐式重试。

## 允许修改

- `.trae/specs/n34-bounded-failure-artifacts/`
- `android/test-control/reporting/`
- `test-lab/artifacts/`
- `.github/workflows/emulator-artifacts.yml`

## 禁止事项

- 不修改根 Gradle、settings、version catalog、N32 core/debug、device fixture、
  公共协议清单、路线图状态或其他 change spec。
- 不提交测试运行产物、截图、日志、secret、APK、缓存或仓库外 SDK 内容。
- workflow 只运行 fake-input 语义 smoke，不声明真实设备或 API 矩阵已通过。

## 验收

- 先观察缺少实现时测试失败，再完成最小实现。
- timeout 与 assertion failure 均生成 PNG、脱敏 hierarchy、短日志窗、测试报告、
  动作时间线和设备元数据；fake-input PNG 只能由测试内受控 verifier 验证。
- 嵌套、大小写与编码敏感值均不可在场景目录中检索到；不可信 PNG 和不可判定文本
  只留下稳定阻断摘要。
- 同一自报 proof 在缺失 verifier、verifier 返回 `false` 或抛错时均失败关闭；
  默认 CLI 不能保留 PNG，显式省略截图时才可保留其余安全证据。
- 单文件、场景和整轮预算确定性生效；成功清理和 TTL 清理后均无残留。
- 20 轮分类在输入顺序变化后输出一致。
- 专用测试、Schema 验证、`git diff --check` 和 `make comments` 通过。
