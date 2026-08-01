#!/usr/bin/env node

// 脚本用途：离线检查小程序报告 Schema 严格对象边界、固定 fixture 与泄密反例。
import { readFile } from "node:fs/promises";
import path from "node:path";

import {
  loadMiniappReportSchema,
  validateMiniappReport,
} from "./report-validator.mjs";

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
  const schema = await loadMiniappReportSchema();
  if (schema.$schema !== "https://json-schema.org/draft/2020-12/schema") {
    fail("unsupported JSON Schema draft");
  }
  if (
    schema.$id
    !== "https://aiauto.dev/schema/test-lab/miniapps/exploration-report.schema.json"
  ) {
    fail("unexpected exploration report schema id");
  }
  requireStrictObjects(schema);

  const fixturePath = path.resolve(
    import.meta.dirname,
    "..",
    "fixtures",
    "n42-n43-classification.report.json",
  );
  const fixture = JSON.parse(await readFile(fixturePath, "utf8"));
  const positiveErrors = validateMiniappReport(schema, fixture);
  if (positiveErrors.length > 0) {
    fail(`fixed fixture was rejected: ${positiveErrors.join("; ")}`);
  }
  const secret = structuredClone(fixture);
  secret.samples[0].observations.hierarchy.rawXml = "<node text='secret'/>";
  if (validateMiniappReport(schema, secret).length === 0) {
    fail("schema accepted raw hierarchy content");
  }
  const action = structuredClone(fixture);
  action.samples[0].probes[0].action = {
    type: "deep-link",
  };
  if (validateMiniappReport(schema, action).length === 0) {
    fail("schema accepted arbitrary action");
  }
  process.stdout.write(
    `${JSON.stringify({ ok: true, schemas: 1, fixtures: 1 })}\n`,
  );
};

await main();
