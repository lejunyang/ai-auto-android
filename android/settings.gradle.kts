// 配置用途：固定插件与依赖仓库，并注册主 App、原生 fixture 和离线 Web fixture。
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
