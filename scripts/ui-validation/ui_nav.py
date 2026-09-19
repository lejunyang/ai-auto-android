#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能用途：模拟器 UI 导航辅助工具。通过 uiautomator dump 按文本查找元素中心坐标，
支持仅查找（find）与查找后点击（tap），避免在命令行内联 Python 造成引号转义问题。

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


def find_text(text):
    """返回所有文本包含 text 的节点：(完整文本, 中心x, 中心y, left, top, right, bottom)。"""
    xml = dump_xml()
    results = []
    # 逐节点解析，属性顺序不固定，分别取 text 与 bounds。
    for node in re.findall(r"<node\b[^>]*>", xml):
        tm = re.search(r'text="([^"]*)"', node)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not tm or not bm or text not in tm.group(1):
            continue
        left, top, right, bottom = map(int, bm.groups())
        results.append((tm.group(1), (left + right) // 2, (top + bottom) // 2,
                        left, top, right, bottom))
    return results


def main():
    """命令入口：find 打印匹配；tap 在匹配项中心执行点击。"""
    if len(sys.argv) < 3:
        print("用法: ui_nav.py find|tap <文本> [序号]")
        return
    action, text = sys.argv[1], sys.argv[2]
    matches = find_text(text)
    for item in matches:
        print(item)
    if action == "tap":
        idx = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        if matches:
            _, cx, cy, *_ = matches[idx]
            adb("shell", "input", "tap", str(cx), str(cy))
            print("TAPPED", cx, cy)
        else:
            print("NO MATCH")


if __name__ == "__main__":
    main()
