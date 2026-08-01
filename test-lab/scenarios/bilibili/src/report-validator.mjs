// 功能用途：严格验证 B站固定十轮场景计划与报告，并从轮次内容重算安全统计。
import { readFile } from "node:fs/promises";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import {
  validateRunnerDocument,
} from "../../../runner/src/schema-validator.mjs";

const schemasDirectory = path.resolve(import.meta.dirname, "..", "schema");

const identity = Object.freeze({
  manifest: "bilibili-9.5.0-arm64.json",
  packageName: "tv.danmaku.bili",
  versionName: "9.5.0",
  versionCode: 9050300,
  abi: "arm64-v8a",
  sizeBytes: 203302151,
  apkSha256: "618b8517d6226300c7223b22b9c9d985df4e2a339d9ea957e96f60cea7a9bf6a",
  signingCertificateSha256:
    "93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e",
});

const packages = Object.freeze({
  bilibili: "tv.danmaku.bili",
  fixture: "dev.aiauto.fixture",
  launcher: "com.android.launcher",
  recents: "com.android.systemui",
});

const fixedSteps = Object.freeze([
  {
    stepId: "launch-bilibili",
    actionId: "launch",
    pre: packages.launcher,
    post: packages.bilibili,
  },
  {
    stepId: "open-public-search",
    actionId: "open-public-search",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "input-public-query",
    actionId: "input-public-query",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "click-public-result",
    actionId: "click-public-result",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "scroll-public-result",
    actionId: "scroll-public-result",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "swipe-public-result",
    actionId: "swipe-public-result",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "long-press-safe-content",
    actionId: "long-press-safe-content",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "back-result-detail",
    actionId: "back",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "back-search-results",
    actionId: "back",
    pre: packages.bilibili,
    post: packages.bilibili,
  },
  {
    stepId: "home-from-bilibili",
    actionId: "home",
    pre: packages.bilibili,
    post: packages.launcher,
  },
  {
    stepId: "recents-from-home",
    actionId: "recents",
    pre: packages.launcher,
    post: packages.recents,
  },
  {
    stepId: "return-bilibili-from-recents",
    actionId: "return-from-recents",
    pre: packages.recents,
    post: packages.bilibili,
  },
  {
    stepId: "switch-native-fixture",
    actionId: "switch-fixture",
    pre: packages.bilibili,
    post: packages.fixture,
  },
  {
    stepId: "return-bilibili-from-fixture",
    actionId: "return-from-fixture",
    pre: packages.fixture,
    post: packages.bilibili,
  },
  {
    stepId: "final-home",
    actionId: "home",
    pre: packages.bilibili,
    post: packages.launcher,
  },
]);

const systemActions = new Set([
  "back",
  "home",
  "recents",
  "return-from-recents",
]);
const actionRoutes = Object.freeze({
  content: Object.freeze(["semantic", "hybrid", "visual"]),
  global: Object.freeze(["semantic"]),
});
const safePageClasses = new Set([
  "consent",
  "privacy",
  "permission",
  "login",
  "update",
  "advertisement",
  "unknown",
]);
const classificationNames = Object.freeze([
  "advertisement",
  "api33-hierarchy-unavailable",
  "api34-package-invisible",
  "cleanup-failure",
  "compatible",
  "consent",
  "long-press-side-effect-unproven",
  "login",
  "permission",
  "privacy",
  "unknown",
  "unknown-commit",
  "update",
]);
const blockerNames = Object.freeze([
  "ACTION_COMMIT_UNKNOWN",
  "BILIBILI_ADVERTISEMENT_BLOCKED",
  "BILIBILI_API33_HIERARCHY_UNAVAILABLE",
  "BILIBILI_API34_PACKAGE_INVISIBLE",
  "BILIBILI_CONSENT_BLOCKED",
  "BILIBILI_LOGIN_BLOCKED",
  "BILIBILI_LONG_PRESS_UNSUPPORTED",
  "BILIBILI_PERMISSION_BLOCKED",
  "BILIBILI_PRIVACY_BLOCKED",
  "BILIBILI_UNKNOWN_BLOCKED",
  "BILIBILI_UPDATE_BLOCKED",
  "SCENARIO_CLEANUP_FAILED",
]);
const blockedCodes = Object.freeze({
  consent: "BILIBILI_CONSENT_BLOCKED",
  privacy: "BILIBILI_PRIVACY_BLOCKED",
  permission: "BILIBILI_PERMISSION_BLOCKED",
  login: "BILIBILI_LOGIN_BLOCKED",
  update: "BILIBILI_UPDATE_BLOCKED",
  advertisement: "BILIBILI_ADVERTISEMENT_BLOCKED",
  unknown: "BILIBILI_UNKNOWN_BLOCKED",
});

