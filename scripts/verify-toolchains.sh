#!/bin/sh

# 脚本用途：校验仓库布局与固定工具链版本；metadata-only 模式供无需本机 SDK 的快速检查使用。
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
METADATA_ONLY=false

if [ "${1:-}" = "--metadata-only" ]; then
  METADATA_ONLY=true
fi

assert_file_value() {
  file=$1
  expected=$2

  if [ ! -f "$ROOT_DIR/$file" ]; then
    printf 'missing required file: %s\n' "$file" >&2
    exit 1
  fi

  actual=$(tr -d '\r\n' < "$ROOT_DIR/$file")
  if [ "$actual" != "$expected" ]; then
    printf '%s must contain %s, got %s\n' "$file" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_contains() {
  file=$1
  expected=$2

  if ! grep -F "$expected" "$ROOT_DIR/$file" >/dev/null 2>&1; then
    printf '%s must contain: %s\n' "$file" "$expected" >&2
    exit 1
  fi
}

assert_file_value ".go-version" "1.26.5"
assert_file_value ".java-version" "21"
assert_contains ".tool-versions" "golang 1.26.5"
assert_contains ".tool-versions" "java temurin-21.0.7+6"
assert_contains ".tool-versions" "gradle 9.3.1"
assert_contains "android/gradle/libs.versions.toml" 'agp = "9.1.1"'
assert_contains "android/gradle/libs.versions.toml" 'compileSdk = "36"'
assert_contains "android/gradle/libs.versions.toml" 'targetSdk = "36"'
assert_contains "android/gradle/libs.versions.toml" 'minSdk = "30"'

for path in protocol cmd internal android skills docs tests integration-tests; do
  if [ ! -d "$ROOT_DIR/$path" ]; then
    printf 'missing required directory: %s\n' "$path" >&2
    exit 1
  fi
done

printf 'repository metadata is valid\n'

if [ "$METADATA_ONLY" = "true" ]; then
  exit 0
fi

missing=0

check_command() {
  command_name=$1

  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'missing command: %s\n' "$command_name" >&2
    missing=1
  fi
}

check_command git
check_command go
check_command java
check_command adb

if command -v go >/dev/null 2>&1; then
  go_version=$(go version | awk '{print $3}')
  if [ "$go_version" != "go1.26.5" ]; then
    printf 'Go 1.26.5 required, found %s\n' "$go_version" >&2
    missing=1
  fi
fi

if command -v java >/dev/null 2>&1; then
  java_version=$(java -version 2>&1 | awk -F '"' 'NR == 1 {print $2}')
  case "$java_version" in
    21.*) ;;
    *)
      printf 'JDK 21 required, found %s\n' "$java_version" >&2
      missing=1
      ;;
  esac
fi

if [ -n "${ANDROID_HOME:-}" ] || [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [ ! -d "$android_sdk/platforms/android-36" ]; then
    printf 'Android SDK platform 36 is not installed under %s\n' "$android_sdk" >&2
    missing=1
  fi
else
  printf 'ANDROID_HOME or ANDROID_SDK_ROOT is not set\n' >&2
  missing=1
fi

if [ "$missing" -ne 0 ]; then
  exit 1
fi

printf 'local toolchains are ready\n'
