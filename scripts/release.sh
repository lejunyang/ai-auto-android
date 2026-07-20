#!/bin/sh

# 构建跨平台 CLI 与 Android debug APK，并在临时目录中生成完整校验和后原子发布。
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${VERSION:-dev}
DIST_DIR=${DIST_DIR:-dist}
GO_CMD=${GO:-go}
ANDROID_GRADLEW=${ANDROID_GRADLEW:-./android/gradlew}

case "$VERSION" in
  ""|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*)
    printf 'VERSION must start with a letter or number and contain only letters, numbers, dots, underscores, and hyphens\n' >&2
    exit 2
    ;;
esac

case "$DIST_DIR" in
  ""|"."|".."|"/"|./*|*/./*|*/.|../*|*/../*|*/..|*/)
    printf 'DIST_DIR must identify a dedicated normalized release directory\n' >&2
    exit 2
    ;;
esac

case "$DIST_DIR" in
  /*) OUTPUT_DIR=$DIST_DIR ;;
  *) OUTPUT_DIR=$ROOT_DIR/$DIST_DIR ;;
esac

OUTPUT_PARENT=$(dirname -- "$OUTPUT_DIR")
STAGING_DIR=$OUTPUT_DIR.tmp.$$
mkdir -p "$OUTPUT_PARENT"
rm -rf "$STAGING_DIR"
mkdir "$STAGING_DIR"

file_list=$STAGING_DIR/.release-files
checksum_tmp=$STAGING_DIR/.SHA256SUMS
trap 'rm -rf "$STAGING_DIR"' EXIT HUP INT TERM
: > "$file_list"

cd "$ROOT_DIR"

ldflags="-s -w -X github.com/lejunyang/ai-auto-android/internal/cli.Version=$VERSION"
for target in darwin/arm64 darwin/amd64 windows/amd64 linux/amd64; do
  goos=${target%/*}
  goarch=${target#*/}
  suffix=
  if [ "$goos" = "windows" ]; then
    suffix=.exe
  fi

  name=aactl_"$VERSION"_"$goos"_"$goarch""$suffix"
  printf 'building %s\n' "$name"
  CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch "$GO_CMD" build \
    -trimpath \
    -ldflags "$ldflags" \
    -o "$STAGING_DIR/$name" \
    ./cmd/aactl
  printf '%s\n' "$name" >> "$file_list"
done

printf 'building Android debug APK\n'
"$ANDROID_GRADLEW" -p "$ROOT_DIR/android" --no-daemon assembleDebug

apk_source=$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk
if [ ! -f "$apk_source" ]; then
  printf 'Android build did not produce %s\n' "$apk_source" >&2
  exit 1
fi

apk_name=ai-auto-android_"$VERSION"_debug.apk
cp "$apk_source" "$STAGING_DIR/$apk_name"
printf '%s\n' "$apk_name" >> "$file_list"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{print $NF}'
  else
    printf 'sha256sum, shasum, or openssl is required\n' >&2
    exit 1
  fi
}

: > "$checksum_tmp"
while IFS= read -r name; do
  hash=$(sha256_file "$STAGING_DIR/$name")
  printf '%s  %s\n' "$hash" "$name" >> "$checksum_tmp"
done < "$file_list"
mv "$checksum_tmp" "$STAGING_DIR/SHA256SUMS"
rm "$file_list"

rm -rf "$OUTPUT_DIR"
mv "$STAGING_DIR" "$OUTPUT_DIR"
trap - EXIT HUP INT TERM

printf 'release artifacts written to %s\n' "$OUTPUT_DIR"
