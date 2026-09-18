#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能用途：补齐悬浮窗"暂停 -> 继续 -> running -> 停止"的真实设备证据。

走真实交互路径（点悬浮窗卡片自身按钮，而非被卡片遮挡的演练场按钮）：
暂停卡片点"继续" -> 应回到 running 卡片；running 卡片点"停止" -> 呈现停止态。
每步坐标均来自当次 uiautomator dump，不跨页复用；截图覆盖 s5/s6。
"""

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = r"E:\osdk-data\data\installs\android-platform-tools\37.0.1\adb.exe"
SERIAL = "emulator-5554"
ACT = "dev.aiauto.android/.ui.overlay.debug.OverlayDemoActivity"
PKG = "dev.aiauto.android"
OUT = r"E:\Projects\ai-auto-android\artifacts\ui-validation\overlay-states"


def adb_run(*args):
    """执行 adb 并返回 stdout。"""
    res = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True)
    return res.stdout.strip()


def shell(*args):
    """设备 shell 命令。"""
    return adb_run("shell", *args)


def center(bounds):
    """bounds 文本 -> 中心坐标。"""
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def dump_tree():
    """dump 当前界面并解析为 node 列表。"""
    shell("uiautomator", "dump", "/sdcard/u.xml")
    local = os.path.join(OUT, "_live.xml")
    adb_run("pull", "/sdcard/u.xml", local)
    return list(ET.parse(local).iter("node"))


def find_text(text):
    """按精确文本找节点中心（用于卡片内按钮，文字居中）。"""
    for n in dump_tree():
        if n.get("text") == text:
            return center(n.get("bounds"))
    return None


def find_demo_button(text):
    """按文案找演练场 Button 中心。"""
    for n in dump_tree():
        if n.get("class") == "android.widget.Button" and n.get("text") == text:
            return center(n.get("bounds"))
    return None


def shot(name):
    """截图并 pull。"""
    shell("screencap", "-p", f"/sdcard/{name}.png")
    adb_run("pull", f"/sdcard/{name}.png", os.path.join(OUT, f"{name}.png"))
    print("已截图：", name)


def tap(c):
    """点击坐标。"""
    shell("input", "tap", str(c[0]), str(c[1]))


def main():
    os.makedirs(OUT, exist_ok=True)
    shell("am", "force-stop", PKG)
    shell("am", "start", "-n", ACT)
    time.sleep(1.6)

    # 1) 演练场进入暂停态（收起圆不挡该按钮）。
    tap(find_demo_button("暂停态"))
    time.sleep(1.0)

    # 2) 点暂停卡片自身的"继续" -> 应回到 running 卡片。
    c = find_text("继续")
    print("继续按钮坐标：", c)
    tap(c)
    time.sleep(1.0)
    shot("s5-resumed-running")

    # 3) running 卡片点"停止" -> 呈现停止态。
    c = find_text("停止")
    print("停止按钮坐标：", c)
    tap(c)
    time.sleep(1.0)
    shot("s6-stopped")


if __name__ == "__main__":
    main()
