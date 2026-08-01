# 抖音安全兼容场景契约

本目录提供 N49 当前可自动交付的离线场景策略、兼容报告 Schema、validator、fake
fixtures 和测试。它不操作设备、不启动抖音、不安装 APK，也不代表真实连续 10 轮已经
完成。

## 固定身份

策略和报告都固定绑定 `test-lab/apps/manifests/douyin-39.8.0-arm64.json`：

- package：`com.ss.android.ugc.aweme`
- version：`39.8.0`
- versionCode：`390801`
- ABI：`arm64-v8a`
- APK 与签名证书 SHA-256：由 Schema 使用 `const` 固定

调用方不能传入其他 package、APK、来源、路径或下载地址。

## 报告分类

报告使用固定分类区分：

- `content-change-verified`
- `video-surface`
- `visual-only`
- `login-wall`
- `advertisement`
- `emulator-detected`
- `hierarchy-failure-api33`
- `hierarchy-failure-api34`
- `long-press-unsupported`
- `action-commit-unknown`

视频 Surface、API 33/34 hierarchy failure 与其他观察失败不能自动升级到 visual
action。`visual-only` 也只记录观察分类，必须保持 action commit 为零。

## 动作约束

- 纵向 swipe 只能提交一次，必须绑定 fresh pre observation，并以不同 post
  observation ID 和内容 fingerprint 证明页面内容发生可验证变化。
- 长按可能打开互动菜单，因此默认 `unsupported`，attempt、retry 和 commit 都为零。
- 点赞、评论、关注、发送、分享、收藏和下载节点在每轮及重算汇总中恒为零提交。
- unknown commit 只接受 `ACTION_COMMIT_UNKNOWN`，attempt 为一、retry 为零，不能
  自动重试；仅保留动作前已经解析的 semantic/hybrid route，不允许借此升级 visual。

`capabilities` 只是固定策略目录，不是可执行 DSL。报告不会保存 selector、输入值、
坐标、命令、secret、路径、URL、原始 hierarchy/screenshot、OCR、日志或页面内容。

## 无账号与清理

每轮必须声明 `signed-out`，并保留：

- `recommendationState=dynamic-uncontrolled`
- `personalizationAssessment=unavailable-without-account`

这表示无账号仍记录遥测边界，但不能对动态推荐作确定性或个性化结论。每轮还必须确认
App 数据、session、artifacts 已清除且 snapshot 已恢复。

## Fixture 与真实轮次

`fixtures/n49-douyin.fixture-report.json` 包含 10 个 fake 分类样例，只用于验证契约。
它们的 `source=fixture`，所以 validator 重算得到 `fixtureRuns=10`、`realRuns=0` 和
`completedRealRounds=0`。真实报告一旦出现 `source=real`，必须仅包含严格有序的
iteration `1..10`；fixture 不得与真实轮次混合。

## 验证

```sh
cd test-lab/scenarios/douyin
npm run smoke
```

该命令完全离线。它只读取仓库文本 Schema、fixtures 和现有 manifest，不调用 N47
runner、ADB、设备、APK cache 或网络。
