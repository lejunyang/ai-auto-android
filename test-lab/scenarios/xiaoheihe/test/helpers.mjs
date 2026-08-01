// 测试用途：构造固定小黑盒场景、十轮报告和三 API 安全反例，覆盖阻塞与清理边界。
export const MANIFEST_BINDING = Object.freeze({
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

export const API_LEVELS = Object.freeze([30, 33, 34]);

export const SCENARIO_STEPS = Object.freeze([
  {
    sequence: 1,
    stepId: "public-search-input",
    action: "public-search-input",
    target: "xiaoheihe-public-content",
    routePolicy: "content-accessibility",
    postObservationRequired: true,
  },
  {
    sequence: 2,
    stepId: "public-search-submit",
    action: "public-search-submit",
    target: "xiaoheihe-public-content",
    routePolicy: "content-accessibility",
    postObservationRequired: true,
  },
  {
    sequence: 3,
    stepId: "public-detail-open",
    action: "public-detail-open",
    target: "xiaoheihe-public-content",
    routePolicy: "content-accessibility",
    postObservationRequired: true,
  },
  {
    sequence: 4,
    stepId: "long-press-safe-content",
    action: "long-press-safe-content",
    target: "xiaoheihe-public-content",
    routePolicy: "content-accessibility",
    postObservationRequired: true,
  },
  {
    sequence: 5,
    stepId: "public-list-scroll",
    action: "public-list-scroll",
    target: "xiaoheihe-public-content",
    routePolicy: "content-accessibility",
    postObservationRequired: true,
  },
  {
    sequence: 6,
    stepId: "public-list-swipe",
    action: "public-list-swipe",
    target: "xiaoheihe-public-content",
    routePolicy: "content-accessibility",
    postObservationRequired: true,
  },
  {
    sequence: 7,
    stepId: "back",
    action: "back",
    target: "xiaoheihe-public-content",
    routePolicy: "system-navigation",
    postObservationRequired: true,
  },
  {
    sequence: 8,
    stepId: "home",
    action: "home",
    target: "android-system-ui",
    routePolicy: "system-navigation",
    postObservationRequired: true,
  },
  {
    sequence: 9,
    stepId: "recents",
    action: "recents",
    target: "android-system-ui",
    routePolicy: "system-navigation",
    postObservationRequired: true,
  },
  {
    sequence: 10,
    stepId: "switch-to-fixture-peer",
    action: "switch-to-fixture-peer",
    target: "fixture-peer",
    routePolicy: "system-navigation",
    postObservationRequired: true,
  },
  {
    sequence: 11,
    stepId: "cross-app-return",
    action: "cross-app-return",
    target: "xiaoheihe-public-content",
    routePolicy: "system-navigation",
    postObservationRequired: true,
  },
]);

const forbiddenEffects = [
  "login",
  "follow",
  "favorite",
  "comment",
  "post",
  "download",
  "share",
];

export const makeScenario = () => ({
  schemaVersion: "1.0",
  scenarioId: "n50-xiaoheihe-public-browse-v1",
  manifestBinding: structuredClone(MANIFEST_BINDING),
  execution: {
    supportedApiLevels: [...API_LEVELS],
    requiredRuns: 10,
    loginState: "logged-out",
    sideEffectPolicy: "none",
    domInjection: false,
    javascriptInjection: false,
    forbiddenEffects: [...forbiddenEffects],
  },
  steps: structuredClone(SCENARIO_STEPS),
});

const makeObservation = (
  observationId,
  sequence,
  {
    foreground = "xiaoheihe",
    surface = "native",
    consent = "clear",
    permission = "clear",
    dynamicContent = "stable",
    advertisement = "absent",
    network = "available",
  } = {},
) => ({
  observationId,
  sequence,
  foreground,
  surface,
  consent,
  permission,
  dynamicContent,
  advertisement,
  network,
});

const cleanState = () => ({
  searchHistory: "cleared",
  appData: "cleared",
  bridge: "cleared",
  session: "cleared",
  artifacts: "cleared",
  snapshot: "restored",
});

const bindingFor = (apiLevel, iteration) => ({
  scenarioId: "n50-xiaoheihe-public-browse-v1",
  ...structuredClone(MANIFEST_BINDING),
  apiLevel,
  iteration,
});

const postState = (sequence) => {
  if (sequence === 3) return { foreground: "xiaoheihe", surface: "webview" };
  if (sequence <= 7) {
    return {
      foreground: "xiaoheihe",
      surface: sequence >= 3 ? "webview" : "native",
    };
  }
  if (sequence === 8 || sequence === 9) {
    return { foreground: "system-ui", surface: "system" };
  }
  if (sequence === 10) return { foreground: "fixture-peer", surface: "native" };
  return { foreground: "xiaoheihe", surface: "native" };
};

const routeFor = (sequence) => {
  if (sequence <= 3) return "native-accessibility";
  if (sequence <= 6) return "webview-accessibility";
  return "system-navigation";
};

export const makeCompatibleRun = (
  apiLevel = 30,
  iteration = 1,
  source = "fixture",
) => {
  const runId = `${source}-api-${apiLevel}-iteration-${iteration}`;
  const observations = [makeObservation(`${runId}-obs-01`, 1)];
  const steps = [];
  for (const step of SCENARIO_STEPS) {
    const preObservation = observations.at(-1);
    const postObservation = makeObservation(
      `${runId}-obs-${String(step.sequence + 1).padStart(2, "0")}`,
      step.sequence + 1,
      postState(step.sequence),
    );
    observations.push(postObservation);
    steps.push({
      sequence: step.sequence,
      stepId: step.stepId,
      action: step.action,
      status: "passed",
      route: routeFor(step.sequence),
      attempts: 1,
      retryCount: 0,
      actionCommits: 1,
      preObservationId: preObservation.observationId,
      postObservationId: postObservation.observationId,
      errorCode: null,
    });
  }
  const run = {
    runId,
    source,
    fixtureSource: source === "fixture" ? "N50_OFFLINE_CONTRACT" : null,
    binding: bindingFor(apiLevel, iteration),
    observations,
    steps,
    cleanup: cleanState(),
    decision: null,
  };
  run.decision = expectedDecision(run);
  return run;
};

export const makeBlockedRun = (
  apiLevel = 30,
  iteration = 1,
  source = "fixture",
) => {
  const runId = `${source}-blocked-api-${apiLevel}-iteration-${iteration}`;
  const run = {
    runId,
    source,
    fixtureSource: source === "fixture" ? "N50_OFFLINE_CONTRACT" : null,
    binding: bindingFor(apiLevel, iteration),
    observations: [
      makeObservation(`${runId}-obs-01`, 1, {
        consent: "blocked",
        permission: "blocked",
      }),
    ],
    steps: [],
    cleanup: cleanState(),
    decision: null,
  };
  run.decision = expectedDecision(run);
  return run;
};

const uniqueSorted = (values) => [...new Set(values)].sort();

export const expectedDecision = (run) => {
  const reasons = [];
  const observations = run.observations ?? [];
  const steps = run.steps ?? [];
  if (observations.some((item) => item.consent === "blocked")) {
    reasons.push("CONSENT_BLOCKED");
  }
  if (observations.some((item) => item.permission === "blocked")) {
    reasons.push("PERMISSION_BLOCKED");
  }
  if (observations.some((item) => item.dynamicContent === "unknown")) {
    reasons.push("DYNAMIC_CONTENT_UNKNOWN");
  }
  if (observations.some((item) => item.advertisement === "present")) {
    reasons.push("ADVERTISEMENT_PRESENT");
  }
  if (observations.some((item) => item.advertisement === "unknown")) {
    reasons.push("ADVERTISEMENT_UNKNOWN");
  }
  if (observations.some((item) => item.network === "failure")) {
    reasons.push("NETWORK_FAILURE");
  }
  if (observations.some((item) => item.network === "unknown")) {
    reasons.push("NETWORK_UNKNOWN");
  }
  if (observations.some((item) =>
    item.foreground === "unknown" || item.surface === "unknown")) {
    reasons.push("OBSERVATION_STATE_UNKNOWN");
  }
  if (steps.some((step) => step.actionCommits === null)) {
    reasons.push("ACTION_COMMIT_UNKNOWN");
  }
  if (
    steps.length !== SCENARIO_STEPS.length
    || steps.some((step, index) =>
      step.sequence !== index + 1
      || step.stepId !== SCENARIO_STEPS[index]?.stepId
      || step.action !== SCENARIO_STEPS[index]?.action)
  ) {
    reasons.push("STEP_SEQUENCE_INCOMPLETE");
  }
  if (steps.some((step) => step.status !== "passed")) reasons.push("STEP_FAILED");
  if (steps.some((step) =>
    step.actionCommits === 1 && step.postObservationId === null)) {
    reasons.push("POST_OBSERVATION_MISSING");
  }
  const observationById = new Map(
    observations.map((observation) => [observation.observationId, observation]),
  );
  if (steps.some((step, index) => {
    const expected = SCENARIO_STEPS[index];
    if (!expected) return true;
    if (expected.routePolicy === "system-navigation") {
      return step.route !== "system-navigation";
    }
    return !["native-accessibility", "webview-accessibility"].includes(step.route);
  })) {
    reasons.push("ROUTE_POLICY_MISMATCH");
  }
  if (steps.some((step) => {
    const pre = observationById.get(step.preObservationId);
    if (!pre) return true;
    if (step.route === "native-accessibility") return pre.surface !== "native";
    if (step.route === "webview-accessibility") return pre.surface !== "webview";
    return step.route !== "system-navigation";
  })) {
    reasons.push("ROUTE_SURFACE_MISMATCH");
  }
  if (steps.some((step) =>
    !observationById.has(step.preObservationId)
    || step.postObservationId !== null
      && !observationById.has(step.postObservationId))) {
    reasons.push("OBSERVATION_REFERENCE_INVALID");
  }
  if (steps.some((step) => {
    if (step.postObservationId === null) return false;
    const post = observationById.get(step.postObservationId);
    if (!post) return false;
    if (["home", "recents"].includes(step.action)) {
      return post.foreground !== "system-ui" || post.surface !== "system";
    }
    if (step.action === "switch-to-fixture-peer") {
      return post.foreground !== "fixture-peer" || post.surface !== "native";
    }
    return post.foreground !== "xiaoheihe"
      || !["native", "webview"].includes(post.surface);
  })) {
    reasons.push("POST_OBSERVATION_MISMATCH");
  }
  if (
    Object.entries(run.cleanup ?? {}).some(([key, value]) =>
      value !== (key === "snapshot" ? "restored" : "cleared"))
  ) {
    reasons.push("CLEANUP_FAILED");
  }
  const hasUnknownCommit = steps.some((step) => step.actionCommits === null);
  const routeCounts = {
    nativeAccessibilitySteps: steps.filter(
      (step) => step.status === "passed"
        && step.route === "native-accessibility",
    ).length,
    webviewAccessibilitySteps: steps.filter(
      (step) => step.status === "passed"
        && step.route === "webview-accessibility",
    ).length,
    systemNavigationSteps: steps.filter(
      (step) => step.status === "passed" && step.route === "system-navigation",
    ).length,
  };
  const reasonCodes = uniqueSorted(reasons);
  return {
    compatible: reasonCodes.length === 0,
    reasonCodes,
    actionCommits: hasUnknownCommit
      ? null
      : steps.reduce((total, step) => total + step.actionCommits, 0),
    executedSteps: steps.filter((step) => step.attempts > 0).length,
    completedSteps: steps.filter((step) => step.status === "passed").length,
    routeCounts,
  };
};

const emptyActionCoverage = () => Object.fromEntries(
  SCENARIO_STEPS.map((step) => [step.action, 0]),
);

const emptyApiCounts = () => ({
  totalRuns: 0,
  fixtureRuns: 0,
  realRuns: 0,
  compatibleRuns: 0,
  incompatibleRuns: 0,
  consentBlockedRuns: 0,
  permissionBlockedRuns: 0,
});

export const expectedStatistics = (runs) => {
  const statistics = {
    totalRuns: runs.length,
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
  for (const run of runs) {
    const decision = expectedDecision(run);
    const apiCounts = statistics.apiCounts[`api${run.binding.apiLevel}`];
    apiCounts.totalRuns += 1;
    statistics[run.source === "real" ? "realRuns" : "fixtureRuns"] += 1;
    apiCounts[run.source === "real" ? "realRuns" : "fixtureRuns"] += 1;
    const compatibleKey = decision.compatible
      ? "compatibleRuns"
      : "incompatibleRuns";
    statistics[compatibleKey] += 1;
    apiCounts[compatibleKey] += 1;
    if (run.source === "real") {
      statistics[decision.compatible
        ? "realCompatibleRuns"
        : "realIncompatibleRuns"] += 1;
    }
    for (const [key, value] of Object.entries(decision.routeCounts)) {
      statistics.routeCounts[key] += value;
    }
    for (const step of run.steps) {
      if (step.status === "passed") statistics.actionCoverage[step.action] += 1;
    }
    for (const observation of run.observations) {
      statistics.classificationCounts.dynamicContent[
        observation.dynamicContent
      ] += 1;
      statistics.classificationCounts.advertisement[
        observation.advertisement
      ] += 1;
      statistics.classificationCounts.network[observation.network] += 1;
    }
    if (decision.reasonCodes.includes("CONSENT_BLOCKED")) {
      statistics.consentBlockedRuns += 1;
      apiCounts.consentBlockedRuns += 1;
    }
    if (decision.reasonCodes.includes("PERMISSION_BLOCKED")) {
      statistics.permissionBlockedRuns += 1;
      apiCounts.permissionBlockedRuns += 1;
    }
    if (decision.actionCommits === null) statistics.unknownCommitRuns += 1;
    if (decision.reasonCodes.includes("CLEANUP_FAILED")) {
      statistics.cleanupFailedRuns += 1;
    }
  }
  statistics.successRate = runs.length === 0
    ? 0
    : statistics.compatibleRuns / runs.length;
  statistics.realSuccessRate = statistics.realRuns === 0
    ? null
    : statistics.realCompatibleRuns / statistics.realRuns;
  statistics.realMatrixComplete = statistics.realRuns === 10;
  return statistics;
};

export const makeReport = ({
  runFactory = makeCompatibleRun,
  sources = () => "fixture",
  runApiLevels = Array.from(
    { length: 10 },
    (_, index) => API_LEVELS[index % API_LEVELS.length],
  ),
} = {}) => {
  const runs = runApiLevels.map((apiLevel, index) =>
    runFactory(apiLevel, index + 1, sources(apiLevel, index + 1)));
  return {
    schemaVersion: "1.0",
    reportId: "n50-xiaoheihe-contract-report-v1",
    scenarioId: "n50-xiaoheihe-public-browse-v1",
    manifestBinding: structuredClone(MANIFEST_BINDING),
    matrix: {
      supportedApiLevels: [...API_LEVELS],
      requiredRuns: 10,
    },
    runs,
    statistics: expectedStatistics(runs),
  };
};
