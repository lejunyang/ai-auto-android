"""功能用途：从 uiautomator dump 解析元素真实坐标，供设备交互验证使用。

用户明确指出：此前用错了底部导航坐标（y=1853，实际 y=2272），导致"验证"根本没点到控件。
本脚本强制从当次 dump 解析坐标，不允许猜测。

用法：
    python scripts/ui-validation/find-element.py --serial emulator-5554 --text 助理
    python scripts/ui-validation/find-element.py --serial emulator-5554 --list-clickable
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def run_adb(serial: str, args: list[str]) -> str:
    """执行 adb 命令并返回 stdout。"""
    proc = subprocess.run(
        ["adb", "-s", serial] + args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {proc.stderr[-400:]}")
    return proc.stdout


def foreground_package(serial: str) -> str:
    """读取当前前台包名，用于确认操作对象正确。"""
    out = run_adb(serial, ["shell", "dumpsys", "window"])
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+)/(\S+)\}", out)
    return m.group(1) if m else "<unknown>"


def dump(serial: str) -> ET.Element:
    """获取界面层级 XML。"""
    remote = "/sdcard/_find_dump.xml"
    run_adb(serial, ["shell", "uiautomator", "dump", remote])
    text = run_adb(serial, ["shell", "cat", remote])
    run_adb(serial, ["shell", "rm", remote])
    idx = text.find("<?xml")
    if idx < 0:
        idx = text.find("<hierarchy")
    if idx < 0:
        raise RuntimeError("dump 输出中没有 XML")
    return ET.fromstring(text[idx:])


def center(bounds: str) -> tuple[int, int] | None:
    """计算元素中心点像素坐标。"""
    m = BOUNDS_RE.match(bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def main() -> int:
    parser = argparse.ArgumentParser(description="从 dump 解析元素真实坐标")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--text", help="按 text / content-desc 匹配")
    parser.add_argument("--list-clickable", action="store_true", help="列出所有可点击元素")
    args = parser.parse_args()

    print(f"前台包: {foreground_package(args.serial)}")
    root = dump(args.serial)

    rows = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        clickable = node.get("clickable") == "true"
        label = text or desc
        bounds = node.get("bounds", "")
        c = center(bounds)
        if not c:
            continue
        if args.text:
            if args.text not in label:
                continue
        elif args.list_clickable:
            if not clickable:
                continue
        elif not label:
            continue
        rows.append(
            {
                "label": label or "<no text>",
                "class": (node.get("class") or "").split(".")[-1],
                "clickable": clickable,
                "bounds": bounds,
                "tapX": c[0],
                "tapY": c[1],
            }
        )

    if not rows:
        print("未找到匹配元素")
        return 1

    for r in rows:
        flag = "clickable" if r["clickable"] else "static"
        print(f"{r['label'][:30]:32s} {r['class']:22s} {flag:10s} tap=({r['tapX']},{r['tapY']}) {r['bounds']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