const isRecord = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const countObject = (names) =>
  Object.fromEntries(names.map((name) => [name, 0]));

const cleanupComplete = (cleanup) =>
  isRecord(cleanup)
  && cleanup.appDataCleared === true
  && cleanup.searchHistoryCleared === true
  && cleanup.sessionCleared === true
  && cleanup.artifactsCleared === true
  && cleanup.snapshotRestored === true;

const fixedIterations = (rounds) =>
  Array.isArray(rounds)
  && rounds.length === 10
  && rounds.every((round, index) => round?.iteration === index + 1);

const validFixtureBinding = (binding) =>
  isRecord(binding)
  && binding.kind === "fixture"
  && binding.fixtureId === "n48-contract-fake-v1"
  && Object.keys(binding).length === 2;

const validRealBinding = (binding) =>
  isRecord(binding)
  && binding.kind === "n47-run-report"
  && typeof binding.runId === "string"
  && typeof binding.runReportSha256 === "string"
  && typeof binding.profileFingerprintSha256 === "string"
  && binding.verifiedApkSha256 === identity.apkSha256
  && binding.actualRun === true;

export const recomputeBilibiliStatistics = (report) => {
  const rounds = Array.isArray(report?.rounds)
    ? report.rounds.filter(isRecord)
    : [];
  const routeCounts = countObject(["semantic", "hybrid", "visual"]);
  const classificationCounts = countObject(classificationNames);
  const blockerCounts = countObject(blockerNames);
  let fixtureRounds = 0;
  let realRounds = 0;
  let passedRounds = 0;
  let blockedRounds = 0;
  let failedRounds = 0;
  let unsupportedRounds = 0;
  let actionCommits = 0;
  let unknownCommitRounds = 0;
  let cleanedRounds = 0;

  for (const round of rounds) {
    if (round.evidenceKind === "fixture") fixtureRounds += 1;
    if (round.evidenceKind === "real-device") realRounds += 1;
    if (round.status === "passed") passedRounds += 1;
    if (round.status === "blocked") blockedRounds += 1;
    if (round.status === "failed") failedRounds += 1;
    if (round.status === "unsupported") unsupportedRounds += 1;
    if (Object.hasOwn(classificationCounts, round.classification)) {
      classificationCounts[round.classification] += 1;
    }
    if (Object.hasOwn(blockerCounts, round.blockerCode)) {
      blockerCounts[round.blockerCode] += 1;
    }
    if (cleanupComplete(round.cleanup)) cleanedRounds += 1;
    const steps = Array.isArray(round.steps) ? round.steps.filter(isRecord) : [];
    if (steps.some((step) => step.actionCommits === null)) {
      unknownCommitRounds += 1;
    }
    for (const step of steps) {
      if (Number.isInteger(step.actionCommits)) {
        actionCommits += step.actionCommits;
      }
      if (Object.hasOwn(routeCounts, step.route)) {
        routeCounts[step.route] += 1;
      }
    }
  }

  const tenRoundRealEvidenceComplete =
    report?.evidenceKind === "real-device"
    && fixedIterations(rounds)
    && realRounds === 10
    && cleanedRounds === 10
    && unknownCommitRounds === 0
    && rounds.every((round) => validRealBinding(round.evidenceBinding))
    && new Set(rounds.map((round) => round.evidenceBinding.runId)).size === 10
    && new Set(rounds.map((round) => round.evidenceBinding.runReportSha256)).size
      === 10;
  return {
    plannedRounds: 10,
    reportedRounds: rounds.length,
    fixtureRounds,
    realRounds,
    passedRounds,
    blockedRounds,
    failedRounds,
    unsupportedRounds,
    successRate: rounds.length === 0 ? 0 : passedRounds / rounds.length,
    actionCommits,
    unknownCommitRounds,
    cleanedRounds,
    routeCounts,
    classificationCounts,
    blockerCounts,
    tenRoundRealEvidenceComplete,
  };
};

