// 测试用途：构造固定 B站十轮 fake 报告，覆盖安全页面、提交未知与清理回归。
import {
  recomputeBilibiliStatistics,
} from "../src/report-validator.mjs";

export const BILIBILI_PACKAGE = "tv.danmaku.bili";
export const FIXTURE_PACKAGE = "dev.aiauto.fixture";
export const LAUNCHER_PACKAGE = "com.android.launcher";
export const RECENTS_PACKAGE = "com.android.systemui";

export const PROFILE = Object.freeze({
  profileId: "api-30",
  apiLevel: 30,
  abi: "arm64-v8a",
  snapshot: "clean",
});

export const IDENTITY = Object.freeze({
  manifest: "bilibili-9.5.0-arm64.json",
  packageName: BILIBILI_PACKAGE,
  versionName: "9.5.0",
  versionCode: 9050300,
  abi: "arm64-v8a",
  sizeBytes: 203302151,
  apkSha256: "618b8517d6226300c7223b22b9c9d985df4e2a339d9ea957e96f60cea7a9bf6a",
  signingCertificateSha256:
    "93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e",
});

export const STEP_PLAN = Object.freeze([
  ["launch-bilibili", "launch", LAUNCHER_PACKAGE, BILIBILI_PACKAGE],
  ["open-public-search", "open-public-search", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["input-public-query", "input-public-query", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["click-public-result", "click-public-result", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["scroll-public-result", "scroll-public-result", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["swipe-public-result", "swipe-public-result", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["long-press-safe-content", "long-press-safe-content", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["back-result-detail", "back", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["back-search-results", "back", BILIBILI_PACKAGE, BILIBILI_PACKAGE],
  ["home-from-bilibili", "home", BILIBILI_PACKAGE, LAUNCHER_PACKAGE],
  ["recents-from-home", "recents", LAUNCHER_PACKAGE, RECENTS_PACKAGE],
  ["return-bilibili-from-recents", "return-from-recents", RECENTS_PACKAGE, BILIBILI_PACKAGE],
  ["switch-native-fixture", "switch-fixture", BILIBILI_PACKAGE, FIXTURE_PACKAGE],
  ["return-bilibili-from-fixture", "return-from-fixture", FIXTURE_PACKAGE, BILIBILI_PACKAGE],
  ["final-home", "home", BILIBILI_PACKAGE, LAUNCHER_PACKAGE],
]);

const systemActions = new Set([
  "back",
  "home",
  "recents",
  "return-from-recents",
]);

const routeFor = (actionId) =>
  systemActions.has(actionId)
    ? "semantic"
    : actionId === "switch-fixture" || actionId === "return-from-fixture"
      ? "semantic"
      : "semantic";

export const makePlan = () => ({
  schemaVersion: "1.0",
  planId: "bilibili-unlogged-public-content-v1",
  identity: structuredClone(IDENTITY),
  plannedIterations: 10,
  sideEffectPolicy: "none",
  queryRetention: "runtime-only",
  steps: STEP_PLAN.map(
    ([stepId, actionId, preForegroundPackage, postForegroundPackage], index) => ({
      sequence: index + 1,
      stepId,
      actionId,
      preForegroundPackage,
      postForegroundPackage,
      allowedRoutes: systemActions.has(actionId)
        ? ["semantic"]
        : ["semantic", "hybrid", "visual"],
      longPressProofRequired: actionId === "long-press-safe-content",
    }),
  ),
});

export const makeStep = ({
  sequence,
  stepId,
  actionId,
  preForegroundPackage,
  postForegroundPackage,
  status = "passed",
  classification = "normal",
  route = routeFor(actionId),
  attempts = 1,
  actionCommits = 1,
  postObserved = true,
  sideEffectProof = actionId === "long-press-safe-content"
    ? {
        noLike: true,
        noFavorite: true,
        noDownload: true,
        noShare: true,
      }
    : "not-required",
} = {}) => ({
  sequence,
  stepId,
  actionId,
  preForegroundPackage,
  postForegroundPackage,
  status,
  classification,
  route,
  attempts,
  actionCommits,
  postObserved,
  sideEffectProof,
});

export const makeRound = ({
  iteration = 1,
  evidenceKind = "fixture",
  evidenceBinding = evidenceKind === "fixture"
    ? {
        kind: "fixture",
        fixtureId: "n48-contract-fake-v1",
      }
    : {
        kind: "n47-run-report",
        runId: `018f47a2-4bc8-7f31-8b9a-${String(iteration).padStart(12, "0")}`,
        runReportSha256: iteration.toString(16).padStart(64, "0"),
        profileFingerprintSha256: "f".repeat(64),
        verifiedApkSha256: IDENTITY.apkSha256,
        actualRun: true,
      },
  apiLevel = PROFILE.apiLevel,
  status = "passed",
  classification = "compatible",
  blockerCode = null,
  steps = STEP_PLAN.map(
    ([stepId, actionId, preForegroundPackage, postForegroundPackage], index) =>
      makeStep({
        sequence: index + 1,
        stepId,
        actionId,
        preForegroundPackage,
        postForegroundPackage,
      }),
  ),
  cleanup = {
    appDataCleared: true,
    searchHistoryCleared: true,
    sessionCleared: true,
    artifactsCleared: true,
    snapshotRestored: true,
  },
} = {}) => ({
  iteration,
  evidenceKind,
  evidenceBinding: structuredClone(evidenceBinding),
  apiLevel,
  status,
  classification,
  blockerCode,
  steps: structuredClone(steps),
  cleanup: structuredClone(cleanup),
});

export const makeReport = ({
  evidenceKind = "fixture",
  profile = PROFILE,
  rounds = Array.from(
    { length: 10 },
    (_, index) => makeRound({
      iteration: index + 1,
      evidenceKind,
      apiLevel: profile.apiLevel,
    }),
  ),
} = {}) => {
  const report = {
    schemaVersion: "1.0",
    reportId: "bilibili-safe-compatibility-v1",
    identity: structuredClone(IDENTITY),
    profile: structuredClone(profile),
    planId: "bilibili-unlogged-public-content-v1",
    plannedIterations: 10,
    evidenceKind,
    rounds: structuredClone(rounds),
    statistics: {},
  };
  report.statistics = recomputeBilibiliStatistics(report);
  return report;
};

export const blockedStep = ({
  sourceStep,
  classification = "consent",
  actionCommits = 0,
  attempts = 0,
  postObserved = false,
} = {}) =>
  makeStep({
    ...sourceStep,
    postForegroundPackage: null,
    status: "blocked",
    classification,
    route: null,
    attempts,
    actionCommits,
    postObserved,
    sideEffectProof: "not-required",
  });
