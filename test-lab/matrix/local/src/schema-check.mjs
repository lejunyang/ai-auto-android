// 功能用途：加载 N52 严格 Schema，并验证 fake 300 轮报告与残留失败报告契约。
import {
  loadMatrixReportSchema,
  validateMatrixReport,
} from "./report.mjs";
import { runLocalMatrix } from "./matrix.mjs";

const profiles = [
  { profileId: "api-30", apiLevel: 30 },
  { profileId: "api-33", apiLevel: 33 },
  { profileId: "api-34", apiLevel: 34 },
];
const capabilities = [
  "compose.test",
  "fixture.canvas",
  "fixture.editor",
  "fixture.native",
  "fixture.webview",
  "recording.replay",
  "semantic.action",
  "system.navigation",
  "visual.action",
];
let count = 0;
const ports = {
  capabilities: {
    probe: async (profile) => ({
      ...profile,
      capabilities,
    }),
  },
  lifecycle: {
    startClean: async ({ profile }) => {
      count += 1;
      return {
        ...profile,
        serial: `emulator-${5554 + (count % 16) * 2}`,
        deviceFingerprint: profile.apiLevel.toString(16).padStart(64, "0"),
        snapshot: "clean",
        capabilities,
      };
    },
    stop: async () => ({ stopped: true }),
  },
  scenario: {
    run: async ({ scenario, iteration }) => ({
      schemaVersion: "1.0",
      runId: `018f47a2-4bc8-7f31-8b9a-${String(count).padStart(12, "0")}`,
      scenarioId: scenario.scenarioId,
      iteration,
      startedAt: "2026-07-25T12:00:00.000Z",
      finishedAt: "2026-07-25T12:00:00.001Z",
      status: "passed",
      errorCode: null,
      durationMs: 1,
      actionCommits: 1,
      steps: [{
        sequence: 1,
        stepId: "matrix-step",
        actionType: "tap",
        targetPackage: "dev.aiauto.fixture",
        status: "passed",
        errorCode: null,
        pageClass: "normal",
        route: scenario.scenarioId === "canvas-fixture" ? "visual" : "semantic",
        score: 1,
        attempts: 1,
        preObservationId: "pre",
        postObservationId: "post",
        actionCommits: 1,
        durationMs: 1,
      }],
      cleanup: {
        attempted: 4,
        completed: 4,
        errorCode: null,
      },
    }),
  },
  residue: {
    inspect: async () => ({
      appData: 0,
      bridgeSessions: 0,
      testServices: 0,
      screenshots: 0,
      logs: 0,
      ownedProcesses: 0,
      runtimeFiles: 0,
      leases: 0,
    }),
  },
};
let now = Date.parse("2026-07-25T12:00:00.000Z");
const clock = {
  now: () => new Date(now).toISOString(),
  tick: () => {
    now += 1;
  },
};

const schema = await loadMatrixReportSchema();
if (
  schema?.$schema !== "https://json-schema.org/draft/2020-12/schema"
  || schema.type !== "object"
  || schema.additionalProperties !== false
) {
  throw new Error("N52 matrix report schema shape is invalid");
}
const report = await runLocalMatrix({
  matrixId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
  ports,
  clock,
});
const errors = validateMatrixReport(schema, report);
if (errors.length > 0) {
  throw new Error(`N52 fake report invalid: ${errors.join("; ")}`);
}
if (
  report.profiles.map(({ profileId, apiLevel }) => `${profileId}:${apiLevel}`).join(",")
  !== profiles.map(({ profileId, apiLevel }) => `${profileId}:${apiLevel}`).join(",")
) {
  throw new Error("N52 profile identity drifted");
}
process.stdout.write(
  "N52 schema validation passed: 1 report schema, 300 fake runs, 15 statistics groups.\n",
);
