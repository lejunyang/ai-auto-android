// 功能用途：按严格 DSL 编排观察、页面分类、route、安全动作、N34 结果和全路径清理。
import {
  assertRunnerDocument,
  loadRunnerSchemas,
} from "./schema-validator.mjs";
import {
  loadArtifactSchemas,
  validateArtifactDocument,
} from "../../artifacts/src/schema-validator.mjs";

const requiredPortMethods = Object.freeze({
  observer: ["observe"],
  conditions: ["verify"],
  router: ["resolve"],
  executor: ["execute"],
  values: ["resolve"],
  artifacts: ["collect"],
  lifecycle: [
    "stopScenario",
    "closeBridge",
    "clearAppData",
    "restoreSnapshot",
  ],
});

const pageErrorCodes = Object.freeze({
  advertisement: "PAGE_ADVERTISEMENT",
  "update-prompt": "PAGE_UPDATE_PROMPT",
  "ab-variant": "PAGE_AB_VARIANT",
  "emulator-detected": "PAGE_EMULATOR_DETECTED",
  "network-failure": "PAGE_NETWORK_FAILURE",
  "login-wall": "PAGE_LOGIN_WALL",
  unknown: "PAGE_UNKNOWN",
});

const stableId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const safeUuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const packageName =
  /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/u;
const allowedPageClasses = new Set([
  "normal",
  "advertisement",
  "update-prompt",
  "ab-variant",
  "emulator-detected",
  "network-failure",
  "login-wall",
  "unknown",
]);
const allowedRoutes = new Set(["semantic", "hybrid", "visual"]);

export class ScenarioRunnerError extends Error {
  constructor(code) {
    super(code);
    this.name = "ScenarioRunnerError";
    this.code = code;
  }
}

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const assertPorts = (ports) => {
  if (!exactKeys(ports, Object.keys(requiredPortMethods))) {
    throw new ScenarioRunnerError("RUNNER_PORT_INVALID");
  }
  for (const [port, methods] of Object.entries(requiredPortMethods)) {
    if (
      ports[port] === null
      || typeof ports[port] !== "object"
      || methods.some((method) => typeof ports[port][method] !== "function")
    ) {
      throw new ScenarioRunnerError("RUNNER_PORT_INVALID");
    }
  }
};

const assertRunInputs = ({ iteration, device, clock, runId }) => {
  if (
    !Number.isInteger(iteration)
    || iteration < 1
    || iteration > 10000
    || !safeUuid.test(runId ?? "")
    || clock === null
    || typeof clock !== "object"
    || typeof clock.now !== "function"
    || device === null
    || typeof device !== "object"
    || !stableId.test(device.serial ?? "")
    || !stableId.test(device.avdName ?? "")
  ) {
    throw new ScenarioRunnerError("RUNNER_INPUT_INVALID");
  }
};

const assertObservation = (
  observation,
  now,
  packagePredicate = () => true,
) => {
  if (
    !exactKeys(
      observation,
      [
        "expiresAt",
        "foregroundPackage",
        "observationId",
        "observedAt",
        "pageClass",
      ],
    )
    || !stableId.test(observation.observationId ?? "")
    || !packageName.test(observation.foregroundPackage ?? "")
    || !allowedPageClasses.has(observation.pageClass)
    || !Number.isFinite(Date.parse(observation.observedAt))
    || !Number.isFinite(Date.parse(observation.expiresAt))
    || Date.parse(observation.observedAt) > Date.parse(now)
    || Date.parse(observation.expiresAt) <= Date.parse(now)
  ) {
    throw new ScenarioRunnerError("OBSERVATION_INVALID");
  }
  if (!packagePredicate(observation.foregroundPackage)) {
    throw new ScenarioRunnerError("FOREGROUND_PACKAGE_MISMATCH");
  }
};

const prePackagePredicate = (step) => {
  if (step.action.type === "launch" || step.action.type === "switch-app") {
    return () => true;
  }
  return (value) => value === step.targetPackage;
};

