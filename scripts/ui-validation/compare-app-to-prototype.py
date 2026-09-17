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
    # 底栏 tab 项：原型是含图标+标签的容器（98.8x60dp），Compose NavigationBarItem
    # 的可命中节点是标签 Text（约 25x18dp），两者不是同一层级对象，无法按文本对齐。
    # 底栏尺寸由 AppSpacing.NavHeight / NavIconSize / NavLabelFontSize 三个 token
    # 在 DesignTokenParityTest 中断言，不在本脚本判定。
    "navItemActive", "navItemInactive",
    # 带图标的按钮（composer 内「选择应用」pill、快捷 chip）：原型是单个 <button>，
    # Compose 是 Row(Icon + Text)，dump 命中的是内部 Text 节点而非可点击容器。
    # 实测可点击容器高 48dp（Material 最小尺寸），满足 >=44dp 点击区要求；
    # 宽度随中文文案长度变化，不是固定值。圆角/配色由 token 断言。
    "composerPill", "quickChip",
    # 筛选 chip（FilterChip）：原型是 <button>，Compose FilterChip 的 dump 命中节点
    # 是内部标签 Text。实测可点击容器 58.7x48dp（原型 48x44），高度满足 >=44dp
    # 点击区，宽度随中文文案变化。圆角/选中态配色由 token 断言。
    "filterTabActive", "filterTabInactive",
}

# 文本元素：原型的 <b>/<small> 撑满父容器宽度，Compose Text 是内容宽度。
# 这类元素的 width 偏差属测量口径差异，只判定 x 起点与高度。
TEXT_WIDTH_EXEMPT = {
    "sectionTitle", "settingRowTitle", "settingRowSub", "skillTileTitle",
    "skillTileMeta", "headerTitle", "headerSubtitle", "greetingHello",
    "greetingTitle", "pageHeading", "pageDesc", "note", "tileTitle", "tileMeta",
    "label", "textBtn", "empty",
}

# 文本高度豁免：Compose Text 的行盒高度含字体上下留白，与 HTML line-height
# 盒模型不同（实测差约 7dp）。字号/行高由 DesignTokenParityTest 断言。
TEXT_HEIGHT_EXEMPT = TEXT_WIDTH_EXEMPT

# notice/noticeAmber 匹配到内部 Text 而非容器时，宽/高/x 不可比（容器尺寸由 token 断言）。
NOTICE_INNER_TEXT_EXEMPT = {"notice", "noticeAmber"}

# x 起点豁免：图标+文本 Row 的内部间距由 Compose Arrangement 决定，
# 与 HTML flex gap 的累计起点不同；容器整体位置一致即可。
TEXT_X_EXEMPT = {"settingRowTitle", "settingRowSub", "skillTileTitle", "skillTileMeta"}

# 文案别名映射：原型演示文案 ↔ 真实功能文案。
# 原型使用"演示/体验"字样，真实功能使用实际操作文案，因此按文本无法直接匹配。
# key 是原型 text 片段，value 是实机 text 片段列表（任一命中即匹配）。
TEXT_ALIASES: dict[str, list[str]] = {
    # provider 页按钮
    "保存演示配置": ["保存配置"],
    "演示连接检查": ["测试连接", "连接检查"],
    # connection 页按钮
    "体验配对流程": ["开启桌面桥", "关闭桌面桥"],
    "输入邀请码演示": ["改用局域网邀请码连接", "邀请码", "局域网"],
    # permissions 页按钮
    "查看系统授权步骤": ["授权", "开启无障碍", "系统设置"],
    # 页面标题（文案有意不同）
    "教会它的事": ["技能"],
    "做过的，都在这里": ["记录", "运行记录"],
    # 通用
    "重新体验首次设置": ["首次设置", "设置向导"],
}

# 页面标题类元素要求位于页面上方（y < 300dp），避免匹配到页面中部的空态文字
PAGE_HEADING_LABELS = {"pageHeading", "greetingTitle"}
# 页面描述类元素也位于页面上方（y < 250dp），避免匹配到状态标题
PAGE_DESC_LABELS = {"pageDesc", "greetingHello"}

