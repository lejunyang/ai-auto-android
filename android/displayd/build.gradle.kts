// 配置用途：构建不安装的 shell 辅助进程 APK，纯逻辑测试仅复用仓库已有 JUnit。
plugins {
    alias(libs.plugins.android.application)
}

android {
    // 纯 Java 进程不需要 AGP 9 默认注入的 Kotlin 标准库，保持设备端无第三方运行依赖。
    enableKotlin = false
    namespace = "dev.aiauto.displayd"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.aiauto.displayd"
        minSdk = 30
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
