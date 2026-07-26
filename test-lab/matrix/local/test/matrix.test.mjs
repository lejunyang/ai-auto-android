// 测试用途：验证 N52 固定计划、capability 前置门、300 轮执行和清理 fail-fast。
import assert from "node:assert/strict";
import test from "node:test";

import {
  createLocalMatrixPlan,
  LocalMatrixError,
  runLocalMatrix,
} from "../src/matrix.mjs";
import {
  fakePorts,
  fixedClock,
  MATRIX_ID,
} from "./helpers.mjs";

test("固定计划恰好包含三 API 五场景各二十轮", () => {
  const plan = createLocalMatrixPlan();

  assert.equal(plan.length, 300);
  assert.deepEqual(
    [...new Set(plan.map((entry) => entry.profile.profileId))],
    ["api-30", "api-33", "api-34"],
  );
  assert.equal(
    new Set(plan.map((entry) =>
      `${entry.profile.profileId}:${entry.scenario.scenarioId}:${entry.iteration}`)).size,
    300,
  );
  for (const profileId of ["api-30", "api-33", "api-34"]) {
    for (const scenarioId of [
      "native-fixture",
      "webview-fixture",
      "canvas-fixture",
      "recording-editor",
      "recording-replay",
    ]) {
      assert.deepEqual(
        plan
          .filter((entry) =>
            entry.profile.profileId === profileId
            && entry.scenario.scenarioId === scenarioId)
          .map((entry) => entry.iteration),
        Array.from({ length: 20 }, (_, index) => index + 1),
      );
    }
  }
});

test("capability 缺失时探测三个 profile 但设备启动数为零", async () => {
  const fixture = fakePorts({ missingCapability: "visual.action" });

  await assert.rejects(
    () => runLocalMatrix({
      matrixId: MATRIX_ID,
      ports: fixture.ports,
      clock: fixedClock(),
    }),
    (error) => {
      assert.ok(error instanceof LocalMatrixError);
      assert.equal(error.code, "MATRIX_CAPABILITY_MISSING");
      return true;
    },
  );
  assert.equal(fixture.calls.filter((call) => call.startsWith("probe:")).length, 3);
  assert.equal(fixture.calls.some((call) => call.startsWith("start:")), false);
});

test("capability probe 多报未声明能力时在设备启动前失败关闭", async () => {
  const fixture = fakePorts({ extraProbeCapability: "private.capability" });

  await assert.rejects(
    () => runLocalMatrix({
      matrixId: MATRIX_ID,
      ports: fixture.ports,
      clock: fixedClock(),
    }),
    (error) => {
      assert.ok(error instanceof LocalMatrixError);
      assert.equal(error.code, "MATRIX_CAPABILITY_INVALID");
      return true;
    },
  );
  assert.equal(fixture.calls.filter((call) => call.startsWith("probe:")).length, 1);
  assert.equal(fixture.calls.some((call) => call.startsWith("start:")), false);
});

test("输入、端口、时钟和 capability probe 异常均返回稳定错误码", async () => {
  const base = fakePorts();
  const cases = [
    [
      {
        matrixId: "not-a-uuid",
        ports: base.ports,
        clock: fixedClock(),
      },
      "MATRIX_INPUT_INVALID",
    ],
    [
      {
        matrixId: MATRIX_ID,
        ports: {},
        clock: fixedClock(),
      },
      "MATRIX_PORT_INVALID",
    ],
    [
      {
        matrixId: MATRIX_ID,
        ports: base.ports,
        clock: {},
      },
      "MATRIX_CLOCK_INVALID",
    ],
  ];
  for (const [input, code] of cases) {
    await assert.rejects(
      () => runLocalMatrix(input),
      (error) => error instanceof LocalMatrixError && error.code === code,
      code,
    );
  }

  const probeFixture = fakePorts();
  probeFixture.ports.capabilities.probe = async () => {
    throw new Error("private capability failure");
  };
  await assert.rejects(
    () => runLocalMatrix({
      matrixId: MATRIX_ID,
      ports: probeFixture.ports,
      clock: fixedClock(),
    }),
    (error) =>
      error instanceof LocalMatrixError
      && error.code === "MATRIX_CAPABILITY_PROBE_FAILED",
  );
});

