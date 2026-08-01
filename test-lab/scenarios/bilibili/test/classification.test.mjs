// 测试用途：验证 API 被动基线稳定分类、清理门和 fixture 真实证据隔离。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadBilibiliSchemas,
  recomputeBilibiliStatistics,
  validateBilibiliReport,
} from "../src/report-validator.mjs";
import {
  makeReport,
  makeRound,
} from "./helpers.mjs";

const schemas = await loadBilibiliSchemas();
const validate = (report) => validateBilibiliReport(schemas.report, report);

test("API 33 hierarchy unavailable 使用固定分类并保持零动作提交", () => {
  const profile = {
    profileId: "api-33",
    apiLevel: 33,
    abi: "arm64-v8a",
    snapshot: "clean",
  };
  const report = makeReport({
    profile,
    rounds: Array.from({ length: 10 }, (_, index) => makeRound({
      iteration: index + 1,
      apiLevel: 33,
      status: "blocked",
      classification: "api33-hierarchy-unavailable",
      blockerCode: "BILIBILI_API33_HIERARCHY_UNAVAILABLE",
      steps: [],
    })),
  });
  report.statistics = recomputeBilibiliStatistics(report);

  assert.deepEqual(validate(report), []);

  const wrong = structuredClone(report);
  wrong.rounds[0].blockerCode = "OBSERVATION_FAILED";
  assert.equal(
    validate(wrong).some((error) => error.includes("API 33")),
    true,
  );
});

test("API 34 target package invisible 使用固定分类并保持零动作提交", () => {
  const profile = {
    profileId: "api-34",
    apiLevel: 34,
    abi: "arm64-v8a",
    snapshot: "clean",
  };
  const report = makeReport({
    profile,
    rounds: Array.from({ length: 10 }, (_, index) => makeRound({
      iteration: index + 1,
      apiLevel: 34,
      status: "blocked",
      classification: "api34-package-invisible",
      blockerCode: "BILIBILI_API34_PACKAGE_INVISIBLE",
      steps: [],
    })),
  });
  report.statistics = recomputeBilibiliStatistics(report);

  assert.deepEqual(validate(report), []);

  const wrong = structuredClone(report);
  wrong.rounds[0].classification = "unknown";
  assert.equal(
    validate(wrong).some((error) => error.includes("API 34")),
    true,
  );
});

test("fixture 十轮只验证契约，不计真实十轮证据", () => {
  const report = makeReport();

  assert.deepEqual(report.statistics, {
    plannedRounds: 10,
    reportedRounds: 10,
    fixtureRounds: 10,
    realRounds: 0,
    passedRounds: 10,
    blockedRounds: 0,
    failedRounds: 0,
    unsupportedRounds: 0,
    successRate: 1,
    actionCommits: 150,
    unknownCommitRounds: 0,
    cleanedRounds: 10,
    routeCounts: {
      semantic: 150,
      hybrid: 0,
      visual: 0,
    },
    classificationCounts: {
      advertisement: 0,
      "api33-hierarchy-unavailable": 0,
      "api34-package-invisible": 0,
      "cleanup-failure": 0,
      compatible: 10,
      consent: 0,
      "long-press-side-effect-unproven": 0,
      login: 0,
      permission: 0,
      privacy: 0,
      unknown: 0,
      "unknown-commit": 0,
      update: 0,
    },
    blockerCounts: {
      ACTION_COMMIT_UNKNOWN: 0,
      BILIBILI_ADVERTISEMENT_BLOCKED: 0,
      BILIBILI_API33_HIERARCHY_UNAVAILABLE: 0,
      BILIBILI_API34_PACKAGE_INVISIBLE: 0,
      BILIBILI_CONSENT_BLOCKED: 0,
      BILIBILI_LOGIN_BLOCKED: 0,
      BILIBILI_LONG_PRESS_UNSUPPORTED: 0,
      BILIBILI_PERMISSION_BLOCKED: 0,
      BILIBILI_PRIVACY_BLOCKED: 0,
      BILIBILI_UNKNOWN_BLOCKED: 0,
      BILIBILI_UPDATE_BLOCKED: 0,
      SCENARIO_CLEANUP_FAILED: 0,
    },
    tenRoundRealEvidenceComplete: false,
  });
  assert.deepEqual(validate(report), []);

  const disguised = structuredClone(report);
  disguised.evidenceKind = "real-device";
  for (const round of disguised.rounds) round.evidenceKind = "real-device";
  disguised.statistics.realRounds = 10;
  disguised.statistics.fixtureRounds = 0;
  disguised.statistics.tenRoundRealEvidenceComplete = true;
  assert.equal(
    validate(disguised).some((error) =>
      error.includes("evidence source is inconsistent")),
    true,
  );
});

test("真实十轮必须绑定十个唯一 N47 run 与报告摘要", () => {
  const report = makeReport({ evidenceKind: "real-device" });

  assert.equal(report.statistics.fixtureRounds, 0);
  assert.equal(report.statistics.realRounds, 10);
  assert.equal(report.statistics.tenRoundRealEvidenceComplete, true);
  assert.deepEqual(validate(report), []);

  const duplicate = structuredClone(report);
  duplicate.rounds[1].evidenceBinding.runId =
    duplicate.rounds[0].evidenceBinding.runId;
  duplicate.rounds[1].evidenceBinding.runReportSha256 =
    duplicate.rounds[0].evidenceBinding.runReportSha256;
  duplicate.statistics = recomputeBilibiliStatistics(duplicate);
  assert.equal(duplicate.statistics.tenRoundRealEvidenceComplete, false);
  assert.equal(
    validate(duplicate).some((error) =>
      error.includes("real evidence bindings must be unique")),
    true,
  );
});

test("统计必须从 rounds 内容重算，拒绝陈旧总数与通过声明", () => {
  const report = makeReport();
  report.statistics.realRounds = 10;
  report.statistics.successRate = 0;
  report.statistics.actionCommits = 0;
  report.statistics.routeCounts.semantic = 0;
  report.statistics.classificationCounts.compatible = 0;
  report.statistics.tenRoundRealEvidenceComplete = true;

  const errors = validate(report);
  for (const field of [
    "realRounds",
    "successRate",
    "actionCommits",
    "routeCounts",
    "classificationCounts",
    "tenRoundRealEvidenceComplete",
  ]) {
    assert.equal(errors.some((error) => error.includes(field)), true, field);
  }
});

test("每轮必须清 App 数据、搜索历史、session、artifacts 与 snapshot", () => {
  const fields = [
    "appDataCleared",
    "searchHistoryCleared",
    "sessionCleared",
    "artifactsCleared",
    "snapshotRestored",
  ];

  for (const field of fields) {
    const report = makeReport();
    report.rounds[0].cleanup[field] = false;
    assert.equal(
      validate(report).some((error) => error.includes("cleanup")),
      true,
      field,
    );
  }
});
