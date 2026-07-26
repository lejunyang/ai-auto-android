// 功能用途：加载并严格验证 N52 报告结构、轮次身份、统计计数和清理通过条件。
import { readFile } from "node:fs/promises";
import path from "node:path";

import { aggregateTwentyRuns } from "../../../artifacts/src/statistics.mjs";
import { validateRunnerDocument } from "../../../runner/src/schema-validator.mjs";

const profileIdentity = new Map([
  ["api-30", 30],
  ["api-33", 33],
  ["api-34", 34],
]);
const scenarioIds = [
  "native-fixture",
  "webview-fixture",
  "canvas-fixture",
  "recording-editor",
  "recording-replay",
];
const requiredCapabilities = [
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
const routeIds = new Set(["semantic", "hybrid", "visual"]);
const pageClassIds = new Set([
  "normal",
  "advertisement",
  "update-prompt",
  "ab-variant",
  "emulator-detected",
  "network-failure",
  "login-wall",
  "unknown",
]);

const allZero = (cleanup) =>
  cleanup !== null
  && Object.values(cleanup).every((value) => value === 0);

const countValues = (runs, field) => {
  const counts = new Map();
  for (const run of runs) {
    const value = run[field];
    if (value === null) continue;
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return Object.fromEntries(
    [...counts.entries()].sort(([left], [right]) => left.localeCompare(right)),
  );
};

const incrementCounts = (target, source) => {
  for (const [key, value] of Object.entries(source)) {
    target.set(key, (target.get(key) ?? 0) + value);
  }
};

const sortedCounts = (counts) =>
  Object.fromEntries(
    [...counts.entries()].sort(([left], [right]) => left.localeCompare(right)),
  );

const hasOnlyCountKeys = (value, allowed) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && Object.keys(value).every((key) => allowed.has(key));

const countTotal = (value) =>
  Object.values(value ?? {}).reduce(
    (total, count) => total + (Number.isInteger(count) ? count : 0),
    0,
  );

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

const statisticsForRuns = (matrixId, profile, scenarioId, runs) => {
  const statistics = aggregateTwentyRuns(
    runs.map((run) => ({
      runId: matrixId,
      scenarioId: `${profile.profileId}.${scenarioId}`,
      iteration: run.iteration,
      status: run.status === "passed" ? "passed" : "failed",
      category: run.status === "passed" ? null : failureCategory(run.errorCode),
      errorCode: run.status === "passed" ? null : run.errorCode,
      durationMs: run.durationMs,
      retries: run.retries,
    })),
  );
  const routes = new Map();
  const pageClasses = new Map();
  for (const run of runs) {
    incrementCounts(routes, run.routeCounts);
    incrementCounts(pageClasses, run.pageClassCounts);
  }
  return {
    profileId: profile.profileId,
    apiLevel: profile.apiLevel,
    scenarioId,
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
    routeCounts: sortedCounts(routes),
    pageClassCounts: sortedCounts(pageClasses),
  };
};

const sameJson = (left, right) => JSON.stringify(left) === JSON.stringify(right);

const semanticErrors = (report) => {
  if (report === null || typeof report !== "object" || Array.isArray(report)) {
    return [];
  }
  const errors = [];
  const profiles = Array.isArray(report.profiles) ? report.profiles : [];
  const runs = profiles.flatMap((profile) =>
    Array.isArray(profile.runs) ? profile.runs : []);
  if (report.executedRuns !== runs.length) {
    errors.push("$.executedRuns: does not equal recorded run count");
  }
  const cleanupPassed = runs.filter((run) =>
    run.cleanupVerified === true && allZero(run.cleanup)).length;
  if (report.cleanupPassedRuns !== cleanupPassed) {
    errors.push("$.cleanupPassedRuns: does not equal verified clean runs");
  }
  if (
    profiles.length === 3
    && !sameJson(
      profiles.map(({ profileId, apiLevel }) => ({ profileId, apiLevel })),
      [...profileIdentity].map(([profileId, apiLevel]) => ({ profileId, apiLevel })),
    )
  ) {
    errors.push("$.profiles: fixed profile order or API identity drifted");
  }

  const identities = new Set();
  for (const [profileIndex, profile] of profiles.entries()) {
    if (profileIdentity.get(profile.profileId) !== profile.apiLevel) {
      errors.push(`$.profiles[${profileIndex}]: profile API identity mismatch`);
    }
    const profileRuns = Array.isArray(profile.runs) ? profile.runs : [];
    if (
      !Array.isArray(profile.capabilities)
      || new Set(profile.capabilities).size !== profile.capabilities.length
      || !sameJson([...profile.capabilities].sort(), requiredCapabilities)
    ) {
      errors.push(`$.profiles[${profileIndex}].capabilities: required set drifted`);
    }
    const passed = profileRuns.filter((run) => run.status === "passed").length;
    const failed = profileRuns.filter((run) => run.status === "failed").length;
    const cancelled = profileRuns.filter((run) => run.status === "cancelled").length;
    const profileCleanupPassed = profileRuns.filter((run) =>
      run.cleanupVerified === true && allZero(run.cleanup)).length;
    const profileRoutes = new Map();
    const profilePageClasses = new Map();
    for (const run of profileRuns) {
      incrementCounts(profileRoutes, run.routeCounts ?? {});
      incrementCounts(profilePageClasses, run.pageClassCounts ?? {});
    }
    if (
      profile.passed !== passed
      || profile.failed !== failed
      || profile.cancelled !== cancelled
      || profile.cleanupPassed !== profileCleanupPassed
    ) {
      errors.push(`$.profiles[${profileIndex}]: run counters are inconsistent`);
    }
    if (!sameJson(profile.errorCodeCounts, countValues(profileRuns, "errorCode"))) {
      errors.push(`$.profiles[${profileIndex}].errorCodeCounts: counts drifted`);
    }
    if (!sameJson(profile.routeCounts, sortedCounts(profileRoutes))) {
      errors.push(`$.profiles[${profileIndex}].routeCounts: counts drifted`);
    }
    if (!sameJson(profile.pageClassCounts, sortedCounts(profilePageClasses))) {
      errors.push(`$.profiles[${profileIndex}].pageClassCounts: counts drifted`);
    }
    if (
      new Set(profileRuns.map((run) => run.deviceFingerprint)).size > 1
      && !(
        report.succeeded === false
        && report.errorCode === "MATRIX_FINGERPRINT_DRIFT"
      )
    ) {
      errors.push(`$.profiles[${profileIndex}]: device fingerprint drifted`);
    }
    for (const [runIndex, run] of profileRuns.entries()) {
      if (
        run.profileId !== profile.profileId
        || run.apiLevel !== profile.apiLevel
      ) {
        errors.push(
          `$.profiles[${profileIndex}].runs[${runIndex}]: profile identity mismatch`,
        );
      }
      if (!hasOnlyCountKeys(run.routeCounts, routeIds)) {
        errors.push(
          `$.profiles[${profileIndex}].runs[${runIndex}].routeCounts: unknown route`,
        );
      }
      if (!hasOnlyCountKeys(run.pageClassCounts, pageClassIds)) {
        errors.push(
          `$.profiles[${profileIndex}].runs[${runIndex}].pageClassCounts: unknown page class`,
        );
      }
      if (
        countTotal(run.pageClassCounts) > 200
        || countTotal(run.routeCounts) > countTotal(run.pageClassCounts)
      ) {
        errors.push(
          `$.profiles[${profileIndex}].runs[${runIndex}]: step summary counts are inconsistent`,
        );
      }
      const identity = `${run.profileId}:${run.scenarioId}:${run.iteration}`;
      if (identities.has(identity)) {
        errors.push(
          `$.profiles[${profileIndex}].runs[${runIndex}]: duplicate iteration identity`,
        );
      }
      identities.add(identity);
      if (
        run.status === "passed" && run.errorCode !== null
        || run.status !== "passed" && run.errorCode === null
      ) {
        errors.push(
          `$.profiles[${profileIndex}].runs[${runIndex}]: status/error mismatch`,
        );
      }
      if (
        run.cleanupVerified !== allZero(run.cleanup)
        || run.cleanupVerified === false
          && run.cleanup !== null
          && allZero(run.cleanup)
      ) {
        errors.push(
          `$.profiles[${profileIndex}].runs[${runIndex}].cleanup: cleanup verification mismatch`,
        );
      }
      if (run.cleanup !== null) {
        for (const [key, value] of Object.entries(run.cleanup)) {
          if (
            value !== 0
            && (
              run.cleanupVerified
              || report.succeeded
              || run.errorCode !== "MATRIX_RESIDUE_DETECTED"
            )
          ) {
            errors.push(
              `$.profiles[${profileIndex}].runs[${runIndex}].cleanup.${key}: residue contradicts status`,
            );
          }
        }
      }
    }
  }

  const statistics = Array.isArray(report.scenarioStatistics)
    ? report.scenarioStatistics
    : [];
  const statisticIds = new Set();
  const statisticsById = new Map();
  for (const [index, statistic] of statistics.entries()) {
    const identity = `${statistic.profileId}:${statistic.scenarioId}`;
    if (statisticIds.has(identity)) {
      errors.push(`$.scenarioStatistics[${index}]: duplicate profile/scenario`);
    }
    statisticIds.add(identity);
    statisticsById.set(identity, statistic);
    if (
      profileIdentity.get(statistic.profileId) !== statistic.apiLevel
      || !scenarioIds.includes(statistic.scenarioId)
      || statistic.passed + statistic.failed !== statistic.total
      || statistic.successRate !== statistic.passed / statistic.total
    ) {
      errors.push(`$.scenarioStatistics[${index}]: statistics are inconsistent`);
    }
  }
  const expectedStatistics = [];
  for (const profile of profiles) {
    for (const scenarioId of scenarioIds) {
      const scenarioRuns = profile.runs.filter(
        (run) => run.scenarioId === scenarioId,
      );
      if (scenarioRuns.length !== 20) continue;
      let expected;
      try {
        expected = statisticsForRuns(
          report.matrixId,
          profile,
          scenarioId,
          scenarioRuns,
        );
      } catch {
        errors.push(
          `$.profiles[${profile.profileId}].runs: ${scenarioId} cannot be aggregated`,
        );
        continue;
      }
      expectedStatistics.push(expected);
      if (!sameJson(statisticsById.get(`${profile.profileId}:${scenarioId}`), expected)) {
        errors.push(
          `$.scenarioStatistics: ${profile.profileId}/${scenarioId} aggregate drifted`,
        );
      }
    }
  }
  if (
    statistics.length !== expectedStatistics.length
    || statistics.some((statistic, index) =>
      !sameJson(statistic, expectedStatistics[index]))
  ) {
    errors.push("$.scenarioStatistics: completed group set or order drifted");
  }

  const allStatisticsPass = statistics.length === 15
    && statistics.every((statistic) => statistic.successRate >= 0.95);
  const noUnknownCommits = runs.every((run) => !run.actionCommitUnknown);
  const fingerprintsStable = profiles.every((profile) =>
    new Set((profile.runs ?? []).map((run) => run.deviceFingerprint)).size <= 1);
  const expectedSuccess =
    report.executedRuns === 300
    && report.cleanupPassedRuns === 300
    && allStatisticsPass
    && noUnknownCommits
    && fingerprintsStable;
  if (
    report.succeeded
    && profiles.some((profile) => profile.runs.length !== 100)
  ) {
    errors.push("$.profiles: succeeded report requires 100 runs per profile");
  }
  if (report.succeeded !== expectedSuccess) {
    errors.push("$.succeeded: does not match matrix acceptance gates");
  }
  if (
    report.succeeded && report.errorCode !== null
    || !report.succeeded && report.errorCode === null
  ) {
    errors.push("$.errorCode: does not match matrix status");
  }
  return errors;
};

export const validateMatrixReport = (schema, report) => {
  let schemaErrors;
  try {
    schemaErrors = validateRunnerDocument(schema, report);
  } catch {
    return ["$: matrix schema validation failed closed"];
  }
  try {
    return [...schemaErrors, ...semanticErrors(report)];
  } catch {
    return [...schemaErrors, "$: matrix semantic validation failed closed"];
  }
};

export const loadMatrixReportSchema = async () =>
  JSON.parse(
    await readFile(
      path.resolve(
        import.meta.dirname,
        "..",
        "schema",
        "matrix-report.schema.json",
      ),
      "utf8",
    ),
  );