const postPackagePredicate = (step, scenario) => {
  if (step.action.type === "launch" || step.action.type === "switch-app") {
    return (value) => value === step.action.package;
  }
  if (step.action.type === "home" || step.action.type === "recents") {
    return () => true;
  }
  if (step.action.type === "back") {
    return (value) => scenario.targetPackages.includes(value);
  }
  return (value) => value === step.targetPackage;
};

const assertRoute = (routeResult, observation, step) => {
  if (
    !exactKeys(
      routeResult,
      ["attempts", "observationId", "route", "score", "token"],
    )
    || !allowedRoutes.has(routeResult.route)
    || typeof routeResult.score !== "number"
    || !Number.isFinite(routeResult.score)
    || routeResult.score < 0
    || routeResult.score > 1
    || !Number.isInteger(routeResult.attempts)
    || routeResult.attempts < 1
    || routeResult.attempts > 100
    || routeResult.token === null
    || typeof routeResult.token !== "object"
    || Array.isArray(routeResult.token)
  ) {
    throw new ScenarioRunnerError("ROUTE_INVALID");
  }
  if (routeResult.observationId !== observation.observationId) {
    throw new ScenarioRunnerError("ROUTE_OBSERVATION_MISMATCH");
  }
  if (!step.allowedRoutes.includes(routeResult.route)) {
    throw new ScenarioRunnerError("ROUTE_NOT_ALLOWED");
  }
  if (routeResult.score < step.minimumScore) {
    throw new ScenarioRunnerError("ROUTE_SCORE_TOO_LOW");
  }
};

const durationBetween = (start, end, maximum) => {
  const duration = Date.parse(end) - Date.parse(start);
  if (!Number.isFinite(duration) || duration < 0 || duration > maximum) {
    throw new ScenarioRunnerError("RUNNER_CLOCK_INVALID");
  }
  return duration;
};

const assertDeadline = (startedAt, timeoutMs, now) => {
  if (durationBetween(startedAt, now, 86400000) > timeoutMs) {
    throw new ScenarioRunnerError("STEP_TIMEOUT");
  }
};

const runCleanup = async (lifecycle, context) => {
  const methods = requiredPortMethods.lifecycle;
  let completed = 0;
  for (const method of methods) {
    try {
      await lifecycle[method](context);
      completed += 1;
    } catch {
      // 清理必须继续执行全部步骤，最终仅公开稳定错误码。
    }
  }
  return Object.freeze({
    attempted: methods.length,
    completed,
    errorCode: completed === methods.length
      ? null
      : "SCENARIO_CLEANUP_FAILED",
  });
};

const stepBase = ({
  sequence,
  step,
  observation,
  routeResult = null,
  pageClass,
  status,
  errorCode,
  postObservationId = null,
  actionCommits,
  durationMs,
}) => Object.freeze({
  sequence,
  stepId: step.id,
  actionType: step.action.type,
  targetPackage: step.targetPackage,
  status,
  errorCode,
  pageClass,
  route: routeResult?.route ?? null,
  score: routeResult?.score ?? null,
  attempts: routeResult?.attempts ?? 0,
  preObservationId: observation.observationId,
  postObservationId,
  actionCommits,
  durationMs,
});

const timelineEntry = ({
  sequence,
  at,
  step,
  status,
  errorCode,
  durationMs,
}) => {
  const entry = {
    sequence,
    at,
    type: `scenario.${step.action.type}`,
    phase: status === "passed" ? "verify" : "execute",
    status,
    durationMs,
    targetPackage: step.targetPackage,
  };
  if (errorCode !== null) entry.errorCode = errorCode;
  return Object.freeze(entry);
};