const planErrors = (plan) => {
  if (!isRecord(plan)) return [];
  const errors = [];
  if (!isDeepStrictEqual(plan.identity, identity)) {
    errors.push("$.identity: does not match fixed Bilibili manifest identity");
  }
  const steps = Array.isArray(plan.steps) ? plan.steps : [];
  if (steps.length !== fixedSteps.length) {
    errors.push("$.steps: does not match fixed safe step plan");
    return errors;
  }
  for (const [index, step] of steps.entries()) {
    const expected = fixedSteps[index];
    const routes = systemActions.has(expected.actionId)
      ? actionRoutes.global
      : actionRoutes.content;
    if (
      step?.sequence !== index + 1
      || step?.stepId !== expected.stepId
      || step?.actionId !== expected.actionId
      || step?.preForegroundPackage !== expected.pre
      || step?.postForegroundPackage !== expected.post
      || !isDeepStrictEqual(step?.allowedRoutes, routes)
      || step?.longPressProofRequired
        !== (expected.actionId === "long-press-safe-content")
    ) {
      errors.push(`$.steps[${index}]: does not match fixed safe step plan`);
    }
  }
  return errors;
};

const validLongPressProof = (proof) =>
  isRecord(proof)
  && proof.noLike === true
  && proof.noFavorite === true
  && proof.noDownload === true
  && proof.noShare === true;

const safeBlockedStepErrors = (step, round, prefix) => {
  const errors = [];
  if (
    step.status !== "blocked"
    || step.classification !== round.classification
    || step.attempts !== 0
    || step.actionCommits !== 0
    || step.postObserved !== false
    || step.route !== null
    || step.postForegroundPackage !== null
  ) {
    errors.push(`${prefix}: unsafe page must remain zero commit`);
  }
  if (round.blockerCode !== blockedCodes[round.classification]) {
    errors.push(`${prefix}: unsafe page blocker code is inconsistent`);
  }
  return errors;
};

const longPressErrors = (step, round, prefix) => {
  const errors = [];
  const proofSafe = validLongPressProof(step.sideEffectProof);
  if (proofSafe) {
    if (
      step.status !== "passed"
      || step.actionCommits !== 1
      || step.attempts !== 1
    ) {
      errors.push(`${prefix}: safe long press proof requires one commit`);
    }
    return errors;
  }
  if (
    round.status !== "unsupported"
    || round.classification !== "long-press-side-effect-unproven"
    || round.blockerCode !== "BILIBILI_LONG_PRESS_UNSUPPORTED"
    || step.status !== "unsupported"
    || step.classification !== "long-press-side-effect-unproven"
    || step.route !== null
    || step.attempts !== 0
    || step.actionCommits !== 0
    || step.postObserved !== false
    || step.postForegroundPackage !== null
  ) {
    errors.push(`${prefix}: long press proof is incomplete but action was not unsupported`);
  }
  return errors;
};

const stepErrors = (step, expected, round, index) => {
  if (!isRecord(step)) return [];
  const prefix = `$.rounds[${round.iteration - 1}].steps[${index}]`;
  const errors = [];
  if (
    step.sequence !== index + 1
    || step.stepId !== expected.stepId
    || step.actionId !== expected.actionId
  ) {
    errors.push(`${prefix}: does not match fixed safe step plan`);
  }
  if (step.preForegroundPackage !== expected.pre) {
    errors.push(`${prefix}.preForegroundPackage: foreground package is not fixed`);
  }
  if (
    step.postForegroundPackage !== null
    && step.postForegroundPackage !== expected.post
  ) {
    errors.push(`${prefix}.postForegroundPackage: foreground package is not fixed`);
  }

  if (safePageClasses.has(round.classification) && index === round.steps.length - 1) {
    return errors;
  }
  if (expected.actionId === "long-press-safe-content") {
    errors.push(...longPressErrors(step, round, prefix));
    if (step.status !== "passed") return errors;
  } else if (step.sideEffectProof !== "not-required") {
    errors.push(`${prefix}.sideEffectProof: proof is only valid for long press`);
  }

  if (step.status === "unknown-commit") {
    if (
      round.status !== "failed"
      || round.classification !== "unknown-commit"
      || round.blockerCode !== "ACTION_COMMIT_UNKNOWN"
      || step.attempts !== 1
      || step.actionCommits !== null
      || step.postObserved !== true
      || step.postForegroundPackage !== expected.post
      || index !== round.steps.length - 1
    ) {
      errors.push(`${prefix}: unknown commit must be observed once and never retried`);
    }
    return errors;
  }

  const allowedRoutes = systemActions.has(expected.actionId)
    ? actionRoutes.global
    : actionRoutes.content;
  if (
    step.status !== "passed"
    || step.classification !== "normal"
    || !allowedRoutes.includes(step.route)
    || step.attempts !== 1
    || step.actionCommits !== 1
    || step.postObserved !== true
    || step.postForegroundPackage !== expected.post
  ) {
    errors.push(`${prefix}: committed action requires one attempt and post observation`);
  }
  return errors;
};

