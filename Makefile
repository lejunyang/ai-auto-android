# 模块用途：统一封装本地与 CI 的协议、Skills、Go、Android 构建和发布入口。
SHELL := /bin/sh
GO ?= go
VERSION ?= dev
DIST_DIR ?= dist
ANDROID_GRADLEW ?= ./android/gradlew
ANDROID_GRADLE_FLAGS ?= --no-daemon

# osdk 工具链入口。Android 构建必须经 osdk exec 才能拿到 JAVA_HOME 与
# ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_AVD_HOME —— osdk.toml 的 pin 只决定
# 「装哪个版本」，不会把这些变量写进当前 shell，直接调 gradlew 会报
# 「SDK location not found」。
OSDK ?= osdk

# 版本一律不在此处重写，只列工具 id，由 osdk 解析 osdk.toml 的 pin。
#
# 这里曾经必须写成 `-t java@21.0.12.1+1` 等完整坐标：`osdk exec -t <tool>`（不带
# @版本）会忽略项目 pin 并按 latest 解析，于是注入 JDK 26 的 JAVA_HOME，Gradle 报
# 「Cannot find a Java installation … languageVersion=21」。该缺陷已在 one-sdk
# 侧修复（bare operand 现在继承项目 pin），故此处回到只列 id 的写法——单一事实
# 来源仍是 osdk.toml，改 pin 不需要同步改 Makefile。
OSDK_ANDROID_TOOLS ?= -t java -t android-platforms -t android-build-tools -t android-platform-tools
OSDK_EXEC_ANDROID ?= $(OSDK) exec $(OSDK_ANDROID_TOOLS) --

# Gradle wrapper 的启动脚本按平台分两份。POSIX 的 `gradlew` 是 shell 脚本，
# Windows 上 osdk exec 直接 CreateProcess 它会得到 os error 193（不是有效的
# Win32 程序），必须换成 `gradlew.bat`。判定用 make 自带的 OS 变量：即使在
# msys make 里，Windows 下它也恒为 Windows_NT（MSYSTEM 在 conda 的 m2 包下为空，
# 不能用）。
ifeq ($(OS),Windows_NT)
ANDROID_GRADLEW_LAUNCHER ?= $(ANDROID_GRADLEW).bat
else
ANDROID_GRADLEW_LAUNCHER ?= $(ANDROID_GRADLEW)
endif


.PHONY: verify doctor fake-adb-smoke protocol-smoke protocol-test skills-sync skills-smoke skills-check comments
.PHONY: go-fmt go-test go-vet test build android-test android-lint android-build android-verify release

verify: fake-adb-smoke protocol-smoke skills-smoke comments
	@./scripts/verify-toolchains.sh --metadata-only

doctor:
	@./scripts/verify-toolchains.sh

fake-adb-smoke:
	@$(GO) test ./internal/adb -run '^(TestDeviceInfoReadsPropertiesForExplicitOnlineDevice|TestDevicesMapsTimeout|TestConnectRejectsInjectionBeforeStartingADB|TestTapWithMultipleDevicesTargetsExplicitSerial)$$'

protocol-smoke:
	@cd protocol && node scripts/validate.mjs

protocol-test: protocol-smoke

skills-sync:
	@node scripts/skills.mjs sync

skills-smoke:
	@node scripts/skills.mjs check
	@$(GO) test ./internal/cli -run '^TestSkillAactlCommandExamplesMatchCurrentCLI$$'

skills-check: skills-smoke

comments:
	@node scripts/check-comment-coverage.mjs
	@node scripts/check-comment-coverage.mjs --self-test

go-fmt:
	@sh ./scripts/check-go-format.sh

go-test:
	@$(GO) test ./...

go-vet:
	@$(GO) vet ./...

test: protocol-test skills-check go-fmt go-test go-vet

build:
	@mkdir -p bin
	@$(GO) build -trimpath -ldflags "-s -w -X github.com/lejunyang/ai-auto-android/internal/cli.Version=$(VERSION)" -o bin/aactl ./cmd/aactl

android-test:
	@$(OSDK_EXEC_ANDROID) "$(ANDROID_GRADLEW_LAUNCHER)" -p android $(ANDROID_GRADLE_FLAGS) testDebugUnitTest

android-lint:
	@$(OSDK_EXEC_ANDROID) "$(ANDROID_GRADLEW_LAUNCHER)" -p android $(ANDROID_GRADLE_FLAGS) lintDebug

android-build:
	@$(OSDK_EXEC_ANDROID) "$(ANDROID_GRADLEW_LAUNCHER)" -p android $(ANDROID_GRADLE_FLAGS) assembleDebug

# 一次 Gradle 调用串起三个任务，复用同一次配置阶段，比分别调用快得多。
android-verify:
	@$(OSDK_EXEC_ANDROID) "$(ANDROID_GRADLEW_LAUNCHER)" -p android $(ANDROID_GRADLE_FLAGS) \
		testDebugUnitTest assembleDebug lintDebug

release: verify
	@VERSION="$(VERSION)" DIST_DIR="$(DIST_DIR)" GO="$(GO)" ANDROID_GRADLEW="$(ANDROID_GRADLEW)" \
		./scripts/release.sh
