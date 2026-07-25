// 功能用途：确定性生成场景通过率、步骤兼容率、route、页面分类和稳定失败汇总。

const reportKeys = [
  "actionCommits",
  "cleanup",
  "durationMs",
  "errorCode",
  "finishedAt",
  "iteration",
  "runId",
  "scenarioId",
  "schemaVersion",
  "startedAt",
  "status",
  "steps",
];
const stepKeys = [
  "actionCommits",
  "actionType",
  "attempts",
  "durationMs",
  "errorCode",
  "pageClass",
  "postObservationId",
  "preObservationId",
  "route",
  "score",
  "sequence",
  "status",
  "stepId",
  "targetPackage",
];
const statuses = new Set(["passed", "failed", "cancelled"]);
const pageClasses = new Set([
  "normal",
  "advertisement",
  "update-prompt",
  "ab-variant",
  "emulator-detected",
  "network-failure",
  "login-wall",
  "unknown",
]);
const routes = new Set(["semantic", "hybrid", "visual"]);
const stableId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const safeErrorCode = /^[A-Z][A-Z0-9_]{2,95}$/u;

export class CompatibilityError extends Error {
  constructor(code) {
    super(code);
    this.name = "CompatibilityError";
    this.code = code;
  }
}

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const validReport = (report) => {
  if (
    !exactKeys(report, reportKeys)
    || report.schemaVersion !== "1.0"
    || !stableId.test(report.scenarioId ?? "")
    || !Number.isInteger(report.iteration)
    || report.iteration < 1
    || !statuses.has(report.status)
    || (
      report.errorCode !== null
      && !safeErrorCode.test(report.errorCode ?? "")
    )
    || (
      report.actionCommits !== null
      && (
        !Number.isInteger(report.actionCommits)
        || report.actionCommits < 0
        || report.actionCommits > 200
      )
    )
    || !Array.isArray(report.steps)
    || report.steps.length > 200
  ) {
    return false;
  }
  const validSteps = report.steps.every((step) =>
    exactKeys(step, stepKeys)
    && statuses.has(step.status)
    && pageClasses.has(step.pageClass)
    && (step.route === null || routes.has(step.route))
    && (step.errorCode === null || safeErrorCode.test(step.errorCode ?? ""))
    && (
      step.actionCommits === null
      || step.actionCommits === 0
      || step.actionCommits === 1
    )
    && (
      step.actionCommits !== null
      || step.status === "failed"
        && step.errorCode === "ACTION_COMMIT_UNKNOWN"
    ));
  if (!validSteps) return false;
  const hasUnknownCommit = report.steps.some(
    (step) => step.actionCommits === null,
  );
  const commits = report.steps.reduce(
    (sum, step) => sum + (step.actionCommits ?? 0),
    0,
  );
  return (
    report.status === "passed" && report.errorCode === null
    || report.status !== "passed" && report.errorCode !== null
  )
    && (
      hasUnknownCommit && report.actionCommits === null
      || !hasUnknownCommit && report.actionCommits === commits
    )
    && report.steps.every((step, index) =>
      step.sequence === index + 1
      && (
        step.status === "passed" && step.errorCode === null
        || step.status !== "passed" && step.errorCode !== null
      ));
};

const increment = (counts, key) => {
  if (key === null) return;
  counts.set(key, (counts.get(key) ?? 0) + 1);
};

const sortedObject = (counts) =>
  Object.fromEntries(
    [...counts.entries()].sort(([left], [right]) => left.localeCompare(right)),
  );

const failureCategory = (errorCode) => {
  if (errorCode === null) return null;
  if (
    errorCode === "SCENARIO_CANCELLED"
    || errorCode.startsWith("RUNNER_")
    || errorCode.startsWith("ARTIFACT_")
    || errorCode === "SCENARIO_CLEANUP_FAILED"
    || errorCode === "ACTION_COMMIT_UNKNOWN"
    || errorCode === "PAGE_NETWORK_FAILURE"
  ) {
    return "infrastructure";
  }
  if (
    errorCode === "PAGE_EMULATOR_DETECTED"
    || errorCode.startsWith("DEVICE_")
    || errorCode === "OBSERVATION_INVALID"
  ) {
    return "device";
  }
  return "product";
};

export const aggregateCompatibility = (reports) => {
  if (
    !Array.isArray(reports)
    || reports.length < 1
    || reports.length > 10000
    || reports.some((report) => !validReport(report))
  ) {
    throw new CompatibilityError("COMPATIBILITY_INPUT_INVALID");
  }
  const scenarioId = reports[0].scenarioId;
  const iterations = new Set();
  for (const report of reports) {
    if (
      report.scenarioId !== scenarioId
      || iterations.has(report.iteration)
    ) {
      throw new CompatibilityError("COMPATIBILITY_INPUT_INVALID");
    }
    iterations.add(report.iteration);
  }

  let passedRuns = 0;
  let failedRuns = 0;
  let cancelledRuns = 0;
  let passedSteps = 0;
  let totalSteps = 0;
  const routeCounts = new Map();
  const pageClassCounts = new Map();
  const failureCategoryCounts = new Map();
  const errorCodeCounts = new Map();
  for (const report of reports) {
    if (report.status === "passed") passedRuns += 1;
    if (report.status === "failed") failedRuns += 1;
    if (report.status === "cancelled") cancelledRuns += 1;
    increment(errorCodeCounts, report.errorCode);
    increment(failureCategoryCounts, failureCategory(report.errorCode));
    for (const step of report.steps) {
      totalSteps += 1;
      if (step.status === "passed") passedSteps += 1;
      increment(routeCounts, step.route);
      increment(pageClassCounts, step.pageClass);
    }
  }

  return Object.freeze({
    schemaVersion: "1.0",
    scenarioId,
    runs: reports.length,
    passedRuns,
    failedRuns,
    cancelledRuns,
    successRate: passedRuns / reports.length,
    stepCompatibilityRate: totalSteps === 0 ? 0 : passedSteps / totalSteps,
    routeCounts: Object.freeze(sortedObject(routeCounts)),
    pageClassCounts: Object.freeze(sortedObject(pageClassCounts)),
    failureCategoryCounts: Object.freeze(sortedObject(failureCategoryCounts)),
    errorCodeCounts: Object.freeze(sortedObject(errorCodeCounts)),
  });
};
