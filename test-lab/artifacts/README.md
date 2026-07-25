# 有界失败产物与 Flaky 统计

本工具以 Node ESM 独立运行，不依赖公网、Android SDK 或真实设备。默认预算为单文件
512 KiB、单场景 2 MiB、整轮 16 MiB；正常产物与 `summary.json` 的物理字节均计入
预算。成功立即清理，失败最长保留 30 天，实际 TTL 由调用方显式给出。

## Collector

先按 [Android 输入契约](../../android/test-control/reporting/README.md) 写入
`.staging` 和三个 JSON 控制文件，再运行：

```sh
node test-lab/artifacts/src/cli.mjs collect \
  --artifact-root "$ARTIFACT_ROOT" \
  --run "$INPUT_ROOT/run.json" \
  --sources "$INPUT_ROOT/sources.json" \
  --sensitivity "$INPUT_ROOT/sensitivity.json" \
  --omit-screenshot \
  --ttl-ms 86400000
```

可用 `--file-bytes`、`--scenario-bytes`、`--run-bytes` 收紧预算。命令只输出受控
JSON；正常失败的 `ok=true` 表示产物通过脱敏与预算校验并可进入后续受控上传流程，
不表示测试场景成功。阻断时退出码为 1，`blocked/summary.json` 只含稳定错误码，
不得上传 `.staging`、原始输入或未验证 PNG。

CLI 不配置截图遮罩器的进程内信任锚。默认读取含截图的输入时，即使
`sources.json` 自报已知 `processorId` 和匹配 SHA-256，也必须以
`REDACTION_UNVERIFIED` 失败关闭。调用方尚未通过代码注入可信
`trustedScreenshotVerifier` 时，只能显式使用 `--omit-screenshot`，并从
`sources.json` 删除 `screenshot` 项；collector 随后只保留其余已验证证据。

嵌入式调用只有在受控进程配置 `trustedScreenshotVerifier(finalPngBytes, proof)`
且该函数依据独立可信上下文严格返回 `true` 时才保留 PNG。proof 是待验证输入，
不能携带或选择可执行代码，也不能仅靠 processor allowlist 与 self-hash 建立信任；
verifier 缺失、返回非 `true` 或抛错均只留下稳定阻断摘要。

## 20 轮统计

输入是恰好 20 个对象的 JSON 数组，每项包含同一 `runId`、`scenarioId`、连续
`iteration`、`status`、明确失败 `category`、稳定 `errorCode`、`durationMs` 和
`retries`。调用方式：

```sh
node test-lab/artifacts/src/cli.mjs stats \
  --input "$INPUT_ROOT/twenty-runs.json" \
  --output "$REPORT_ROOT/statistics.json"
```

全部通过为 `passed`；全部失败且类别一致时为 `product`、`device` 或
`infrastructure`；通过/失败混合或失败类别混合为 `flaky`。相同输入即使顺序不同，
输出字节一致。

## TTL 清理

```sh
node test-lab/artifacts/src/cli.mjs cleanup \
  --artifact-root "$ARTIFACT_ROOT"
```

cleanup 删除已过期、损坏或无法验证保留元数据的场景目录，并复核目录不存在。它不会
跟随 `runs`、run 或 scenario symlink；发现链接时只删除链接本身。

## 本地验证

```sh
npm run smoke --prefix test-lab/artifacts
```

该 smoke 使用 fake input 验证 Schema、脱敏、预算、清理、路径安全和统计，不启动
Emulator，也不能作为 API 30/33/34 或真实设备矩阵证据。
