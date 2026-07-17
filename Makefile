SHELL := /bin/sh

.PHONY: verify doctor test build android-test android-build

verify:
	@./scripts/verify-toolchains.sh --metadata-only

doctor:
	@./scripts/verify-toolchains.sh

test:
	@echo "Go tests will be enabled with the CLI implementation."

build:
	@echo "Go builds will be enabled with the CLI implementation."

android-test:
	@echo "Android tests will be enabled with the Android application."

android-build:
	@echo "Android builds will be enabled with the Android application."
