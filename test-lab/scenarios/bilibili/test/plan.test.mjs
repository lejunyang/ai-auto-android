// 测试用途：验证 B站身份、十轮计划和固定安全动作序列不可由报告调用方改写。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadBilibiliSchemas,
  validateBilibiliPlan,
  validateBilibiliReport,
} from "../src/report-validator.mjs";
import {
  BILIBILI_PACKAGE,
  FIXTURE_PACKAGE,
  IDENTITY,
  makePlan,
  makeReport,
  STEP_PLAN,
} from "./helpers.mjs";

const schemas = await loadBilibiliSchemas();

test("固定计划绑定现有 B站 identity、十轮和唯一安全动作序列", async () => {
  const plan = makePlan();
  const report = makeReport();

  assert.deepEqual(plan.identity, IDENTITY);
  assert.deepEqual(report.identity, IDENTITY);
  assert.equal(report.plannedIterations, 10);
  assert.deepEqual(
    report.rounds.map((round) => round.iteration),
    [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
  );
  for (const round of report.rounds) {
    assert.deepEqual(
      round.steps.map((step) => [
        step.stepId,
        step.actionId,
        step.preForegroundPackage,
        step.postForegroundPackage,
      ]),
      STEP_PLAN,
    );
  }
  assert.deepEqual(validateBilibiliPlan(schemas.plan, plan), []);
  assert.deepEqual(validateBilibiliReport(schemas.report, report), []);
});

test("identity 任一 APK 元数据漂移均被拒绝", () => {
  const cases = [
    ["manifest", "other.json"],
    ["packageName", "com.example.other"],
    ["versionName", "9.5.1"],
    ["versionCode", 1],
    ["abi", "x86_64"],
    ["sizeBytes", 1],
    ["apkSha256", "a".repeat(64)],
    ["signingCertificateSha256", "b".repeat(64)],
  ];

  for (const [field, value] of cases) {
    const report = makeReport();
    report.identity[field] = value;
    assert.notDeepEqual(
      validateBilibiliReport(schemas.report, report),
      [],
      field,
    );
  }
});

test("缺轮、重复轮、额外轮与乱序轮均不能伪装固定十轮", () => {
  const cases = [
    (report) => report.rounds.pop(),
    (report) => {
      report.rounds[9].iteration = 9;
    },
    (report) => {
      report.rounds.push(structuredClone(report.rounds[9]));
      report.rounds[10].iteration = 11;
    },
    (report) => report.rounds.reverse(),
  ];

  for (const mutate of cases) {
    const report = makeReport();
    mutate(report);
    assert.equal(
      validateBilibiliReport(schemas.report, report)
        .some((error) => error.includes("fixed ten-round plan")),
      true,
    );
  }
});

test("仅允许固定 package 与固定动作，不接受任意 action 或目标", () => {
  const cases = [
    (report) => {
      report.rounds[0].steps[0].preForegroundPackage = "com.example.other";
    },
    (report) => {
      report.rounds[0].steps[3].actionId = "tap";
    },
    (report) => {
      report.rounds[0].steps[12].postForegroundPackage =
        "dev.aiauto.webfixture";
    },
    (report) => {
      report.rounds[0].steps[3].action = { type: "click", target: "private" };
    },
  ];

  for (const mutate of cases) {
    const report = makeReport();
    mutate(report);
    assert.notDeepEqual(validateBilibiliReport(schemas.report, report), []);
  }

  assert.deepEqual(
    new Set([BILIBILI_PACKAGE, FIXTURE_PACKAGE]),
    new Set(["tv.danmaku.bili", "dev.aiauto.fixture"]),
  );
});
