// 功能用途：严格验证小黑盒离线场景与报告，并从轮次内容重算安全判定和三 API 统计。
import { readFile } from "node:fs/promises";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import {
  validateRunnerDocument,
} from "../../../runner/src/schema-validator.mjs";

const schemaDirectory = path.resolve(import.meta.dirname, "..", "schema");

const schemaFiles = Object.freeze({
  scenario: "scenario.schema.json",
  runReport: "run-report.schema.json",
});

const apiLevels = Object.freeze([30, 33, 34]);

const manifestBinding = Object.freeze({
  manifestFile: "xiaoheihe-1.3.392-arm64.json",
  packageName: "com.max.xiaoheihe",
  versionName: "1.3.392",
  versionCode: 1114,
  abi: "arm64-v8a",
  apkSha256:
    "ee56e4220c98f160f5b744cb90aab8de87787801c45fa5bcb9cb1b531067b0ea",
  signingCertificateSha256:
    "ef42d004ab18b04e0789a9c2a246156589423f9186a345a5b0c52fcb6612d82c",
});

const scenarioSteps = Object.freeze([
  ["public-search-input", "xiaoheihe-public-content", "content-accessibility"],
  ["public-search-submit", "xiaoheihe-public-content", "content-accessibility"],
  ["public-detail-open", "xiaoheihe-public-content", "content-accessibility"],
  ["long-press-safe-content", "xiaoheihe-public-content", "content-accessibility"],
  ["public-list-scroll", "xiaoheihe-public-content", "content-accessibility"],
  ["public-list-swipe", "xiaoheihe-public-content", "content-accessibility"],
  ["back", "xiaoheihe-public-content", "system-navigation"],
  ["home", "android-system-ui", "system-navigation"],
  ["recents", "android-system-ui", "system-navigation"],
  ["switch-to-fixture-peer", "fixture-peer", "system-navigation"],
  ["cross-app-return", "xiaoheihe-public-content", "system-navigation"],
].map(([name, target, routePolicy], index) => Object.freeze({
  sequence: index + 1,
  stepId: name,
  action: name,
  target,
  routePolicy,
  postObservationRequired: true,
})));

const forbiddenEffects = Object.freeze([
  "login",
  "follow",
  "favorite",
  "comment",
  "post",
  "download",
  "share",
]);

const cleanupExpected = Object.freeze({
  searchHistory: "cleared",
  appData: "cleared",
  bridge: "cleared",
  session: "cleared",
  artifacts: "cleared",
  snapshot: "restored",
});

const sameJson = (left, right) => isDeepStrictEqual(left, right);

const isRecord = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const uniqueSorted = (values) => [...new Set(values)].sort();

const observationMap = (run) => new Map(
  (Array.isArray(run?.observations) ? run.observations : [])
    .filter(isRecord)
    .map((observation) => [observation.observationId, observation]),
);

const routeMatchesSurface = (step, observation) => {
  if (!isRecord(step) || !isRecord(observation)) return false;
  if (step.route === "native-accessibility") {
    return observation.surface === "native";
  }
  if (step.route === "webview-accessibility") {
    return observation.surface === "webview";
  }
  return step.route === "system-navigation";
};

const routeMatchesPolicy = (step, index) => {
  const expected = scenarioSteps[index];
  if (!expected) return false;
  if (expected.routePolicy === "system-navigation") {
    return step?.route === "system-navigation";
  }
  return ["native-accessibility", "webview-accessibility"].includes(step?.route);
};

const postObservationMatchesAction = (step, observation) => {
  if (!isRecord(step) || !isRecord(observation)) return false;
  if (["home", "recents"].includes(step.action)) {
    return observation.foreground === "system-ui"
      && observation.surface === "system";
  }
  if (step.action === "switch-to-fixture-peer") {
    return observation.foreground === "fixture-peer"
      && observation.surface === "native";
  }
  if (
    [
      "public-search-input",
      "public-search-submit",
      "public-detail-open",
      "long-press-safe-content",
      "public-list-scroll",
      "public-list-swipe",
      "back",
      "cross-app-return",
    ].includes(step.action)
  ) {
    return observation.foreground === "xiaoheihe"
      && ["native", "webview"].includes(observation.surface);
  }
  return false;
};

