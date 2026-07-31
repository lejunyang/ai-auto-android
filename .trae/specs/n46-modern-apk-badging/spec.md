# N46 现代 APK Badging 兼容规格

## 目标

让固定 Android `aapt2` inspector 严格识别现代真实 APK 输出的
`minSdkVersion:'N'`，同时保持旧 `sdkVersion:'N'` 兼容和缺失/重复/冲突失败关闭。

## 允许修改

- `.trae/specs/n46-modern-apk-badging/`
- `scripts/test-lab/src/apk-inspector.mjs`
- `scripts/test-lab/test/apk-inspector.test.mjs`
- `.trae/specs/n46-android-apk-inspector/progress.md` 的 append-only 证据

## 禁止修改

- 下载器、安装器、ADB argv、工具路径策略和公共路线图
- 猜测 minSdk、忽略缺失字段、接受两个 SDK 字段或解析任意工具输出
- 提交 APK、签名、构建产物或仓库外缓存

## 验收

- 旧 `sdkVersion` 与现代 `minSdkVersion` 分别可严格解析。
- 两者同时出现、重复、缺失、非正整数或超过 100 均拒绝。
- 三个官方真实 APK 可由固定 aapt2/JDK/apksigner inspector 读取六项元数据。
