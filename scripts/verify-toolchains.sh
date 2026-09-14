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

# osdk.toml 是工具版本的唯一事实来源。在第二个文件里重复同一个版本，只会多出一条
# 让两者相互矛盾的途径，所以这里校验的是「pin 存在、且是 Android 构建需要的那个」，
# 而不是「某个别的文件也写了同一个版本」。
#
# 这里原先还校验 `.tool-versions`，已随该文件一并移除：它早已静默腐坏 —— 其中写的
# `java temurin-21.0.7+6` 上游索引已不再发布，任何真的按它安装的动作都会失败。而旧
# 校验只断言「文件里包含这个字符串」，从不去解析它，所以没人发现。这正是版本事实被
# 重复声明时的典型失效方式，也是现在只保留一个来源的原因。
assert_contains "osdk.toml" 'java = "21.0.12.1+1"'
assert_contains "osdk.toml" 'go = "1.26.5"'
# `.go-version` 是仅存的一处重复，且是刻意保留：actions/setup-go 直接读它、读不了
# osdk.toml，而纯 Go 的那几个 CI job 不值得为此装一次 osdk。因此在这里断言两者一致，
# 使这处重复不会像 `.tool-versions` 那样悄悄漂移。`.java-version` 则已直接删除 ——
# 它不再被任何地方消费。
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

# 期望的 Go 版本从 osdk.toml 读取，而不是在这里再写一遍：升版本只需改一处，
# 不会留下两处可能互相漂移的声明。
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
  # `platforms/android-36` 是 AndroidVersion.ApiLevel 恰为 36 的基础平台包。
  # `android-36.1`（ApiLevel 为 36.1）与 `android-36-ext19`（side-by-side 扩展包，
  # ApiLevel 报告为 `36x`）都不满足 compileSdk = 36，故不能用它们替代。
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
