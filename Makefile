SHELL := /bin/sh
GO ?= go
VERSION ?= dev
DIST_DIR ?= dist
ANDROID_GRADLEW ?= ./android/gradlew
ANDROID_GRADLE_FLAGS ?= --no-daemon

.PHONY: verify doctor fake-adb-smoke protocol-smoke protocol-test skills-sync skills-smoke skills-check
.PHONY: go-fmt go-test go-vet test build android-test android-lint android-build release

verify: fake-adb-smoke protocol-smoke skills-smoke
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

go-fmt:
	@files=$$(gofmt -l .); \
	if [ -n "$$files" ]; then \
		printf 'Go files require formatting:\n%s\n' "$$files" >&2; \
		exit 1; \
	fi

go-test:
	@$(GO) test ./...

go-vet:
	@$(GO) vet ./...

test: protocol-test skills-check go-fmt go-test go-vet

build:
	@mkdir -p bin
	@$(GO) build -trimpath -ldflags "-s -w -X github.com/lejunyang/ai-auto-android/internal/cli.Version=$(VERSION)" -o bin/aactl ./cmd/aactl

android-test:
	@"$(ANDROID_GRADLEW)" -p android $(ANDROID_GRADLE_FLAGS) testDebugUnitTest

android-lint:
	@"$(ANDROID_GRADLEW)" -p android $(ANDROID_GRADLE_FLAGS) lintDebug

android-build:
	@"$(ANDROID_GRADLEW)" -p android $(ANDROID_GRADLE_FLAGS) assembleDebug

release: verify
	@VERSION="$(VERSION)" DIST_DIR="$(DIST_DIR)" GO="$(GO)" ANDROID_GRADLEW="$(ANDROID_GRADLEW)" \
		./scripts/release.sh
