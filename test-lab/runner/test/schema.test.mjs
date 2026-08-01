// 测试用途：验证 N47 严格 Schema 接受 fixture 场景并拒绝未知字段、越权动作和泄密报告。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadRunnerSchemas,
  validateRunnerDocument,
} from "../src/schema-validator.mjs";
import {
  loadFixture,
  oneStepScenario,
} from "./helpers.mjs";

test("native 和 Web fixture 场景通过严格 Schema 且合计覆盖十类动作", async () => {
  const schemas = await loadRunnerSchemas();
  const native = await loadFixture("native-fixture.scenario.json");
  const web = await loadFixture("web-fixture.scenario.json");

  assert.deepEqual(validateRunnerDocument(schemas.scenario, native), []);
  assert.deepEqual(validateRunnerDocument(schemas.scenario, web), []);
  assert.deepEqual(
    [...new Set([...native.steps, ...web.steps].map((step) => step.action.type))].sort(),
    [
      "back",
      "home",
      "input",
      "launch",
      "long-click",
      "recents",
      "scroll",
      "swipe",
      "switch-app",
      "tap",
    ],
  );
  assert.deepEqual(
    [...new Set([...native.steps, ...web.steps].flatMap((step) => step.allowedRoutes))].sort(),
    ["hybrid", "semantic", "visual"],
  );
});

test("production native WebView Canvas 场景固定包、route 和 input 引用", async () => {
  const schemas = await loadRunnerSchemas();
  const scenarios = await Promise.all([
    "production-native-fixture.scenario.json",
    "production-webview-fixture.scenario.json",
    "production-canvas-fixture.scenario.json",
  ].map(loadFixture));

  for (const scenario of scenarios) {
    assert.deepEqual(validateRunnerDocument(schemas.scenario, scenario), []);
  }
  assert.deepEqual(
    scenarios.map(({ id }) => id),
    [
      "production-native-fixture",
      "production-webview-fixture",
      "production-canvas-fixture",
    ],
  );
  assert.deepEqual(
    [...new Set(scenarios.flatMap((scenario) => scenario.targetPackages))].sort(),
    ["dev.aiauto.fixture", "dev.aiauto.webfixture"],
  );
  assert.deepEqual(
    scenarios.flatMap((scenario) => scenario.steps)
      .filter((step) => step.action.type === "input")
      .map((step) => step.action.valueRef),
    ["production-fixture-input", "production-fixture-input"],
  );
  assert.deepEqual(
    scenarios[2].steps.map((step) => step.allowedRoutes),
    [["semantic"], ["semantic"], ["visual"]],
  );
});

test("场景拒绝未知字段、副作用策略、未知动作和越权包", async () => {
  const schemas = await loadRunnerSchemas();
  const base = oneStepScenario();
  const cases = [
    { ...base, privateToken: "must-not-pass" },
    { ...base, sideEffectPolicy: "purchase" },
    {
      ...base,
      steps: [{
        ...base.steps[0],
        action: { type: "shell", command: "id" },
      }],
    },
    {
      ...base,
      steps: [{
        ...base.steps[0],
        targetPackage: "com.example.not.allowed",
      }],
    },
    {
      ...base,
      steps: [{
        ...base.steps[0],
        action: {
          type: "launch",
          package: "dev.aiauto.webfixture",
        },
      }],
    },
    {
      ...base,
      steps: [{
        ...base.steps[0],
        dynamicRegions: [{
          id: "zero-area",
          x: 0,
          y: 0,
          width: 0,
          height: 0.5,
        }],
      }],
    },
    {
      ...base,
      steps: [{
        ...base.steps[0],
        dynamicRegions: [{
          id: "outside-screen",
          x: 0.8,
          y: 0.8,
          width: 0.3,
          height: 0.3,
        }],
      }],
    },
  ];

  for (const scenario of cases) {
    assert.notDeepEqual(validateRunnerDocument(schemas.scenario, scenario), []);
  }
});

test("报告 Schema 不允许 selector、坐标、输入、route token 或自由异常", async () => {
  const schemas = await loadRunnerSchemas();
  const report = {
    schemaVersion: "1.0",
    runId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
    scenarioId: "fixture-one-step",
    iteration: 1,
    startedAt: "2026-07-25T12:00:00.000Z",
    finishedAt: "2026-07-25T12:00:00.020Z",
    status: "failed",
    errorCode: "POSTCONDITION_FAILED",
    durationMs: 20,
    actionCommits: 1,
    steps: [
      {
        sequence: 1,
        stepId: "fixture-step",
        actionType: "input",
        targetPackage: "dev.aiauto.fixture",
        status: "failed",
        errorCode: "POSTCONDITION_FAILED",
        pageClass: "normal",
        route: "semantic",
        score: 1,
        attempts: 1,
        preObservationId: "observation-pre",
        postObservationId: "observation-post",
        actionCommits: 1,
        durationMs: 10,
        input: "secret",
        selector: "private-selector",
        x: 10,
        routeToken: "private-token",
        message: "raw exception",
      },
    ],
    cleanup: {
      attempted: 4,
      completed: 4,
      errorCode: null,
    },
  };

  const errors = validateRunnerDocument(schemas.runReport, report);
  for (const field of ["input", "selector", "x", "routeToken", "message"]) {
    assert.equal(errors.some((error) => error.includes(field)), true);
  }
});

test("报告和兼容汇总拒绝状态、提交与计数不一致", async () => {
  const schemas = await loadRunnerSchemas();
  const invalidReport = {
    schemaVersion: "1.0",
    runId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
    scenarioId: "fixture-one-step",
    iteration: 1,
    startedAt: "2026-07-25T12:00:00.000Z",
    finishedAt: "2026-07-25T12:00:00.020Z",
    status: "passed",
    errorCode: "IMPOSSIBLE_ERROR",
    durationMs: 20,
    actionCommits: 0,
    steps: [
      {
        sequence: 2,
        stepId: "fixture-step",
        actionType: "tap",
        targetPackage: "dev.aiauto.fixture",
        status: "passed",
        errorCode: null,
        pageClass: "normal",
        route: "semantic",
        score: 1,
        attempts: 1,
        preObservationId: "observation-pre",
        postObservationId: "observation-post",
        actionCommits: 1,
        durationMs: 10,
      },
    ],
    cleanup: {
      attempted: 4,
      completed: 3,
      errorCode: null,
    },
  };
  const reportErrors = validateRunnerDocument(
    schemas.runReport,
    invalidReport,
  );
  for (const fragment of [
    "status and error code",
    "does not equal step commits",
    "sequence is not contiguous",
    "completion and error code",
  ]) {
    assert.equal(
      reportErrors.some((error) => error.includes(fragment)),
      true,
      fragment,
    );
  }

  const summaryErrors = validateRunnerDocument(
    schemas.compatibilitySummary,
    {
      schemaVersion: "1.0",
      scenarioId: "fixture-one-step",
      runs: 2,
      passedRuns: 2,
      failedRuns: 1,
      cancelledRuns: 0,
      successRate: 0.5,
      stepCompatibilityRate: 1,
      routeCounts: {},
      pageClassCounts: {},
      failureCategoryCounts: {},
      errorCodeCounts: {},
    },
  );
  assert.equal(
    summaryErrors.some((error) => error.includes("run status counts")),
    true,
  );
  assert.equal(
    summaryErrors.some((error) => error.includes("successRate")),
    true,
  );
});
