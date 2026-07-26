// 功能用途：按固定 300 轮计划编排 N31 lifecycle、N47 场景、残留复核和 N34 统计。
import { aggregateTwentyRuns } from "../../../artifacts/src/statistics.mjs";
import {
  loadRunnerSchemas,
  validateRunnerDocument,
} from "../../../runner/src/schema-validator.mjs";
import {
  loadMatrixReportSchema,
  validateMatrixReport,
} from "./report.mjs";

const profiles = Object.freeze([
  Object.freeze({ profileId: "api-30", apiLevel: 30 }),
  Object.freeze({ profileId: "api-33", apiLevel: 33 }),
  Object.freeze({ profileId: "api-34", apiLevel: 34 }),
]);
const scenarios = Object.freeze([
  Object.freeze({
    scenarioId: "native-fixture",
    requiredCapabilities: Object.freeze([
      "fixture.native",
      "semantic.action",
      "system.navigation",
    ]),
  }),
  Object.freeze({
    scenarioId: "webview-fixture",
    requiredCapabilities: Object.freeze([
      "fixture.webview",
      "semantic.action",
    ]),
  }),
  Object.freeze({
    scenarioId: "canvas-fixture",
    requiredCapabilities: Object.freeze([
      "fixture.canvas",
      "visual.action",
    ]),
  }),
  Object.freeze({
    scenarioId: "recording-editor",
    requiredCapabilities: Object.freeze([
      "compose.test",
      "fixture.editor",
    ]),
  }),
  Object.freeze({
    scenarioId: "recording-replay",
    requiredCapabilities: Object.freeze([
      "recording.replay",
      "semantic.action",
    ]),
  }),
]);
const requiredCapabilities = Object.freeze([
  ...new Set(scenarios.flatMap((scenario) => scenario.requiredCapabilities)),
].sort());
const residueKeys = [
  "appData",
  "bridgeSessions",
  "leases",
  "logs",
  "ownedProcesses",
  "runtimeFiles",
  "screenshots",
  "testServices",
];
const safeUuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const safeId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const errorCode = /^[A-Z][A-Z0-9_]{2,95}$/u;

export class LocalMatrixError extends Error {
  constructor(code) {
    super(code);
    this.name = "LocalMatrixError";
    this.code = code;
  }
}

