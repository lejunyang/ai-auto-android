#!/usr/bin/env python3
"""功能用途：逐元素比对 App 实机界面与原型 token，输出机器可读 diff 报告。

替代"截图目测"：从 uiautomator dump 解析实机元素 bounds/text，换算为 dp，
与 artifacts/ui-validation/proto/<view>.json 的原型 token 逐项比对。

容差（来自用户约定的视觉验收标准）：
- 颜色 hex：必须完全一致（无容差）
- 字号 / 字重 / 圆角：必须一致
- 行高：±1dp
- 间距 / 尺寸：±2dp
- 点击区：>=44dp（硬下限）

合法平台差异白名单（不计为超差）：
系统状态栏、手势条、Material 涟漪与选中态、IME、系统字形（只比字号字重行高不比字形）、滚动条。

输出：
- artifacts/ui-validation/diff/<view>.json  机器可读 diff 报告
- 控制台摘要：逐项「元素 / 原型值 / 实机值 / 偏差 / 是否超差」

限制（诚实声明）：uiautomator dump 只提供 bounds/text/class/clickable，
**不提供颜色、字号、圆角**。因此颜色/字号/圆角项标记为 NOT_MEASURABLE_VIA_DUMP，
需由 DesignTokenParityTest（token 常量层单测）保证，本脚本负责尺寸/间距/点击区。

用法：
    python scripts/ui-validation/compare-app-to-prototype.py --serial emulator-5554 --view settings
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
PROTO_DIR = REPO_ROOT / "artifacts" / "ui-validation" / "proto"
DIFF_DIR = REPO_ROOT / "artifacts" / "ui-validation" / "diff"

# 设备物理参数：1080x2400 / 420dpi。dp = px / (dpi / 160)。
DEVICE_DENSITY_DPI = 420
PX_PER_DP = DEVICE_DENSITY_DPI / 160.0  # 2.625

# 容差定义
TOLERANCE = {
    "lineHeight": 1.0,
    "spacing": 2.0,
    "size": 2.0,
}
MIN_TOUCH_TARGET_DP = 44.0

# dump 无法测量的维度，需由 token 常量层单测保证。
NOT_MEASURABLE = "NOT_MEASURABLE_VIA_DUMP"

# 容器类元素：原型的 text 是所有子元素文本拼接，实机是独立节点树，
# 无法按文本对齐。这些元素的尺寸对比无意义，明确跳过而不是伪造"未找到"。
CONTAINER_ELEMENTS = {
    "appHeader", "screen", "bottomNav", "greeting", "composer", "composerFoot",
    "quickChips", "sectionHead", "skillGrid", "card", "filterTabs", "stack",
    "editorTabs", "stepList", "divider",
}


def px_to_dp(px: float) -> float:
    """设备像素转 dp，保留一位小数。"""
    return round(px / PX_PER_DP, 1)


def run_adb(serial: str, args: list[str]) -> str:
    """执行 adb 命令，返回 stdout。"""
    cmd = ["adb", "-s", serial] + args
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {proc.stderr[-500:]}")
    return proc.stdout


def check_foreground_package(serial: str, expected: str) -> str:
    """确认前台包，防止在错误界面上采集（曾误采到 Google 搜索页）。"""
    out = run_adb(serial, ["shell", "dumpsys", "window"])
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+)/(\S+)\}", out)
    if not m:
        raise RuntimeError("无法解析 mCurrentFocus，界面状态未知")
    pkg = m.group(1)
    if pkg != expected:
        raise RuntimeError(f"前台包不符：期望 {expected}，实际 {pkg}。停止采集。")
    return pkg


def dump_hierarchy(serial: str) -> ET.Element:
    """通过 uiautomator dump 获取界面层级 XML。"""
    remote = "/sdcard/_ui_dump.xml"
    run_adb(serial, ["shell", "uiautomator", "dump", remote])
    xml_text = run_adb(serial, ["shell", "cat", remote])
    run_adb(serial, ["shell", "rm", remote])
    start = xml_text.find("<?xml")
    if start < 0:
        start = xml_text.find("<hierarchy")
    if start < 0:
        raise RuntimeError("dump 输出中没有 XML")
    return ET.fromstring(xml_text[start:])


BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def parse_bounds(bounds: str) -> dict[str, float] | None:
    """解析 uiautomator 的 bounds 字符串为 dp 尺寸。"""
    m = BOUNDS_RE.match(bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return {
        "x": px_to_dp(x1),
        "y": px_to_dp(y1),
        "width": px_to_dp(x2 - x1),
        "height": px_to_dp(y2 - y1),
    }


def collect_app_elements(root: ET.Element) -> list[dict]:
    """遍历 dump 树，收集所有有文本或可点击的节点。"""
    out: list[dict] = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        clickable = node.get("clickable") == "true"
        if not text and not desc and not clickable:
            continue
        box = parse_bounds(node.get("bounds", ""))
        if not box or box["width"] <= 0 or box["height"] <= 0:
            continue
        out.append(
            {
                "text": text or desc,
                "class": node.get("class", ""),
                "clickable": clickable,
                "box": box,
            }
        )
    return out


def find_app_element(app_elements: list[dict], text_fragment: str) -> dict | None:
    """按文本片段匹配实机元素（原型与实机文案一致时可对齐）。"""
    if not text_fragment:
        return None
    frag = text_fragment.strip()[:12]
    if not frag:
        return None
    for el in app_elements:
        if frag and frag in el["text"]:
            return el
    return None


def compare_value(name: str, proto: float | None, app: float | None, tol: float) -> dict:
    """比对单个数值维度，返回带超差判定的结果。"""
    if proto is None or app is None:
        return {"metric": name, "prototype": proto, "app": app, "delta": None, "status": "MISSING"}
    delta = round(app - proto, 1)
    over = abs(delta) > tol
    return {
        "metric": name,
        "prototype": proto,
        "app": app,
        "delta": delta,
        "tolerance": tol,
        "status": "OVER_TOLERANCE" if over else "OK",
    }


def compare_element(label: str, proto_el: dict, app_el: dict | None) -> dict:
    """比对单个元素的全部可测维度。"""
    entry: dict = {
        "element": label,
        "prototypeText": proto_el.get("text"),
        "appText": app_el["text"] if app_el else None,
        "matched": app_el is not None,
        "metrics": [],
        "notMeasurable": [],
    }
    # dump 无法提供的维度，明确登记而不是沉默跳过。
    pcolor = proto_el.get("color") or {}
    pfont = proto_el.get("font") or {}
    pradius = proto_el.get("radius") or {}
    for key, val in (
        ("color.text", pcolor.get("text")),
        ("color.background", pcolor.get("background")),
        ("font.size", pfont.get("size")),
        ("font.weight", pfont.get("weight")),
        ("radius.topLeft", pradius.get("topLeft")),
    ):
        if val is not None:
            entry["notMeasurable"].append(
                {"metric": key, "prototype": val, "reason": NOT_MEASURABLE}
            )

    if not app_el:
        entry["status"] = "NOT_FOUND"
        return entry

    pbox = proto_el.get("box") or {}
    abox = app_el["box"]
    entry["metrics"].append(compare_value("box.width", pbox.get("width"), abox["width"], TOLERANCE["size"]))
    entry["metrics"].append(compare_value("box.height", pbox.get("height"), abox["height"], TOLERANCE["size"]))
    entry["metrics"].append(compare_value("box.x", pbox.get("x"), abox["x"], TOLERANCE["spacing"]))

    # 点击区硬下限
    if app_el["clickable"]:
        h = abox["height"]
        entry["metrics"].append(
            {
                "metric": "touchTarget.height",
                "prototype": f">={MIN_TOUCH_TARGET_DP}",
                "app": h,
                "delta": round(h - MIN_TOUCH_TARGET_DP, 1),
                "tolerance": 0,
                "status": "OK" if h >= MIN_TOUCH_TARGET_DP else "OVER_TOLERANCE",
            }
        )

    over = [m for m in entry["metrics"] if m["status"] == "OVER_TOLERANCE"]
    entry["status"] = "OVER_TOLERANCE" if over else "OK"
    return entry


def main() -> int:
    parser = argparse.ArgumentParser(description="逐元素比对 App 与原型")
    parser.add_argument("--serial", required=True, help="设备 serial，必须显式指定")
    parser.add_argument("--view", required=True, help="原型视图名，对应 proto/<view>.json")
    parser.add_argument("--package", default="dev.aiauto.android", help="期望前台包")
    args = parser.parse_args()

    proto_path = PROTO_DIR / f"{args.view}.json"
    if not proto_path.is_file():
        print(f"原型 token 不存在: {proto_path}", file=sys.stderr)
        return 1
    proto = json.loads(proto_path.read_text(encoding="utf-8"))

    # 采集前确认前台包，避免在错误界面上比对。
    pkg = check_foreground_package(args.serial, args.package)
    print(f"前台包确认: {pkg}")

    root = dump_hierarchy(args.serial)
    app_elements = collect_app_elements(root)
    print(f"实机元素数: {len(app_elements)}")

    results = []
    skipped_containers = []
    for label, proto_el in (proto.get("elements") or {}).items():
        if not proto_el:
            continue
        # 容器元素按文本无法对齐（原型 text 是子元素拼接），明确跳过并登记。
        if label in CONTAINER_ELEMENTS:
            skipped_containers.append(label)
            continue
        app_el = find_app_element(app_elements, proto_el.get("text") or "")
        results.append(compare_element(label, proto_el, app_el))

    over = [r for r in results if r["status"] == "OVER_TOLERANCE"]
    missing = [r for r in results if r["status"] == "NOT_FOUND"]
    ok = [r for r in results if r["status"] == "OK"]

    report = {
        "view": args.view,
        "serial": args.serial,
        "package": pkg,
        "device": {"densityDpi": DEVICE_DENSITY_DPI, "pxPerDp": PX_PER_DP},
        "tolerance": TOLERANCE,
        "minTouchTargetDp": MIN_TOUCH_TARGET_DP,
        "summary": {
            "total": len(results),
            "ok": len(ok),
            "overTolerance": len(over),
            "notFound": len(missing),
            "skippedContainers": len(skipped_containers),
        },
        "skippedContainers": {
            "elements": skipped_containers,
            "reason": (
                "容器元素的原型 text 是子元素文本拼接，实机是独立节点树，"
                "无法按文本对齐；其尺寸由子元素位置间接决定，不单独比对。"
            ),
        },
        "notMeasurableNote": (
            "uiautomator dump 不提供颜色/字号/圆角，这些维度由 DesignTokenParityTest "
            "（token 常量层单测）保证；本报告负责尺寸/间距/点击区。"
        ),
        "results": results,
    }

    DIFF_DIR.mkdir(parents=True, exist_ok=True)
    out_path = DIFF_DIR / f"{args.view}.json"
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n=== {args.view} 比对摘要 ===")
    print(f"总计 {len(results)}  达标 {len(ok)}  超差 {len(over)}  未找到 {len(missing)}")
    if over:
        print("\n超差项:")
        for r in over:
            for m in r["metrics"]:
                if m["status"] == "OVER_TOLERANCE":
                    print(
                        f"  {r['element']}.{m['metric']}: "
                        f"原型={m['prototype']} 实机={m['app']} 偏差={m['delta']} "
                        f"容差=±{m['tolerance']}"
                    )
    if missing:
        print(f"\n未在实机找到的原型元素 ({len(missing)}):")
        for r in missing[:20]:
            print(f"  {r['element']} (原型文案: {r['prototypeText']})")

    print(f"\n报告: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