test("完整 fake 矩阵执行三百轮并生成十五组通过统计", async () => {
  const fixture = fakePorts();
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fixture.ports,
    clock: fixedClock(),
  });

  assert.equal(report.succeeded, true);
  assert.equal(report.executedRuns, 300);
  assert.equal(report.cleanupPassedRuns, 300);
  assert.equal(report.scenarioStatistics.length, 15);
  assert.equal(report.scenarioStatistics.every((item) =>
    item.total === 20
    && item.passed === 20
    && item.successRate === 1
    && item.classification === "passed"), true);
  const nativeStatistics = report.scenarioStatistics.find((item) =>
    item.profileId === "api-30" && item.scenarioId === "native-fixture");
  const canvasStatistics = report.scenarioStatistics.find((item) =>
    item.profileId === "api-30" && item.scenarioId === "canvas-fixture");
  assert.deepEqual(nativeStatistics.routeCounts, { semantic: 20 });
  assert.deepEqual(nativeStatistics.pageClassCounts, { normal: 20 });
  assert.deepEqual(canvasStatistics.routeCounts, { visual: 20 });
  assert.deepEqual(canvasStatistics.pageClassCounts, { normal: 20 });
  assert.equal(fixture.calls.filter((call) => call.startsWith("start:")).length, 300);
  assert.equal(fixture.calls.filter((call) => call.startsWith("run:")).length, 300);
  assert.equal(fixture.calls.filter((call) => call.startsWith("stop:")).length, 300);
  assert.equal(fixture.calls.filter((call) => call.startsWith("residue:")).length, 300);
});

test("普通场景失败仍继续全部轮次并形成 product 统计", async () => {
  const fixture = fakePorts({
    scenarioFailure: "api-30:native-fixture:1",
  });
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fixture.ports,
    clock: fixedClock(),
  });

  assert.equal(report.executedRuns, 300);
  assert.equal(report.succeeded, true);
  const statistics = report.scenarioStatistics.find((item) =>
    item.profileId === "api-30" && item.scenarioId === "native-fixture");
  assert.equal(statistics.passed, 19);
  assert.equal(statistics.successRate, 0.95);
  assert.equal(statistics.classification, "flaky");
});

test("设备失败和取消均保留零提交证据且仍完成清理", async () => {
  const cases = [
    {
      options: { deviceFailure: "api-30:webview-fixture:2" },
      scenarioId: "webview-fixture",
      iteration: 2,
      status: "failed",
      errorCode: "PAGE_EMULATOR_DETECTED",
      category: "device",
      pageClass: "emulator-detected",
    },
    {
      options: { cancelledScenario: "api-30:native-fixture:3" },
      scenarioId: "native-fixture",
      iteration: 3,
      status: "cancelled",
      errorCode: "SCENARIO_CANCELLED",
      category: "infrastructure",
      pageClass: "normal",
    },
  ];
  for (const expected of cases) {
    const fixture = fakePorts(expected.options);
    const report = await runLocalMatrix({
      matrixId: MATRIX_ID,
      ports: fixture.ports,
      clock: fixedClock(),
    });
    const profile = report.profiles[0];
    const run = profile.runs.find((candidate) =>
      candidate.scenarioId === expected.scenarioId
      && candidate.iteration === expected.iteration);
    const statistics = report.scenarioStatistics.find((candidate) =>
      candidate.profileId === "api-30"
      && candidate.scenarioId === expected.scenarioId);

    assert.equal(report.succeeded, true, expected.errorCode);
    assert.equal(report.executedRuns, 300, expected.errorCode);
    assert.equal(report.cleanupPassedRuns, 300, expected.errorCode);
    assert.equal(run.status, expected.status, expected.errorCode);
    assert.equal(run.errorCode, expected.errorCode, expected.errorCode);
    assert.equal(run.actionCommitUnknown, false, expected.errorCode);
    assert.deepEqual(run.routeCounts, {}, expected.errorCode);
    assert.deepEqual(run.pageClassCounts, {
      [expected.pageClass]: 1,
    }, expected.errorCode);
    assert.equal(statistics.failureCounts[expected.category], 1, expected.errorCode);
    assert.equal(statistics.successRate, 0.95, expected.errorCode);
    assert.equal(
      fixture.calls.filter((call) => call.startsWith("stop:")).length,
      300,
      expected.errorCode,
    );
    assert.equal(
      fixture.calls.filter((call) => call.startsWith("residue:")).length,
      300,
      expected.errorCode,
    );
  }
});

