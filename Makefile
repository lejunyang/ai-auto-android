SHELL := /bin/sh

.PHONY: verify doctor protocol-test test build android-test android-build

verify: protocol-test
	@./scripts/verify-toolchains.sh --metadata-only

doctor:
	@./scripts/verify-toolchains.sh

protocol-test:
	@cd protocol && node scripts/validate.mjs

test:
	@echo "Go tests will be enabled with the CLI implementation."

build:
	@echo "Go builds will be enabled with the CLI implementation."

android-test:
	@echo "Android tests will be enabled with the Android application."

android-build:
	@echo "Android builds will be enabled with the Android application."
