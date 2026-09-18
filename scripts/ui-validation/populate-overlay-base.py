#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能用途：把悬浮窗 10 个状态的截图批量写入飞书多维表格，每行含界面状态、说明与截图附件。

流程：逐行 +record-upsert 创建记录（只写文本字段）拿到 record_id ->
+record-upload-attachment 上传对应截图到"截图"附件字段。
通过 subprocess 以参数列表传 JSON，规避 PowerShell 对双引号的转义问题。
"""

import json
import os
import shutil
import subprocess

# lark-cli 路径、目标 Base/Table、截图目录。
CLI = shutil.which("lark-cli")
BASE = "O17cbOK1iaCeU2sKZ9rcsvwznFg"
TABLE = "tblmRLMioRBkQgAd"
IMGDIR = r"E:\Projects\ai-auto-android\artifacts\ui-validation\overlay-states"

# 每行：界面状态、说明、截图文件名。
ROWS = [
    ("Running · 展开", "展开卡片：任务标题 + 状态描述 + 暂停/停止", "s1-running-expanded.png"),
    ("收起 · 边缘圆", "右上角 48dp 圆，只挡很小区域，不拦截其他触摸", "s2-collapsed-dot.png"),
    ("重新展开", "从收起态重新展开卡片", "s3-reexpanded.png"),
    ("Paused · 暂停", "暂停卡片：已暂停，等你回来 + 继续/停止", "s4-paused.png"),
    ("继续 · 回到 Running", "点卡片继续，真实回到 running", "s5-resumed-running.png"),
    ("已停止", "Running 卡片点停止后的停止态（文案打磨中）", "s6-stopped.png"),
    ("完成 · 结果", "成功完成：蓝色对勾 + 任务执行完毕", "s7-result.png"),
    ("失败", "失败：红色警示 + 失败原因 + 收起", "s8-failed.png"),
    ("隐藏悬浮窗", "隐藏后屏幕无任何悬浮窗", "s9-hidden.png"),
    ("重新显示", "重新显示，状态与形态保留", "s10-shown.png"),
]


def run(args):
    """执行 lark-cli 并返回 (returncode, stdout, stderr)。"""
    res = subprocess.run([CLI, *args], capture_output=True, text=True)
    return res.returncode, res.stdout, res.stderr


def main():
    for name, desc, fname in ROWS:
        # 1) 创建记录（仅文本字段）。
        payload = json.dumps({"界面状态": name, "说明": desc}, ensure_ascii=False)
        code, out, err = run([
            "base", "+record-upsert", "--base-token", BASE, "--table-id", TABLE,
            "--json", payload, "--as", "user",
        ])
        try:
            rec = json.loads(out)["data"]["record"]
            rid_list = rec.get("record_id_list") or []
            rid = rid_list[0] if rid_list else (rec.get("record_id") or rec.get("id"))
        except Exception:
            print("创建记录失败：", name, out, err)
            continue

        # 2) 上传截图附件。
        fpath = os.path.join(IMGDIR, fname)
        code, out, err = run([
            "base", "+record-upload-attachment", "--base-token", BASE,
            "--table-id", TABLE, "--record-id", rid, "--field-id", "截图",
            "--file", fpath, "--as", "user",
        ])
        status = "已上传" if code == 0 else f"上传失败 {err}"
        print(f"{name}  [{rid}]  {status}")


if __name__ == "__main__":
    main()
