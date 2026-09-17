#!/usr/bin/env python3
"""功能用途：批量为已实现的界面生成 diff 报告。

对每个视图：先导航到对应界面（坐标从当次 dump 解析），确认前台包，
再调用 compare-app-to-prototype.py 生成报告。

只覆盖当前已实现且可稳定导航到的界面；未实现的视图不生成报告，
避免用空报告伪装覆盖率。

用法：
    python scripts/ui-validation/batch-diff.py --serial emulator-5554
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
COMPARE = Path(__file__).resolve().parent / "compare-app-to-prototype.py"
DIFF_DIR = REPO_ROOT / "artifacts" / "ui-validation" / "diff"

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(serial: str, args: list[str]) -> str:
    """执行 adb 命令。"""
    proc = subprocess.run(
        ["adb", "-s", serial] + args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {proc.stderr[-300:]}")
    return proc.stdout


def dump_nodes(serial: str) -> list[dict]:
    """dump 界面并返回带文本的节点列表（含中心坐标）。"""
    remote = "/sdcard/_batch_dump.xml"
    adb(serial, ["shell", "uiautomator", "dump", remote])
    text = adb(serial, ["shell", "cat", remote])
    adb(serial, ["shell", "rm", remote])
    idx = text.find("<?xml")
    if idx < 0:
        idx = text.find("<hierarchy")
    root = ET.fromstring(text[idx:])
    out = []
    for node in root.iter("node"):
        label = (node.get("text") or node.get("content-desc") or "").strip()
        m = BOUNDS_RE.match(node.get("bounds", "") or "")
        if not label or not m:
            continue
        x1, y1, x2, y2 = (int(g) for g in m.groups())
        out.append({"text": label, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "y": y1})
    return out


def tap_text(serial: str, fragment: str, min_y: int = 0) -> bool:
    """按文本片段查找并点击。坐标来自当次 dump，不硬编码。

    对二级页入口（设置行），返回 y 最大的匹配——因为同名文本可能出现在
    页面上方的状态卡中，而设置行在页面下方。
    """
    matches = []
    for n in dump_nodes(serial):
        if fragment in n["text"] and n["y"] >= min_y:
            matches.append(n)
    if not matches:
        return False
    # 取 y 最大的匹配（页面下方的设置行），避免误点上方状态卡中的同名文本
    best = max(matches, key=lambda n: n["y"])
    adb(serial, ["shell", "input", "tap", str(best["cx"]), str(best["cy"])])
    return True


def tap_tab(serial: str, tab_label: str) -> bool:
    """点击底栏 tab。底栏文本位于屏幕下方，用 y 下限区分同名文本。

    重试一次：首次可能因页面过渡导致 dump 中无底栏文本。
    """
    for attempt in range(2):
        if tap_text(serial, tab_label, min_y=2100):
            return True
        if attempt == 0:
            wait(serial, 1.5)
    return False


def run_compare(serial: str, view: str) -> tuple[bool, str]:
    """调用比对脚本生成报告。"""
    proc = subprocess.run(
        [sys.executable, str(COMPARE), "--serial", serial, "--view", view],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode == 0, proc.stdout + proc.stderr


def wait(serial: str, seconds: float = 2.5) -> None:
    """等待界面稳定。"""
    import time

    time.sleep(seconds)


def verify_page(serial: str, expected_fragments: list[str]) -> bool:
    """验证当前页面包含预期文本片段（导航成功的后置条件）。

    任一预期片段出现在当前 dump 中即视为页面正确。
    """
    nodes = dump_nodes(serial)
    all_text = " ".join(n["text"] for n in nodes)
    return any(frag in all_text for frag in expected_fragments)


# 每个视图导航后应出现的独特标题/特征文本（用于验证导航成功）。
# 必须使用只在目标页面出现的文本，避免在其他页面也存在导致误判。
VIEW_VERIFY_TEXT: dict[str, list[str]] = {
    "home-empty": ["你好，今天想做点什么", "常用技能"],
    "skills-all": ["教它一次", "我教的"],
    "history-all": ["暂无运行记录", "全部", "已完成"],
    "settings": ["把边界设好", "助理状态", "随时唤起"],
    "provider": ["接上你的 AI", "服务地址", "API Key 使用 Android Keystore"],
    "permissions": ["只在你允许的范围内", "无障碍服务可以读取屏幕", "允许操作的目标应用包名"],
    "connection": ["手机与电脑，配合做事", "开启桌面桥", "改用局域网邀请码连接"],
}


def foreground_package(serial: str) -> str:
    """读取当前前台包。"""
    out = adb(serial, ["shell", "dumpsys", "window"])
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+)/(\S+)\}", out)
    return m.group(1) if m else "<unknown>"


def ensure_app_foreground(serial: str, package: str) -> None:
    """确保 App 在前台。不在则重新启动（BACK 可能已把 App 退到桌面）。"""
    if foreground_package(serial) != package:
        adb(serial, ["shell", "am", "start", "-n", f"{package}/.MainActivity"])
        wait(serial, 3.0)


def main() -> int:
    parser = argparse.ArgumentParser(description="批量生成 diff 报告")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="dev.aiauto.android")
    args = parser.parse_args()

    # 导航步骤：(视图名, 导航动作序列)。动作是 ("tab", 标签) 或 ("text", 片段) 或 ("back", None)。
    plan: list[tuple[str, list[tuple[str, str | None]]]] = [
        ("home-empty", [("tab", "助理")]),
        ("skills-all", [("tab", "技能")]),
        ("history-all", [("tab", "记录")]),
        ("settings", [("tab", "我的")]),
        ("provider", [("tab", "我的"), ("text", "AI 模型")]),
        ("permissions", [("tab", "我的"), ("text", "允许操作的应用")]),
        ("connection", [("tab", "我的"), ("text", "连接电脑")]),
    ]

    DIFF_DIR.mkdir(parents=True, exist_ok=True)
    ok, failed = [], []

    for view, actions in plan:
        print(f"\n{'=' * 16} {view} {'=' * 16}")
        # 每个视图开始前确保 App 在前台，并回到 tab 根页面。
        ensure_app_foreground(args.serial, args.package)
        # 仅在底栏不可见（二级页）时才 BACK；每次 BACK 后等待页面过渡完成。
        # 最多 3 次 BACK（从深层二级页返回），但每次前重新检查底栏。
        for _ in range(3):
            nodes = dump_nodes(args.serial)
            if any(n["y"] >= 2100 for n in nodes):
                break  # 已在 tab 根
            adb(args.serial, ["shell", "input", "keyevent", "KEYCODE_BACK"])
            wait(args.serial, 3.0)  # 等待页面过渡完成
            ensure_app_foreground(args.serial, args.package)

        nav_ok = True
        for kind, value in actions:
            if kind == "tab":
                nav_ok = tap_tab(args.serial, value or "")
            elif kind == "text":
                nav_ok = tap_text(args.serial, value or "", min_y=300)
            elif kind == "back":
                adb(args.serial, ["shell", "input", "keyevent", "KEYCODE_BACK"])
            if not nav_ok:
                print(f"  导航失败：找不到 {kind}={value}，跳过该视图（不伪装已验证）")
                break
            wait(args.serial)

        if not nav_ok:
            failed.append((view, f"导航失败: {actions}"))
            continue

        # 导航后置验证：确认当前页面是目标视图，避免在错误页面上比对。
        expected = VIEW_VERIFY_TEXT.get(view, [])
        if expected and not verify_page(args.serial, expected):
            # 重试一次：可能点击未生效或页面过渡中
            print(f"  页面验证失败，重试导航...")
            for kind, value in actions:
                if kind == "tab":
                    nav_ok = tap_tab(args.serial, value or "")
                elif kind == "text":
                    nav_ok = tap_text(args.serial, value or "", min_y=300)
                elif kind == "back":
                    adb(args.serial, ["shell", "input", "keyevent", "KEYCODE_BACK"])
                if not nav_ok:
                    break
                wait(args.serial)
            if not expected or not verify_page(args.serial, expected):
                print(f"  重试后仍验证失败：未找到预期文本 {expected}，跳过该视图")
                failed.append((view, f"页面验证失败: expected {expected}"))
                continue

        success, output = run_compare(args.serial, view)
        # 打印摘要行
        for line in output.splitlines():
            if "比对摘要" in line or "总计" in line or "前台包" in line:
                print(f"  {line}")
        if (DIFF_DIR / f"{view}.json").is_file():
            ok.append(view)
        else:
            failed.append((view, "报告未生成"))

        # 二级页跑完后立即 BACK 回 tab 根，让下一个视图从干净状态开始。
        # 避免下一个视图的 BACK 循环因页面过渡未完成而误判。
        if view in {"provider", "permissions", "connection"}:
            adb(args.serial, ["shell", "input", "keyevent", "KEYCODE_BACK"])
            wait(args.serial, 3.0)

    print(f"\n生成 {len(ok)}/{len(plan)} 份报告：{DIFF_DIR}")
    if failed:
        print("未生成：")
        for view, reason in failed:
            print(f"  - {view}: {reason}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
