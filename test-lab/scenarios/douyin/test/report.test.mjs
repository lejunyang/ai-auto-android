// 测试用途：验证 N49 报告重算真实轮次、分类、fresh swipe、unknown commit 和清理。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadDouyinSchemas,
  validateDouyinReport,
} from "../src/contract-validator.mjs";
import {
  makeReport,
  makeRun,
  makeStatistics,
} from "./helpers.mjs";

const validate = async (report) =>
  validateDouyinReport((await loadDouyinSchemas()).report, report);

const classifiedBlockedRun = (classification, overrides = {}) =>
  makeRun({
    runId: `fixture-${classification}`,
    classification,
    status: "blocked",
    errorCode: classification.replaceAll("-", "_").toUpperCase(),
    route: null,
    actionType: "observe",
    postObservationId: null,
    postContentFingerprint: null,
    actionCommits: 0,
    attempts: 0,
    ...overrides,
  });

test("fixture 通过契约但不计入真实 10 轮", async () => {
  const report = makeReport();

  assert.deepEqual(await validate(report), []);
  assert.equal(report.statistics.fixtureRuns, 1);
  assert.equal(report.statistics.realRuns, 0);
  assert.equal(report.statistics.completedRealRounds, 0);
});

test("真实报告必须恰好包含 iteration 1 到 10", async () => {
  const runs = Array.from({ length: 10 }, (_, index) =>
    makeRun({
      runId: `real-douyin-run-${index + 1}`,
      iteration: index + 1,
      source: "real",
    }));
  const report = makeReport(runs);

  assert.deepEqual(await validate(report), []);

  for (const mutate of [
    (value) => value.runs.pop(),
    (value) => {
      value.runs[9].iteration = 9;
    },
    (value) => {
      value.runs.reverse();
    },
  ]) {
    const invalid = structuredClone(report);
    mutate(invalid);
    invalid.statistics = makeStatistics(invalid.runs);
    assert.notDeepEqual(await validate(invalid), []);
  }
});

test("video、visual-only、登录墙、广告、模拟器和 API 33/34 hierarchy 分别分类", async () => {
  const classes = [
    "video-surface",
    "visual-only",
    "login-wall",
    "advertisement",
    "emulator-detected",
    "hierarchy-failure-api33",
    "hierarchy-failure-api34",
  ];
  const runs = classes.map((classification, index) =>
    classifiedBlockedRun(classification, {
      runId: `fixture-classification-${index + 1}`,
      iteration: index + 1,
      apiLevel: classification.endsWith("api33")
        ? 33
        : classification.endsWith("api34") ? 34 : 30,
      route: classification === "visual-only" ? "visual" : null,
    }));
  const report = makeReport(runs);

  assert.deepEqual(await validate(report), []);
  assert.deepEqual(
    Object.entries(report.statistics.classificationCounts)
      .filter(([, count]) => count > 0)
      .map(([classification]) => classification),
    classes,
  );

  const blindVisual = structuredClone(report);
  blindVisual.runs[0].route = "visual";
  blindVisual.runs[0].actionType = "tap";
  blindVisual.runs[0].attempts = 1;
  assert.notDeepEqual(await validate(blindVisual), []);

  const arbitraryCode = structuredClone(report);
  arbitraryCode.runs[3].errorCode = "ARBITRARY_BLOCK";
  assert.notDeepEqual(await validate(arbitraryCode), []);
});

test("纵向 swipe 必须绑定 fresh observation 并验证内容 fingerprint 变化", async () => {
  const report = makeReport();
  assert.deepEqual(await validate(report), []);

  for (const mutate of [
    (run) => {
      run.preObservationFresh = false;
    },
    (run) => {
      run.postObservationId = run.preObservationId;
    },
    (run) => {
      run.postContentFingerprint = run.preContentFingerprint;
    },
    (run) => {
      run.postObservationId = null;
    },
    (run) => {
      run.actionCommits = 0;
      run.attempts = 0;
    },
  ]) {
    const invalid = makeReport();
    mutate(invalid.runs[0]);
    invalid.statistics = makeStatistics(invalid.runs);
    assert.notDeepEqual(await validate(invalid), []);
  }
});

test("长按默认 unsupported，unknown commit 不允许 retry", async () => {
  const longPress = classifiedBlockedRun("long-press-unsupported", {
    actionType: "long-press",
  });
  const unknown = classifiedBlockedRun("action-commit-unknown", {
    runId: "fixture-action-commit-unknown",
    iteration: 2,
    status: "failed",
    errorCode: "ACTION_COMMIT_UNKNOWN",
    actionType: "vertical-swipe",
    route: "semantic",
    actionCommits: null,
    attempts: 1,
  });
  const report = makeReport([longPress, unknown]);

  assert.deepEqual(await validate(report), []);

  const retried = structuredClone(report);
  retried.runs[1].retries = 1;
  assert.notDeepEqual(await validate(retried), []);

  const bypassedLongPress = structuredClone(report);
  bypassedLongPress.runs[1].actionType = "long-press";
  assert.notDeepEqual(await validate(bypassedLongPress), []);
});

test("无账号仍记录遥测限制，四项清理必须全部完成", async () => {
  const report = makeReport();
  assert.deepEqual(report.runs[0].telemetry, {
    accountState: "signed-out",
    recommendationState: "dynamic-uncontrolled",
    personalizationAssessment: "unavailable-without-account",
  });

  for (const field of [
    "appDataCleared",
    "sessionCleared",
    "artifactsCleared",
    "snapshotRestored",
  ]) {
    const invalid = makeReport();
    invalid.runs[0].cleanup[field] = false;
    assert.notDeepEqual(await validate(invalid), [], field);
  }
});
