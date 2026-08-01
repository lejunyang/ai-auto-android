// 测试用途：构造受控抖音策略与报告，覆盖固定身份、分类、真实轮次排除和清理语义。
export const DOUYIN_IDENTITY = Object.freeze({
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

export const PROTECTED_NODE_TYPES = Object.freeze([
  "like",
  "comment",
  "follow",
  "send",
  "share",
  "favorite",
  "download",
]);

export const makeScenario = () => ({
  schemaVersion: "1.0",
  scenarioId: "n49-douyin-public-browse-v1",
  manifest: { ...DOUYIN_IDENTITY },
  requiredRealRuns: 10,
  accountMode: "signed-out",
  sideEffectPolicy: "none",
  capabilities: [
    { type: "observe-public-feed", policy: "observe-only" },
    { type: "vertical-swipe", policy: "fresh-content-change-required" },
    { type: "search-input", policy: "typed-n47-only" },
    { type: "page-navigation", policy: "typed-n47-only" },
    { type: "back", policy: "typed-n47-only" },
    { type: "home", policy: "typed-n47-only" },
    { type: "recents", policy: "typed-n47-only" },
    { type: "return-to-douyin", policy: "typed-n47-only" },
    { type: "long-press", policy: "unsupported-interaction-menu-risk" },
  ],
  protectedNodes: PROTECTED_NODE_TYPES.map((type) => ({
    type,
    maxActionCommits: 0,
  })),
});

export const makeRun = ({
  runId = "fixture-douyin-contract-run-1",
  iteration = 1,
  source = "fixture",
  apiLevel = 30,
  classification = "content-change-verified",
  status = "passed",
  errorCode = null,
  route = "semantic",
  actionType = "vertical-swipe",
  preObservationId = `observation-pre-${iteration}`,
  postObservationId = `observation-post-${iteration}`,
  preContentFingerprint =
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  postContentFingerprint =
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  actionCommits = 1,
  attempts = 1,
  retries = 0,
} = {}) => ({
  runId,
  iteration,
  source,
  apiLevel,
  observationPackage: DOUYIN_IDENTITY.packageName,
  preObservationFresh: true,
  status,
  errorCode,
  classification,
  route,
  actionType,
  preObservationId,
  postObservationId,
  preContentFingerprint,
  postContentFingerprint,
  actionCommits,
  attempts,
  retries,
  protectedNodeCommits: Object.fromEntries(
    PROTECTED_NODE_TYPES.map((type) => [type, 0]),
  ),
  telemetry: {
    accountState: "signed-out",
    recommendationState: "dynamic-uncontrolled",
    personalizationAssessment: "unavailable-without-account",
  },
  cleanup: {
    appDataCleared: true,
    sessionCleared: true,
    artifactsCleared: true,
    snapshotRestored: true,
  },
});

export const makeStatistics = (runs) => {
  const classificationCounts = {
    "content-change-verified": 0,
    "video-surface": 0,
    "visual-only": 0,
    "login-wall": 0,
    advertisement: 0,
    "emulator-detected": 0,
    "hierarchy-failure-api33": 0,
    "hierarchy-failure-api34": 0,
    "long-press-unsupported": 0,
    "action-commit-unknown": 0,
  };
  const statusCounts = { passed: 0, failed: 0, blocked: 0 };
  for (const run of runs) {
    classificationCounts[run.classification] += 1;
    statusCounts[run.status] += 1;
  }
  return {
    totalRuns: runs.length,
    fixtureRuns: runs.filter((run) => run.source === "fixture").length,
    realRuns: runs.filter((run) => run.source === "real").length,
    completedRealRounds: runs.filter((run) => run.source === "real").length,
    statusCounts,
    classificationCounts,
    verifiedContentChanges: runs.filter(
      (run) => run.classification === "content-change-verified",
    ).length,
    unknownCommitRuns: runs.filter(
      (run) => run.actionCommits === null,
    ).length,
    protectedNodeCommits: Object.fromEntries(
      PROTECTED_NODE_TYPES.map((type) => [
        type,
        runs.reduce(
          (total, run) => total + (run.protectedNodeCommits[type] ?? 0),
          0,
        ),
      ]),
    ),
  };
};

export const makeReport = (runs = [makeRun()]) => ({
  schemaVersion: "1.0",
  reportId: "n49-douyin-fixture-contract-report",
  scenarioId: "n49-douyin-public-browse-v1",
  manifest: { ...DOUYIN_IDENTITY },
  requiredRealRuns: 10,
  runs: structuredClone(runs),
  statistics: makeStatistics(runs),
});
