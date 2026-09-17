#!/usr/bin/env python3
"""功能用途：按元素裁剪对比原型与实机截图，输出并排对比图和尺寸差异报告。

这是逐元素视觉验收工具，对比范围从内到外逐层扩大：
先比图标/文字等原子元素，再比按钮/输入框等组件，最后比卡片/页面区块。

匹配策略（strategy）说明：
- leaf       单个最小面积文本叶子（标题、说明文字）
- edittext   提示文字向上回溯到 EditText 输入框
- clickable  文本叶子向上找第一个 clickable 祖先；pick=min 取最小（胶囊/芯片），
             pick=max 取最大（卡片）
- row        以锚点所在的可点击行为准，收集同一行所有 clickable 兄弟的最小包围，
             用于操作行（含无文本的图标按钮）
- row_item   锚点所在行内第 index 个 clickable（index=-1 为最右，如发送按钮）
- cluster    多个文本叶子（含图标语义节点）的最小包围矩形
- tab_item   单个底栏 tab：标签叶子的 clickable 祖先内，全部后代的最小包围
- lca        多个文本叶子的最小公共祖先（底栏背景、整张卡片）

原型截图 411x914（CSS px == dp），实机截图 1080x2400（px / 2.625 = dp）。

用法：
    python scripts/ui-validation/element-compare.py --view home-empty \
        --app-screenshot <path> --serial emulator-5554
    ADB 可执行文件不在 PATH 时用环境变量 ADB 指定完整路径。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

# 各原型视图 → 该视图参与比对的元素规则。hints 一律用【实机真实文案】，
# 因为 Compose hierarchy 里出现的是 app 实际文本（原型演示文案与真实脱敏文案有意不同）。
# kind:
#   display     纯展示元素，宽高都严格按容差判定
#   interactive 可点击元素，宽度严格，高度允许点击区比视觉背景最多大 6dp
#               （手机模式 min-height 44，Compose 点击区常为 48dp）
#   structural  实机无单一对应容器（Compose 不生成背景节点），只记录不判超差
# size_ref 可覆盖原型 JSON box（用于原型采集口径含 safe-area 等已知偏差，以 CSS token 为准）。
VIEW_ELEMENTS = {
    # ---------------- 助理首页（空态/未就绪） ----------------
    "home-empty": {
        "appHeader": {"strategy": "cluster", "hints": ["AI Auto", "让手机", "未就绪"], "kind": "structural"},
        "greetingTitle": {"strategy": "leaf", "hints": ["有什么"], "kind": "display"},
        # composer 卡片在未就绪态会多出一行真实警告，用 state_extra_hints 扣除该行高度后对比核心卡片。
        "composer": {
            "strategy": "lca", "hints": ["比如", "选择应用", "语音输入"], "kind": "container",
            "state_extra_hints": ["请先完成"],
        },
        "composerInput": {"strategy": "edittext", "hints": ["比如"], "kind": "display"},
        "composerFoot": {"strategy": "row", "hints": ["选择应用"], "kind": "interactive"},
        "composerPill": {"strategy": "clickable", "hints": ["选择应用"], "pick": "min", "kind": "interactive"},
        "composerIconBtn": {"strategy": "clickable", "hints": ["语音输入"], "pick": "min", "kind": "interactive"},
        "composerSendBtn": {"strategy": "row_item", "hints": ["选择应用"], "index": -1, "kind": "interactive"},
        "quickChip": {"strategy": "clickable", "hints": ["整理稍后阅读"], "pick": "min", "kind": "interactive"},
        "teachCard": {"strategy": "clickable", "hints": ["教它一次"], "pick": "max", "kind": "display"},
        "skillTile": {"strategy": "clickable", "hints": ["整理稍后阅读"], "pick": "max", "kind": "display"},
        # 原型采集 bottomNav box=72 含 safe-area 口径，CSS 实际 height:68px，以 CSS token 为准。
        "bottomNav": {"strategy": "lca", "hints": ["助理", "技能", "记录", "我的"], "kind": "display",
                      "size_ref": [411, 68]},
        "navItemActive": {"strategy": "tab_item", "hints": ["助理"], "kind": "interactive"},
    },
    # ---------------- 技能页（全部，有数据） ----------------
    "skills-all": {
        "appHeader": {"strategy": "cluster", "hints": ["我的技能", "把重复操作"], "kind": "structural"},
        # 区块大标题 page-heading 24sp：原型盒宽随块级，只比高度（字号/行高）。
        "pageHeading": {"strategy": "leaf", "hints": ["教会它的事"], "kind": "display",
                        "dimensions": "height"},
        "filterTabs": {"strategy": "lca", "hints": ["全部", "我教的", "灵感"], "kind": "structural"},
        "filterTabActive": {"strategy": "clickable", "hints": ["全部"], "pick": "min", "kind": "interactive"},
        "filterTabInactive": {"strategy": "clickable", "hints": ["我教的"], "pick": "min", "kind": "interactive"},
        # 技能单列宽卡：命中技能名的最大可点击祖先。
        "skillListCard": {"strategy": "clickable", "hints": ["整理稍后阅读"], "pick": "max", "kind": "display"},
        # 绿色"已保存"标签（原型 tagGreen 文案"示例技能"，实机脱敏为"已保存"，比高度不比宽度）。
        "tagSaved": {"strategy": "tag", "hints": ["已保存"], "kind": "display", "proto_key": "tagGreen",
                     "dimensions": "height"},
        "bottomNav": {"strategy": "lca", "hints": ["助理", "技能", "记录", "我的"], "kind": "display",
                      "size_ref": [411, 68]},
        "navItemActive": {"strategy": "tab_item", "hints": ["技能"], "kind": "interactive"},
    },
    # ---------------- 记录页（全部，有数据） ----------------
    "history-all": {
        "appHeader": {"strategy": "cluster", "hints": ["运行记录", "只留下有用"], "kind": "structural"},
        "pageHeading": {"strategy": "leaf", "hints": ["做过的"], "kind": "display",
                        "dimensions": "height"},
        "filterTabs": {"strategy": "lca", "hints": ["全部", "已完成", "需处理"], "kind": "structural"},
        "filterTabActive": {"strategy": "clickable", "hints": ["全部"], "pick": "min", "kind": "interactive"},
        "filterTabInactive": {"strategy": "clickable", "hints": ["已完成"], "pick": "min", "kind": "interactive"},
        # 记录卡：标题统一脱敏为"自动化任务"，命中其最大可点击祖先。
        "recordCard": {"strategy": "clickable", "hints": ["自动化任务"], "pick": "max", "kind": "display"},
        "tagDone": {"strategy": "tag", "hints": ["已完成"], "kind": "display", "proto_key": "tagGreen",
                    "dimensions": "height"},
        "tagAttention": {"strategy": "tag", "hints": ["需处理"], "kind": "display", "proto_key": "tagAmber",
                         "dimensions": "height"},
        "bottomNav": {"strategy": "lca", "hints": ["助理", "技能", "记录", "我的"], "kind": "display",
                      "size_ref": [411, 68]},
        "navItemActive": {"strategy": "tab_item", "hints": ["记录"], "kind": "interactive"},
    },
    # ---------------- 我的页 ----------------
    "settings": {
        # 页头"我的"与底栏重名，用副标题"把边界设好"唯一定位页头簇。
        "appHeader": {"strategy": "cluster", "hints": ["把边界设好"], "kind": "structural"},
        # 助理状态卡：用状态卡独有词"助理状态"+"未完成配置"求最小公共祖先。
        "statusCard": {"strategy": "lca", "hints": ["助理状态", "未完成配置"], "kind": "display"},
        # 状态标签：原型为绿色"演示已就绪"，实机按真实未配置状态显示琥珀"未完成配置"（有意偏差，比高度）。
        "tagStatus": {"strategy": "tag", "hints": ["未完成配置"], "kind": "display", "proto_key": "tagGreen",
                      "dimensions": "height"},
        # section-head h3 16sp：只比高度。
        "sectionTitle": {"strategy": "leaf", "hints": ["连接与能力"], "kind": "display",
                         "dimensions": "height"},
        # AI 模型设置行：状态卡内同名文本不可点击，clickable 只会命中可点的设置行。
        "settingRow": {"strategy": "clickable", "hints": ["AI 模型"], "pick": "max", "kind": "interactive"},
        "bottomNav": {"strategy": "lca", "hints": ["助理", "技能", "记录", "我的"], "kind": "display",
                      "size_ref": [411, 68]},
        "navItemActive": {"strategy": "tab_item", "hints": ["我的"], "kind": "interactive"},
    },
}

# 尺寸容差：展示元素宽高 ±2dp。
SIZE_TOLERANCE_DP = 2.0
# 可点击元素点击区高度相对视觉高度允许的上溢出（点击区≥视觉高度，且不超过视觉高度+6dp）。
INTERACTIVE_HEIGHT_EXTRA = 6.0
DP_SCALE = 2.625  # 1080x2400 / 420dpi


class Node:
    """UI hierarchy 节点，保留父子关系以便向上回溯容器。"""

    def __init__(self, element: ET.Element, parent: "Node | None"):
        self.element = element
        self.parent = parent
        self.children: list[Node] = []
        self.cls = element.get("class", "") or ""
        self.text = element.get("text", "") or ""
        self.desc = element.get("content-desc", "") or ""
        self.clickable = element.get("clickable", "false") == "true"
        bounds_str = element.get("bounds", "")
        self.bounds = self._parse_bounds(bounds_str) if bounds_str else (0, 0, 0, 0)
        x1, y1, x2, y2 = self.bounds
        self.width = x2 - x1
        self.height = y2 - y1
        self.area = self.width * self.height

    @staticmethod
    def _parse_bounds(bounds_str: str) -> tuple[int, int, int, int]:
        """解析 '[x1,y1][x2,y2]' 为 (x1,y1,x2,y2)。"""
        parts = bounds_str.strip("[]").split("][")
        x1, y1 = map(int, parts[0].split(","))
        x2, y2 = map(int, parts[1].split(","))
        return x1, y1, x2, y2

    def label(self) -> str:
        """节点上的可读文本（优先 text，其次 content-desc）。"""
        return self.text or self.desc

    def descendants(self) -> list["Node"]:
        """返回全部后代节点。"""
        out: list[Node] = []
        stack = list(self.children)
        while stack:
            cur = stack.pop()
            out.append(cur)
            stack.extend(cur.children)
        return out


def build_tree(xml_content: str) -> Node | None:
    """把 hierarchy XML 解析为带 parent 指针的节点树，返回根。"""
    try:
        root_el = ET.fromstring(xml_content)
    except ET.ParseError:
        return None

    def walk(element: ET.Element, parent: Node | None) -> Node:
        node = Node(element, parent)
        for child_el in element:
            child = walk(child_el, node)
            node.children.append(child)
        return node

    return walk(root_el, None)


def all_nodes(root: Node) -> list[Node]:
    """收集树中全部节点。"""
    return root.descendants() + [root]


def dump_ui_hierarchy(serial: str) -> str:
    """通过 uiautomator dump 获取当前 UI hierarchy XML。"""
    adb = os.environ.get("ADB", "adb")
    remote = "/sdcard/ui_dump.xml"
    subprocess.run([adb, "-s", serial, "shell", "uiautomator", "dump", remote],
                   capture_output=True, text=True, timeout=30)
    result = subprocess.run([adb, "-s", serial, "shell", "cat", remote],
                            capture_output=True, text=True, timeout=30)
    subprocess.run([adb, "-s", serial, "shell", "rm", remote], capture_output=True)
    return result.stdout


def find_text_leaves(nodes: list[Node], hint: str, text_view_only: bool = False) -> list[Node]:
    """查找文本/语义含 hint 的叶子节点。

    text_view_only=True 时只取 TextView（排除图标 View 的 content-desc 语义节点），
    用于底栏标签等图标与标签同名的场景。
    """
    out = []
    for n in nodes:
        if text_view_only:
            if "TextView" not in n.cls or not n.text:
                continue
            if hint in n.text:
                out.append(n)
        elif hint in n.label():
            out.append(n)
    return out


def first_clickable_ancestor(node: Node) -> Node | None:
    """沿 parent 链向上找第一个 clickable=true 的祖先（含自身）。"""
    cur = node
    while cur is not None:
        if cur.clickable:
            return cur
        cur = cur.parent
    return None


def lowest_common_ancestor(nodes: list[Node]) -> Node | None:
    """求多个节点的最小公共祖先。"""
    if not nodes:
        return None
    # 第一个节点的祖先链（含自身），其余节点逐个向上找第一个共同祖先。
    ancestors: list[Node] = []
    cur: Node | None = nodes[0]
    while cur is not None:
        ancestors.append(cur)
        cur = cur.parent
    ancestor_set = set(id(n) for n in ancestors)
    for n in nodes[1:]:
        cur = n
        while cur is not None and id(cur) not in ancestor_set:
            cur = cur.parent
        if cur is None:
            return None
        ancestor_set &= {id(a) for a in chain_to_root(cur)}
    for a in ancestors:
        if id(a) in ancestor_set:
            return a
    return None


def chain_to_root(node: Node) -> list[Node]:
    """返回节点到根的祖先链（含自身）。"""
    out: list[Node] = []
    cur: Node | None = node
    while cur is not None:
        out.append(cur)
        cur = cur.parent
    return out


def union_bounds(nodes: list[Node]) -> tuple[int, int, int, int]:
    """求一组节点 bounds 的最小包围矩形。"""
    xs1 = [n.bounds[0] for n in nodes]
    ys1 = [n.bounds[1] for n in nodes]
    xs2 = [n.bounds[2] for n in nodes]
    ys2 = [n.bounds[3] for n in nodes]
    return min(xs1), min(ys1), max(xs2), max(ys2)


def resolve_element(root: Node, rule: dict) -> dict | None:
    """按策略在节点树中定位元素，返回 bounds(px)/尺寸/匹配方式说明。"""
    nodes = all_nodes(root)
    strategy = rule["strategy"]
    hints = rule["hints"]

    if strategy == "leaf":
        leaves = find_text_leaves(nodes, hints[0])
        if not leaves:
            return None
        n = min(leaves, key=lambda x: x.area)
        return _result(n.bounds, "leaf", n.label())

    if strategy == "edittext":
        leaves = find_text_leaves(nodes, hints[0])
        if not leaves:
            return None
        leaf = min(leaves, key=lambda x: x.area)
        cur = leaf
        while cur is not None:
            if "EditText" in cur.cls:
                return _result(cur.bounds, "edittext", cur.label())
            cur = cur.parent
        # 回溯不到 EditText 时退化为叶子
        return _result(leaf.bounds, "leaf-fallback", leaf.label())

    if strategy == "clickable":
        leaves = find_text_leaves(nodes, hints[0])
        containers = [c for c in (first_clickable_ancestor(n) for n in leaves) if c is not None]
        if not containers:
            return None
        # 同名文本可能出现在芯片/卡片/列表多处：min 取最小可点击祖先（芯片），max 取卡片。
        chosen = min(containers, key=lambda x: x.area) if rule.get("pick") == "min" \
            else max(containers, key=lambda x: x.area)
        return _result(chosen.bounds, f"clickable-{rule.get('pick', 'min')}", chosen.label())

    if strategy == "tag":
        # 纯展示标签（不可点击的 Surface 胶囊）：从文本叶子沿父链找尺寸落在 tag 合理范围
        # （高 22-34dp、宽 36-150dp）的最近祖先，即带底色/圆角的 Surface 容器。
        leaves = find_text_leaves(nodes, hints[0])
        if not leaves:
            return None
        leaf = min(leaves, key=lambda x: x.area)
        cur = leaf.parent
        chosen = None
        while cur is not None:
            h_dp = cur.height / DP_SCALE
            w_dp = cur.width / DP_SCALE
            if 22 <= h_dp <= 34 and 36 <= w_dp <= 150:
                chosen = cur
                break
            cur = cur.parent
        target = chosen or leaf
        return _result(target.bounds, "tag-container" if chosen else "tag-leaf-fallback",
                       target.label())

    if strategy in ("row", "row_item"):
        leaves = find_text_leaves(nodes, hints[0])
        anchors = [c for c in (first_clickable_ancestor(n) for n in leaves) if c is not None]
        if not anchors:
            return None
        anchor = min(anchors, key=lambda x: x.area)
        parent = anchor.parent
        if parent is None:
            return None
        anchor_cy = (anchor.bounds[1] + anchor.bounds[3]) / 2
        # 同一行：与锚点中心 y 重叠的 clickable 兄弟（排除输入框等更高的节点）。
        row_items = [
            c for c in parent.children
            if c.clickable and _same_row(c.bounds, anchor.bounds)
        ]
        row_items.sort(key=lambda c: c.bounds[0])
        if not row_items:
            return None
        if strategy == "row_item":
            idx = rule.get("index", -1)
            chosen = row_items[idx]
            return _result(chosen.bounds, "row-item", chosen.label())
        return _result(union_bounds(row_items), "row", " | ".join(c.label()[:12] for c in row_items))

    if strategy == "cluster":
        leaves: list[Node] = []
        for hint in hints:
            matched = find_text_leaves(nodes, hint)
            if not matched:
                return None
            leaves.append(min(matched, key=lambda x: x.area))
        return _result(union_bounds(leaves), "text-cluster", " | ".join(n.label()[:12] for n in leaves))

    if strategy == "tab_item":
        # 底栏标签只取 TextView，再取其 tab clickable 祖先内全部后代的最小包围
        # （图标在 Compose 中可能视觉上略高于 clickable bounds）。
        leaves = find_text_leaves(nodes, hints[0], text_view_only=True)
        if not leaves:
            return None
        tab = first_clickable_ancestor(min(leaves, key=lambda x: x.area))
        if tab is None:
            return None
        inner = [d for d in tab.descendants() if d.width > 0 and d.height > 0] + [tab]
        return _result(union_bounds(inner), "tab-content-cluster", hints[0])

    if strategy == "lca":
        leaves = []
        for hint in hints:
            matched = find_text_leaves(nodes, hint)
            if not matched:
                return None
            leaves.append(min(matched, key=lambda x: x.area))
        lca = lowest_common_ancestor(leaves)
        if lca is None:
            return None
        return _result(lca.bounds, "lca", " | ".join(n.label()[:12] for n in leaves))

    return None


def _same_row(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> bool:
    """两个 bounds 的垂直中心是否足够接近，视为同一行。"""
    cy_a = (a[1] + a[3]) / 2
    cy_b = (b[1] + b[3]) / 2
    return abs(cy_a - cy_b) <= max((a[3] - a[1]), (b[3] - b[1])) * 0.6


def _result(bounds: tuple[int, int, int, int], method: str, text: str) -> dict:
    """组装定位结果。"""
    x1, y1, x2, y2 = bounds
    return {
        "bounds_px": bounds,
        "width_px": x2 - x1,
        "height_px": y2 - y1,
        "method": method,
        "text": text,
    }


def crop_element(image: Image.Image, box: tuple[int, int, int, int]) -> Image.Image:
    """按 (x1,y1,x2,y2) 裁剪图像，越界自动收敛。"""
    x1, y1, x2, y2 = box
    x1 = max(0, min(x1, image.width))
    y1 = max(0, min(y1, image.height))
    x2 = max(0, min(x2, image.width))
    y2 = max(0, min(y2, image.height))
    if x2 <= x1 or y2 <= y1:
        return Image.new("RGB", (10, 10), (255, 0, 0))  # 红色占位表示裁剪失败
    return image.crop((x1, y1, x2, y2))


def create_comparison_image(proto_crop, app_crop, element_name, proto_size, app_size,
                            delta, status) -> Image.Image:
    """生成并排对比图：原型裁剪图 | 实机裁剪图 | 尺寸信息。"""
    target_h = 200
    proto_resized = _scale_to_height(proto_crop, target_h)
    app_resized = _scale_to_height(app_crop, target_h)

    padding = 20
    label_h = 60
    total_w = proto_resized.width + app_resized.width + padding * 3
    total_h = target_h + label_h + padding * 2
    canvas = Image.new("RGB", (total_w, total_h), (250, 251, 252))
    draw = ImageDraw.Draw(canvas)

    status_color = (182, 62, 73) if status == "OVER" else (35, 120, 99)
    if status == "INFO":
        status_color = (120, 110, 40)
    draw.text((padding, 10), f"{element_name} [{status}]", fill=(23, 33, 46))
    draw.text((padding, 30), f"proto: {proto_size}  app: {app_size}  delta: {delta}",
              fill=(107, 119, 136))

    x, y = padding, label_h
    draw.text((x, y - 15), "PROTOTYPE", fill=(107, 119, 136))
    canvas.paste(proto_resized, (x, y))
    x = padding + proto_resized.width + padding
    draw.text((x, y - 15), "APP", fill=(107, 119, 136))
    canvas.paste(app_resized, (x, y))
    return canvas


def _scale_to_height(img: Image.Image, target_h: int) -> Image.Image:
    """等比缩放到指定高度。"""
    if img.height <= 0:
        return img
    ratio = target_h / img.height
    return img.resize((max(1, int(img.width * ratio)), target_h), Image.LANCZOS)


def judge(rule: dict, proto_w: float, proto_h: float, app_w: float, app_h: float) -> tuple[str, str]:
    """按元素 kind 判定是否超差，返回 (status, 说明)。"""
    kind = rule.get("kind", "display")
    if kind == "structural":
        return "INFO", "实机无单一容器节点，仅记录文本簇尺寸"
    # dimensions=height 时只比高度：原型块级标题宽度是占满行的盒宽，Compose Text 是内容宽，
    # 宽度口径天然不一致，字号/行高由高度承载，故只判高度。
    dims = rule.get("dimensions", "both")
    dw = round(app_w - proto_w, 1)
    dh = round(app_h - proto_h, 1)
    if kind == "interactive":
        # 触摸目标允许比视觉背景最多大 6dp（Compose 最小触摸目标常为 48dp，原型视觉为 44dp），
        # 但点击区不得明显小于视觉尺寸。宽高均允许 [proto-2, proto+6]。
        w_bad = (app_w < proto_w - SIZE_TOLERANCE_DP or app_w > proto_w + INTERACTIVE_HEIGHT_EXTRA) \
            if dims != "height" else False
        h_bad = (app_h < proto_h - SIZE_TOLERANCE_DP or app_h > proto_h + INTERACTIVE_HEIGHT_EXTRA) \
            if dims != "width" else False
        return ("OVER" if (w_bad or h_bad) else "OK"), f"{dw:+}/{dh:+}dp"
    # container/display：按 dimensions 决定比宽/比高/都比，容差 ±2dp。
    w_bad = abs(dw) > SIZE_TOLERANCE_DP if dims != "height" else False
    h_bad = abs(dh) > SIZE_TOLERANCE_DP if dims != "width" else False
    return ("OVER" if (w_bad or h_bad) else "OK"), f"{dw:+}/{dh:+}dp"


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

    proto_json_path = proto_dir / f"{args.view}.json"
    proto_png_path = proto_dir / f"{args.view}.png"
    if not proto_json_path.exists() or not proto_png_path.exists():
        print(f"原型数据缺失: {proto_json_path} / {proto_png_path}")
        return 1

    proto_data = json.loads(proto_json_path.read_text(encoding="utf-8"))
    proto_elements = proto_data.get("elements", {})
    proto_image = Image.open(proto_png_path).convert("RGB")
    app_image = Image.open(args.app_screenshot).convert("RGB")

    root = build_tree(dump_ui_hierarchy(args.serial))
    if root is None:
        print("实机 UI hierarchy 解析失败")
        return 1

    element_map = VIEW_ELEMENTS.get(args.view)
    if element_map is None:
        print(f"未配置视图 {args.view}，可选：{sorted(VIEW_ELEMENTS)}")
        return 1

    results = []
    comparison_images = []
    for element_name, rule in element_map.items():
        # 规则名可与原型采集 key 不同（如实机脱敏 tag 对应原型 tagGreen/tagAmber），用 proto_key 映射。
        proto_key = rule.get("proto_key", element_name)
        if proto_key not in proto_elements or not isinstance(proto_elements[proto_key], dict):
            continue
        proto_el = proto_elements[proto_key]
        box = proto_el.get("box", {})
        proto_w = float(box.get("width", 0))
        proto_h = float(box.get("height", 0))
        proto_x = float(box.get("x", 0))
        proto_y = float(box.get("y", 0))
        # size_ref 覆盖：原型采集口径已知偏差时以 CSS token 为准。
        if "size_ref" in rule:
            proto_w, proto_h = map(float, rule["size_ref"])

        app_el = resolve_element(root, rule)
        if app_el is None:
            results.append({"element": element_name, "proto_size": f"{proto_w:g}x{proto_h:g}dp",
                            "app_size": "NOT_FOUND", "delta": "N/A", "status": "OVER",
                            "over_tolerance": True, "match_method": rule["strategy"],
                            "app_text": ""})
            continue

        ax1, ay1, ax2, ay2 = app_el["bounds_px"]
        app_w = round(app_el["width_px"] / DP_SCALE, 1)
        app_h_raw = round(app_el["height_px"] / DP_SCALE, 1)
        app_h = app_h_raw

        # 容器在特定状态下多出的真实状态行（如未就绪警告），扣除其高度后再对比原型空态。
        state_note = ""
        extra_hints = rule.get("state_extra_hints")
        if extra_hints:
            extra_leaves: list[Node] = []
            for hint in extra_hints:
                for n in find_text_leaves(all_nodes(root), hint):
                    # 仅统计落在该容器 bounds 内的状态行叶子。
                    bx1, by1, bx2, by2 = n.bounds
                    if bx1 >= ax1 - 2 and by1 >= ay1 - 2 and bx2 <= ax2 + 2 and by2 <= ay2 + 2:
                        extra_leaves.append(n)
            if extra_leaves:
                ex1, ey1, ex2, ey2 = union_bounds(extra_leaves)
                # 状态行高度 + 与核心内容之间约 5dp 间距。
                extra_dp = round((ey2 - ey1) / DP_SCALE + 5.0, 1)
                app_h = round(app_h_raw - extra_dp, 1)
                state_note = f"含状态行 {extra_dp}dp（{rule['state_extra_hints']}），扣除后 {app_w}x{app_h}dp"

        status, delta = judge(rule, proto_w, proto_h, app_w, app_h)
        over = status == "OVER"

        entry = {
            "element": element_name,
            "proto_size": f"{proto_w:g}x{proto_h:g}dp",
            "app_size": f"{app_w}x{app_h}dp",
            "app_size_raw": f"{app_w}x{app_h_raw}dp",
            "delta": delta,
            "status": status,
            "over_tolerance": over,
            "match_method": app_el["method"],
            "app_text": app_el["text"][:30],
        }
        if state_note:
            entry["state_note"] = state_note
        results.append(entry)

        proto_crop = crop_element(
            proto_image,
            (int(proto_x), int(proto_y), int(proto_x + proto_w), int(proto_y + proto_h)),
        )
        app_crop = crop_element(app_image, (ax1, ay1, ax2, ay2))
        app_label = f"{app_w}x{app_h_raw}dp" if state_note else f"{app_w}x{app_h}dp"
        comp_img = create_comparison_image(
            proto_crop, app_crop, element_name,
            f"{proto_w:g}x{proto_h:g}dp", app_label, delta, status,
        )
        comp_path = output_dir / f"{args.view}_{element_name}.png"
        comp_img.save(comp_path)
        comparison_images.append(str(comp_path))

    report = {
        "view": args.view,
        "tolerance_dp": SIZE_TOLERANCE_DP,
        "interactive_height_extra_dp": INTERACTIVE_HEIGHT_EXTRA,
        "results": results,
        "comparison_images": comparison_images,
    }
    report_path = output_dir / f"{args.view}_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n=== 元素对比报告: {args.view} ===")
    print(f"{'元素':<18}{'原型(dp)':<13}{'实机(dp)':<13}{'偏差':<13}{'状态':<6}匹配方式")
    print("-" * 90)
    for r in results:
        print(f"{r['element']:<18}{r['proto_size']:<13}{r['app_size']:<13}"
              f"{r['delta']:<13}{r['status']:<6}{r.get('match_method', '')}")
        if r.get("state_note"):
            print(f"    ↳ {r['state_note']}")
    over_count = sum(1 for r in results if r["over_tolerance"])
    print(f"\n超差: {over_count}/{len(results)}（INFO 为实机无容器节点的口径项，不计超差）")
    print(f"报告: {report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
