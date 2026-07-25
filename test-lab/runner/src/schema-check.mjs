// 功能用途：验证 N47 三份严格 Schema、fixture 场景及 N34 scenario-result 兼容契约。
import { readFile } from "node:fs/promises";
import path from "node:path";

import {
  loadArtifactSchemas,
  validateArtifactDocument,
} from "../../artifacts/src/schema-validator.mjs";
import {
  loadRunnerSchemas,
  validateRunnerDocument,
} from "./schema-validator.mjs";

const fixturesDirectory = path.resolve(import.meta.dirname, "..", "fixtures");

const loadJson = async (file) =>
  JSON.parse(await readFile(file, "utf8"));

const assertSchemaShape = (name, schema) => {
  if (
    schema?.$schema !== "https://json-schema.org/draft/2020-12/schema"
    || typeof schema.$id !== "string"
    || schema.type !== "object"
    || schema.additionalProperties !== false
  ) {
    throw new Error(`${name} schema shape is invalid`);
  }
};

const device = {
  serial: "emulator-5554",
  avdName: "ai-auto-api-33",
  fingerprint: "a".repeat(64),
  apiLevel: 33,
  androidVersion: "13",
  abi: "arm64-v8a",
  locale: "zh-CN",
  resolution: {
    widthPx: 1080,
    heightPx: 2400,
    densityDpi: 420,
  },
  webView: {
    packageName: "com.google.android.webview",
    version: "109.0.5414.123",
  },
};

const report = {
  schemaVersion: "1.0",
  runId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
  scenarioId: "fixture-schema-smoke",
  iteration: 1,
  startedAt: "2026-07-25T12:00:00.000Z",
  finishedAt: "2026-07-25T12:00:00.010Z",
  status: "passed",
  errorCode: null,
  durationMs: 10,
  actionCommits: 1,
  steps: [
    {
      sequence: 1,
      stepId: "fixture-step",
      actionType: "tap",
      targetPackage: "dev.aiauto.fixture",
      status: "passed",
      errorCode: null,
      pageClass: "normal",
      route: "semantic",
      score: 1,
      attempts: 1,
      preObservationId: "observation-pre",
      postObservationId: "observation-post",
      actionCommits: 1,
      durationMs: 10,
    },
  ],
  cleanup: {
    attempted: 4,
    completed: 4,
    errorCode: null,
  },
};

const summary = {
  schemaVersion: "1.0",
  scenarioId: "fixture-schema-smoke",
  runs: 1,
  passedRuns: 1,
  failedRuns: 0,
  cancelledRuns: 0,
  successRate: 1,
  stepCompatibilityRate: 1,
  routeCounts: {
    semantic: 1,
  },
  pageClassCounts: {
    normal: 1,
  },
  failureCategoryCounts: {},
  errorCodeCounts: {},
};

const artifactRun = {
  schemaVersion: "1.0",
  runId: report.runId,
  scenarioId: report.scenarioId,
  iteration: report.iteration,
  startedAt: report.startedAt,
  finishedAt: report.finishedAt,
  device,
  timeline: [
    {
      sequence: 1,
      at: report.finishedAt,
      type: "scenario.tap",
      phase: "verify",
      status: "passed",
      durationMs: 10,
      targetPackage: "dev.aiauto.fixture",
    },
  ],
  outcome: {
    status: "passed",
    errorCode: null,
    durationMs: 10,
    retries: 0,
  },
};

const main = async () => {
  const schemas = await loadRunnerSchemas();
  for (const [name, schema] of Object.entries(schemas)) {
    assertSchemaShape(name, schema);
  }
  for (const fixture of [
    "native-fixture.scenario.json",
    "web-fixture.scenario.json",
  ]) {
    const errors = validateRunnerDocument(
      schemas.scenario,
      await loadJson(path.join(fixturesDirectory, fixture)),
    );
    if (errors.length > 0) {
      throw new Error(`${fixture}: ${errors.join("; ")}`);
    }
  }
  for (const [name, schema, value] of [
    ["run-report", schemas.runReport, report],
    ["compatibility-summary", schemas.compatibilitySummary, summary],
  ]) {
    const errors = validateRunnerDocument(schema, value);
    if (errors.length > 0) {
      throw new Error(`${name}: ${errors.join("; ")}`);
    }
  }
  const artifactSchemas = await loadArtifactSchemas();
  const artifactErrors = validateArtifactDocument(
    artifactSchemas.scenarioResult,
    artifactRun,
  );
  if (artifactErrors.length > 0) {
    throw new Error(`N34 scenario-result: ${artifactErrors.join("; ")}`);
  }
  process.stdout.write(
    "N47 schema validation passed: 3 schemas, 2 fixture scenarios, N34 result mapping.\n",
  );
};

main().catch((error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
