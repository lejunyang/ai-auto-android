#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能用途：生成当前时间有效的 LAN 测试邀请手工码（AIAUTO1- + Base32），
供局域网配对底部弹窗的手工码流程走查。脚本同时复现 App 端指纹算法做自检。

仅用于 debug 自动化验收；邀请内为无敏感测试数据，TTL 固定 300 秒。
"""

import base64
import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 指纹域分隔与 TTL，必须与 LanCrypto / fixture 保持一致。
FINGERPRINT_DOMAIN = "AIAUTO-LAN-INVITATION-FINGERPRINT-V1"
TTL_SECONDS = 300
FIXTURE = Path(r"E:\Projects\ai-auto-android\protocol\fixtures\lan-invitation-v1-valid.json")


def canonical_bytes(invitation):
    """按 App 端规则产出 canonical JSON：移除 fingerprint、key 排序、紧凑、ASCII 转义。"""
    calc = {key: value for key, value in invitation.items() if key != "fingerprint"}
    text = json.dumps(calc, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return text.encode("utf-8")


def fingerprint_of(invitation):
    """复现 LanCrypto.invitationFingerprint：域哈希前 8 字节，大写 hex 每 4 位分组。"""
    digest = hashlib.sha256(
        FINGERPRINT_DOMAIN.encode("utf-8") + b"\x00" + canonical_bytes(invitation),
    ).digest()
    hex16 = digest[:8].hex().upper()
    # 16 个 hex 字符按每 4 位一组，共四组（如 8975-0256-F8CF-C56A）。
    return "-".join(hex16[index:index + 4] for index in range(0, 16, 4))


def base32_no_padding(raw_bytes):
    """标准 RFC4648 Base32 并去除 '=' padding，与 App decodeManualCode 兼容。"""
    return base64.b32encode(raw_bytes).decode("ascii").rstrip("=")


def main():
    """入口：先校验算法能还原 fixture 指纹，再生成当前有效邀请码。"""
    invitation = json.loads(FIXTURE.read_text(encoding="utf-8"))

    # 自检：未改动的 fixture 必须算出其既有指纹。
    check = fingerprint_of(invitation)
    expected = invitation["fingerprint"]
    print("fixture 指纹自检:", check, "期望:", expected)
    if check != expected:
        raise SystemExit("指纹算法复现错误，终止生成")

    # 改为当前时间窗口。
    now = datetime.now(timezone.utc).replace(microsecond=0)
    invitation["issuedAt"] = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    invitation["expiresAt"] = (now + timedelta(seconds=TTL_SECONDS)).strftime(
        "%Y-%m-%dT%H:%M:%SZ",
    )
    invitation["ttlSeconds"] = TTL_SECONDS
    invitation["fingerprint"] = fingerprint_of(invitation)

    raw = json.dumps(invitation, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    code = "AIAUTO1-" + base32_no_padding(raw)
    print("新指纹:", invitation["fingerprint"])
    print("手工码长度:", len(code))
    print(code)


if __name__ == "__main__":
    main()
