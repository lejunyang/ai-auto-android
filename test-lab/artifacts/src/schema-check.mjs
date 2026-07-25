#!/usr/bin/env node

// 脚本用途：独立检查 N34 Schema 标识、严格对象边界和受控正反例，不访问设备或网络。
import {
  loadArtifactSchemas,
  validateArtifactDocument,
} from "./schema-validator.mjs";

const fail = (message) => {
  throw new Error(message);
};

const requireStrictObjects = (schema, location = "#") => {
  if (Array.isArray(schema)) {
    schema.forEach((item, index) =>
      requireStrictObjects(item, `${location}/${index}`));
    return;
  }
  if (schema === null || typeof schema !== "object") return;
  if (schema.type === "object" && schema.additionalProperties !== false) {
    fail(`${location}: object must set additionalProperties=false`);
  }
  for (const [name, value] of Object.entries(schema)) {
    requireStrictObjects(value, `${location}/${name}`);
  }
};

const main = async () => {
  const schemas = await loadArtifactSchemas();
  const ids = new Set();
  for (const [name, schema] of Object.entries(schemas)) {
    if (schema.$schema !== "https://json-schema.org/draft/2020-12/schema") {
      fail(`${name}: unsupported JSON Schema draft`);
    }
    if (typeof schema.$id !== "string" || ids.has(schema.$id)) {
      fail(`${name}: missing or duplicate schema id`);
    }
    ids.add(schema.$id);
    requireStrictObjects(schema);
  }

  const blocked = {
    schemaVersion: "1.0",
    uploadAllowed: false,
    errorCode: "REDACTION_UNVERIFIED",
  };
  if (validateArtifactDocument(schemas.blockedSummary, blocked).length !== 0) {
    fail("blocked summary positive example was rejected");
  }
  if (
    validateArtifactDocument(
      schemas.blockedSummary,
      { ...blocked, message: "forbidden free text" },
    ).length === 0
  ) {
    fail("blocked summary accepted free text");
  }
  process.stdout.write(
    `${JSON.stringify({ ok: true, schemas: Object.keys(schemas).length })}\n`,
  );
};

await main();
