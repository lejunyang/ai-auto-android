// 配置用途：独立构建 N32 纯 JVM 安全内核，并提供 release APK 零入口检查命令。
plugins {
    `java-library`
}

group = "dev.aiauto.testcontrol"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

tasks.register<JavaExec>("verifyReleaseApk") {
    group = "verification"
    description = "检查 release APK 不包含 N32 测试控制入口"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.aiauto.testcontrol.core.ReleaseApkInspector")
    doFirst {
        val releaseApk = providers.gradleProperty("releaseApk").orNull
            ?: error("必须通过 -PreleaseApk=/absolute/path/app-release.apk 指定制品")
        args(releaseApk)
    }
}