const artifactResult = ({
  runId,
  scenario,
  iteration,
  device,
  startedAt,
  finishedAt,
  steps,
  status,
  errorCode,
  durationMs,
}) => Object.freeze({
  schemaVersion: "1.0",
  runId,
  scenarioId: scenario.id,
  iteration,
  startedAt,
  finishedAt,
  device,
  timeline: steps.map((step, index) => timelineEntry({
    sequence: index + 1,
    at: finishedAt,
    step: scenario.steps[index],
    status: step.status === "cancelled" ? "failed" : step.status,
    errorCode: step.errorCode,
    durationMs: step.durationMs,
  })),
  outcome: {
    status: status === "passed" ? "passed" : "failed",
    errorCode: status === "passed" ? null : errorCode,
    durationMs,
    retries: Math.min(
      100,
      steps.reduce(
        (total, step) => total + Math.max(step.attempts - 1, 0),
        0,
      ),
    ),
  },
});

const isArtifactRunValid = async (run) => {
  const schemas = await loadArtifactSchemas();
  return validateArtifactDocument(schemas.scenarioResult, run).length === 0;
};

const assertArtifactRun = async (run) => {
  if (!await isArtifactRunValid(run)) {
    throw new ScenarioRunnerError("ARTIFACT_RUN_INVALID");
  }
};

const safeArtifactResult = async (artifacts, artifactRun) => {
  try {
    const result = await artifacts.collect({ run: artifactRun });
    if (
      !exactKeys(result, ["errorCode", "retained"])
      || typeof result.retained !== "boolean"
      || (
        result.errorCode !== null
        && !/^[A-Z][A-Z0-9_]{2,95}$/u.test(result.errorCode ?? "")
      )
    ) {
      throw new ScenarioRunnerError("ARTIFACT_COLLECTION_FAILED");
    }
    return Object.freeze({
      retained: result.retained,
      errorCode: result.errorCode,
    });
  } catch {
    return Object.freeze({
      retained: false,
      errorCode: "ARTIFACT_COLLECTION_FAILED",
    });
  }
};

