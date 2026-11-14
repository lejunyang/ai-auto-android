#!/bin/sh

# 脚本用途：以显式工具路径启动发布器，统一校验许可、构建同版 APK 并原子生成完整与精简归档。
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
GO_CMD=${GO:-go}
ANDROID_GRADLEW=${ANDROID_GRADLEW:-./android/gradlew}
# 精简包源码构建也禁止隐式下载依赖；缺少本地缓存时明确失败，由开发者预先准备工具链。
if [ "${BUNDLES:-full,slim}" = "slim" ]; then
  export GOPROXY=off GOSUMDB=off GOTOOLCHAIN=local
fi
cd "$ROOT_DIR"
exec "$GO_CMD" run ./scripts/releasebundles --root "$ROOT_DIR" --go "$GO_CMD" --gradlew "$ANDROID_GRADLEW"