const stableApiErrors = (round, prefix) => {
  if (round.apiLevel === 33) {
    if (
      round.status !== "blocked"
      || round.classification !== "api33-hierarchy-unavailable"
      || round.blockerCode !== "BILIBILI_API33_HIERARCHY_UNAVAILABLE"
      || round.steps?.length !== 0
    ) {
      return [`${prefix}: API 33 must use stable hierarchy unavailable classification`];
    }
  }
  if (round.apiLevel === 34) {
    if (
      round.status !== "blocked"
      || round.classification !== "api34-package-invisible"
      || round.blockerCode !== "BILIBILI_API34_PACKAGE_INVISIBLE"
      || round.steps?.length !== 0
    ) {
      return [`${prefix}: API 34 must use stable package invisible classification`];
    }
  }
  return [];
};

const outcomeErrors = (round, steps, prefix) => {
  const passed = round.status === "passed"
    && round.classification === "compatible"
    && round.blockerCode === null;
  const safeBlocked = round.status === "blocked"
    && safePageClasses.has(round.classification)
    && round.blockerCode === blockedCodes[round.classification];
  const apiBlocked = round.status === "blocked"
    && (
      round.classification === "api33-hierarchy-unavailable"
        && round.blockerCode === "BILIBILI_API33_HIERARCHY_UNAVAILABLE"
      || round.classification === "api34-package-invisible"
        && round.blockerCode === "BILIBILI_API34_PACKAGE_INVISIBLE"
    );
  const longPressUnsupported = round.status === "unsupported"
    && round.classification === "long-press-side-effect-unproven"
    && round.blockerCode === "BILIBILI_LONG_PRESS_UNSUPPORTED";
  const unknownCommit = round.status === "failed"
    && round.classification === "unknown-commit"
    && round.blockerCode === "ACTION_COMMIT_UNKNOWN"
    && steps.at(-1)?.status === "unknown-commit";
  const cleanupFailure = round.status === "failed"
    && round.classification === "cleanup-failure"
    && round.blockerCode === "SCENARIO_CLEANUP_FAILED"
    && !cleanupComplete(round.cleanup);
  if (
    !passed
    && !safeBlocked
    && !apiBlocked
    && !longPressUnsupported
    && !unknownCommit
    && !cleanupFailure
  ) {
    return [`${prefix}: status, classification and blocker are inconsistent`];
  }
  return [];
};

