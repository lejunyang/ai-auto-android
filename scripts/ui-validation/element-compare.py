#!/usr/bin/env python3
"""功能用途：按元素裁剪对比原型与实机截图，输出并排对比图和尺寸差异报告。

对比范围从内到外：先比图标/文字等原子元素，再比按钮/输入框等组件，最后比卡片/页面区块。
原型截图 411x914（CSS px == dp），实机截图 1080x2400（px / 2.625 = dp）。

用法：
    python scripts/ui-validation/element-compare.py --view home-empty --app-screenshot <path> --serial emulator-5554
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# 原型元素名 → 实机匹配规则。
# text_hints 为多个文本片段时，取包含所有匹配文本的最小包围矩形（用于容器元素）。
ELEMENT_MAP = {
    "composer": {"type": "container", "text_hints": ["比如", "选择应用"]},
    "composerInput": {"type": "text", "text_hints": ["比如"]},
    "bottomNav": {"type": "container", "text_hints": ["助理", "技能", "记录", "我的"]},
    "greetingTitle": {"type": "text", "text_hints": ["有什么"]},
    "teachCard": {"type": "card", "text_hints": ["教它一次"]},
    "skillTile": {"type": "card", "text_hints": ["整理稍后阅读"]},
    "quickChip": {"type": "button", "text_hints": ["整理稍后阅读"]},
    "appHeader": {"type": "container", "text_hints": ["AI Auto", "让手机"]},
}

# 容差：尺寸 ±2dp，颜色/字号由 token 测试保证。
SIZE_TOLERANCE_DP = 2.0
DP_SCALE = 2.625  # 1080x2400 / 420dpi


def dump_ui_hierarchy(serial: str) -> str:
    """通过 uiautomator dump 获取当前 UI hierarchy XML。"""
    remote = "/sdcard/ui_dump.xml"
    subprocess.run(
        ["adb", "-s", serial, "shell", "uiautomator", "dump", remote],
        capture_output=True, text=True, timeout=30,
    )
    result = subprocess.run(
        ["adb", "-s", serial, "shell", "cat", remote],
        capture_output=True, text=True, timeout=30,
    )
    subprocess.run(["adb", "-s", serial, "shell", "rm", remote], capture_output=True)
    return result.stdout


def parse_bounds(bounds_str: str) -> tuple[int, int, int, int]:
    """解析 '[x1,y1][x2,y2]' 格式为 (x1, y1, x2, y2)。"""
    parts = bounds_str.strip("[]").split("][")
    x1, y1 = map(int, parts[0].split(","))
    x2, y2 = map(int, parts[1].split(","))
    return x1, y1, x2, y2


def find_app_element(xml_content: str, text_hints: list[str], element_type: str) -> dict | None:
    """从 UI hierarchy XML 中查找匹配的元素，返回 bounds（px）和 text。

    匹配策略按元素类型：
    - text/button: 匹配第一个文本提示的节点
    - card: 找包含该文本的最大可点击容器
    - container: 取包含所有文本提示匹配节点的最小包围矩形
    """
    try:
        root = ET.fromstring(xml_content)
    except ET.ParseError:
        return None

    all_nodes = []
    for node in root.iter("node"):
        text = node.get("text", "") or ""
        content_desc = node.get("content-desc", "") or ""
        bounds_str = node.get("bounds", "")
        if not bounds_str:
            continue
        x1, y1, x2, y2 = parse_bounds(bounds_str)
        all_nodes.append({
            "text": text or content_desc,
            "bounds": (x1, y1, x2, y2),
            "width": x2 - x1,
            "height": y2 - y1,
            "clickable": node.get("clickable", "false") == "true",
            "area": (x2 - x1) * (y2 - y1),
        })

    # container 类型：取包含所有文本提示匹配节点的最小包围矩形
    if element_type == "container":
        all_matching = []
        for hint in text_hints:
            matching = [n for n in all_nodes if hint in n["text"]]
            if not matching:
                return None
            # 选面积最小的（最内层文本节点）
            all_matching.append(min(matching, key=lambda n: n["area"]))

        # 取最小包围矩形
        min_x = min(n["bounds"][0] for n in all_matching)
        min_y = min(n["bounds"][1] for n in all_matching)
        max_x = max(n["bounds"][2] for n in all_matching)
        max_y = max(n["bounds"][3] for n in all_matching)

        return {
            "text": " | ".join(n["text"][:20] for n in all_matching),
            "bounds_px": (min_x, min_y, max_x, max_y),
            "width_px": max_x - min_x,
            "height_px": max_y - min_y,
            "clickable": False,
        }

    # 非 container 类型：用第一个文本提示
    text_hint = text_hints[0]
    matching = [n for n in all_nodes if text_hint in n["text"]]
    if not matching:
        return None

    if element_type == "text":
        best = min(matching, key=lambda n: n["area"])
    elif element_type == "button":
        clickable = [n for n in matching if n["clickable"]]
        best = min(clickable, key=lambda n: n["area"]) if clickable else min(matching, key=lambda n: n["area"])
    else:
        # card 类型：找包含匹配节点的最大可点击容器
        candidates = []
        for m in matching:
            mx1, my1, mx2, my2 = m["bounds"]
            containers = [
                n for n in all_nodes
                if n["clickable"]
                and n["bounds"][0] <= mx1 + 1
                and n["bounds"][1] <= my1 + 1
                and n["bounds"][2] >= mx2 - 1
                and n["bounds"][3] >= my2 - 1
            ]
            if containers:
                candidates.append(max(containers, key=lambda n: n["area"]))
            else:
                candidates.append(m)
        best = max(candidates, key=lambda n: n["area"])

    return {
        "text": best["text"],
        "bounds_px": best["bounds"],
        "width_px": best["width"],
        "height_px": best["height"],
        "clickable": best["clickable"],
    }


def crop_element(image: Image.Image, box: tuple[int, int, int, int]) -> Image.Image:
    """按 (x1, y1, x2, y2) 裁剪图像。"""
    x1, y1, x2, y2 = box
    # 确保坐标在图像范围内
    x1 = max(0, min(x1, image.width))
    y1 = max(0, min(y1, image.height))
    x2 = max(0, min(x2, image.width))
    y2 = max(0, min(y2, image.height))
    if x2 <= x1 or y2 <= y1:
        return Image.new("RGB", (10, 10), (255, 0, 0))  # 红色占位表示裁剪失败
    return image.crop((x1, y1, x2, y2))


def create_comparison_image(
    proto_crop: Image.Image,
    app_crop: Image.Image,
    element_name: str,
    proto_size: str,
    app_size: str,
    delta: str,
    over_tolerance: bool,
) -> Image.Image:
    """生成并排对比图：原型裁剪图 | 实机裁剪图 | 尺寸信息。"""
    # 统一高度为 200px，宽度按比例
    target_h = 200
    proto_resized = proto_crop.copy()
    app_resized = app_crop.copy()
    if proto_resized.height > 0:
        ratio = target_h / proto_resized.height
        proto_resized = proto_resized.resize((int(proto_resized.width * ratio), target_h), Image.LANCZOS)
    if app_resized.height > 0:
        ratio = target_h / app_resized.height
        app_resized = app_resized.resize((int(app_resized.width * ratio), target_h), Image.LANCZOS)

    padding = 20
    label_h = 60
    total_w = proto_resized.width + app_resized.width + padding * 3
    total_h = target_h + label_h + padding * 2

    canvas = Image.new("RGB", (total_w, total_h), (250, 251, 252))
    draw = ImageDraw.Draw(canvas)

    # 标题
    status = "OVER" if over_tolerance else "OK"
    status_color = (182, 62, 73) if over_tolerance else (35, 120, 99)
    draw.text((padding, 10), f"{element_name} [{status}]", fill=(23, 33, 46))
    draw.text((padding, 30), f"proto: {proto_size}  app: {app_size}  delta: {delta}", fill=(107, 119, 136))

    # 原型裁剪图
    x = padding
    y = label_h
    draw.text((x, y - 15), "PROTOTYPE", fill=(107, 119, 136))
    canvas.paste(proto_resized, (x, y))

    # 实机裁剪图
    x = padding + proto_resized.width + padding
    draw.text((x, y - 15), "APP", fill=(107, 119, 136))
    canvas.paste(app_resized, (x, y))

    return canvas


def main() -> int:
    parser = argparse.ArgumentParser(description="按元素裁剪对比原型与实机")
    parser.add_argument("--view", required=True, help="原型视图名，如 home-empty")
    parser.add_argument("--app-screenshot", required=True, help="实机截图路径")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--proto-dir", default="artifacts/ui-validation/proto")
    parser.add_argument("--output-dir", default="artifacts/ui-validation/element-compare")
    args = parser.parse_args()

    proto_dir = Path(args.proto_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # 读取原型 JSON 和截图
    proto_json_path = proto_dir / f"{args.view}.json"
    proto_png_path = proto_dir / f"{args.view}.png"
    if not proto_json_path.exists():
        print(f"原型 JSON 不存在: {proto_json_path}")
        return 1
    if not proto_png_path.exists():
        print(f"原型截图不存在: {proto_png_path}")
        return 1

    proto_data = json.loads(proto_json_path.read_text(encoding="utf-8"))
    proto_elements = proto_data.get("elements", {})
    proto_image = Image.open(proto_png_path).convert("RGB")

    # 读取实机截图和 UI hierarchy
    app_image = Image.open(args.app_screenshot).convert("RGB")
    xml_content = dump_ui_hierarchy(args.serial)

    results = []
    comparison_images = []

    for element_name, match_rule in ELEMENT_MAP.items():
        if element_name not in proto_elements:
            continue

        proto_el = proto_elements[element_name]
        proto_box = proto_el.get("box", {})
        proto_w = proto_box.get("width", 0)
        proto_h = proto_box.get("height", 0)
        proto_x = proto_box.get("x", 0)
        proto_y = proto_box.get("y", 0)

        # 在实机中查找对应元素
        app_el = find_app_element(
            xml_content,
            text_hints=match_rule["text_hints"],
            element_type=match_rule["type"],
        )

        if app_el is None:
            results.append({
                "element": element_name,
                "proto_size": f"{proto_w}x{proto_h}dp",
                "app_size": "NOT_FOUND",
                "delta": "N/A",
                "over_tolerance": True,
            })
            continue

        app_x1, app_y1, app_x2, app_y2 = app_el["bounds_px"]
        app_w_dp = round(app_el["width_px"] / DP_SCALE, 1)
        app_h_dp = round(app_el["height_px"] / DP_SCALE, 1)
        delta_w = round(app_w_dp - proto_w, 1)
        delta_h = round(app_h_dp - proto_h, 1)
        over_tolerance = abs(delta_w) > SIZE_TOLERANCE_DP or abs(delta_h) > SIZE_TOLERANCE_DP

        results.append({
            "element": element_name,
            "proto_size": f"{proto_w}x{proto_h}dp",
            "app_size": f"{app_w_dp}x{app_h_dp}dp",
            "delta": f"{delta_w:+}/{delta_h:+}dp",
            "over_tolerance": over_tolerance,
            "app_text": app_el["text"][:30],
        })

        # 裁剪对比图
        proto_crop = crop_element(proto_image, (int(proto_x), int(proto_y), int(proto_x + proto_w), int(proto_y + proto_h)))
        app_crop = crop_element(app_image, (app_x1, app_y1, app_x2, app_y2))

        comp_img = create_comparison_image(
            proto_crop, app_crop, element_name,
            f"{proto_w}x{proto_h}dp",
            f"{app_w_dp}x{app_h_dp}dp",
            f"{delta_w:+}/{delta_h:+}dp",
            over_tolerance,
        )
        comp_path = output_dir / f"{args.view}_{element_name}.png"
        comp_img.save(comp_path)
        comparison_images.append(str(comp_path))

    # 输出报告
    report = {
        "view": args.view,
        "tolerance_dp": SIZE_TOLERANCE_DP,
        "results": results,
        "comparison_images": comparison_images,
    }
    report_path = output_dir / f"{args.view}_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    # 打印摘要
    print(f"\n=== 元素对比报告: {args.view} ===")
    print(f"{'元素':<20} {'原型(dp)':<15} {'实机(dp)':<15} {'偏差(dp)':<15} {'状态'}")
    print("-" * 80)
    for r in results:
        status = "OVER" if r["over_tolerance"] else "OK"
        print(f"{r['element']:<20} {r['proto_size']:<15} {r['app_size']:<15} {r['delta']:<15} {status}")

    over_count = sum(1 for r in results if r["over_tolerance"])
    print(f"\n超差: {over_count}/{len(results)}")
    print(f"对比图: {output_dir}")
    print(f"报告: {report_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
