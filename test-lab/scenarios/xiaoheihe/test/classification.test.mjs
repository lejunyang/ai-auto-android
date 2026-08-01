// 测试用途：验证动态内容、广告、网络、未知提交与六项清理独立失败关闭且不重试。
import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyXiaoheiheRun,
  loadXiaoheiheSchemas,
  recomputeXiaoheiheStatistics,
  validateXiaoheiheReport,
} from "../src/report-validator.mjs";
import {
  makeReport,
} from "./helpers.mjs";

const schemas = await loadXiaoheiheSchemas();
const validate = (report) =>
  validateXiaoheiheReport(schemas.runReport, report);
const refresh = (report) => {
  report.runs[0].decision = classifyXiaoheiheRun(report.runs[0]);
  report.statistics = recomputeXiaoheiheStatistics(report.runs);
};

test("dynamic content、advertisement 与 network 使用独立分类和统计", () => {
  const cases = [
    ["dynamicContent", "unknown", "DYNAMIC_CONTENT_UNKNOWN"],
    ["advertisement", "present", "ADVERTISEMENT_PRESENT"],
    ["advertisement", "unknown", "ADVERTISEMENT_UNKNOWN"],
    ["network", "failure", "NETWORK_FAILURE"],
    ["network", "unknown", "NETWORK_UNKNOWN"],
  ];
  for (const [field, value, reason] of cases) {
    const report = makeReport();
    report.runs[0].observations[0][field] = value;
    refresh(report);
    assert.equal(
      report.runs[0].decision.reasonCodes.includes(reason),
      true,
      reason,
    );
    assert.equal(report.runs[0].decision.compatible, false);
    assert.deepEqual(validate(report), []);
  }

  const changed = makeReport();
  changed.runs[0].observations[0].dynamicContent = "changed";
  refresh(changed);
  assert.equal(changed.runs[0].decision.compatible, true);
  assert.equal(
    changed.statistics.classificationCounts.dynamicContent.changed,
    1,
  );
  assert.equal(
    changed.statistics.classificationCounts.advertisement.absent > 0,
    true,
  );
  assert.equal(
    changed.statistics.classificationCounts.network.available > 0,
    true,
  );
  assert.deepEqual(validate(changed), []);
});

test("unknown commit 必须 attempts=1、retryCount=0 并停止后续执行", () => {
  const report = makeReport();
  const run = report.runs[0];
  run.steps = run.steps.slice(0, 1);
  run.steps[0].status = "failed";
  run.steps[0].actionCommits = null;
  run.steps[0].postObservationId = null;
  run.steps[0].errorCode = "ACTION_COMMIT_UNKNOWN";
  run.observations = run.observations.slice(0, 1);
  refresh(report);

  assert.equal(run.decision.actionCommits, null);
  assert.equal(run.decision.reasonCodes.includes("ACTION_COMMIT_UNKNOWN"), true);
  assert.deepEqual(validate(report), []);

  const retried = structuredClone(report);
  retried.runs[0].steps[0].retryCount = 1;
  assert.notDeepEqual(validate(retried), []);

  const continued = structuredClone(report);
  continued.runs[0].steps.push(makeReport().runs[0].steps[1]);
  continued.runs[0].decision = classifyXiaoheiheRun(continued.runs[0]);
  continued.statistics = recomputeXiaoheiheStatistics(continued.runs);
  assert.equal(
    validate(continued).some((error) =>
      error.includes("unknown commit must stop later steps")),
    true,
  );
});

test("未知前台或 surface 以及错误 post observation 均失败关闭", () => {
  const unknown = makeReport();
  unknown.runs[0].observations[0].foreground = "unknown";
  refresh(unknown);
  assert.equal(
    unknown.runs[0].decision.reasonCodes.includes("OBSERVATION_STATE_UNKNOWN"),
    true,
  );
  assert.deepEqual(validate(unknown), []);

  const wrongPost = makeReport();
  wrongPost.runs[0].observations[8].foreground = "xiaoheihe";
  wrongPost.runs[0].observations[8].surface = "native";
  refresh(wrongPost);
  assert.equal(
    wrongPost.runs[0].decision.reasonCodes.includes("POST_OBSERVATION_MISMATCH"),
    true,
  );
  assert.deepEqual(validate(wrongPost), []);
});

test("搜索历史、App 数据、Bridge、session、artifacts、snapshot 均必须清理", () => {
  const cleanupFields = [
    "searchHistory",
    "appData",
    "bridge",
    "session",
    "artifacts",
    "snapshot",
  ];
  for (const field of cleanupFields) {
    const report = makeReport();
    report.runs[0].cleanup[field] = "failed";
    refresh(report);
    assert.equal(
      report.runs[0].decision.reasonCodes.includes("CLEANUP_FAILED"),
      true,
      field,
    );
    assert.equal(report.runs[0].decision.compatible, false);
    assert.deepEqual(validate(report), []);
  }
});
