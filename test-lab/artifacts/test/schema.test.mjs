// 测试用途：验证 N34 JSON Schema 接受完整结果并拒绝未知字段、无效标识和自由文本摘要。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadArtifactSchemas,
  validateArtifactDocument,
} from "../src/schema-validator.mjs";
import { makeRun } from "./helpers.mjs";

test("strict scenario result schema accepts a complete failed run", async () => {
  const schemas = await loadArtifactSchemas();
  const errors = validateArtifactDocument(
    schemas.scenarioResult,
    makeRun(),
    schemas,
  );

  assert.deepEqual(errors, []);
});

test("scenario result schema rejects unknown fields and invalid identifiers", async () => {
  const schemas = await loadArtifactSchemas();
  const document = {
    ...makeRun(),
    runId: "../escape",
    secretMessage: "must never be accepted",
  };
  const errors = validateArtifactDocument(
    schemas.scenarioResult,
    document,
    schemas,
  );

  assert.equal(errors.some((error) => error.includes("runId")), true);
  assert.equal(errors.some((error) => error.includes("secretMessage")), true);
});

test("blocked summary schema has no free-form message field", async () => {
  const schemas = await loadArtifactSchemas();
  const blocked = {
    schemaVersion: "1.0",
    runId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
    scenarioId: "fixture-timeout",
    iteration: 1,
    retainedAt: "2026-07-25T12:01:00.000Z",
    expiresAt: "2026-07-25T12:02:00.000Z",
    uploadAllowed: false,
    result: {
      status: "blocked",
      category: "infrastructure",
      errorCode: "REDACTION_UNVERIFIED",
    },
    artifacts: [],
    budget: {
      fileBytes: 524288,
      scenarioBytes: 2097152,
      runBytes: 16777216,
      usedBytes: 0,
      scenarioTruncated: false,
      runTruncated: false,
    },
    message: "raw exception text is forbidden",
  };
  const errors = validateArtifactDocument(
    schemas.artifactSummary,
    blocked,
    schemas,
  );

  assert.equal(errors.some((error) => error.includes("message")), true);
});