export const classifyXiaoheiheRun = (run) => {
  if (!isRecord(run)) {
    return {
      compatible: false,
      reasonCodes: ["STEP_SEQUENCE_INCOMPLETE"],
      actionCommits: 0,
      executedSteps: 0,
      completedSteps: 0,
      routeCounts: {
        nativeAccessibilitySteps: 0,
        webviewAccessibilitySteps: 0,
        systemNavigationSteps: 0,
      },
    };
  }

  const observations = Array.isArray(run.observations) ? run.observations : [];
  const steps = Array.isArray(run.steps) ? run.steps : [];
  const reasons = [];

  if (observations.some((item) => item?.consent === "blocked")) {
    reasons.push("CONSENT_BLOCKED");
  }
  if (observations.some((item) => item?.permission === "blocked")) {
    reasons.push("PERMISSION_BLOCKED");
  }
  if (observations.some((item) => item?.dynamicContent === "unknown")) {
    reasons.push("DYNAMIC_CONTENT_UNKNOWN");
  }
  if (observations.some((item) => item?.advertisement === "present")) {
    reasons.push("ADVERTISEMENT_PRESENT");
  }
  if (observations.some((item) => item?.advertisement === "unknown")) {
    reasons.push("ADVERTISEMENT_UNKNOWN");
  }
  if (observations.some((item) => item?.network === "failure")) {
    reasons.push("NETWORK_FAILURE");
  }
  if (observations.some((item) => item?.network === "unknown")) {
    reasons.push("NETWORK_UNKNOWN");
  }
  if (observations.some((item) =>
    item?.foreground === "unknown" || item?.surface === "unknown")) {
    reasons.push("OBSERVATION_STATE_UNKNOWN");
  }
  if (steps.some((step) => step?.actionCommits === null)) {
    reasons.push("ACTION_COMMIT_UNKNOWN");
  }
  if (
    steps.length !== scenarioSteps.length
    || steps.some((step, index) =>
      step?.sequence !== index + 1
      || step?.stepId !== scenarioSteps[index]?.stepId
      || step?.action !== scenarioSteps[index]?.action)
  ) {
    reasons.push("STEP_SEQUENCE_INCOMPLETE");
  }
  if (steps.some((step) => step?.status !== "passed")) {
    reasons.push("STEP_FAILED");
  }
  if (steps.some((step) =>
    step?.actionCommits === 1 && step?.postObservationId === null)) {
    reasons.push("POST_OBSERVATION_MISSING");
  }

  const observationsById = observationMap(run);
  if (steps.some((step, index) => !routeMatchesPolicy(step, index))) {
    reasons.push("ROUTE_POLICY_MISMATCH");
  }
  if (steps.some((step) =>
    !routeMatchesSurface(step, observationsById.get(step?.preObservationId)))) {
    reasons.push("ROUTE_SURFACE_MISMATCH");
  }
  if (steps.some((step) =>
    !observationsById.has(step?.preObservationId)
    || step?.postObservationId !== null
      && !observationsById.has(step?.postObservationId))) {
    reasons.push("OBSERVATION_REFERENCE_INVALID");
  }
  if (steps.some((step) =>
    step?.postObservationId !== null
    && !postObservationMatchesAction(
      step,
      observationsById.get(step.postObservationId),
    ))) {
    reasons.push("POST_OBSERVATION_MISMATCH");
  }
  if (!sameJson(run.cleanup, cleanupExpected)) reasons.push("CLEANUP_FAILED");

  const hasUnknownCommit = steps.some((step) => step?.actionCommits === null);
  const routeCounts = {
    nativeAccessibilitySteps: steps.filter((step) =>
      step?.status === "passed" && step?.route === "native-accessibility").length,
    webviewAccessibilitySteps: steps.filter((step) =>
      step?.status === "passed" && step?.route === "webview-accessibility").length,
    systemNavigationSteps: steps.filter((step) =>
      step?.status === "passed" && step?.route === "system-navigation").length,
  };
  const reasonCodes = uniqueSorted(reasons);

  return {
    compatible: reasonCodes.length === 0,
    reasonCodes,
    actionCommits: hasUnknownCommit
      ? null
      : steps.reduce(
        (total, step) =>
          total + (Number.isInteger(step?.actionCommits) ? step.actionCommits : 0),
        0,
      ),
    executedSteps: steps.filter((step) =>
      Number.isInteger(step?.attempts) && step.attempts > 0).length,
    completedSteps: steps.filter((step) => step?.status === "passed").length,
    routeCounts,
  };
};

