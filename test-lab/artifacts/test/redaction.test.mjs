// 测试用途：验证敏感字段、嵌套值和常见编码变体被统一过滤，无法判断时必须失败关闭。
import assert from "node:assert/strict";
import test from "node:test";

import {
  RedactionError,
  containsSensitiveData,
  redactText,
} from "../src/redaction.mjs";
import { SECRETS } from "./helpers.mjs";

test("redacts password OTP token pairing code API key and target text", () => {
  const input = [
    `PASSWORD=${SECRETS.password}`,
    `verification_code: ${SECRETS.otp}`,
    `Authorization: Bearer ${SECRETS.token}`,
    `pairing-code=${SECRETS.pairingCode}`,
    `X-API-Key: ${SECRETS.apiKey}`,
    SECRETS.targetText.toUpperCase(),
  ].join("\n");

  const result = redactText(input, {
    declaration: "complete",
    values: Object.values(SECRETS),
  });

  assert.equal(result.verified, true);
  assert.equal(result.text.includes("[REDACTED]"), true);
  for (const secret of Object.values(SECRETS)) {
    assert.equal(result.text.toLowerCase().includes(secret.toLowerCase()), false);
  }
  assert.equal(
    containsSensitiveData(result.text, Object.values(SECRETS)),
    false,
  );
});

test("redacts nested JSON values regardless of key casing", () => {
  const input = JSON.stringify({
    Password: SECRETS.password,
    nested: {
      ACCESS_TOKEN: SECRETS.token,
      ApiKey: SECRETS.apiKey,
      otpCode: SECRETS.otp,
    },
  });

  const result = redactText(input, {
    declaration: "complete",
    values: Object.values(SECRETS),
  });
  const parsed = JSON.parse(result.text);

  assert.equal(parsed.Password, "[REDACTED]");
  assert.equal(parsed.nested.ACCESS_TOKEN, "[REDACTED]");
  assert.equal(parsed.nested.ApiKey, "[REDACTED]");
  assert.equal(parsed.nested.otpCode, "[REDACTED]");
});

test("redacts URL Base64 Base64URL and hex encodings", () => {
  const secret = SECRETS.targetText;
  const variants = [
    encodeURIComponent(secret),
    Buffer.from(secret).toString("base64"),
    Buffer.from(secret).toString("base64url"),
    Buffer.from(secret).toString("hex"),
  ];
  const result = redactText(variants.join("\n"), {
    declaration: "complete",
    values: [secret],
  });

  assert.equal(result.verified, true);
  for (const variant of variants) {
    assert.equal(result.text.toLowerCase().includes(variant.toLowerCase()), false);
  }
});

test("recursively redacts a Base64 encoded JSON secret object", () => {
  const encoded = Buffer.from(
    JSON.stringify({ token: SECRETS.token }),
  ).toString("base64");
  const result = redactText(`payload=${encoded}`, {
    declaration: "complete",
    values: [SECRETS.token],
  });

  assert.equal(result.verified, true);
  assert.equal(result.text.includes(encoded), false);
});

test("rejects incomplete sensitive text declarations", () => {
  assert.throws(
    () => redactText("apparently harmless", {
      declaration: "partial",
      values: [],
    }),
    (error) =>
      error instanceof RedactionError
      && error.code === "SENSITIVE_DECLARATION_INCOMPLETE",
  );
});

test("rejects invalid UTF-8 instead of guessing an encoding", () => {
  assert.throws(
    () => redactText(Buffer.from([0xc3, 0x28]), {
      declaration: "complete",
      values: [],
    }),
    (error) =>
      error instanceof RedactionError
      && error.code === "TEXT_ENCODING_UNVERIFIED",
  );
});
