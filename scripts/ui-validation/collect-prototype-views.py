#!/usr/bin/env python3
"""功能用途：驱动 UI 原型逐视图采集截图与设计 token。

原型是交互式的（hash 路由 + JS 状态机），静态截首页无法获得二级页信息。
本脚本通过 shot.py --eval 注入 JS：先设置 location.hash 切视图（必要时再点按钮
驱动内部状态），等渲染稳定后调用 extract-view-tokens.js 的采集逻辑。

输出：
- artifacts/ui-validation/proto/<view>.png   411x914 截图
- artifacts/ui-validation/proto/<view>.json  完整 computed token

坐标口径：411x914 视口下 CSS px == Android dp（1080x2400 / 420dpi = 2.625x）。

用法：
    python scripts/ui-validation/collect-prototype-views.py
    python scripts/ui-validation/collect-prototype-views.py --views home settings connection
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PROTOTYPE = REPO_ROOT / "docs" / "ui-prototype" / "index.html"
EXTRACT_JS = Path(__file__).resolve().parent / "extract-view-tokens.js"
OUT_DIR = REPO_ROOT / "artifacts" / "ui-validation" / "proto"
SHOT_PY = Path(
    r"C:\Users\LJY\AppData\Local\DoubaoWork\User Data\Default\.doubaowork"
    r"\agent_mode\workspace\.skills\html\scripts\shot.py"
)

MOBILE_VIEWPORT = "411x914"

# 视图定义：name -> 驱动脚本片段。
# setup 片段在采集前执行，用于切 hash 并驱动内部状态（点筛选、进入运行态等）。
# 每段末尾不要 return，采集 return 由 extract-view-tokens.js 负责。
VIEWS: dict[str, str] = {
    # ---- 主 tab ----
    "home-empty": """
        location.hash = 'home';
        await new Promise(r => setTimeout(r, 400));
    """,
    "home-data": """
        location.hash = 'home';
        await new Promise(r => setTimeout(r, 400));
    """,
    "skills-all": """
        location.hash = 'skills';
        await new Promise(r => setTimeout(r, 400));
    """,
    "skills-mine": """
        location.hash = 'skills';
        await new Promise(r => setTimeout(r, 300));
        document.querySelector('[data-action=skill-filter][data-value=mine]')?.click();
        await new Promise(r => setTimeout(r, 300));
    """,
    "skills-ideas": """
        location.hash = 'skills';
        await new Promise(r => setTimeout(r, 300));
        document.querySelector('[data-action=skill-filter][data-value=ideas]')?.click();
        await new Promise(r => setTimeout(r, 300));
    """,
    "history-all": """
        location.hash = 'history';
        await new Promise(r => setTimeout(r, 400));
    """,
    "history-done": """
        location.hash = 'history';
        await new Promise(r => setTimeout(r, 300));
        document.querySelector('[data-action=history-filter][data-value=done]')?.click();
        await new Promise(r => setTimeout(r, 300));
    """,
    "history-attention": """
        location.hash = 'history';
        await new Promise(r => setTimeout(r, 300));
        document.querySelector('[data-action=history-filter][data-value=attention]')?.click();
        await new Promise(r => setTimeout(r, 300));
    """,
    "settings": """
        location.hash = 'settings';
        await new Promise(r => setTimeout(r, 400));
    """,
    # ---- 我的下的二级页 ----
    "provider": """
        location.hash = 'provider';
        await new Promise(r => setTimeout(r, 400));
    """,
    "permissions": """
        location.hash = 'permissions';
        await new Promise(r => setTimeout(r, 400));
    """,
    "connection": """
        location.hash = 'connection';
        await new Promise(r => setTimeout(r, 400));
    """,
    "setup": """
        location.hash = 'setup';
        await new Promise(r => setTimeout(r, 400));
    """,
    # ---- 录制与编辑 ----
    "record": """
        location.hash = 'record';
        await new Promise(r => setTimeout(r, 400));
    """,
    "editor": """
        location.hash = 'editor';
        await new Promise(r => setTimeout(r, 400));
    """,
    # ---- 会话各状态 ----
    "session-plan": """
        location.hash = 'home';
        await new Promise(r => setTimeout(r, 250));
        const ta = document.getElementById('task-input');
        if (ta) { ta.value = '把稍后阅读整理成清单'; }
        document.querySelector('[data-action=start-task]')?.click();
        await new Promise(r => setTimeout(r, 400));
    """,
    "session-running": """
        location.hash = 'home';
        await new Promise(r => setTimeout(r, 250));
        const ta2 = document.getElementById('task-input');
        if (ta2) { ta2.value = '把稍后阅读整理成清单'; }
        document.querySelector('[data-action=start-task]')?.click();
        await new Promise(r => setTimeout(r, 300));
        document.querySelector('[data-action=run-task]')?.click();
        await new Promise(r => setTimeout(r, 400));
    """,
    "session-paused": """
        location.hash = 'home';
        await new Promise(r => setTimeout(r, 250));
        const ta3 = document.getElementById('task-input');
        if (ta3) { ta3.value = '把稍后阅读整理成清单'; }
        document.querySelector('[data-action=start-task]')?.click();
        await new Promise(r => setTimeout(r, 250));
        document.querySelector('[data-action=run-task]')?.click();
        await new Promise(r => setTimeout(r, 250));
        document.querySelector('[data-action=pause-task]')?.click();
        await new Promise(r => setTimeout(r, 400));
    """,
    "session-failed": """
        location.hash = 'home';
        await new Promise(r => setTimeout(r, 250));
        const ta4 = document.getElementById('task-input');
        if (ta4) { ta4.value = '把稍后阅读整理成清单'; }
        document.querySelector('[data-action=start-task]')?.click();
        await new Promise(r => setTimeout(r, 250));
        document.querySelector('[data-action=run-task]')?.click();
        await new Promise(r => setTimeout(r, 250));
        document.querySelector('[data-action=task-fail]')?.click();
        await new Promise(r => setTimeout(r, 400));
    """,
    "session-done": """
        document.querySelector('[data-action=view-demo-result]')?.click();
        await new Promise(r => setTimeout(r, 500));
    """,
}


def build_eval_code(setup: str, extract_source: str) -> str:
    """把视图驱动片段与采集逻辑拼成 shot.py --eval 可执行的代码。"""
    return f"{setup.strip()}\n\n{extract_source}"


def run_shot(view: str, eval_file: Path, outdir: Path) -> dict:
    """调用 shot.py 采集单个视图，返回 eval 结果。"""
    cmd = [
        sys.executable,
        str(SHOT_PY),
        str(PROTOTYPE),
        "--outdir", str(outdir),
        "--only", "mobile",
        "--mobile", MOBILE_VIEWPORT,
        "--format", "png",
        "--max-width", "0",
        "--scale", "1",
        "--include", "screenshots",
        "--eval-file", str(eval_file),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"shot.py failed for {view}: {proc.stderr[-2000:]}")
    # shot.py 把结果写到 stdout 的 JSON 中；解析出 eval.result。
    return parse_shot_output(proc.stdout, view)


def parse_shot_output(stdout: str, view: str) -> dict:
    """从 shot.py 的 stdout 提取 eval 结果。"""
    # shot.py 输出可能包含日志行，找到最外层 JSON 对象。
    start = stdout.find("{")
    if start < 0:
        raise RuntimeError(f"no JSON in shot.py output for {view}")
    depth = 0
    for i, ch in enumerate(stdout[start:], start):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                payload = stdout[start : i + 1]
                break
    else:
        raise RuntimeError(f"unbalanced JSON in shot.py output for {view}")
    data = json.loads(payload)
    shots = data.get("shots", {})
    for _, shot in shots.items():
        ev = shot.get("eval") or {}
        if "result" in ev:
            return ev["result"]
    raise RuntimeError(f"no eval.result for {view}; keys={list(shots.keys())}")


def main() -> int:
    parser = argparse.ArgumentParser(description="逐视图采集原型截图与 token")
    parser.add_argument("--views", nargs="*", help="只采集指定视图，默认全部")
    args = parser.parse_args()

    if not PROTOTYPE.is_file():
        print(f"原型不存在: {PROTOTYPE}", file=sys.stderr)
        return 1
    if not SHOT_PY.is_file():
        print(f"shot.py 不存在: {SHOT_PY}", file=sys.stderr)
        return 1

    extract_source = EXTRACT_JS.read_text(encoding="utf-8")
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    targets = args.views if args.views else list(VIEWS.keys())
    unknown = [v for v in targets if v not in VIEWS]
    if unknown:
        print(f"未知视图: {unknown}；可用: {list(VIEWS.keys())}", file=sys.stderr)
        return 1

    tmp_dir = OUT_DIR / "_tmp"
    ok, failed = [], []

    for view in targets:
        tmp_dir.mkdir(parents=True, exist_ok=True)
        eval_file = tmp_dir / f"_eval_{view}.js"
        eval_file.write_text(build_eval_code(VIEWS[view], extract_source), encoding="utf-8")
        try:
            result = run_shot(view, eval_file, tmp_dir)
            # 写 token JSON
            (OUT_DIR / f"{view}.json").write_text(
                json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            # 搬运截图（shot.py 的命名含 _mobile 后缀）
            pngs = sorted(tmp_dir.glob("*mobile*.png"))
            if pngs:
                shutil.copy2(pngs[-1], OUT_DIR / f"{view}.png")
            for p in tmp_dir.glob("*.png"):
                p.unlink()
            ok.append(view)
            print(f"[ok] {view}")
        except Exception as exc:  # noqa: BLE001 - 采集失败需逐视图报告而不中断整批
            failed.append((view, str(exc)[:300]))
            print(f"[fail] {view}: {exc}", file=sys.stderr)

    if tmp_dir.exists():
        shutil.rmtree(tmp_dir, ignore_errors=True)

    print(f"\n成功 {len(ok)}/{len(targets)}；输出目录 {OUT_DIR}")
    if failed:
        print("失败视图:")
        for view, err in failed:
            print(f"  - {view}: {err}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
