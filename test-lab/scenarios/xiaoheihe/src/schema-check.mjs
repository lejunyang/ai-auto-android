// 脚本用途：离线检查小黑盒严格 Schema、固定十轮场景和三 API 阻塞 fixture 契约。
import { readFile } from "node:fs/promises";
import path from "node:path";

import {
  loadXiaoheiheSchemas,
  recomputeXiaoheiheStatistics,
  validateXiaoheiheReport,
  validateXiaoheiheScenario,
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
    fail(`${location}: object schema must forbid additional properties`);
  }
  for (const [name, value] of Object.entries(schema)) {
    requireStrictObjects(value, `${location}/${name}`);
  }
};

const loadFixture = async (name) => JSON.parse(
  await readFile(
    path.resolve(import.meta.dirname, "..", "fixtures", name),
    "utf8",
  ),
);

const makeBlockedReport = (template) => {
  const runApiLevels = [30, 33, 34, 30, 33, 34, 30, 33, 34, 30];
  const runs = runApiLevels.map((apiLevel, index) => {
    const run = structuredClone(template);
    const iteration = index + 1;
    run.runId = `fixture-blocked-api-${apiLevel}-iteration-${iteration}`;
    run.binding.apiLevel = apiLevel;
    run.binding.iteration = iteration;
    run.observations[0].observationId = `${run.runId}-obs-01`;
    return run;
  });
  return {
    schemaVersion: "1.0",
    reportId: "n50-xiaoheihe-blocked-fixture-v1",
    scenarioId: "n50-xiaoheihe-public-browse-v1",
    manifestBinding: {
      manifestFile: "xiaoheihe-1.3.392-arm64.json",
      packageName: "com.max.xiaoheihe",
      versionName: "1.3.392",
      versionCode: 1114,
      abi: "arm64-v8a",
      apkSha256:
        "ee56e4220c98f160f5b744cb90aab8de87787801c45fa5bcb9cb1b531067b0ea",
      signingCertificateSha256:
        "ef42d004ab18b04e0789a9c2a246156589423f9186a345a5b0c52fcb6612d82c",
    },
    matrix: {
      supportedApiLevels: [30, 33, 34],
      requiredRuns: 10,
    },
    runs,
    statistics: recomputeXiaoheiheStatistics(runs),
  };
};

const main = async () => {
  const schemas = await loadXiaoheiheSchemas();
  const expectedIds = {
    scenario:
      "https://aiauto.dev/schema/test-lab/scenarios/xiaoheihe/scenario-v1.json",
    runReport:
      "https://aiauto.dev/schema/test-lab/scenarios/xiaoheihe/run-report-v1.json",
  };
  for (const [name, schema] of Object.entries(schemas)) {
    if (
      schema.$schema !== "https://json-schema.org/draft/2020-12/schema"
      || schema.$id !== expectedIds[name]
      || schema.type !== "object"
      || schema.additionalProperties !== false
    ) {
      fail(`${name}: unexpected strict schema shape`);
    }
    requireStrictObjects(schema);
  }

  const scenario = await loadFixture("n50-safe-scenario.fixture.json");
  const blockedTemplate = await loadFixture(
    "consent-permission-blocked-run.fixture.json",
  );
  const report = makeBlockedReport(blockedTemplate);

  const scenarioErrors = validateXiaoheiheScenario(schemas.scenario, scenario);
  if (scenarioErrors.length > 0) {
    fail(`fixed scenario rejected: ${scenarioErrors.join("; ")}`);
  }
  const reportErrors = validateXiaoheiheReport(schemas.runReport, report);
  if (reportErrors.length > 0) {
    fail(`fixed report rejected: ${reportErrors.join("; ")}`);
  }
  if (
    report.statistics.realRuns !== 0
    || report.statistics.realCompatibleRuns !== 0
    || report.statistics.realSuccessRate !== null
    || report.statistics.realMatrixComplete !== false
  ) {
    fail("fixture incorrectly counted as real ten-round evidence");
  }

  for (const [label, mutate] of [
    ["arbitrary package", (value) => {
      value.manifestBinding.packageName = "com.example.other";
    }],
    ["arbitrary apk", (value) => {
      value.manifestBinding.apkSha256 = "a".repeat(64);
    }],
    ["arbitrary action", (value) => {
      value.runs[0].steps = [{
        action: "tap",
        x: 1,
        y: 1,
      }];
    }],
    ["secret path raw content", (value) => {
      value.runs[0].observations[0].rawContent =
        "/private/tmp/token-private.xml";
    }],
  ]) {
    const invalid = structuredClone(report);
    mutate(invalid);
    if (validateXiaoheiheReport(schemas.runReport, invalid).length === 0) {
      fail(`report accepted ${label}`);
    }
  }

  process.stdout.write(
    `${JSON.stringify({
      ok: true,
      schemas: 2,
      scenarioFixtures: 1,
      runTemplates: 1,
      generatedFixtureRuns: 10,
      realRuns: 0,
    })}\n`,
  );
};

await main();
