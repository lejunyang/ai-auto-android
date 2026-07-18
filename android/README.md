# Android Application

Kotlin 与 Jetpack Compose Android 应用位于此目录。当前包含 Material 3 App
外壳、页面导航、Provider 配置、OpenAI 兼容请求、严格动作解析和 Android
Keystore 加密存储，以及受目标包白名单约束的 AccessibilityService。

无障碍页会先显示读取与操作范围，要求用户明确同意并配置目标包，再跳转
系统设置启用服务。执行层按需生成脱敏 UI 快照，不长期保存平台节点；节点
动作优先于手势，只有协议显式提供时才使用坐标回退，歧义选择器会明确失败。
密码、验证码和支付相关节点不保留文本或内容描述。

工具链基线由 `gradle/libs.versions.toml` 定义，需要 JDK 21 与 Android SDK
36。SDK 路径应通过 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或未提交的
`local.properties` 提供。

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
