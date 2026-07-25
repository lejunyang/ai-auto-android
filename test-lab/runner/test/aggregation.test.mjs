// 测试用途：验证兼容率、route、页面和失败类别聚合稳定且不受输入顺序影响。
import assert from "node:assert/strict";
import test from "node:test";

import { aggregateCompatibility } from "../src/aggregation.mjs";

const report = ({
  runId,
  iteration,
  status,
  errorCode = null,
  route = "semantic",
  pageClass = "normal",
  stepStatus = status,
}) => ({
  schemaVersion: "1.0",
  runId,
  scenarioId: "fixture-compatibility",
  iteration,
  startedAt: "2026-07-25T12:00:00.000Z",
  finishedAt: "2026-07-25T12:00:00.010Z",
  status,
  errorCode,
  durationMs: 10,
  actionCommits: status === "passed" ? 1 : 0,
  steps: [
    {
      sequence: 1,
      stepId: "fixture-step",
      actionType: "tap",
      targetPackage: "dev.aiauto.fixture",
      status: stepStatus,
      errorCode,
      pageClass,
      route,
      score: route === null ? null : 1,
      attempts: route === null ? 0 : 1,
      preObservationId: `observation-${iteration}`,
      postObservationId: status === "passed" ? `post-${iteration}` : null,
      actionCommits: status === "passed" ? 1 : 0,
      durationMs: 10,
    },
  ],
  cleanup: {
    attempted: 4,
    completed: 4,
    errorCode: null,
  },
});

test("聚合兼容率、route、页面、失败类别和错误码", () => {
  const reports = [
    report({
      runId: "018f47a2-4bc8-7f31-8b9a-123456789001",
      iteration: 1,
      status: "passed",
    }),
    report({
      runId: "018f47a2-4bc8-7f31-8b9a-123456789002",
      iteration: 2,
      status: "failed",
      errorCode: "PAGE_ADVERTISEMENT",
      route: null,
      pageClass: "advertisement",
      stepStatus: "failed",
    }),
    report({
      runId: "018f47a2-4bc8-7f31-8b9a-123456789003",
      iteration: 3,
      status: "failed",
      errorCode: "RUNNER_PORT_FAILED",
      route: null,
      stepStatus: "failed",
    }),
    report({
      runId: "018f47a2-4bc8-7f31-8b9a-123456789004",
      iteration: 4,
      status: "cancelled",
      errorCode: "SCENARIO_CANCELLED",
      route: null,
      stepStatus: "cancelled",
    }),
  ];

  assert.deepEqual(aggregateCompatibility(reports), {
    schemaVersion: "1.0",
    scenarioId: "fixture-compatibility",
    runs: 4,
    passedRuns: 1,
    failedRuns: 2,
    cancelledRuns: 1,
    successRate: 0.25,
    stepCompatibilityRate: 0.25,
    routeCounts: {
      semantic: 1,
    },
    pageClassCounts: {
      advertisement: 1,
      normal: 3,
    },
    failureCategoryCounts: {
      infrastructure: 2,
      product: 1,
    },
    errorCodeCounts: {
      PAGE_ADVERTISEMENT: 1,
      RUNNER_PORT_FAILED: 1,
      SCENARIO_CANCELLED: 1,
    },
  });
});

test("聚合结果不依赖输入顺序且拒绝混合场景或重复 iteration", () => {
  const reports = [
    report({
      runId: "018f47a2-4bc8-7f31-8b9a-123456789011",
      iteration: 1,
      status: "passed",
      route: "hybrid",
    }),
    report({
      runId: "018f47a2-4bc8-7f31-8b9a-123456789012",
      iteration: 2,
      status: "passed",
      route: "visual",
    }),
  ];

  assert.deepEqual(
    aggregateCompatibility(reports),
    aggregateCompatibility([...reports].reverse()),
  );
  assert.throws(
    () => aggregateCompatibility([
      reports[0],
      { ...reports[1], scenarioId: "other-scenario" },
    ]),
    (error) => error.code === "COMPATIBILITY_INPUT_INVALID",
  );
  assert.throws(
    () => aggregateCompatibility([
      reports[0],
      { ...reports[1], iteration: 1 },
    ]),
    (error) => error.code === "COMPATIBILITY_INPUT_INVALID",
  );
});
