#!/usr/bin/env node

// 脚本用途：离线检查 N49 两份严格 Schema、固定 fixtures、manifest 绑定与安全反例。
import { readFile } from "node:fs/promises";
import path from "node:path";

import {
  loadDouyinSchemas,
  validateDouyinReport,
  validateDouyinScenario,
} from "./contract-validator.mjs";

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

const readJson = async (...parts) =>
  JSON.parse(await readFile(path.resolve(...parts), "utf8"));

const main = async () => {
  const schemas = await loadDouyinSchemas();
  const expectedSchemaIds = {
    scenario: "https://aiauto.dev/schema/test-lab/douyin/scenario.schema.json",
    report:
      "https://aiauto.dev/schema/test-lab/douyin/compatibility-report.schema.json",
  };
  for (const [name, schema] of Object.entries(schemas)) {
    if (schema.$schema !== "https://json-schema.org/draft/2020-12/schema") {
      fail(`${name}: unsupported JSON Schema draft`);
    }
    if (schema.$id !== expectedSchemaIds[name]) {
      fail(`${name}: unexpected schema id`);
    }
    requireStrictObjects(schema);
  }

  const fixtureDirectory = path.resolve(import.meta.dirname, "..", "fixtures");
  const scenario = await readJson(
    fixtureDirectory,
    "n49-douyin.scenario.json",
  );
  const report = await readJson(
    fixtureDirectory,
    "n49-douyin.fixture-report.json",
  );
  const scenarioErrors = validateDouyinScenario(schemas.scenario, scenario);
  if (scenarioErrors.length > 0) {
    fail(`fixed scenario was rejected: ${scenarioErrors.join("; ")}`);
  }
  const reportErrors = validateDouyinReport(schemas.report, report);
  if (reportErrors.length > 0) {
    fail(`fixed report was rejected: ${reportErrors.join("; ")}`);
  }

  const manifest = await readJson(
    import.meta.dirname,
    "..",
    "..",
    "..",
    "apps",
    "manifests",
    "douyin-39.8.0-arm64.json",
  );
  const expectedManifestBinding = {
    packageName: manifest.package,
    versionName: manifest.version,
    versionCode: manifest.versionCode,
    abi: manifest.abi[0],
    apkSha256: manifest.sha256,
    signingCertificateSha256: manifest.signingCertificateSha256,
  };
  for (const [key, value] of Object.entries(expectedManifestBinding)) {
    if (scenario.manifest[key] !== value || report.manifest[key] !== value) {
      fail(`fixed fixture drifted from manifest field ${key}`);
    }
  }

  const arbitraryAction = structuredClone(report);
  arbitraryAction.runs[0].action = {
    type: "tap",
    x: 100,
    y: 200,
  };
  if (validateDouyinReport(schemas.report, arbitraryAction).length === 0) {
    fail("report accepted arbitrary action or coordinates");
  }
  const rawContent = structuredClone(report);
  rawContent.runs[0].rawHierarchy = "<node text='private'/>";
  if (validateDouyinReport(schemas.report, rawContent).length === 0) {
    fail("report accepted raw hierarchy content");
  }
  const protectedCommit = structuredClone(report);
  protectedCommit.runs[0].protectedNodeCommits.like = 1;
  if (validateDouyinReport(schemas.report, protectedCommit).length === 0) {
    fail("report accepted protected-node commit");
  }

  process.stdout.write(
    `${JSON.stringify({ ok: true, schemas: 2, fixtures: 2, realRuns: 0 })}\n`,
  );
};

await main();
