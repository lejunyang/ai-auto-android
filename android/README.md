# Android Application

Kotlin 与 Jetpack Compose Android 应用位于此目录。当前包含 Material 3 App
外壳、页面导航、Provider 配置、OpenAI 兼容请求、严格动作解析和 Android
Keystore 加密存储；AccessibilityService 从 Task 6 开始实现。

工具链基线由 `gradle/libs.versions.toml` 定义，需要 JDK 21 与 Android SDK
36。SDK 路径应通过 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或未提交的
`local.properties` 提供。

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