const emptyApiCounts = () => ({
  totalRuns: 0,
  fixtureRuns: 0,
  realRuns: 0,
  compatibleRuns: 0,
  incompatibleRuns: 0,
  consentBlockedRuns: 0,
  permissionBlockedRuns: 0,
});

const emptyActionCoverage = () => Object.fromEntries(
  scenarioSteps.map((step) => [step.action, 0]),
);

export const recomputeXiaoheiheStatistics = (runs) => {
  const safeRuns = Array.isArray(runs) ? runs.filter(isRecord) : [];
  const statistics = {
    totalRuns: safeRuns.length,
    fixtureRuns: 0,
    realRuns: 0,
    compatibleRuns: 0,
    incompatibleRuns: 0,
    realCompatibleRuns: 0,
    realIncompatibleRuns: 0,
    successRate: 0,
    realSuccessRate: null,
    realMatrixComplete: false,
    apiCounts: {
      api30: emptyApiCounts(),
      api33: emptyApiCounts(),
      api34: emptyApiCounts(),
    },
    routeCounts: {
      nativeAccessibilitySteps: 0,
      webviewAccessibilitySteps: 0,
      systemNavigationSteps: 0,
    },
    actionCoverage: emptyActionCoverage(),
    classificationCounts: {
      dynamicContent: { stable: 0, changed: 0, unknown: 0 },
      advertisement: { absent: 0, present: 0, unknown: 0 },
      network: { available: 0, failure: 0, unknown: 0 },
    },
    consentBlockedRuns: 0,
    permissionBlockedRuns: 0,
    unknownCommitRuns: 0,
    cleanupFailedRuns: 0,
  };

  for (const run of safeRuns) {
    const decision = classifyXiaoheiheRun(run);
    const apiCounts = statistics.apiCounts[`api${run.binding?.apiLevel}`];
    if (apiCounts) apiCounts.totalRuns += 1;

    const sourceKey = run.source === "real" ? "realRuns" : "fixtureRuns";
    statistics[sourceKey] += 1;
    if (apiCounts) apiCounts[sourceKey] += 1;

    const compatibilityKey = decision.compatible
      ? "compatibleRuns"
      : "incompatibleRuns";
    statistics[compatibilityKey] += 1;
    if (apiCounts) apiCounts[compatibilityKey] += 1;
    if (run.source === "real") {
      statistics[decision.compatible
        ? "realCompatibleRuns"
        : "realIncompatibleRuns"] += 1;
    }

    for (const [key, value] of Object.entries(decision.routeCounts)) {
      statistics.routeCounts[key] += value;
    }
    for (const step of Array.isArray(run.steps) ? run.steps : []) {
      if (
        step?.status === "passed"
        && Object.hasOwn(statistics.actionCoverage, step.action)
      ) {
        statistics.actionCoverage[step.action] += 1;
      }
    }
    for (const observation of Array.isArray(run.observations)
      ? run.observations
      : []) {
      for (const [field, key] of [
        ["dynamicContent", "dynamicContent"],
        ["advertisement", "advertisement"],
        ["network", "network"],
      ]) {
        const value = observation?.[field];
        if (Object.hasOwn(statistics.classificationCounts[key], value)) {
          statistics.classificationCounts[key][value] += 1;
        }
      }
    }

    if (decision.reasonCodes.includes("CONSENT_BLOCKED")) {
      statistics.consentBlockedRuns += 1;
      if (apiCounts) apiCounts.consentBlockedRuns += 1;
    }
    if (decision.reasonCodes.includes("PERMISSION_BLOCKED")) {
      statistics.permissionBlockedRuns += 1;
      if (apiCounts) apiCounts.permissionBlockedRuns += 1;
    }
    if (decision.actionCommits === null) statistics.unknownCommitRuns += 1;
    if (decision.reasonCodes.includes("CLEANUP_FAILED")) {
      statistics.cleanupFailedRuns += 1;
    }
  }

  statistics.successRate = statistics.totalRuns === 0
    ? 0
    : statistics.compatibleRuns / statistics.totalRuns;
  statistics.realSuccessRate = statistics.realRuns === 0
    ? null
    : statistics.realCompatibleRuns / statistics.realRuns;
  statistics.realMatrixComplete = statistics.realRuns === 10;
  return statistics;
};