const roundErrors = (round, report, index) => {
  if (!isRecord(round)) return [];
  const prefix = `$.rounds[${index}]`;
  const errors = [];
  if (
    round.iteration !== index + 1
    || report.profile?.apiLevel !== round.apiLevel
    || report.evidenceKind !== round.evidenceKind
  ) {
    errors.push(`${prefix}: round does not match fixed ten-round plan`);
  }
  if (
    round.evidenceKind === "fixture"
    && !validFixtureBinding(round.evidenceBinding)
    || round.evidenceKind === "real-device"
      && !validRealBinding(round.evidenceBinding)
  ) {
    errors.push(`${prefix}.evidenceBinding: evidence source is inconsistent`);
  }
  errors.push(...stableApiErrors(round, prefix));
  const steps = Array.isArray(round.steps) ? round.steps : [];
  errors.push(...outcomeErrors(round, steps, prefix));
  if (steps.length > fixedSteps.length) {
    errors.push(`${prefix}.steps: exceeds fixed safe step plan`);
  }
  if (safePageClasses.has(round.classification) && steps.length > 0) {
    errors.push(
      ...safeBlockedStepErrors(
        steps.at(-1),
        round,
        `${prefix}.steps[${steps.length - 1}]`,
      ),
    );
  }
  for (const [stepIndex, step] of steps.entries()) {
    errors.push(
      ...stepErrors(step, fixedSteps[stepIndex], round, stepIndex),
    );
  }

  const hasUnknown = steps.some((step) => step?.actionCommits === null);
  if (hasUnknown && steps.at(-1)?.status !== "unknown-commit") {
    errors.push(`${prefix}.steps: unknown commit must stop the round`);
  }
  if (round.status === "passed") {
    if (
      round.apiLevel !== 30
      || round.classification !== "compatible"
      || round.blockerCode !== null
      || steps.length !== fixedSteps.length
      || steps.some((step) => step?.status !== "passed")
    ) {
      errors.push(`${prefix}: passed round does not match complete safe plan`);
    }
  }
  if (safePageClasses.has(round.classification)) {
    if (
      round.status !== "blocked"
      || steps.length < 1
      || steps.at(-1)?.status !== "blocked"
    ) {
      errors.push(`${prefix}: unsafe page must stop at the blocked step`);
    }
  }
  if (round.classification === "long-press-side-effect-unproven") {
    if (steps.length !== 7 || steps.at(-1)?.actionId !== "long-press-safe-content") {
      errors.push(`${prefix}: unsupported long press must stop before submission`);
    }
  }
  const cleaned = cleanupComplete(round.cleanup);
  if (
    !cleaned
    && (
      round.status !== "failed"
      || round.classification !== "cleanup-failure"
      || round.blockerCode !== "SCENARIO_CLEANUP_FAILED"
    )
  ) {
    errors.push(`${prefix}.cleanup: incomplete cleanup requires stable failure`);
  }
  if (
    cleaned
    && (
      round.classification === "cleanup-failure"
      || round.blockerCode === "SCENARIO_CLEANUP_FAILED"
    )
  ) {
    errors.push(`${prefix}.cleanup: cleanup failure contradicts completed cleanup`);
  }
  return errors;
};

const profileErrors = (profile) => {
  if (!isRecord(profile)) return [];
  if (profile.profileId !== `api-${profile.apiLevel}`) {
    return ["$.profile: profile ID and API level are inconsistent"];
  }
  return [];
};

const statisticsErrors = (report) => {
  if (!isRecord(report?.statistics)) return [];
  const expected = recomputeBilibiliStatistics(report);
  const errors = [];
  for (const key of Object.keys(expected)) {
    if (!isDeepStrictEqual(report.statistics[key], expected[key])) {
      errors.push(`$.statistics.${key}: does not match recomputed content`);
    }
  }
  return errors;
};

const reportErrors = (report) => {
  if (!isRecord(report)) return [];
  const errors = [];
  if (!isDeepStrictEqual(report.identity, identity)) {
    errors.push("$.identity: does not match fixed Bilibili manifest identity");
  }
  errors.push(...profileErrors(report.profile));
  const rounds = Array.isArray(report.rounds) ? report.rounds : [];
  if (!fixedIterations(rounds)) {
    errors.push("$.rounds: does not match fixed ten-round plan");
  }
  for (const [index, round] of rounds.entries()) {
    errors.push(...roundErrors(round, report, index));
  }
  const realBindings = rounds
    .filter((round) => round?.evidenceKind === "real-device")
    .map((round) => round.evidenceBinding)
    .filter(validRealBinding);
  if (
    new Set(realBindings.map((binding) => binding.runId)).size
      !== realBindings.length
    || new Set(realBindings.map((binding) => binding.runReportSha256)).size
      !== realBindings.length
  ) {
    errors.push("$.rounds: real evidence bindings must be unique");
  }
  errors.push(...statisticsErrors(report));
  return errors;
};

const validateSafely = (schema, value, semanticValidator, label) => {
  let schemaErrors;
  try {
    schemaErrors = validateRunnerDocument(schema, value);
  } catch {
    return [`$: ${label} schema validation failed closed`];
  }
  try {
    return [...schemaErrors, ...semanticValidator(value)];
  } catch {
    return [...schemaErrors, `$: ${label} semantic validation failed closed`];
  }
};

export const validateBilibiliPlan = (schema, plan) =>
  validateSafely(schema, plan, planErrors, "Bilibili plan");

export const validateBilibiliReport = (schema, report) =>
  validateSafely(schema, report, reportErrors, "Bilibili report");

export const loadBilibiliSchemas = async () => {
  const [plan, report] = await Promise.all([
    readFile(path.join(schemasDirectory, "scenario-plan.schema.json"), "utf8"),
    readFile(
      path.join(schemasDirectory, "compatibility-report.schema.json"),
      "utf8",
    ),
  ]);
  return Object.freeze({
    plan: JSON.parse(plan),
    report: JSON.parse(report),
  });
};
