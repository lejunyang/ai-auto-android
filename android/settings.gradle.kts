// 固定插件与依赖仓库，并注册主 App 和无副作用设备验收 fixture。
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