test("场景端口异常和无效报告按基础设施失败收集且不泄露自由异常", async () => {
  const cases = [
    {
      options: { scenarioPortFailure: "api-30:native-fixture:4" },
      code: "MATRIX_SCENARIO_PORT_FAILED",
    },
    {
      options: { invalidScenarioReport: "api-30:native-fixture:4" },
      code: "MATRIX_SCENARIO_REPORT_INVALID",
    },
  ];
  for (const expected of cases) {
    const fixture = fakePorts(expected.options);
    const report = await runLocalMatrix({
      matrixId: MATRIX_ID,
      ports: fixture.ports,
      clock: fixedClock(),
    });
    const run = report.profiles[0].runs.find((candidate) =>
      candidate.scenarioId === "native-fixture"
      && candidate.iteration === 4);
    const statistics = report.scenarioStatistics.find((candidate) =>
      candidate.profileId === "api-30"
      && candidate.scenarioId === "native-fixture");

    assert.equal(report.succeeded, true, expected.code);
    assert.equal(report.executedRuns, 300, expected.code);
    assert.equal(report.cleanupPassedRuns, 300, expected.code);
    assert.equal(run.status, "failed", expected.code);
    assert.equal(run.errorCode, expected.code, expected.code);
    assert.deepEqual(run.routeCounts, {}, expected.code);
    assert.deepEqual(run.pageClassCounts, {}, expected.code);
    assert.equal(statistics.failureCounts.infrastructure, 1, expected.code);
    assert.deepEqual(
      statistics.errorCodes,
      [{ code: expected.code, count: 1 }],
      expected.code,
    );
    assert.equal(JSON.stringify(report).includes("private scenario"), false);
    assert.equal(JSON.stringify(report).includes("must not pass"), false);
  }
});

test("start 失败、stop 失败和残留失败均立即停止后续轮次", async () => {
  const cases = [
    [{ startFailureAt: 2 }, "MATRIX_START_FAILED", 1],
    [{ stopFailureAt: 2 }, "MATRIX_STOP_FAILED", 2],
    [{ residueFailureAt: 2 }, "MATRIX_RESIDUE_DETECTED", 2],
  ];
  for (const [options, code, executedRuns] of cases) {
    const fixture = fakePorts(options);
    const report = await runLocalMatrix({
      matrixId: MATRIX_ID,
      ports: fixture.ports,
      clock: fixedClock(),
    });

    assert.equal(report.succeeded, false, code);
    assert.equal(report.errorCode, code, code);
    assert.equal(report.executedRuns, executedRuns, code);
    assert.ok(report.executedRuns < 300, code);
  }
});

test("stop 和 residue 畸形返回值失败关闭且不继续后续轮次", async () => {
  const stopFixture = fakePorts();
  stopFixture.ports.lifecycle.stop = async () => ({ stopped: false });
  const stopReport = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: stopFixture.ports,
    clock: fixedClock(),
  });
  assert.equal(stopReport.errorCode, "MATRIX_STOP_FAILED");
  assert.equal(stopReport.executedRuns, 1);
  assert.equal(stopReport.cleanupPassedRuns, 0);

  const residueFixture = fakePorts();
  residueFixture.ports.residue.inspect = async () => ({ leases: -1 });
  const residueReport = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: residueFixture.ports,
    clock: fixedClock(),
  });
  assert.equal(residueReport.errorCode, "MATRIX_RESIDUE_INVALID");
  assert.equal(residueReport.executedRuns, 1);
  assert.equal(residueReport.cleanupPassedRuns, 0);
});

