#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能用途：通过 debug 悬浮窗演练场（OverlayDemoActivity）确定性地逐一切换悬浮窗状态并截图留证。

流程：启动演练场 -> uiautomator dump 解析各按钮坐标 -> 按预设顺序点击按钮 ->
每步 screencap 并 pull 到 artifacts/ui-validation/overlay-states/。
仅用于 UI 验证，不操作支付/登录/系统设置；演练场本身仅 debug 源集存在。
"""

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

# adb 路径、设备序列号、演练场 Activity 组件名与截图输出目录。
ADB = r"E:\osdk-data\data\installs\android-platform-tools\37.0.1\adb.exe"
SERIAL = "emulator-5554"
ACT = "dev.aiauto.android/.ui.overlay.debug.OverlayDemoActivity"
PKG = "dev.aiauto.android"
OUT = r"E:\Projects\ai-auto-android\artifacts\ui-validation\overlay-states"


def adb_run(*args):
    """执行 adb 命令并返回 stdout 文本。"""
    cmd = [ADB, "-s", SERIAL, *args]
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.stdout.strip()


def shell(*args):
    """在设备 shell 内执行命令。"""
    return adb_run("shell", *args)


def parse_bounds(bounds):
    """解析 Android bounds '[x1,y1][x2,y2]' 为中心坐标 (cx, cy)。"""
    nums = list(map(int, re.findall(r"\d+", bounds)))
    x1, y1, x2, y2 = nums
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    os.makedirs(OUT, exist_ok=True)

    # 先强制停止，保证从干净状态进入演练场，避免 Activity 复用导致状态错乱。
    shell("am", "force-stop", PKG)
    # 启动演练场 Activity（其 onCreate 会 show 收起态悬浮窗）。
    shell("am", "start", "-n", ACT)
    time.sleep(1.6)

    # dump 当前界面并 pull，解析演练场按钮坐标。
    shell("uiautomator", "dump", "/sdcard/ui.xml")
    ui_xml = os.path.join(OUT, "ui.xml")
    adb_run("pull", "/sdcard/ui.xml", ui_xml)

    buttons = {}
    for node in ET.parse(ui_xml).iter("node"):
        if node.get("class") == "android.widget.Button" and node.get("text"):
            buttons[node.get("text")] = parse_bounds(node.get("bounds"))
    print("检测到按钮：", list(buttons.keys()))

    # 预设截图序列：(演练场按钮文案, 输出文件名)。
    seq = [
        ("RUNNING 展开", "s1-running-expanded"),
        ("收起为边缘圆", "s2-collapsed-dot"),
        ("重新展开", "s3-reexpanded"),
        ("暂停态", "s4-paused"),
        ("继续/RUNNING", "s5-resumed-running"),
        ("停止态", "s6-stopped"),
        ("完成结果态", "s7-result"),
        ("失败态", "s8-failed"),
        ("隐藏悬浮窗", "s9-hidden"),
        ("显示悬浮窗", "s10-shown"),
    ]

    for label, name in seq:
        if label not in buttons:
            print("缺少按钮，跳过：", label)
            continue
        cx, cy = buttons[label]
        shell("input", "tap", str(cx), str(cy))
        time.sleep(0.9)
        shell("screencap", "-p", f"/sdcard/{name}.png")
        local = os.path.join(OUT, f"{name}.png")
        adb_run("pull", f"/sdcard/{name}.png", local)
        print("已截图：", name, "->", local)


if __name__ == "__main__":
    main()
