#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能用途：驱动局域网配对底部弹窗的手工码流程走查。冷启动进入连接电脑页，
生成当前有效邀请码并通过 input commitText 填入，点检查进入 REVIEW 态后逐态截图。

仅用于 debug 自动化验收；坐标全部来自当次 uiautomator dump。
"""

import base64
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCRIPTS = Path(r"E:\Projects\ai-auto-android\scripts\ui-validation")
ART = Path(r"E:\Projects\ai-auto-android\artifacts\ui-validation")
sys.path.insert(0, str(SCRIPTS))

import ui_nav  # noqa: E402
import make_test_invitation as mti  # noqa: E402

PACKAGE = "dev.aiauto.android"


def tap_text(text, index=0, pause=1.8):
    """点击包含文本的最小可点击容器中心，index 指定同名匹配项。"""
    target = ui_nav.clickable_target(text, index)
    if target is None:
        raise RuntimeError("未找到可点击目标: " + text)
    cx, cy = target
    ui_nav.adb("shell", "input", "tap", str(cx), str(cy))
    time.sleep(pause)


def wait_for_text(text, timeout=20, interval=1.0):
    """轮询 dump 直到指定文本出现，用于冷启动等待界面渲染完成。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if ui_nav.find_text(text):
            return True
        time.sleep(interval)
    return False


def screencap(name):
    """screencap 到设备再 pull，返回本地路径，避免 shell 重定向损坏二进制。"""
    ui_nav.adb("shell", "screencap", "-p", "/sdcard/s.png")
    target = str(ART / name)
    subprocess.run([ui_nav.ADB, "-s", ui_nav.SERIAL, "pull", "/sdcard/s.png", target],
                   capture_output=True)
    return target


def build_code():
    """生成当前时间有效的手工邀请码，复用 make_test_invitation 的指纹与编码。"""
    invitation = json.loads(mti.FIXTURE.read_text(encoding="utf-8"))
    now = datetime.now(timezone.utc).replace(microsecond=0)
    invitation["issuedAt"] = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    invitation["expiresAt"] = (now + timedelta(seconds=mti.TTL_SECONDS)).strftime(
        "%Y-%m-%dT%H:%M:%SZ",
    )
    invitation["ttlSeconds"] = mti.TTL_SECONDS
    invitation["fingerprint"] = mti.fingerprint_of(invitation)
    raw = json.dumps(invitation, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    return "AIAUTO1-" + mti.base32_no_padding(raw)


def main():
    """入口：冷启动→我的→连接电脑→弹窗→填码→检查进入 REVIEW。"""
    subprocess.run([ui_nav.ADB, "-s", ui_nav.SERIAL, "shell", "am", "force-stop", PACKAGE],
                   capture_output=True)
    subprocess.run([ui_nav.ADB, "-s", ui_nav.SERIAL, "shell", "am", "start",
                    "-n", f"{PACKAGE}/.MainActivity"], capture_output=True)
    if not wait_for_text("我的"):
        raise RuntimeError("冷启动后底栏未出现")

    tap_text("我的")
    tap_text("连接电脑")
    tap_text("改用局域网邀请码连接", pause=1.2)

    # 点击邀请码输入框并 commitText 填入长码。
    cx, cy = ui_nav.clickable_target("粘贴或输入邀请码")
    ui_nav.adb("shell", "input", "tap", str(cx), str(cy))
    time.sleep(1.0)
    code = build_code()
    ui_nav.adb("shell", "input", "text", code)
    time.sleep(1.0)
    screencap("lan-r1-typed.png")

    # 点检查进入 REVIEW 态。
    tap_text("检查邀请码", pause=2.2)
    screencap("lan-r2-review.png")
    print("DONE")


if __name__ == "__main__":
    main()