test("start 无可信 serial 时不得调用 stop 或 residue 端口", async () => {
  const fixture = fakePorts({ unsafeStartAt: 1 });
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fixture.ports,
    clock: fixedClock(),
  });

  assert.equal(report.succeeded, false);
  assert.equal(report.errorCode, "MATRIX_START_INVALID");
  assert.equal(report.executedRuns, 0);
  assert.equal(fixture.calls.filter((call) => call.startsWith("stop:")).length, 0);
  assert.equal(fixture.calls.filter((call) => call.startsWith("residue:")).length, 0);
});

test("未知提交状态使矩阵失败但仍完成全部轮次和清理", async () => {
  const fixture = fakePorts({
    unknownCommitAt: "api-33:webview-fixture:7",
  });
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fixture.ports,
    clock: fixedClock(),
  });

  assert.equal(report.executedRuns, 300);
  assert.equal(report.cleanupPassedRuns, 300);
  assert.equal(report.succeeded, false);
  assert.equal(report.errorCode, "MATRIX_ACTION_COMMIT_UNKNOWN");
});

test("任一二十轮统计低于百分之九十五时矩阵验收失败", async () => {
  const fixture = fakePorts({
    scenarioFailure: [
      "api-30:native-fixture:1",
      "api-30:native-fixture:2",
    ],
  });
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fixture.ports,
    clock: fixedClock(),
  });
  const statistics = report.scenarioStatistics.find((item) =>
    item.profileId === "api-30" && item.scenarioId === "native-fixture");

  assert.equal(report.succeeded, false);
  assert.equal(report.errorCode, "MATRIX_ACCEPTANCE_FAILED");
  assert.equal(report.executedRuns, 300);
  assert.equal(report.cleanupPassedRuns, 300);
  assert.equal(statistics.successRate, 0.9);
});

test("start capability 漂移立即失败，fingerprint 漂移使报告失败关闭", async () => {
  for (const options of [
    { extraStartCapabilityAt: 2 },
    { duplicateStartCapabilityAt: 2 },
  ]) {
    const capabilityFixture = fakePorts(options);
    const capabilityReport = await runLocalMatrix({
      matrixId: MATRIX_ID,
      ports: capabilityFixture.ports,
      clock: fixedClock(),
    });
    assert.equal(capabilityReport.succeeded, false);
    assert.equal(capabilityReport.errorCode, "MATRIX_START_INVALID");
    assert.equal(capabilityReport.executedRuns, 1);
    assert.equal(
      capabilityFixture.calls.filter((call) => call.startsWith("stop:")).length,
      2,
    );
    assert.equal(
      capabilityFixture.calls.filter((call) => call.startsWith("residue:")).length,
      2,
    );
  }

  const fingerprintFixture = fakePorts({ fingerprintDriftAt: 2 });
  const fingerprintReport = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fingerprintFixture.ports,
    clock: fixedClock(),
  });
  assert.equal(fingerprintReport.succeeded, false);
  assert.equal(fingerprintReport.errorCode, "MATRIX_FINGERPRINT_DRIFT");
  assert.equal(fingerprintReport.executedRuns, 2);
  assert.equal(fingerprintReport.cleanupPassedRuns, 2);

  const lastFingerprintFixture = fakePorts({ fingerprintDriftAt: 300 });
  const lastFingerprintReport = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: lastFingerprintFixture.ports,
    clock: fixedClock(),
  });
  assert.equal(lastFingerprintReport.succeeded, false);
  assert.equal(lastFingerprintReport.errorCode, "MATRIX_FINGERPRINT_DRIFT");
  assert.equal(lastFingerprintReport.executedRuns, 300);
  assert.equal(lastFingerprintReport.cleanupPassedRuns, 300);
});
