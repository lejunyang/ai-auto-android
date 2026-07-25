// 配置用途：构建完全离线的 WebView 与 Canvas 测试 App，并运行资源和网络边界单测。
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.aiauto.webfixture"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.aiauto.webfixture"
        minSdk = 30
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // instrumentation runner 必须进入测试 APK，不能依赖 ext-junit 的非契约传递关系。
    androidTestImplementation(libs.androidx.test.runner)
}
