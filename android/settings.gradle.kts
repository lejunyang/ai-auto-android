// 配置用途：固定仓库并注册主 App、测试 fixture 与 debug-only 控制面安全内核。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AiAutoAndroid"
include(":app")
include(":device-fixture")
include(":web-fixture")
include(":test-control-core")
project(":test-control-core").projectDir = file("test-control/core")
