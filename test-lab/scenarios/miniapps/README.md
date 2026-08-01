# 小程序分级探索分类契约

本目录提供 N51 当前可自动交付的离线报告 Schema、分类 validator、固定 fixture 和测试。
它不操作设备、不启动微信或支付宝，也不代表任何真实小程序已经完成探索。

## 报告结构

- `schema/exploration-report.schema.json`：严格 JSON Schema，所有 object 禁止未知字段。
- `src/report-validator.mjs`：从探针重算四级分类、失败关闭原因和全部统计。
- `fixtures/n42-n43-classification.report.json`：只用于验证契约的 N42/N43 fixture。
- `src/schema-check.mjs`：递归检查严格对象，并验证 fixture、原始内容和任意动作反例。

每个样例必须绑定：

1. `sampleId`。
2. 宿主 `packageName`、`versionName`、`versionCode`。
3. `pageId`、`sampleIdentity` 与页面 `fingerprintSha256`。
4. 每个 probe 内完全重复的 `binding`。

任何 sample、宿主或页面绑定漂移都会重算为 `unsupported`。页面 `state=unknown` 或存在
`confidence=low` 的 probe 也会失败关闭；低置信度 probe 的 `actionCommits` 必须为零。

## 四级分类

| 分类 | 重算条件 |
| --- | --- |
| `full-semantic` | click、input、scroll 三类语义 probe 均为高置信度且已验证 |
| `hybrid` | 至少一种但少于三种语义 probe 已验证，且 visual probe 已验证 |
| `visual-only` | 无语义 probe 已验证，visual probe 已验证 |
| `unsupported` | 无可验证 route，证据不足，或触发任一失败关闭条件 |

报告中的 `decision` 和 `statistics` 不是可信输入。validator 会从 `probes` 重算
`decision`，再从样例重算总数、fixture 数、真实宿主数、四级统计和失败关闭数量。

## 数据最小化

- hierarchy 只允许 SHA-256、原始字节数、节点计数与截断标记。
- screenshot 只允许 SHA-256、原始字节数、尺寸与截断标记。
- 两类摘要的 `retainedBytes` 必须为 `0`，表示报告不持久化原始内容。
- 禁止路径、URL、OCR 文本、原始 XML/图片、selector、坐标、输入、命令、deep link、
  自由异常和 secret。
- probe `type` 仅允许四种分类证据标签，不包含动作参数，也不是可执行动作 DSL。

## Fixture 与真实证据

`dev.aiauto.webfixture` 必须声明：

- `source=fixture`
- `platform=n42-n43-web-fixture`
- `fixtureSource=N42_N43`

fixture 可以验证分类器，但 `statistics.realSamples`、`realHostCounts` 和
`realClassificationCounts` 必须排除它。只有绑定 `com.tencent.mm` 或
`com.eg.android.AlipayGphone` 的受控报告才能计入真实宿主统计；即使结构验证通过，
仍需另行提供真实宿主、公开无登录页面与无副作用动作的设备证据。

## 验证

```sh
cd test-lab/scenarios/miniapps
npm run smoke
```

该命令完全离线。它不会下载 APK、访问账号、发现设备或猜测 deep link。
