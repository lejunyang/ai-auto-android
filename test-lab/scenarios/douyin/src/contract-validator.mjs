// 功能用途：严格验证固定抖音策略与报告，并从离线 runs 重算真实轮次和安全统计。
import { readFile } from "node:fs/promises";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import {
  validateRunnerDocument,
} from "../../../runner/src/schema-validator.mjs";

const schemaDirectory = path.resolve(import.meta.dirname, "..", "schema");

const manifestIdentity = Object.freeze({
  manifestId: "douyin-39.8.0-arm64.json",
  packageName: "com.ss.android.ugc.aweme",
  versionName: "39.8.0",
  versionCode: 390801,
  abi: "arm64-v8a",
  apkSha256:
    "863707c8bac6c18a33907c4c2292d7994b4be0bc38bde1f24b7370e34cfe0a42",
  signingCertificateSha256:
    "5182ae3b1b85337bb182cf24882449f84447ded18e296a747a9f6a0a2622512e",
});

const capabilityPolicy = Object.freeze([
  ["observe-public-feed", "observe-only"],
  ["vertical-swipe", "fresh-content-change-required"],
  ["search-input", "typed-n47-only"],
  ["page-navigation", "typed-n47-only"],
  ["back", "typed-n47-only"],
  ["home", "typed-n47-only"],
  ["recents", "typed-n47-only"],
  ["return-to-douyin", "typed-n47-only"],
  ["long-press", "unsupported-interaction-menu-risk"],
]);

const protectedNodeTypes = Object.freeze([
  "like",
  "comment",
  "follow",
  "send",
  "share",
  "favorite",
  "download",
]);

const classifications = Object.freeze([
  "content-change-verified",
  "video-surface",
  "visual-only",
  "login-wall",
  "advertisement",
  "emulator-detected",
  "hierarchy-failure-api33",
  "hierarchy-failure-api34",
  "long-press-unsupported",
  "action-commit-unknown",
]);

const classificationOutcomes = Object.freeze({
  "content-change-verified": ["passed", null],
  "video-surface": ["blocked", "VIDEO_SURFACE"],
  "visual-only": ["blocked", "VISUAL_ONLY"],
  "login-wall": ["blocked", "LOGIN_WALL"],
  advertisement: ["blocked", "ADVERTISEMENT"],
  "emulator-detected": ["blocked", "EMULATOR_DETECTED"],
  "hierarchy-failure-api33": ["blocked", "HIERARCHY_FAILURE_API33"],
  "hierarchy-failure-api34": ["blocked", "HIERARCHY_FAILURE_API34"],
  "long-press-unsupported": ["blocked", "LONG_PRESS_UNSUPPORTED"],
  "action-commit-unknown": ["failed", "ACTION_COMMIT_UNKNOWN"],
});

const isRecord = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const sameJson = (left, right) => isDeepStrictEqual(left, right);

const emptyProtectedNodeCounts = () =>
  Object.fromEntries(protectedNodeTypes.map((type) => [type, 0]));

const emptyClassificationCounts = () =>
  Object.fromEntries(classifications.map((classification) => [classification, 0]));

const validateSchemaFailClosed = (schema, value, label) => {
  try {
    return validateRunnerDocument(schema, value);
  } catch {
    return [`$: ${label} schema validation failed closed`];
  }
};

const scenarioSemanticErrors = (scenario) => {
  if (!isRecord(scenario)) return [];
  const errors = [];
  if (!sameJson(scenario.manifest, manifestIdentity)) {
    errors.push("$.manifest: does not match fixed Douyin manifest identity");
  }
  const capabilities = Array.isArray(scenario.capabilities)
    ? scenario.capabilities
    : [];
  capabilityPolicy.forEach(([type, policy], index) => {
    if (
      capabilities[index]?.type !== type
      || capabilities[index]?.policy !== policy
    ) {
      errors.push(
        `$.capabilities[${index}]: capability policy does not match catalog`,
      );
    }
  });
  const protectedNodes = Array.isArray(scenario.protectedNodes)
    ? scenario.protectedNodes
    : [];
  protectedNodeTypes.forEach((type, index) => {
    if (
      protectedNodes[index]?.type !== type
      || protectedNodes[index]?.maxActionCommits !== 0
    ) {
      errors.push(
        `$.protectedNodes[${index}]: protected node must remain zero commit`,
      );
    }
  });
  return errors;
};

