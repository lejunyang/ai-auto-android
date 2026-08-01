#!/usr/bin/env node

// 脚本用途：离线检查 B站 Schema 严格对象边界、固定 fixture 与安全反例。
import { readFile } from "node:fs/promises";
import path from "node:path";

import {
  loadBilibiliSchemas,
  validateBilibiliPlan,
  validateBilibiliReport,
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

const readFixture = async (name) =>
  JSON.parse(
    await readFile(
      path.resolve(import.meta.dirname, "..", "fixtures", name),
      "utf8",
    ),
  );

const readManifest = async () =>
  JSON.parse(
    await readFile(
      path.resolve(
        import.meta.dirname,
        "..",
        "..",
        "..",
        "apps",
        "manifests",
        "bilibili-9.5.0-arm64.json",
      ),
      "utf8",
    ),
  );

const main = async () => {
  const schemas = await loadBilibiliSchemas();
  const expectedIds = {
    plan: "https://aiauto.dev/schema/test-lab/bilibili/scenario-plan.schema.json",
    report:
      "https://aiauto.dev/schema/test-lab/bilibili/compatibility-report.schema.json",
  };
  for (const [name, schema] of Object.entries(schemas)) {
    if (schema.$schema !== "https://json-schema.org/draft/2020-12/schema") {
      fail(`${name}: unsupported JSON Schema draft`);
    }
    if (schema.$id !== expectedIds[name]) {
      fail(`${name}: unexpected schema id`);
    }
    requireStrictObjects(schema);
  }

  const plan = await readFixture("bilibili-safe.plan.json");
  const report = await readFixture("api33-hierarchy-unavailable.report.json");
  const manifest = await readManifest();
  const manifestIdentity = {
    manifest: "bilibili-9.5.0-arm64.json",
    packageName: manifest.package,
    versionName: manifest.version,
    versionCode: manifest.versionCode,
    abi: manifest.abi?.length === 1 ? manifest.abi[0] : null,
    sizeBytes: manifest.sizeBytes,
    apkSha256: manifest.sha256,
    signingCertificateSha256: manifest.signingCertificateSha256,
  };
  if (JSON.stringify(plan.identity) !== JSON.stringify(manifestIdentity)) {
    fail("fixed plan identity drifted from verified manifest");
  }
  if (JSON.stringify(report.identity) !== JSON.stringify(manifestIdentity)) {
    fail("fixed report identity drifted from verified manifest");
  }
  const planErrors = validateBilibiliPlan(schemas.plan, plan);
  const reportErrors = validateBilibiliReport(schemas.report, report);
  if (planErrors.length > 0) {
    fail(`fixed plan was rejected: ${planErrors.join("; ")}`);
  }
  if (reportErrors.length > 0) {
    fail(`fixed report was rejected: ${reportErrors.join("; ")}`);
  }

  const queryLeak = structuredClone(report);
  queryLeak.rounds[0].query = "must-not-be-retained";
  if (validateBilibiliReport(schemas.report, queryLeak).length === 0) {
    fail("report accepted retained search query");
  }
  const arbitraryAction = structuredClone(report);
  arbitraryAction.rounds[0].steps = [{
    action: {
      type: "shell",
    },
  }];
  if (validateBilibiliReport(schemas.report, arbitraryAction).length === 0) {
    fail("report accepted arbitrary action");
  }
  const rawArtifact = structuredClone(report);
  rawArtifact.rounds[0].rawHierarchy = "<hierarchy/>";
  if (validateBilibiliReport(schemas.report, rawArtifact).length === 0) {
    fail("report accepted raw hierarchy");
  }
  process.stdout.write(
    `${JSON.stringify({ ok: true, schemas: 2, fixtures: 2 })}\n`,
  );
};

await main();
