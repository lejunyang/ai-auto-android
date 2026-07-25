# N46 验收清单

- [x] 独立 change spec 已建立且 progress 仅追加
- [x] manifest 和 source 对象严格拒绝未知字段
- [x] HTTPS 与 user-provided 来源契约通过，缺失或不安全来源被拒绝
- [x] cache 根必须显式、绝对、仓库外且不能经符号链接逃逸
- [x] artifact 按 SHA-256 定位并在 inspector 和 runner 前校验 identity、hash 与 size
- [x] package、version、versionCode、ABI、minSdk 和签名摘要全部匹配
- [x] 成功返回类型化 verified descriptor，默认不安装
- [x] 固定 argv provider 不开放任意 shell 或命令
- [x] clean AVD 前置失败时零安装，最终清理或恢复失败时失败关闭
- [x] 测试 fixture 不使用 `.apk` 扩展名且仓库不包含 APK
- [x] `.gitignore`、工作区和 Git 历史 APK 扫描通过
- [x] 专用测试、`make comments`、`git diff --check` 和范围审计通过
- [x] 未把 fake 测试描述为真实设备或真实 APK 验收