# 类型匹配规则：原型 label → 实机 class 后缀集合。
# 当文本（含别名）匹配失败时，按类型+最近 y 位置配对。
TYPE_HINTS: dict[str, set[str]] = {
    "input": {"EditText"},
    "primaryBtn": {"View", "Button"},
    "primaryBtnFull": {"View", "Button"},
    "secondaryBtn": {"View", "Button"},
    "textBtn": {"View", "Button", "TextView"},
    "notice": {"TextView", "View"},
    "noticeAmber": {"TextView", "View"},
    "pageHeading": {"TextView"},
    "pageDesc": {"TextView"},
    "note": {"TextView"},
    "label": {"TextView"},
    "sectionTitle": {"TextView"},
    "card": {"View", "FrameLayout", "LinearLayout"},
}

# 按钮类元素要求 clickable=true（排除静态文本）
BUTTON_LABELS = {"primaryBtn", "primaryBtnFull", "secondaryBtn"}
# 文字按钮：clickable=true，高度 < 40dp（与主按钮区分）
TEXT_BUTTON_LABELS = {"textBtn"}

# 类型匹配时的 y 搜索带宽（dp）：在原型 y ± 此范围内找最近的同类型元素
TYPE_MATCH_Y_BAND = 120.0


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
    """遍历 dump 树，收集所有有文本或可点击的节点。

    对 clickable=true 但自身无 text 的节点（Compose Button 渲染为
    android.view.View，文案在子 TextView），聚合其后代节点的文本，
    使按钮可按文案（含别名）匹配。
    """
    out: list[dict] = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        clickable = node.get("clickable") == "true"
        # 聚合后代文本（用于无文案的可点击容器）
        aggregated = ""
        if clickable and not text and not desc:
            parts = []
            for child in node.iter("node"):
                ct = (child.get("text") or child.get("content-desc") or "").strip()
                if ct and ct not in parts:
                    parts.append(ct)
            aggregated = " ".join(parts)
        display_text = text or desc or aggregated
        if not display_text and not clickable:
            continue
        box = parse_bounds(node.get("bounds", ""))
        if not box or box["width"] <= 0 or box["height"] <= 0:
            continue
        out.append(
            {
                "text": display_text,
                "class": node.get("class", ""),
                "clickable": clickable,
                "box": box,
            }
        )
    return out


def _text_fragment_match(el: dict, frag: str) -> bool:
    """判断实机元素文本是否包含原型片段（取前 12 字符避免长文案截断问题）。"""
    if not frag:
        return False
    return frag[:12] in el["text"]


def _alias_match(proto_text: str, app_el: dict) -> bool:
    """通过别名映射表匹配：原型文案的任一别名片段出现在实机文本中。"""
    for proto_frag, app_aliases in TEXT_ALIASES.items():
        if proto_frag[:10] in proto_text:
            for alias in app_aliases:
                if alias in app_el["text"]:
                    return True
    return False


def _type_candidates(app_elements: list[dict], label: str, proto_el: dict) -> list[dict]:
    """按类型提示筛选候选元素，并在 y 带宽内按距离排序。

    对容器类元素（notice/card）附加最小宽度约束，避免匹配到内部小文本节点。
    """
    hints = TYPE_HINTS.get(label)
    if not hints:
        return []
    proto_box = proto_el.get("box") or {}
    proto_y = proto_box.get("y", 0)
    proto_w = proto_box.get("width", 0)
    # 容器类元素要求宽度至少为原型的 50%，避免匹配到内部小文本
    min_width = proto_w * 0.5 if label in {"notice", "noticeAmber", "card"} else 0
    # 段落类文本（note/pageDesc）要求宽度 >= 200dp，避免匹配到 placeholder/label
    if label in {"note", "pageDesc"}:
        min_width = max(min_width, 200.0)
    candidates = []
    for el in app_elements:
        cls_short = el["class"].split(".")[-1]
        if cls_short not in hints:
            continue
        if label in BUTTON_LABELS and not el["clickable"]:
            continue
        if label in BUTTON_LABELS and el["box"]["height"] < 40:
            continue
        if el["box"]["width"] < min_width:
            continue
        if abs(el["box"]["y"] - proto_y) <= TYPE_MATCH_Y_BAND:
            candidates.append(el)
    candidates.sort(key=lambda e: abs(e["box"]["y"] - proto_y))
    return candidates


