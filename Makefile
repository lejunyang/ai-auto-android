SHELL := /bin/sh
GO ?= go
VERSION ?= dev

.PHONY: verify doctor protocol-test go-test go-vet test build android-test android-build

verify: protocol-test
	@./scripts/verify-toolchains.sh --metadata-only

doctor:
	@./scripts/verify-toolchains.sh

protocol-test:
	@cd protocol && node scripts/validate.mjs

go-test:
	@$(GO) test ./...

go-vet:
	@$(GO) vet ./...

test: protocol-test go-test go-vet

build:
	@mkdir -p bin
	@$(GO) build -trimpath -ldflags "-s -w -X github.com/lejunyang/ai-auto-android/internal/cli.Version=$(VERSION)" -o bin/aactl ./cmd/aactl

android-test:
	@echo "Android tests will be enabled with the Android application."

android-build:
	@echo "Android builds will be enabled with the Android application."
