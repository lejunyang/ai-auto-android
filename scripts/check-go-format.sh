#!/bin/sh

# 脚本用途：跨平台检查所有 Go 源码使用 gofmt 格式，并输出稳定的仓库相对文件列表。
set -eu

files=$(gofmt -l .)
if [ -n "$files" ]; then
    printf 'Go files require formatting:\n%s\n' "$files" >&2
    exit 1
fi