const executeStep = async ({
  step,
  sequence,
  scenario,
  ports,
  clock,
}) => {
  const startedAt = clock.now();
  let observation = null;
  let routeResult = null;
  let actionCommits = 0;
  let pageClass = "unknown";
  let postObservationId = null;
  try {
    observation = await ports.observer.observe({
      targetPackage: step.targetPackage,
      timeoutMs: step.timeoutMs,
      dynamicRegions: step.dynamicRegions,
    });
    const observedAt = clock.now();
    assertDeadline(startedAt, step.timeoutMs, observedAt);
    assertObservation(
      observation,
      observedAt,
      prePackagePredicate(step),
    );
    pageClass = observation.pageClass;
    if (!step.acceptedPageClasses.includes(pageClass)) {
      throw new ScenarioRunnerError(
        pageErrorCodes[pageClass] ?? "PAGE_UNKNOWN",
      );
    }
    const preconditionPassed = await ports.conditions.verify({
      phase: "pre",
      conditions: step.preconditions,
      observation,
      targetPackage: step.targetPackage,
      deadlineAt: new Date(
        Date.parse(startedAt) + step.timeoutMs,
      ).toISOString(),
    });
    assertDeadline(startedAt, step.timeoutMs, clock.now());
    if (preconditionPassed !== true) {
      throw new ScenarioRunnerError("PRECONDITION_FAILED");
    }
    routeResult = await ports.router.resolve({
      action: step.action,
      observation,
      targetPackage: step.targetPackage,
      allowedRoutes: step.allowedRoutes,
      minimumScore: step.minimumScore,
      dynamicRegions: step.dynamicRegions,
    });
    assertDeadline(startedAt, step.timeoutMs, clock.now());
    assertRoute(routeResult, observation, step);
    let value;
    if (step.action.type === "input") {
      try {
        value = await ports.values.resolve(step.action.valueRef);
      } catch {
        throw new ScenarioRunnerError("INPUT_VALUE_UNAVAILABLE");
      }
      assertDeadline(startedAt, step.timeoutMs, clock.now());
      if (
        typeof value !== "string"
        || Buffer.byteLength(value, "utf8") > 4096
      ) {
        throw new ScenarioRunnerError("INPUT_VALUE_INVALID");
      }
    }
    let execution;
    try {
      execution = await ports.executor.execute({
        action: step.action,
        route: routeResult.route,
        routeToken: routeResult.token,
        observationId: observation.observationId,
        targetPackage: step.targetPackage,
        deadlineAt: new Date(
          Date.parse(startedAt) + step.timeoutMs,
        ).toISOString(),
        value,
      });
    } catch {
      actionCommits = null;
      throw new ScenarioRunnerError("ACTION_COMMIT_UNKNOWN");
    } finally {
      value = undefined;
    }
    if (
      !exactKeys(execution, ["committed"])
      || typeof execution.committed !== "boolean"
    ) {
      actionCommits = null;
      throw new ScenarioRunnerError("ACTION_COMMIT_UNKNOWN");
    }
    if (execution.committed !== true) {
      throw new ScenarioRunnerError("ACTION_REJECTED");
    }
    actionCommits = 1;
    assertDeadline(startedAt, step.timeoutMs, clock.now());
    const postObservation = await ports.observer.observe({
      targetPackage: step.targetPackage,
      timeoutMs: step.timeoutMs,
      dynamicRegions: step.dynamicRegions,
    });
    const postObservedAt = clock.now();
    assertDeadline(startedAt, step.timeoutMs, postObservedAt);
    assertObservation(
      postObservation,
      postObservedAt,
      postPackagePredicate(step, scenario),
    );
    postObservationId = postObservation.observationId;
    pageClass = postObservation.pageClass;
    if (!step.acceptedPageClasses.includes(postObservation.pageClass)) {
      throw new ScenarioRunnerError(
        pageErrorCodes[postObservation.pageClass] ?? "PAGE_UNKNOWN",
      );
    }
    const postconditionPassed = await ports.conditions.verify({
      phase: "post",
      conditions: step.postconditions,
      observation: postObservation,
      previousObservationId: observation.observationId,
      targetPackage: step.targetPackage,
      deadlineAt: new Date(
        Date.parse(startedAt) + step.timeoutMs,
      ).toISOString(),
    });
    assertDeadline(startedAt, step.timeoutMs, clock.now());
    if (postconditionPassed !== true) {
      throw new ScenarioRunnerError("POSTCONDITION_FAILED");
    }
    const finishedAt = clock.now();
    const durationMs = durationBetween(startedAt, finishedAt, step.timeoutMs);
    return stepBase({
      sequence,
      step,
      observation,
      routeResult,
      pageClass,
      status: "passed",
      errorCode: null,
      postObservationId,
      actionCommits,
      durationMs,
    });
  } catch (error) {
    const finishedAt = clock.now();
    const durationMs = Math.min(
      Math.max(Date.parse(finishedAt) - Date.parse(startedAt), 0),
      step.timeoutMs,
    );
    const safeObservation = observation ?? {
      observationId: "observation-unavailable",
    };
    const fallback = error?.name === "AbortError"
      ? "SCENARIO_CANCELLED"
      : error instanceof ScenarioRunnerError
        ? error.code
        : actionCommits === 1
          ? "POSTCONDITION_FAILED"
          : routeResult === null
            ? "RUNNER_PORT_FAILED"
            : "ACTION_EXECUTION_FAILED";
    return stepBase({
      sequence,
      step,
      observation: safeObservation,
      routeResult,
      pageClass,
      status: fallback === "SCENARIO_CANCELLED" ? "cancelled" : "failed",
      errorCode: fallback,
      postObservationId,
      actionCommits,
      durationMs,
    });
  }
};

