# Android 测试报告输入契约

本目录定义 Android instrumentation 或本地 Emulator runner 向 N34 collector 提供
输入的边界。这里不注册生产组件、不增加权限、不执行 ADB，也不允许把原始设备产物
直接当成可上传证据。

## 暂存目录

调用方只可将本场景输入写入：

```text
ARTIFACT_ROOT/.staging/<runId>/<scenarioId>/
  screenshot.input
  hierarchy.input
  log.input
  report.input
```

`runId` 必须是 UUID，`scenarioId` 只允许 ASCII 字母、数字、点、下划线和连字符。
`sources.json` 中只能使用上述目录内的单段相对文件名；collector 拒绝 `..`、绝对
路径、symlink、非普通文件以及 `lstat` 到读取之间身份变化的文件。

## 必需输入

- `run.json` 遵守
  `test-lab/artifacts/schema/scenario-result.schema.json`，记录明确 serial、AVD、
  fingerprint、API、WebView、动作时间线和稳定错误码。
- `sources.json` 精确包含 `screenshot`、`hierarchy`、`log`、`report` 四项。日志窗
  必须与场景起止时间一致且不超过 5 分钟。
- `sensitivity.json` 的 `declaration` 必须为 `complete`，`targetPackages` 必须覆盖
  时间线中的全部目标包，`values` 必须列出 fixture 注入和场景已知的全部敏感文本。

## 截图证明

collector 不实现 OCR，不能从任意 PNG 自行证明敏感区域已遮罩。`screenshot.proof`
是独立遮罩步骤对最终 PNG 签发的待验证声明：

```json
{
  "method": "verified-masked",
  "processorId": "android-ui-masker-v1",
  "artifactSha256": "<最终 PNG 的小写 SHA-256>"
}
```

信任锚必须由调用进程以代码注入 `trustedScreenshotVerifier`，不能来自
`sources.json`、`processorId` allowlist 或输入文件选择的模块。verifier 接收最终
PNG bytes 与 proof，并须结合独立可信上下文验证遮罩任务、processor 身份和最终
hash 后明确返回 `true`。仅计算原始截图 hash、复制已知 `processorId` 或构造匹配
self-hash 都不构成证明；verifier 缺失、返回 `false`、抛错、hash 漂移或上下文
不匹配时，collector 返回 `REDACTION_UNVERIFIED` 并只保留阻断摘要。

独立 CLI 不装载生产 masker，也不允许输入文件指定可执行 verifier。默认 collect
因此不能保留 PNG；尚无可信集成时必须显式使用 `--omit-screenshot`，并让
`sources.json` 只包含 `hierarchy`、`log` 和 `report`。

## 生命周期

调用 collector 后，无论成功还是阻断，`.staging` 场景目录都必须消失。场景通过时
再次调用 `collect` 并提供 `outcome.status=passed`，collector 立即删除同一
`runId/scenarioId/iteration` 的失败目录；场景失败则由 TTL cleanup 删除。
