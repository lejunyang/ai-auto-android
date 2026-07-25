// 测试用途：验证固定 20 轮结果可复现地归类产品、设备、基础设施失败和 flaky 波动。
import assert from "node:assert/strict";
import test from "node:test";

import {
  aggregateTwentyRuns,
  StatisticsError,
} from "../src/statistics.mjs";
import { RUN_ID } from "./helpers.mjs";

const outcomes = ({
  status = "passed",
  category = null,
  errorCode = null,
  durationBase = 1_000,
  retries = 0,
} = {}) =>
  Array.from({ length: 20 }, (_, index) => ({
    runId: RUN_ID,
    scenarioId: "fixture-repeat",
    iteration: index + 1,
    status,
    category,
    errorCode,
    durationMs: durationBase + index * 10,
    retries,
  }));

test("twenty passing runs produce stable success statistics", () => {
  const input = outcomes();
  const result = aggregateTwentyRuns(input);

  assert.equal(result.classification, "passed");
  assert.equal(result.total, 20);
  assert.equal(result.passed, 20);
  assert.equal(result.failed, 0);
  assert.equal(result.successRate, 1);
  assert.equal(result.durationMs.min, 1_000);
  assert.equal(result.durationMs.max, 1_190);
  assert.equal(result.retryCount, 0);
});

for (const failure of [
  {
    category: "product",
    errorCode: "ASSERTION_FAILED",
  },
  {
    category: "device",
    errorCode: "DEVICE_OFFLINE",
  },
  {
    category: "infrastructure",
    errorCode: "EMULATOR_START_TIMEOUT",
  },
]) {
  test(`twenty consistent ${failure.category} failures preserve their category`, () => {
    const result = aggregateTwentyRuns(outcomes({
      status: "failed",
      category: failure.category,
      errorCode: failure.errorCode,
      retries: 1,
    }));

    assert.equal(result.classification, failure.category);
    assert.equal(result.passed, 0);
    assert.equal(result.failed, 20);
    assert.deepEqual(result.failureCounts, {
      product: failure.category === "product" ? 20 : 0,
      device: failure.category === "device" ? 20 : 0,
      infrastructure: failure.category === "infrastructure" ? 20 : 0,
    });
    assert.deepEqual(result.errorCodes, [{ code: failure.errorCode, count: 20 }]);
  });
}

test("passing and failing iterations are classified as flaky", () => {
  const input = outcomes();
  input[4] = {
    ...input[4],
    status: "failed",
    category: "product",
    errorCode: "ASSERTION_FAILED",
  };
  const result = aggregateTwentyRuns(input);

  assert.equal(result.classification, "flaky");
  assert.equal(result.successRate, 0.95);
  assert.deepEqual(result.flakyIterations, [5]);
});

test("mixed failure categories are classified as flaky", () => {
  const input = outcomes({
    status: "failed",
    category: "device",
    errorCode: "DEVICE_OFFLINE",
  });
  input[9] = {
    ...input[9],
    category: "infrastructure",
    errorCode: "EMULATOR_START_TIMEOUT",
  };
  const result = aggregateTwentyRuns(input);

  assert.equal(result.classification, "flaky");
  assert.deepEqual(result.flakyIterations, [10]);
});

test("shuffled fixed input produces byte-identical statistics", () => {
  const input = outcomes();
  input[7] = {
    ...input[7],
    status: "failed",
    category: "product",
    errorCode: "ASSERTION_FAILED",
    retries: 2,
  };
  const reversed = [...input].reverse();

  assert.equal(
    JSON.stringify(aggregateTwentyRuns(input)),
    JSON.stringify(aggregateTwentyRuns(reversed)),
  );
});

test("statistics reject counts other than twenty and duplicate iterations", () => {
  assert.throws(
    () => aggregateTwentyRuns(outcomes().slice(0, 19)),
    (error) =>
      error instanceof StatisticsError
      && error.code === "ITERATION_COUNT_INVALID",
  );
  const duplicated = outcomes();
  duplicated[19] = { ...duplicated[19], iteration: 19 };
  assert.throws(
    () => aggregateTwentyRuns(duplicated),
    (error) =>
      error instanceof StatisticsError
      && error.code === "ITERATION_SEQUENCE_INVALID",
  );
});