export const runScenario = async ({
  scenario,
  iteration,
  device,
  ports,
  clock,
  runId,
}) => {
  const schemas = await loadRunnerSchemas();
  try {
    assertRunnerDocument(schemas.scenario, scenario, "SCENARIO_SCHEMA_INVALID");
  } catch (error) {
    throw new ScenarioRunnerError(error.code ?? "SCENARIO_SCHEMA_INVALID");
  }
  assertPorts(ports);
  assertRunInputs({ iteration, device, clock, runId });

  const startedAt = clock.now();
  await assertArtifactRun(artifactResult({
    runId,
    scenario,
    iteration,
    device,
    startedAt,
    finishedAt: startedAt,
    steps: [],
    status: "passed",
    errorCode: null,
    durationMs: 0,
  }));
  const stepResults = [];
  let status = "passed";
  let errorCode = null;
  for (const [index, step] of scenario.steps.entries()) {
    const result = await executeStep({
      step,
      sequence: index + 1,
      scenario,
      ports,
      clock,
    });
    stepResults.push(result);
    if (result.status !== "passed") {
      status = result.status;
      errorCode = result.errorCode;
      break;
    }
  }

  const executionFinishedAt = clock.now();
  const executionDurationMs = durationBetween(
    startedAt,
    executionFinishedAt,
    86400000,
  );
  let primaryArtifactRun = artifactResult({
    runId,
    scenario,
    iteration,
    device,
    startedAt,
    finishedAt: executionFinishedAt,
    steps: stepResults,
    status,
    errorCode,
    durationMs: executionDurationMs,
  });
  let primaryArtifactTrusted = await isArtifactRunValid(primaryArtifactRun);
  if (!primaryArtifactTrusted) {
    status = "failed";
    errorCode = "ARTIFACT_RUN_INVALID";
    primaryArtifactRun = artifactResult({
      runId,
      scenario,
      iteration,
      device,
      startedAt,
      finishedAt: executionFinishedAt,
      steps: stepResults,
      status,
      errorCode,
      durationMs: executionDurationMs,
    });
    primaryArtifactTrusted = await isArtifactRunValid(primaryArtifactRun);
  }
  let artifactResultValue = status === "passed"
    ? null
    : primaryArtifactTrusted
      ? await safeArtifactResult(ports.artifacts, primaryArtifactRun)
      : Object.freeze({
        retained: false,
        errorCode: "ARTIFACT_COLLECTION_FAILED",
      });
  let collectedArtifactRun = status !== "passed" && primaryArtifactTrusted
    ? primaryArtifactRun
    : null;

  const cleanup = await runCleanup(ports.lifecycle, {
    runId,
    scenarioId: scenario.id,
    iteration,
    targetPackages: Object.freeze([...scenario.targetPackages]),
  });
  if (cleanup.errorCode !== null) {
    status = "failed";
    errorCode = cleanup.errorCode;
  }
  const finishedAt = clock.now();
  const durationMs = durationBetween(startedAt, finishedAt, 86400000);
  const report = Object.freeze({
    schemaVersion: "1.0",
    runId,
    scenarioId: scenario.id,
    iteration,
    startedAt,
    finishedAt,
    status,
    errorCode,
    durationMs,
    actionCommits: stepResults.some((step) => step.actionCommits === null)
      ? null
      : stepResults.reduce(
        (total, step) => total + step.actionCommits,
        0,
      ),
    steps: Object.freeze([...stepResults]),
    cleanup,
  });
  assertRunnerDocument(schemas.runReport, report, "RUN_REPORT_INVALID");

  const artifactRun = cleanup.errorCode === null
    ? primaryArtifactRun
    : artifactResult({
      runId,
      scenario,
      iteration,
      device,
      startedAt,
      finishedAt,
      steps: stepResults,
      status,
      errorCode,
      durationMs,
    });
  const artifactRunTrusted = await isArtifactRunValid(artifactRun);
  if (artifactResultValue === null && status !== "passed") {
    artifactResultValue = artifactRunTrusted
      ? await safeArtifactResult(ports.artifacts, artifactRun)
      : Object.freeze({
        retained: false,
        errorCode: "ARTIFACT_COLLECTION_FAILED",
      });
    if (artifactRunTrusted) collectedArtifactRun = artifactRun;
  }
  return Object.freeze({
    report,
    artifactRun: collectedArtifactRun
      ?? (artifactRunTrusted ? artifactRun : null),
    artifact: artifactResultValue,
  });
};
