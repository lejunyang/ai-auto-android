// 配置用途：集中声明 Android 子模块使用的插件版本，具体模块按需启用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
