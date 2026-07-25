// 功能用途：聚合固定二十轮结果并稳定区分产品、设备、基础设施失败与 flaky 波动。
const categories = new Set(["product", "device", "infrastructure"]);
const stableId = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u;
const errorCodePattern = /^[A-Z][A-Z0-9_]{2,95}$/u;

export class StatisticsError extends Error {
  constructor(code) {
    super(code);
    this.name = "StatisticsError";
    this.code = code;
  }
}

const requireInteger = (value, minimum, maximum, code) => {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new StatisticsError(code);
  }
};

const validateOutcome = (outcome) => {
  const keys = Object.keys(outcome).sort();
  const expected = [
    "category",
    "durationMs",
    "errorCode",
    "iteration",
    "retries",
    "runId",
    "scenarioId",
    "status",
  ];
  if (JSON.stringify(keys) !== JSON.stringify(expected)) {
    throw new StatisticsError("ITERATION_SCHEMA_INVALID");
  }
  if (
    typeof outcome.runId !== "string"
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu
      .test(outcome.runId)
    || typeof outcome.scenarioId !== "string"
    || !stableId.test(outcome.scenarioId)
  ) {
    throw new StatisticsError("ITERATION_SCHEMA_INVALID");
  }
  requireInteger(outcome.iteration, 1, 20, "ITERATION_SEQUENCE_INVALID");
  requireInteger(outcome.durationMs, 0, 86_400_000, "ITERATION_SCHEMA_INVALID");
  requireInteger(outcome.retries, 0, 100, "ITERATION_SCHEMA_INVALID");
  if (outcome.status === "passed") {
    if (outcome.category !== null || outcome.errorCode !== null) {
      throw new StatisticsError("ITERATION_SCHEMA_INVALID");
    }
    return;
  }
  if (
    outcome.status !== "failed"
    || !categories.has(outcome.category)
    || typeof outcome.errorCode !== "string"
    || !errorCodePattern.test(outcome.errorCode)
  ) {
    throw new StatisticsError("ITERATION_SCHEMA_INVALID");
  }
};

const percentile = (sorted, proportion) =>
  sorted[Math.max(0, Math.ceil(sorted.length * proportion) - 1)];

const signature = (outcome) =>
  outcome.status === "passed" ? "passed" : `failed:${outcome.category}`;

const dominantSignature = (outcomes) => {
  const counts = new Map();
  for (const outcome of outcomes) {
    const key = signature(outcome);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return [...counts.entries()]
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))[0][0];
};

export const aggregateTwentyRuns = (rawOutcomes) => {
  if (!Array.isArray(rawOutcomes) || rawOutcomes.length !== 20) {
    throw new StatisticsError("ITERATION_COUNT_INVALID");
  }
  rawOutcomes.forEach(validateOutcome);
  const outcomes = [...rawOutcomes].sort(
    (left, right) => left.iteration - right.iteration,
  );
  if (outcomes.some((outcome, index) => outcome.iteration !== index + 1)) {
    throw new StatisticsError("ITERATION_SEQUENCE_INVALID");
  }
  const runIds = new Set(outcomes.map((outcome) => outcome.runId));
  const scenarioIds = new Set(outcomes.map((outcome) => outcome.scenarioId));
  if (runIds.size !== 1 || scenarioIds.size !== 1) {
    throw new StatisticsError("ITERATION_IDENTITY_MISMATCH");
  }

  const passed = outcomes.filter((outcome) => outcome.status === "passed").length;
  const failures = outcomes.filter((outcome) => outcome.status === "failed");
  const failureCategories = new Set(failures.map((outcome) => outcome.category));
  const classification = passed === 20
    ? "passed"
    : passed === 0 && failureCategories.size === 1
      ? failures[0].category
      : "flaky";
  const failureCounts = {
    product: failures.filter((outcome) => outcome.category === "product").length,
    device: failures.filter((outcome) => outcome.category === "device").length,
    infrastructure: failures.filter(
      (outcome) => outcome.category === "infrastructure",
    ).length,
  };
  const errorCounts = new Map();
  for (const failure of failures) {
    errorCounts.set(failure.errorCode, (errorCounts.get(failure.errorCode) ?? 0) + 1);
  }
  const durations = outcomes
    .map((outcome) => outcome.durationMs)
    .sort((left, right) => left - right);
  const dominant = dominantSignature(outcomes);

  return {
    schemaVersion: "1.0",
    runId: outcomes[0].runId,
    scenarioId: outcomes[0].scenarioId,
    classification,
    total: 20,
    passed,
    failed: failures.length,
    successRate: passed / 20,
    durationMs: {
      min: durations[0],
      p50: percentile(durations, 0.5),
      p95: percentile(durations, 0.95),
      max: durations.at(-1),
    },
    retryCount: outcomes.reduce((sum, outcome) => sum + outcome.retries, 0),
    failureCounts,
    errorCodes: [...errorCounts.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([code, count]) => ({ code, count })),
    flakyIterations: classification === "flaky"
      ? outcomes
          .filter((outcome) => signature(outcome) !== dominant)
          .map((outcome) => outcome.iteration)
      : [],
  };
};