const scenarioSemanticErrors = (scenario) => {
  if (!isRecord(scenario)) return [];
  const errors = [];
  if (!sameJson(scenario.manifestBinding, manifestBinding)) {
    errors.push("$.manifestBinding: fixed manifest identity mismatch");
  }
  if (!sameJson(scenario.execution?.supportedApiLevels, apiLevels)) {
    errors.push("$.execution.supportedApiLevels: expected API 30, 33 and 34");
  }
  if (!sameJson(scenario.execution?.forbiddenEffects, forbiddenEffects)) {
    errors.push("$.execution.forbiddenEffects: fixed safety set mismatch");
  }
  if (!sameJson(scenario.steps, scenarioSteps)) {
    errors.push("$.steps: fixed safe scenario sequence mismatch");
  }
  return errors;
};

const runSemanticErrors = (run, index, report) => {
  if (!isRecord(run)) return [];
  const errors = [];
  const prefix = `$.runs[${index}]`;
  if (
    run.source === "fixture"
    && run.fixtureSource !== "N50_OFFLINE_CONTRACT"
    || run.source === "real" && run.fixtureSource !== null
  ) {
    errors.push(`${prefix}.fixtureSource: source and fixture identity mismatch`);
  }
  if (
    run.binding?.scenarioId !== report.scenarioId
    || !sameJson(
      Object.fromEntries(
        Object.keys(manifestBinding).map((key) => [key, run.binding?.[key]]),
      ),
      manifestBinding,
    )
  ) {
    errors.push(`${prefix}.binding: report or manifest identity mismatch`);
  }

  const observations = Array.isArray(run.observations) ? run.observations : [];
  const observationIds = new Set();
  observations.forEach((observation, observationIndex) => {
    if (observationIds.has(observation?.observationId)) {
      errors.push(
        `${prefix}.observations[${observationIndex}].observationId: duplicate ID`,
      );
    }
    observationIds.add(observation?.observationId);
    if (observation?.sequence !== observationIndex + 1) {
      errors.push(
        `${prefix}.observations[${observationIndex}].sequence: not contiguous`,
      );
    }
  });

  const steps = Array.isArray(run.steps) ? run.steps : [];
  let unknownCommitIndex = -1;
  steps.forEach((step, stepIndex) => {
    const expected = scenarioSteps[stepIndex];
    if (
      step?.sequence !== stepIndex + 1
      || step?.stepId !== expected?.stepId
      || step?.action !== expected?.action
    ) {
      errors.push(`${prefix}.steps[${stepIndex}]: fixed step sequence mismatch`);
    }
    if (
      step?.status === "passed"
      && (
        step.actionCommits !== 1
        || step.errorCode !== null
        || step.postObservationId === null
      )
    ) {
      errors.push(
        `${prefix}.steps[${stepIndex}]: passed step contract mismatch`,
      );
    }
    if (
      step?.status === "failed"
      && (
        step.actionCommits === null
          ? step.errorCode !== "ACTION_COMMIT_UNKNOWN"
          : step.errorCode !== "STEP_FAILED"
      )
    ) {
      errors.push(
        `${prefix}.steps[${stepIndex}]: failed step contract mismatch`,
      );
    }
    if (step?.actionCommits === 0 && step?.postObservationId !== null) {
      errors.push(
        `${prefix}.steps[${stepIndex}].postObservationId: zero commit cannot have post observation`,
      );
    }
    if (step?.actionCommits === null) unknownCommitIndex = stepIndex;
    const preObservation = observations.find(
      (observation) => observation?.observationId === step?.preObservationId,
    );
    const postObservation = observations.find(
      (observation) => observation?.observationId === step?.postObservationId,
    );
    if (preObservation?.sequence !== stepIndex + 1) {
      errors.push(
        `${prefix}.steps[${stepIndex}].preObservationId: not latest observation`,
      );
    }
    if (
      step?.postObservationId !== null
      && postObservation?.sequence !== stepIndex + 2
    ) {
      errors.push(
        `${prefix}.steps[${stepIndex}].postObservationId: invalid post observation`,
      );
    }
  });
  if (unknownCommitIndex >= 0 && unknownCommitIndex !== steps.length - 1) {
    errors.push(`${prefix}.steps: unknown commit must stop later steps`);
  }
  const expectedObservationCount = steps.length === 0
    ? 1
    : steps.at(-1)?.postObservationId === null
      ? steps.length
      : steps.length + 1;
  if (observations.length !== expectedObservationCount) {
    errors.push(`${prefix}.observations: count does not match stopped timeline`);
  }

  const blocked = observations.some((observation) =>
    observation?.consent === "blocked"
    || observation?.permission === "blocked");
  if (
    blocked
    && steps.some((step) =>
      step?.actionCommits === null
      || Number.isInteger(step?.actionCommits) && step.actionCommits > 0)
  ) {
    errors.push(`${prefix}.steps: blocked run committed action`);
  }

  const recomputedDecision = classifyXiaoheiheRun(run);
  if (!sameJson(run.decision, recomputedDecision)) {
    errors.push(`${prefix}.decision: decision does not match run content`);
  }
  return errors;
};

