#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""功能用途：临时探针，打印 +record-upsert 返回的 record 完整结构以确认 record_id 字段名。"""

import json
import shutil
import subprocess

cli = shutil.which("lark-cli")
payload = json.dumps({"界面状态": "_probe", "说明": "probe"}, ensure_ascii=False)
r = subprocess.run(
    [cli, "base", "+record-upsert", "--base-token", "O17cbOK1iaCeU2sKZ9rcsvwznFg",
     "--table-id", "tblmRLMioRBkQgAd", "--json", payload, "--as", "user"],
    capture_output=True, text=True,
)
print(r.stdout)
print("ERR", r.stderr)
