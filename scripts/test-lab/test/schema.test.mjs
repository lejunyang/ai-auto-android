// 测试用途：验证文本 manifest Schema 与占位模板保持严格字段、来源分支和实现契约一致。
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { validateExternalAppManifest } from "../src/manifest.mjs";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../..",
);

test("Schema 拒绝根对象和来源对象未知字段", async () => {
  const schema = JSON.parse(await readFile(
    path.join(
      repositoryRoot,
      "test-lab/apps/schema/external-app-manifest.schema.json",
    ),
    "utf8",
  ));
  assert.equal(schema.additionalProperties, false);
  assert.equal(schema.properties.source.oneOf.length, 2);
  for (const sourceSchema of schema.properties.source.oneOf) {
    assert.equal(sourceSchema.additionalProperties, false);
  }
  assert.match(
    schema.properties.source.oneOf[0].properties.url.pattern,
    /https/u,
  );
});

test("占位模板可由严格实现解析但不包含真实制品", async () => {
  const template = JSON.parse(await readFile(
    path.join(
      repositoryRoot,
      "test-lab/apps/manifests/example.user-provided.json",
    ),
    "utf8",
  ));
  const manifest = validateExternalAppManifest(template);
  assert.equal(manifest.source.type, "user-provided");
  assert.equal(manifest.sha256, "0".repeat(64));
  assert.match(manifest.package, /replace_before_use/u);
});
