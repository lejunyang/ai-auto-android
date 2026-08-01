# 哔哩哔哩安全场景契约

本目录提供 N48 的离线计划、报告 Schema、validator、fixture 与测试，不运行设备，
不下载或保存 APK，也不修改 N47 Runner。

## 固定边界

- `fixtures/bilibili-safe.plan.json` 固定哔哩哔哩 `9.5.0` identity、十轮要求和
  15 个安全步骤。
- `fixtures/api33-hierarchy-unavailable.report.json` 是十轮离线 fake，用于验证
  API 33 稳定 blocker、零动作提交、五类清理和统计重算；它不是设备证据。
- 搜索输入使用运行期 `valueRef` 的概念边界，但 query/value 不允许进入本报告。
- consent/privacy/permission/login/update/advertisement/unknown 均在动作前零提交。
- 长按仅在 `noLike/noFavorite/noDownload/noShare` 全部为真时可提交，否则
  `BILIBILI_LONG_PRESS_UNSUPPORTED`。
- 每步最多一次尝试；提交或提交状态未知后必须观察，unknown commit 不重试。

## 验证

```sh
npm run smoke
```

该命令只执行本目录的离线 Schema 与 Node 测试。真实连续十轮仍需在独立设备验收中
完成，不能由 fixture 替代。
