// 测试用途：验证小黑盒报告从轮次内容重算统计，并确保 fake fixture 不计真实十轮证据。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadXiaoheiheSchemas,
  recomputeXiaoheiheStatistics,
  validateXiaoheiheReport,
} from "../src/report-validator.mjs";
import {
  makeBlockedRun,
  makeReport,
} from "./helpers.mjs";

const schemas = await loadXiaoheiheSchemas();
const validate = (report) =>
  validateXiaoheiheReport(schemas.runReport, report);

test("10 个 fake fixture 可验契约但真实轮次、成功数与成功率均为空", () => {
  const report = makeReport();

  assert.deepEqual(recomputeXiaoheiheStatistics(report.runs), report.statistics);
  assert.equal(report.statistics.totalRuns, 10);
  assert.equal(report.statistics.fixtureRuns, 10);
  assert.equal(report.statistics.realRuns, 0);
  assert.equal(report.statistics.realCompatibleRuns, 0);
  assert.equal(report.statistics.realSuccessRate, null);
  assert.equal(report.statistics.realMatrixComplete, false);
  assert.deepEqual(validate(report), []);
});

test("恰好十个 real run 才完成真实矩阵，fixture 与 real 不得混合", () => {
  const real = makeReport({ sources: () => "real" });
  assert.equal(real.statistics.realRuns, 10);
  assert.equal(real.statistics.realCompatibleRuns, 10);
  assert.equal(real.statistics.realSuccessRate, 1);
  assert.equal(real.statistics.realMatrixComplete, true);
  assert.deepEqual(validate(real), []);

  const mixed = makeReport();
  mixed.runs[0].source = "real";
  mixed.runs[0].fixtureSource = null;
  mixed.statistics = recomputeXiaoheiheStatistics(mixed.runs);
  assert.equal(
    validate(mixed).some((error) =>
      error.includes("fixture and real runs cannot be mixed")),
    true,
  );
});
test("三 API 阻塞轮次不能进入 compatible 统计", () => {
  const report = makeReport({ runFactory: makeBlockedRun });

  assert.equal(report.statistics.compatibleRuns, 0);
  assert.equal(report.statistics.incompatibleRuns, 10);
  assert.equal(report.statistics.consentBlockedRuns, 10);
  assert.equal(report.statistics.permissionBlockedRuns, 10);
  for (const [api, expected] of Object.entries({
    api30: 4,
    api33: 3,
    api34: 3,
  })) {
    const counts = report.statistics.apiCounts[api];
    assert.equal(counts.compatibleRuns, 0);
    assert.equal(counts.incompatibleRuns, expected);
    assert.equal(counts.consentBlockedRuns, expected);
    assert.equal(counts.permissionBlockedRuns, expected);
  }
  assert.deepEqual(validate(report), []);
});

test("陈旧总数、API、route、动作覆盖和分类统计均被内容重算拒绝", () => {
  const report = makeReport();
  report.statistics.totalRuns = 9;
  report.statistics.apiCounts.api30.compatibleRuns = 3;
  report.statistics.routeCounts.nativeAccessibilitySteps = 0;
  report.statistics.actionCoverage["public-search-input"] = 0;
  report.statistics.classificationCounts.network.available = 0;

  const errors = validate(report);
  for (const fragment of [
    "statistics.totalRuns",
    "statistics.apiCounts",
    "statistics.routeCounts",
    "statistics.actionCoverage",
    "statistics.classificationCounts",
  ]) {
    assert.equal(errors.some((error) => error.includes(fragment)), true, fragment);
  }
});

test("报告中的陈旧逐轮 decision 会被内容重算结果拒绝", () => {
  const report = makeReport();
  report.runs[0].decision.compatible = false;
  report.runs[0].decision.reasonCodes = ["NETWORK_FAILURE"];

  assert.equal(
    validate(report).some((error) =>
      error.includes("decision does not match run content")),
    true,
  );
});
