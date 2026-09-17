#!/usr/bin/env python3
"""功能用途：向 debug 变体设备注入无敏感测试 fixture（脚本 + 会话历史）。

仅用于 UI 视觉验证，数据全部为 test-only 无敏感内容。
通过 adb run-as 写入应用私有目录，不影响 release 变体。

用法：
    python scripts/ui-validation/inject-fixtures.py --serial emulator-5554
    python scripts/ui-validation/inject-fixtures.py --serial emulator-5554 --clean
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import uuid
from pathlib import Path

PACKAGE = "dev.aiauto.android"


def run_as(serial: str, cmd: str) -> str:
    """通过 adb run-as 执行命令。"""
    full = ["adb", "-s", serial, "shell", "run-as", PACKAGE] + cmd.split()
    proc = subprocess.run(full, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"run-as failed: {proc.stderr[-300:]}")
    return proc.stdout


def write_file(serial: str, remote_path: str, content: str) -> None:
    """通过 run-as 写入文件（用 base64 避免 shell 转义问题）。"""
    import base64
    b64 = base64.b64encode(content.encode("utf-8")).decode("ascii")
    # 先写 base64 临时文件，再 base64 -d 解码
    run_as(serial, f"sh -c 'echo {b64} > {remote_path}.b64'")
    run_as(serial, f"sh -c 'base64 -d {remote_path}.b64 > {remote_path}'")
    run_as(serial, f"rm {remote_path}.b64")


def make_script(name: str, target: str, steps_n: int = 2) -> dict:
    """生成无敏感测试脚本。使用 app.launch 类型（参数最简单，易通过校验）。"""
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    steps = []
    for i in range(steps_n):
        steps.append({
            "id": str(uuid.uuid4()),
            "recordedAtMs": int(time.time() * 1000) + i,
            "provenance": "manual",
            "action": {
                "type": "app.launch",
                "params": {"packageName": target},
            },
            "notes": f"debug-only fixture step {i+1}",
        })
    return {
        "id": str(uuid.uuid4()),
        "schemaVersion": "1.1",
        "revision": 1,
        "name": name,
        "description": "debug-only visual test fixture",
        "targetPackages": [target],
        "createdAt": now,
        "updatedAt": now,
        "provenance": "manual",
        "requirements": {
            "minApiLevel": 30,
            "capabilities": ["accessibility.snapshot"],
        },
        "steps": steps,
    }


def make_history(phase: str, target: str, step_count: int) -> dict:
    """生成无敏感会话历史记录。task 固定为「自动化任务」（安全策略）。"""
    now_ms = int(time.time() * 1000)
    return {
        "id": str(uuid.uuid4()),
        "task": "自动化任务",
        "targetPackage": target,
        "phase": phase,
        "completionSummary": None,
        "failureMessage": None,
        "stepCount": step_count,
        "startTimeMs": now_ms - 60000,
        "endTimeMs": now_ms,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="注入 debug fixture")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--clean", action="store_true", help="清理所有 fixture")
    args = parser.parse_args()

    # 确保目录存在
    run_as(args.serial, "mkdir -p files/recordings files/session-history")

    if args.clean:
        # 同时清理 0 字节的 .<id>.json.lock 写入锁残留（点开头隐藏文件需单独 glob）。
        run_as(
            args.serial,
            "sh -c 'rm -f files/recordings/*.json files/recordings/*.lock "
            "files/recordings/.*.lock files/session-history/*.json "
            "files/session-history/*.lock files/session-history/.*.lock'",
        )
        print("已清理所有 fixture 与锁文件")
        return 0

    # 注入 2 个测试脚本
    scripts = [
        make_script("整理稍后阅读", "com.example.reader", 3),
        make_script("每日打卡", "com.example.checkin", 2),
    ]
    for s in scripts:
        path = f"files/recordings/{s['id']}.json"
        write_file(args.serial, path, json.dumps(s, ensure_ascii=False))
        print(f"  脚本: {s['name']} -> {path}")

    # 注入 2 条历史记录（完成 + 失败/需处理）
    histories = [
        make_history("Completed", "com.example.reader", 5),
        make_history("Failed", "com.example.checkin", 2),
    ]
    for h in histories:
        path = f"files/session-history/{h['id']}.json"
        write_file(args.serial, path, json.dumps(h, ensure_ascii=False))
        print(f"  历史: {h['phase']} -> {path}")

    print(f"\n注入完成：{len(scripts)} 脚本 + {len(histories)} 历史记录")
    print("清理方式：python scripts/ui-validation/inject-fixtures.py --serial <serial> --clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