const fail = (code) => {
  throw new LocalMatrixError(code);
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const assertPorts = (ports) => {
  if (
    !exactKeys(ports, ["capabilities", "lifecycle", "residue", "scenario"])
    || !exactKeys(ports.capabilities, ["probe"])
    || typeof ports.capabilities.probe !== "function"
    || !exactKeys(ports.lifecycle, ["startClean", "stop"])
    || typeof ports.lifecycle.startClean !== "function"
    || typeof ports.lifecycle.stop !== "function"
    || !exactKeys(ports.scenario, ["run"])
    || typeof ports.scenario.run !== "function"
    || !exactKeys(ports.residue, ["inspect"])
    || typeof ports.residue.inspect !== "function"
  ) {
    fail("MATRIX_PORT_INVALID");
  }
};

const assertClock = (clock) => {
  if (
    !exactKeys(clock, ["now", "tick"])
    || typeof clock.now !== "function"
    || typeof clock.tick !== "function"
  ) {
    fail("MATRIX_CLOCK_INVALID");
  }
};

const assertCapabilities = (profile, result) => {
  if (
    !exactKeys(result, ["apiLevel", "capabilities", "profileId"])
    || result.profileId !== profile.profileId
    || result.apiLevel !== profile.apiLevel
    || !Array.isArray(result.capabilities)
    || result.capabilities.length < 1
    || result.capabilities.length > 64
    || result.capabilities.some((capability) => !safeId.test(capability))
    || result.capabilities.some(
      (capability) => !requiredCapabilities.includes(capability),
    )
    || new Set(result.capabilities).size !== result.capabilities.length
  ) {
    fail("MATRIX_CAPABILITY_INVALID");
  }
  return Object.freeze([...result.capabilities].sort());
};

const probeCapabilities = async (ports) => {
  const output = new Map();
  let missing = false;
  for (const profile of profiles) {
    let result;
    try {
      result = await ports.capabilities.probe(profile);
    } catch {
      fail("MATRIX_CAPABILITY_PROBE_FAILED");
    }
    const capabilities = assertCapabilities(profile, result);
    output.set(profile.profileId, capabilities);
    if (
      requiredCapabilities.some(
        (capability) => !capabilities.includes(capability),
      )
    ) {
      missing = true;
    }
  }
  if (missing) fail("MATRIX_CAPABILITY_MISSING");
  return output;
};

const assertStart = (planned, result, capabilities) => {
  const startedCapabilities = Array.isArray(result?.capabilities)
    ? [...result.capabilities].sort()
    : [];
  if (
    !exactKeys(result, [
      "apiLevel",
      "capabilities",
      "deviceFingerprint",
      "profileId",
      "serial",
      "snapshot",
    ])
    || result.profileId !== planned.profile.profileId
    || result.apiLevel !== planned.profile.apiLevel
    || !safeId.test(result.serial ?? "")
    || !/^[a-f0-9]{64}$/u.test(result.deviceFingerprint ?? "")
    || result.snapshot !== "clean"
    || !Array.isArray(result.capabilities)
    || result.capabilities.length !== capabilities.length
    || new Set(result.capabilities).size !== result.capabilities.length
    || result.capabilities.some((capability) => !safeId.test(capability))
    || JSON.stringify(startedCapabilities) !== JSON.stringify(capabilities)
    || planned.scenario.requiredCapabilities.some(
      (capability) => !result.capabilities.includes(capability),
    )
  ) {
    fail("MATRIX_START_INVALID");
  }
};

const canCleanupStart = (planned, result) =>
  result !== null
  && typeof result === "object"
  && !Array.isArray(result)
  && result.profileId === planned.profile.profileId
  && result.apiLevel === planned.profile.apiLevel
  && safeId.test(result.serial ?? "");

const assertStop = (result) => {
  if (!exactKeys(result, ["stopped"]) || result.stopped !== true) {
    fail("MATRIX_STOP_FAILED");
  }
};

const assertResidue = (result) => {
  if (
    !exactKeys(result, residueKeys)
    || residueKeys.some(
      (key) => !Number.isInteger(result[key]) || result[key] < 0,
    )
  ) {
    fail("MATRIX_RESIDUE_INVALID");
  }
  return Object.freeze({ ...result });
};

const isClean = (residue) =>
  residue !== null && residueKeys.every((key) => residue[key] === 0);

const failureCategory = (code) => {
  if (code === null) return null;
  if (
    code === "SCENARIO_CANCELLED"
    || code === "ACTION_COMMIT_UNKNOWN"
    || code.startsWith("RUNNER_")
    || code.startsWith("ARTIFACT_")
    || code.startsWith("MATRIX_")
    || code === "SCENARIO_CLEANUP_FAILED"
    || code === "PAGE_NETWORK_FAILURE"
  ) {
    return "infrastructure";
  }
  if (
    code === "PAGE_EMULATOR_DETECTED"
    || code.startsWith("DEVICE_")
    || code === "OBSERVATION_INVALID"
  ) {
    return "device";
  }
  return "product";
};

const reportRetries = (report) =>
  Math.min(
    100,
    report.steps.reduce(
      (total, step) => total + Math.max(step.attempts - 1, 0),
      0,
    ),
  );

const routeCounts = (report) => {
  const counts = new Map();
  for (const step of report.steps) {
    if (step.route === null) continue;
    counts.set(step.route, (counts.get(step.route) ?? 0) + 1);
  }
  return counts;
};

const pageClassCounts = (report) => {
  const counts = new Map();
  for (const step of report.steps) {
    counts.set(step.pageClass, (counts.get(step.pageClass) ?? 0) + 1);
  }
  return counts;
};

const incrementCounts = (target, source) => {
  for (const [key, value] of source) {
    target.set(key, (target.get(key) ?? 0) + value);
  }
};

const sortedCounts = (counts) =>
  Object.fromEntries(
    [...counts.entries()].sort(([left], [right]) => left.localeCompare(right)),
  );

const aggregateRunCounts = (runs, field) => {
  const counts = new Map();
  for (const run of runs) {
    incrementCounts(counts, new Map(Object.entries(run[field])));
  }
  return Object.freeze(sortedCounts(counts));
};

const runSummary = ({
  planned,
  started,
  report,
  error,
  cleanup,
  cleanupVerified,
  durationMs,
}) => Object.freeze({
  profileId: planned.profile.profileId,
  apiLevel: planned.profile.apiLevel,
  scenarioId: planned.scenario.scenarioId,
  iteration: planned.iteration,
  serial: started.serial,
  deviceFingerprint: started.deviceFingerprint,
  snapshot: started.snapshot,
  status: error === null ? report?.status ?? "failed" : "failed",
  errorCode: error ?? report?.errorCode ?? null,
  durationMs,
  retries: report === null ? 0 : reportRetries(report),
  actionCommitUnknown:
    report?.actionCommits === null
    || report?.steps.some((step) => step.actionCommits === null)
    || false,
  routeCounts: Object.freeze(
    report === null
      ? {}
      : sortedCounts(routeCounts(report)),
  ),
  pageClassCounts: Object.freeze(
    report === null
      ? {}
      : sortedCounts(pageClassCounts(report)),
  ),
  cleanupVerified,
  cleanup,
});

const statisticsFor = (matrixId, profile, scenario, runs) => {
  const outcomes = runs.map((run) => ({
    runId: matrixId,
    scenarioId: `${profile.profileId}.${scenario.scenarioId}`,
    iteration: run.iteration,
    status: run.status === "passed" ? "passed" : "failed",
    category: run.status === "passed" ? null : failureCategory(run.errorCode),
    errorCode: run.status === "passed" ? null : run.errorCode,
    durationMs: run.durationMs,
    retries: run.retries,
  }));
  const statistics = aggregateTwentyRuns(outcomes);
  return Object.freeze({
    profileId: profile.profileId,
    apiLevel: profile.apiLevel,
    scenarioId: scenario.scenarioId,
    classification: statistics.classification,
    total: statistics.total,
    passed: statistics.passed,
    failed: statistics.failed,
    successRate: statistics.successRate,
    durationMs: statistics.durationMs,
    retryCount: statistics.retryCount,
    failureCounts: statistics.failureCounts,
    errorCodes: statistics.errorCodes,
    flakyIterations: statistics.flakyIterations,
    routeCounts: aggregateRunCounts(runs, "routeCounts"),
    pageClassCounts: aggregateRunCounts(runs, "pageClassCounts"),
  });
};

export const createLocalMatrixPlan = () => Object.freeze(
  profiles.flatMap((profile) =>
    scenarios.flatMap((scenario) =>
      Array.from({ length: 20 }, (_, index) => Object.freeze({
        profile,
        scenario,
        iteration: index + 1,
      })))),
);

export const runLocalMatrix = async ({
  matrixId,
  ports,
  clock,
}) => {
  if (!safeUuid.test(matrixId ?? "")) fail("MATRIX_INPUT_INVALID");
  assertPorts(ports);
  assertClock(clock);
  const schemas = await loadRunnerSchemas();
  const capabilities = await probeCapabilities(ports);
  const plan = createLocalMatrixPlan();
  const startedAt = clock.now();
  const runsByProfile = new Map(
    profiles.map((profile) => [profile.profileId, []]),
  );
  const routeCountsByProfile = new Map(
    profiles.map((profile) => [profile.profileId, new Map()]),
  );
  const pageClassCountsByProfile = new Map(
    profiles.map((profile) => [profile.profileId, new Map()]),
  );
  const fingerprintByProfile = new Map();
  let matrixError = null;

  for (const planned of plan) {
    const runStartedAt = clock.now();
    let started = null;
    let startedValid = false;
    let cleanupContextSafe = false;
    let report = null;
    let error = null;
    let cleanup = null;
    let cleanupVerified = false;
    let stopSucceeded = false;
    try {
      try {
        started = await ports.lifecycle.startClean(planned);
      } catch {
        matrixError = "MATRIX_START_FAILED";
        break;
      }
      try {
        assertStart(
          planned,
          started,
          capabilities.get(planned.profile.profileId),
        );
        startedValid = true;
      } catch {
        error = "MATRIX_START_INVALID";
        matrixError = error;
      }
      cleanupContextSafe = canCleanupStart(planned, started);
      if (startedValid) {
        const expectedFingerprint = fingerprintByProfile.get(
          planned.profile.profileId,
        );
        if (expectedFingerprint === undefined) {
          fingerprintByProfile.set(
            planned.profile.profileId,
            started.deviceFingerprint,
          );
        } else if (expectedFingerprint !== started.deviceFingerprint) {
          error = "MATRIX_FINGERPRINT_DRIFT";
          matrixError = error;
        }
      }
      if (startedValid && matrixError === null) {
        try {
          report = await ports.scenario.run({
            profile: planned.profile,
            scenario: planned.scenario,
            iteration: planned.iteration,
            device: started,
          });
        } catch {
          error = "MATRIX_SCENARIO_PORT_FAILED";
        }
        if (
          report !== null
          && (
            validateRunnerDocument(schemas.runReport, report).length > 0
            || report.scenarioId !== planned.scenario.scenarioId
            || report.iteration !== planned.iteration
          )
        ) {
          report = null;
          error = "MATRIX_SCENARIO_REPORT_INVALID";
        }
        if (report !== null) {
          incrementCounts(
            routeCountsByProfile.get(planned.profile.profileId),
            routeCounts(report),
          );
          incrementCounts(
            pageClassCountsByProfile.get(planned.profile.profileId),
            pageClassCounts(report),
          );
        }
      }
      if (cleanupContextSafe) {
        try {
          assertStop(await ports.lifecycle.stop(started));
          stopSucceeded = true;
        } catch {
          error = "MATRIX_STOP_FAILED";
          matrixError = error;
        }
      }
      if (stopSucceeded) {
        try {
          cleanup = assertResidue(await ports.residue.inspect(started));
          cleanupVerified = isClean(cleanup);
          if (!cleanupVerified) {
            error = "MATRIX_RESIDUE_DETECTED";
            matrixError = error;
          }
        } catch {
          error = "MATRIX_RESIDUE_INVALID";
          matrixError = error;
        }
      }
    } finally {
      if (started !== null && startedValid) {
        clock.tick();
        const durationMs = Math.max(
          0,
          Date.parse(clock.now()) - Date.parse(runStartedAt),
        );
        const summary = runSummary({
          planned,
          started,
          report,
          error,
          cleanup,
          cleanupVerified,
          durationMs,
        });
        runsByProfile.get(planned.profile.profileId).push(summary);
      }
    }
    if (matrixError !== null) break;
  }

  const scenarioStatistics = [];
  for (const profile of profiles) {
    const profileRuns = runsByProfile.get(profile.profileId);
    for (const scenario of scenarios) {
      const runs = profileRuns.filter(
        (run) => run.scenarioId === scenario.scenarioId,
      );
      if (runs.length === 20) {
        scenarioStatistics.push(
          statisticsFor(matrixId, profile, scenario, runs),
        );
      }
    }
  }

  const profileReports = profiles.map((profile) => {
    const runs = runsByProfile.get(profile.profileId);
    const routes = routeCountsByProfile.get(profile.profileId);
    const pageClasses = pageClassCountsByProfile.get(profile.profileId);
    const errors = new Map();
    for (const run of runs) {
      if (run.errorCode !== null) {
        errors.set(run.errorCode, (errors.get(run.errorCode) ?? 0) + 1);
      }
    }
    return Object.freeze({
      profileId: profile.profileId,
      apiLevel: profile.apiLevel,
      capabilities: capabilities.get(profile.profileId),
      runs: Object.freeze([...runs]),
      passed: runs.filter((run) => run.status === "passed").length,
      failed: runs.filter((run) => run.status === "failed").length,
      cancelled: runs.filter((run) => run.status === "cancelled").length,
      cleanupPassed: runs.filter((run) => run.cleanupVerified).length,
      routeCounts: Object.freeze(sortedCounts(routes)),
      pageClassCounts: Object.freeze(sortedCounts(pageClasses)),
      errorCodeCounts: Object.freeze(sortedCounts(errors)),
    });
  });

  const allRuns = profileReports.flatMap((profile) => profile.runs);
  const unknownCommit = allRuns.some((run) => run.actionCommitUnknown);
  const fingerprintsStable = profileReports.every(
    (profile) =>
      new Set(profile.runs.map((run) => run.deviceFingerprint)).size <= 1,
  );
  const acceptancePassed =
    matrixError === null
    && allRuns.length === 300
    && allRuns.every((run) => run.cleanupVerified)
    && scenarioStatistics.length === 15
    && scenarioStatistics.every((item) => item.successRate >= 0.95)
    && !unknownCommit
    && fingerprintsStable;
  if (matrixError === null && unknownCommit) {
    matrixError = "MATRIX_ACTION_COMMIT_UNKNOWN";
  }
  if (matrixError === null && !acceptancePassed) {
    matrixError = "MATRIX_ACCEPTANCE_FAILED";
  }
  clock.tick();
  const report = Object.freeze({
    schemaVersion: "1.0",
    matrixId,
    startedAt,
    finishedAt: clock.now(),
    iterationsPerScenario: 20,
    plannedRuns: 300,
    executedRuns: allRuns.length,
    cleanupPassedRuns: allRuns.filter((run) => run.cleanupVerified).length,
    succeeded: acceptancePassed,
    errorCode: acceptancePassed ? null : matrixError,
    profiles: Object.freeze(profileReports),
    scenarioStatistics: Object.freeze(scenarioStatistics),
  });
  const schema = await loadMatrixReportSchema();
  const reportErrors = validateMatrixReport(schema, report);
  if (reportErrors.length > 0) {
    const error = new LocalMatrixError("MATRIX_REPORT_INVALID");
    error.validationErrors = Object.freeze([...reportErrors]);
    throw error;
  }
  return report;
};