def find_app_element(
    app_elements: list[dict],
    proto_el: dict,
    label: str,
    matched: set[int] | None = None,
) -> dict | None:
    """多级匹配实机元素。

    优先级：
    1. 原型文本片段直接匹配实机文本
    2. 别名映射匹配（原型演示文案 ↔ 真实功能文案）
    3. 类型+最近 y 位置匹配（对 input/button/notice 等文案不同或无文案元素）

    matched: 已被其他原型元素占用的实机元素 id 集合，避免重复匹配。
    """
    if matched is None:
        matched = set()
    proto_text = proto_el.get("text") or ""
    proto_y = (proto_el.get("box") or {}).get("y", 0)

    def _available(el: dict) -> bool:
        if id(el) in matched:
            return False
        # 主按钮：clickable=true 且高度 >= 40dp
        if label in BUTTON_LABELS:
            if not el["clickable"] or el["box"]["height"] < 40:
                return False
        # 文字按钮：clickable=true 且高度 < 40dp（避免匹配到设置行）
        if label in TEXT_BUTTON_LABELS:
            if not el["clickable"] or el["box"]["height"] >= 40:
                return False
        # 对页面标题类元素，要求位于页面上方（避免匹配到空态文字）
        if label in PAGE_HEADING_LABELS and el["box"]["y"] > 300:
            return False
        # 对页面描述类元素，要求位于页面上方（避免匹配到状态标题）
        if label in PAGE_DESC_LABELS and el["box"]["y"] > 250:
            return False
        return True

    # 第 1 级：文本直接匹配
    if proto_text:
        frag = proto_text.strip()[:12]
        for el in app_elements:
            if _available(el) and _text_fragment_match(el, frag):
                el["_matchBy"] = "text"
                return el

    # 第 2 级：别名匹配
    if proto_text:
        for el in app_elements:
            if _available(el) and _alias_match(proto_text, el):
                el["_matchBy"] = "alias"
                return el

    # 第 3 级：类型+位置匹配
    candidates = _type_candidates(app_elements, label, proto_el)
    candidates = [c for c in candidates if _available(c)]
    if candidates:
        best = candidates[0]
        best["_matchBy"] = "type-position"
        return best

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
        "matchBy": app_el.get("_matchBy") if app_el else None,
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
    # 文本元素的 width 是内容宽度（Compose）vs 父容器宽度（HTML），口径不同，
    # 登记为豁免而不是判超差；高度与 x 起点仍然比对。
    # notice 匹配到内部 Text 时，宽/高/x 均不可比（容器由 token 断言）。
    if label in TEXT_WIDTH_EXEMPT or label in NOTICE_INNER_TEXT_EXEMPT:
        entry["notMeasurable"].append(
            {
                "metric": "box.width",
                "prototype": pbox.get("width"),
                "app": abox["width"],
                "reason": "TEXT_WIDTH_MEASUREMENT_DIFFERS",
            }
        )
    else:
        entry["metrics"].append(
            compare_value("box.width", pbox.get("width"), abox["width"], TOLERANCE["size"])
        )
    if label in TEXT_HEIGHT_EXEMPT or label in NOTICE_INNER_TEXT_EXEMPT:
        entry["notMeasurable"].append(
            {
                "metric": "box.height",
                "prototype": pbox.get("height"),
                "app": abox["height"],
                "reason": "TEXT_LINE_BOX_DIFFERS",
            }
        )
    else:
        entry["metrics"].append(
            compare_value("box.height", pbox.get("height"), abox["height"], TOLERANCE["size"])
        )

    if label in TEXT_X_EXEMPT or label in NOTICE_INNER_TEXT_EXEMPT:
        entry["notMeasurable"].append(
            {
                "metric": "box.x",
                "prototype": pbox.get("x"),
                "app": abox["x"],
                "reason": "FLEX_GAP_VS_ARRANGEMENT_DIFFERS",
            }
        )
    else:
        entry["metrics"].append(
            compare_value("box.x", pbox.get("x"), abox["x"], TOLERANCE["spacing"])
        )

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
    matched_app_indices: set[int] = set()  # 已匹配的实机元素索引，避免重复匹配
    for label, proto_el in (proto.get("elements") or {}).items():
        if not proto_el:
            continue
        # 容器元素按文本无法对齐（原型 text 是子元素拼接），明确跳过并登记。
        if label in CONTAINER_ELEMENTS:
            skipped_containers.append(label)
            continue
        app_el = find_app_element(app_elements, proto_el, label, matched_app_indices)
        if app_el is not None:
            # 记录该实机元素已被占用（用 id 去重，因为元素是 dict）
            matched_app_indices.add(id(app_el))
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
    # 显示各匹配方式分布，便于追溯别名/类型匹配是否生效
    match_dist: dict[str, int] = {}
    for r in results:
        mb = r.get("matchBy") or "none"
        match_dist[mb] = match_dist.get(mb, 0) + 1
    print(f"\n匹配方式分布: {match_dist}")

    print(f"\n报告: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
