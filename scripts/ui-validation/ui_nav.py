#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能用途：模拟器 UI 导航辅助工具。通过 uiautomator dump 按文本查找元素中心坐标，
支持仅查找（find）与查找后点击（tap）。点击默认命中“包含该文字的最小可点击容器”，
避免点到不处理手势的文字子节点导致操作不生效。

用法：
  python ui_nav.py find "连接电脑"
  python ui_nav.py tap  "连接电脑" [匹配序号，默认0]
仅用于 debug 自动化验收，坐标全部来自当次 dump，不使用硬编码坐标。
"""

import os
import re
import subprocess
import sys

# 平台工具与目标设备固定 serial，防止多设备时隐式选错。
ADB = r"E:\osdk-data\data\installs\android-platform-tools\37.0.1\adb.exe"
SERIAL = "emulator-5554"


def adb(*args):
    """执行 adb 命令并返回标准输出文本。"""
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True).stdout


def dump_xml():
    """在设备上生成当前界面 dump 并拉取到本地临时目录，返回 XML 文本。"""
    adb("shell", "uiautomator", "dump", "/sdcard/d.xml")
    local = os.path.join(os.environ["TEMP"], "ui_nav_dump.xml")
    subprocess.run([ADB, "-s", SERIAL, "pull", "/sdcard/d.xml", local], capture_output=True)
    with open(local, encoding="utf-8") as handle:
        return handle.read()


def all_nodes():
    """解析 dump 为节点字典列表：text、clickable、box(left,top,right,bottom)。"""
    xml = dump_xml()
    nodes = []
    for raw in re.findall(r"<node\b[^>]*>", xml):
        tm = re.search(r'text="([^"]*)"', raw)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
        cm = re.search(r'clickable="(true|false)"', raw)
        if not bm:
            continue
        nodes.append({
            "text": tm.group(1) if tm else "",
            "clickable": bool(cm and cm.group(1) == "true"),
            "box": tuple(map(int, bm.groups())),
        })
    return nodes


def find_text(text):
    """返回所有文本包含 text 的节点：(完整文本, 中心x, 中心y, left, top, right, bottom)。"""
    results = []
    for node in all_nodes():
        if text not in node["text"]:
            continue
        left, top, right, bottom = node["box"]
        results.append((node["text"], (left + right) // 2, (top + bottom) // 2,
                        left, top, right, bottom))
    return results


def clickable_target(text, index=0):
    """定位包含目标文字的最小可点击容器中心；无则回退到文字节点中心。"""
    nodes = all_nodes()
    text_nodes = [node for node in nodes if text in node["text"]]
    if not text_nodes or index >= len(text_nodes):
        return None
    left, top, right, bottom = text_nodes[index]["box"]
    cx, cy = (left + right) // 2, (top + bottom) // 2

    best = None
    best_area = None
    for node in nodes:
        if not node["clickable"]:
            continue
        l, t, r, b = node["box"]
        if l <= cx <= r and t <= cy <= b:
            area = (r - l) * (b - t)
            if best is None or area < best_area:
                best = node
                best_area = area
    target_box = best["box"] if best else text_nodes[index]["box"]
    l, t, r, b = target_box
    return (l + r) // 2, (t + b) // 2


def main():
    """命令入口：find 打印匹配；tap 命中最小可点击容器中心执行点击。"""
    if len(sys.argv) < 3:
        print("用法: ui_nav.py find|tap <文本> [序号]")
        return
    action, text = sys.argv[1], sys.argv[2]
    for item in find_text(text):
        print(item)
    if action == "tap":
        index = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        target = clickable_target(text, index)
        if target:
            cx, cy = target
            adb("shell", "input", "tap", str(cx), str(cy))
            print("TAPPED", cx, cy)
        else:
            print("NO MATCH")


if __name__ == "__main__":
    main()
