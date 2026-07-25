// 测试用途：验证第三方 App 文本 manifest 严格拒绝未知字段、不安全来源和不完整身份。
import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  ExternalAppError,
  loadExternalAppManifest,
  validateExternalAppManifest,
} from "../src/manifest.mjs";
import { baseManifest } from "./helpers.mjs";

const rejectsCode = async (operation, code) => {
  await assert.rejects(Promise.resolve().then(operation), (error) => {
    assert.ok(error instanceof ExternalAppError);
    assert.equal(error.code, code);
    return true;
  });
};

test("接受严格 HTTPS 来源 manifest", () => {
  const manifest = baseManifest({
    source: {
      type: "https",
      url: "https://downloads.example.com/releases/app",
      note: "发布者许可的手工获取页面，本工具不会访问该 URL。",
    },
  });
  assert.deepEqual(validateExternalAppManifest(manifest), manifest);
});

test("接受明确 user-provided 来源且不需要 URL", () => {
  const manifest = baseManifest();
  assert.equal(validateExternalAppManifest(manifest).source.type, "user-provided");
});

test("拒绝 manifest 和 source 未知字段", async () => {
  await rejectsCode(
    () => validateExternalAppManifest({ ...baseManifest(), command: "curl" }),
    "MANIFEST_INVALID",
  );
  await rejectsCode(
    () => validateExternalAppManifest({
      ...baseManifest(),
      source: {
        ...baseManifest().source,
        downloadCommand: "store-scraper",
      },
    }),
    "MANIFEST_INVALID",
  );
  const withPrototypeKey = JSON.parse(
    `${JSON.stringify(baseManifest()).slice(0, -1)},"__proto__":{"polluted":true}}`,
  );
  await rejectsCode(
    () => validateExternalAppManifest(withPrototypeKey),
    "MANIFEST_INVALID",
  );
});

test("拒绝 HTTP、认证参数、fragment 和含 URL 的 user-provided 来源", async () => {
  for (const source of [
    {
      type: "https",
      url: "http://downloads.example.com/app",
      note: "不安全协议。",
    },
    {
      type: "https",
      url: "https://user:secret@downloads.example.com/app",
      note: "禁止在来源中保存凭据。",
    },
    {
      type: "https",
      url: "https://downloads.example.com/app?token=secret",
      note: "禁止在来源中保存认证查询参数。",
    },
    {
      type: "https",
      url: "https://downloads.example.com/app#fragment",
      note: "禁止来源携带片段。",
    },
    {
      type: "user-provided",
      note: "用户提供。",
      url: "https://example.com/app",
    },
  ]) {
    await rejectsCode(
      () => validateExternalAppManifest({ ...baseManifest(), source }),
      "SOURCE_UNSAFE",
    );
  }
});

test("拒绝来源缺失及错误 package、ABI、摘要和大小", async () => {
  const invalidValues = [
    { source: undefined },
    { package: "../escape" },
    { abi: ["mips"] },
    { sha256: "A".repeat(64) },
    { signingCertificateSha256: "short" },
    { sizeBytes: 0 },
  ];
  for (const overrides of invalidValues) {
    await rejectsCode(
      () => validateExternalAppManifest({ ...baseManifest(), ...overrides }),
      overrides.source === undefined ? "MANIFEST_INVALID" : "MANIFEST_INVALID",
    );
  }
});

test("从 UTF-8 JSON 文件加载并冻结 manifest", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "n46-manifest-"));
  const manifestPath = path.join(directory, "manifest.json");
  await writeFile(manifestPath, `${JSON.stringify(baseManifest())}\n`, "utf8");
  const loaded = await loadExternalAppManifest(manifestPath);
  assert.ok(Object.isFrozen(loaded));
  assert.ok(Object.isFrozen(loaded.source));
  assert.ok(Object.isFrozen(loaded.abi));
});

test("拒绝重复 JSON 字段和非 JSON 文本", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "n46-manifest-"));
  const duplicatePath = path.join(directory, "duplicate.json");
  const invalidPath = path.join(directory, "invalid.json");
  const prototypePath = path.join(directory, "prototype.json");
  await writeFile(
    duplicatePath,
    '{"schemaVersion":"1.0","schemaVersion":"1.0"}\n',
    "utf8",
  );
  await writeFile(invalidPath, "package=com.example\n", "utf8");
  await writeFile(
    prototypePath,
    `${JSON.stringify(baseManifest()).slice(0, -1)},"__proto__":{"polluted":true}}\n`,
    "utf8",
  );
  await rejectsCode(
    () => loadExternalAppManifest(duplicatePath),
    "MANIFEST_INVALID",
  );
  await rejectsCode(
    () => loadExternalAppManifest(invalidPath),
    "MANIFEST_INVALID",
  );
  await rejectsCode(
    () => loadExternalAppManifest(prototypePath),
    "MANIFEST_INVALID",
  );
});
