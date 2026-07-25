# N46 Android APK Inspector 验收清单

- [x] 独立 change spec 已建立，progress 仅追加
- [x] 先观察 inspector 实现缺失的 RED 测试
- [x] 只接受可信 build-tools 目录或成对固定工具绝对路径
- [x] 拒绝仓库内 executable、symlink、错误工具名和不安全工具 identity
- [x] 固定 aapt2、Java 与 apksigner jar identity，`execFile` 使用固定 argv、`shell: false`
- [x] 公开错误仅包含稳定错误码，不泄露路径或工具输出
- [x] package、version、versionCode、ABI、minSdk 严格且唯一
- [x] 签名证书 SHA-256 严格且只允许一个 signer
- [x] aapt2、Java 与 apksigner jar 调用前后替换检测失败关闭
- [x] metadata mismatch 在 verified descriptor 和安装调用前拒绝
- [x] 测试 fixture 不使用 `.apk` 扩展名
- [x] smoke、注释、diff 和允许范围审计通过
- [x] 未把 fake 工具测试描述为真实 APK 或设备验收
