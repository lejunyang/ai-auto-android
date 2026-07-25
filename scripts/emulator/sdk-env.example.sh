#!/bin/sh

# 脚本用途：统一导出仓库外固定 SDK、AVD 和构建缓存路径，避免依赖系统全局工具或用户目录。
set -eu

: "${SDK_BASE:?Set SDK_BASE to the external versioned SDK directory}"

export GOROOT="$SDK_BASE/go-1.26.5"
export JAVA_HOME="$SDK_BASE/jdk-temurin-21.0.7+6/Contents/Home"
export GRADLE_HOME="$SDK_BASE/gradle-9.3.1/gradle-9.3.1"
export GRADLE_USER_HOME="$SDK_BASE/gradle-cache"
export ANDROID_HOME="$SDK_BASE/android-sdk-36"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="$SDK_BASE/android-avd"
export AACTL_EMULATOR_STATE="$SDK_BASE/android-emulator-state"
export PATH="$GOROOT/bin:$JAVA_HOME/bin:$GRADLE_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
