#!/bin/sh

# 脚本用途：校验仓库布局与固定工具链版本；metadata-only 模式供无需本机 SDK 的快速检查使用。
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
METADATA_ONLY=false

if [ "${1:-}" = "--metadata-only" ]; then
  METADATA_ONLY=true
fi

assert_contains() {
  file=$1
  expected=$2

  if ! grep -F "$expected" "$ROOT_DIR/$file" >/dev/null 2>&1; then
    printf '%s must contain: %s\n' "$file" "$expected" >&2
    exit 1
  fi
}

# osdk.toml is the single source of truth for tool versions. Asserting the same
# version in a second file only creates a way for the two to disagree, so this
# checks that the pin exists and is what the Android build needs -- not that some
# other file repeats it.
#
# `.tool-versions` used to be checked here as well. It was removed because it had
# silently rotted: it named `java temurin-21.0.7+6`, a build the upstream index no
# longer publishes, so anything that actually tried to install from it failed.
# That is the failure mode of duplicated version facts, and the reason there is
# now one file.
assert_contains "osdk.toml" 'java = "21.0.12.1+1"'
assert_contains "osdk.toml" 'go = "1.26.5"'
# `.go-version` is the one remaining duplicate, and it is deliberate:
# actions/setup-go reads it directly and cannot read osdk.toml, while the Go-only
# CI jobs are not worth an osdk install. Keep the two in agreement here so the
# duplication cannot drift the way `.tool-versions` did. `.java-version` was
# removed outright because nothing consumed it any more.
go_pin=$(sed -n 's/^go = "\([^"]*\)".*/\1/p' "$ROOT_DIR/osdk.toml" | head -n 1)
go_version_file=$(tr -d ' \r\n' < "$ROOT_DIR/.go-version")
if [ "$go_pin" != "$go_version_file" ]; then
  printf '.go-version (%s) disagrees with the go pin in osdk.toml (%s)\n' \
    "$go_version_file" "$go_pin" >&2
  exit 1
fi
assert_contains "osdk.toml" 'android-platforms = "=android-36"'
assert_contains "osdk.toml" 'android-build-tools = "36.0.0"'
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

# The expected Go version is read from osdk.toml rather than repeated here, so
# bumping the pin needs one edit instead of two that can drift apart.
expected_go=$(sed -n 's/^go = "\([^"]*\)".*/\1/p' "$ROOT_DIR/osdk.toml" | head -n 1)
if [ -z "$expected_go" ]; then
  printf 'osdk.toml does not pin go\n' >&2
  missing=1
elif command -v go >/dev/null 2>&1; then
  go_version=$(go version | awk '{print $3}')
  if [ "$go_version" != "go$expected_go" ]; then
    printf 'Go %s required, found %s\n' "$expected_go" "$go_version" >&2
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
  # `platforms/android-36` is the base package whose AndroidVersion.ApiLevel is
  # exactly 36. Neither `android-36.1` (ApiLevel 36.1) nor `android-36-ext19`
  # (a side-by-side extension reporting `36x`) satisfies compileSdk = 36.
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