export const recomputeDouyinStatistics = (runs) => {
  const safeRuns = Array.isArray(runs) ? runs.filter(isRecord) : [];
  const statusCounts = { passed: 0, failed: 0, blocked: 0 };
  const classificationCounts = emptyClassificationCounts();
  const protectedNodeCommits = emptyProtectedNodeCounts();
  for (const run of safeRuns) {
    if (Object.hasOwn(statusCounts, run.status)) statusCounts[run.status] += 1;
    if (Object.hasOwn(classificationCounts, run.classification)) {
      classificationCounts[run.classification] += 1;
    }
    for (const type of protectedNodeTypes) {
      if (Number.isInteger(run.protectedNodeCommits?.[type])) {
        protectedNodeCommits[type] += run.protectedNodeCommits[type];
      }
    }
  }
  const realRuns = safeRuns.filter((run) => run.source === "real").length;
  return {
    totalRuns: safeRuns.length,
    fixtureRuns: safeRuns.filter((run) => run.source === "fixture").length,
    realRuns,
    completedRealRounds: realRuns,
    statusCounts,
    classificationCounts,
    verifiedContentChanges: safeRuns.filter(
      (run) => run.classification === "content-change-verified",
    ).length,
    unknownCommitRuns: safeRuns.filter(
      (run) => run.actionCommits === null,
    ).length,
    protectedNodeCommits,
  };
};

const statusErrors = (run, prefix) => {
  const errors = [];
  if (
    run.status === "passed" && run.errorCode !== null
    || run.status !== "passed" && run.errorCode === null
  ) {
    errors.push(`${prefix}.errorCode: status and error code are inconsistent`);
  }
  if (
    run.actionCommits === 0
    && (run.attempts !== 0 || run.postObservationId !== null)
  ) {
    errors.push(`${prefix}: zero commit cannot have attempts or post observation`);
  }
  if (run.actionCommits === 1 && run.attempts !== 1) {
    errors.push(`${prefix}.attempts: committed action must have one attempt`);
  }
  if (run.actionCommits === null && run.attempts !== 1) {
    errors.push(`${prefix}.attempts: unknown commit must have one attempt`);
  }
  return errors;
};

const verifiedContentErrors = (run, prefix) => {
  const errors = [];
  if (
    run.status !== "passed"
    || run.errorCode !== null
    || run.actionType !== "vertical-swipe"
    || !["semantic", "hybrid"].includes(run.route)
    || run.actionCommits !== 1
    || run.attempts !== 1
    || run.retries !== 0
  ) {
    errors.push(`${prefix}: verified content change has invalid action outcome`);
  }
  if (
    typeof run.postObservationId !== "string"
    || run.postObservationId === run.preObservationId
  ) {
    errors.push(`${prefix}.postObservationId: fresh observation is required`);
  }
  if (
    typeof run.postContentFingerprint !== "string"
    || run.postContentFingerprint === run.preContentFingerprint
  ) {
    errors.push(
      `${prefix}.postContentFingerprint: verifiable content change is required`,
    );
  }
  return errors;
};

const blockedObservationErrors = (run, prefix) => {
  const errors = [];
  const isVisualOnly = run.classification === "visual-only";
  if (
    run.status !== "blocked"
    || run.actionType !== "observe"
    || run.actionCommits !== 0
    || run.attempts !== 0
    || run.retries !== 0
    || run.postObservationId !== null
    || run.postContentFingerprint !== null
    || run.route !== (isVisualOnly ? "visual" : null)
  ) {
    errors.push(`${prefix}: blocked observation cannot execute or escalate action`);
  }
  if (
    run.classification === "hierarchy-failure-api33"
    && run.apiLevel !== 33
  ) {
    errors.push(`${prefix}.apiLevel: API 33 hierarchy failure is misclassified`);
  }
  if (
    run.classification === "hierarchy-failure-api34"
    && run.apiLevel !== 34
  ) {
    errors.push(`${prefix}.apiLevel: API 34 hierarchy failure is misclassified`);
  }
  return errors;
};

const longPressErrors = (run, prefix) => {
  if (
    run.status === "blocked"
    && run.actionType === "long-press"
    && run.route === null
    && run.actionCommits === 0
    && run.attempts === 0
    && run.retries === 0
    && run.postObservationId === null
    && run.postContentFingerprint === null
  ) {
    return [];
  }
  return [`${prefix}: long press must remain unsupported with zero attempts`];
};

const unknownCommitErrors = (run, prefix) => {
  if (
    run.status === "failed"
    && run.errorCode === "ACTION_COMMIT_UNKNOWN"
    && run.actionCommits === null
    && run.attempts === 1
    && run.retries === 0
    && ["semantic", "hybrid"].includes(run.route)
    && run.postObservationId === null
    && run.postContentFingerprint === null
  ) {
    return [];
  }
  return [`${prefix}: unknown commit must stop without retry`];
};