const reportSemanticErrors = (report) => {
  if (!isRecord(report)) return [];
  const errors = [];
  if (!sameJson(report.manifestBinding, manifestBinding)) {
    errors.push("$.manifestBinding: fixed manifest identity mismatch");
  }
  if (!sameJson(report.matrix?.supportedApiLevels, apiLevels)) {
    errors.push("$.matrix.supportedApiLevels: expected API 30, 33 and 34");
  }

  const runs = Array.isArray(report.runs) ? report.runs : [];
  const runIds = new Set();
  const iterations = new Set();
  const reportObservationIds = new Set();
  let mixedSources = false;
  let firstSource;
  runs.forEach((run, index) => {
    if (runIds.has(run?.runId)) {
      errors.push(`$.runs[${index}].runId: duplicate run ID`);
    }
    runIds.add(run?.runId);
    const iteration = run?.binding?.iteration;
    if (iterations.has(iteration)) {
      errors.push(`$.runs[${index}].binding.iteration: duplicate iteration`);
    }
    iterations.add(iteration);
    if (firstSource === undefined) firstSource = run?.source;
    if (run?.source !== firstSource) mixedSources = true;
    for (const [observationIndex, observation] of (
      Array.isArray(run?.observations) ? run.observations : []
    ).entries()) {
      if (reportObservationIds.has(observation?.observationId)) {
        errors.push(
          `$.runs[${index}].observations[${observationIndex}].observationId: reused across runs`,
        );
      }
      reportObservationIds.add(observation?.observationId);
    }
    errors.push(...runSemanticErrors(run, index, report));
  });
  if (
    runs.length !== 10
    || runs.some((run, index) => run?.binding?.iteration !== index + 1)
  ) {
    errors.push("$.runs: evidence must contain ordered iterations 1 through 10");
  }
  if (mixedSources) {
    errors.push("$.runs: fixture and real runs cannot be mixed");
  }

  const recomputedStatistics = recomputeXiaoheiheStatistics(runs);
  for (const key of Object.keys(recomputedStatistics)) {
    if (!sameJson(report.statistics?.[key], recomputedStatistics[key])) {
      errors.push(`$.statistics.${key}: does not match run content`);
    }
  }
  return errors;
};

const validateSafely = (schema, value, semanticValidator, label) => {
  try {
    const schemaErrors = validateRunnerDocument(schema, value);
    return [...schemaErrors, ...semanticValidator(value)];
  } catch {
    return [`$: ${label} validation failed closed`];
  }
};

export const validateXiaoheiheScenario = (schema, scenario) =>
  validateSafely(
    schema,
    scenario,
    scenarioSemanticErrors,
    "xiaoheihe scenario",
  );

export const validateXiaoheiheReport = (schema, report) =>
  validateSafely(
    schema,
    report,
    reportSemanticErrors,
    "xiaoheihe report",
  );

export const loadXiaoheiheSchemas = async () => {
  const entries = await Promise.all(
    Object.entries(schemaFiles).map(async ([name, file]) => [
      name,
      JSON.parse(await readFile(path.join(schemaDirectory, file), "utf8")),
    ]),
  );
  return Object.fromEntries(entries);
};