const runSemanticErrors = (run, index) => {
  if (!isRecord(run)) return [];
  const prefix = `$.runs[${index}]`;
  const errors = statusErrors(run, prefix);
  const expectedOutcome = classificationOutcomes[run.classification];
  if (
    expectedOutcome
    && (
      run.status !== expectedOutcome[0]
      || run.errorCode !== expectedOutcome[1]
    )
  ) {
    errors.push(`${prefix}: classification outcome does not match fixed catalog`);
  }
  if (
    run.actionType === "long-press"
    && run.classification !== "long-press-unsupported"
  ) {
    errors.push(`${prefix}: long press cannot bypass unsupported policy`);
  }
  if (run.classification === "content-change-verified") {
    errors.push(...verifiedContentErrors(run, prefix));
  } else if (
    [
      "video-surface",
      "visual-only",
      "login-wall",
      "advertisement",
      "emulator-detected",
      "hierarchy-failure-api33",
      "hierarchy-failure-api34",
    ].includes(run.classification)
  ) {
    errors.push(...blockedObservationErrors(run, prefix));
  } else if (run.classification === "long-press-unsupported") {
    errors.push(...longPressErrors(run, prefix));
  } else if (run.classification === "action-commit-unknown") {
    errors.push(...unknownCommitErrors(run, prefix));
  }
  return errors;
};

const reportSemanticErrors = (report) => {
  if (!isRecord(report)) return [];
  const errors = [];
  if (!sameJson(report.manifest, manifestIdentity)) {
    errors.push("$.manifest: does not match fixed Douyin manifest identity");
  }
  const runs = Array.isArray(report.runs) ? report.runs : [];
  const runIds = new Set();
  const iterations = new Set();
  const observationIds = new Set();
  for (const [index, run] of runs.entries()) {
    errors.push(...runSemanticErrors(run, index));
    if (!isRecord(run)) continue;
    if (runIds.has(run.runId)) {
      errors.push(`$.runs[${index}].runId: duplicate run ID`);
    }
    runIds.add(run.runId);
    if (iterations.has(run.iteration)) {
      errors.push(`$.runs[${index}].iteration: duplicate iteration`);
    }
    iterations.add(run.iteration);
    for (const field of ["preObservationId", "postObservationId"]) {
      const observationId = run[field];
      if (typeof observationId !== "string") continue;
      if (observationIds.has(observationId)) {
        errors.push(`$.runs[${index}].${field}: observation is not fresh`);
      }
      observationIds.add(observationId);
    }
  }

  const realRuns = runs.filter((run) => run?.source === "real");
  const fixtureRuns = runs.filter((run) => run?.source === "fixture");
  if (realRuns.length > 0 && fixtureRuns.length > 0) {
    errors.push("$.runs: fixture and real runs cannot be mixed");
  }
  if (
    realRuns.length > 0
    && (
      realRuns.length !== 10
      || realRuns.some((run, index) => run.iteration !== index + 1)
    )
  ) {
    errors.push("$.runs: real evidence must contain ordered iterations 1 through 10");
  }
  const expectedStatistics = recomputeDouyinStatistics(runs);
  for (const key of [
    "totalRuns",
    "fixtureRuns",
    "realRuns",
    "completedRealRounds",
    "statusCounts",
    "classificationCounts",
    "verifiedContentChanges",
    "unknownCommitRuns",
    "protectedNodeCommits",
  ]) {
    if (!sameJson(report.statistics?.[key], expectedStatistics[key])) {
      errors.push(`$.statistics.${key}: does not match recomputed runs`);
    }
  }
  return errors;
};

export const validateDouyinScenario = (schema, scenario) => {
  const schemaErrors = validateSchemaFailClosed(schema, scenario, "Douyin scenario");
  try {
    return [...schemaErrors, ...scenarioSemanticErrors(scenario)];
  } catch {
    return [...schemaErrors, "$: Douyin scenario validation failed closed"];
  }
};

export const validateDouyinReport = (schema, report) => {
  const schemaErrors = validateSchemaFailClosed(schema, report, "Douyin report");
  try {
    return [...schemaErrors, ...reportSemanticErrors(report)];
  } catch {
    return [...schemaErrors, "$: Douyin report validation failed closed"];
  }
};

export const loadDouyinSchemas = async () => {
  const [scenario, report] = await Promise.all(
    ["scenario.schema.json", "compatibility-report.schema.json"].map(
      async (file) =>
        JSON.parse(await readFile(path.join(schemaDirectory, file), "utf8")),
    ),
  );
  return Object.freeze({ scenario, report });
};
