// 测试用途：验证 N52 报告严格 Schema、确定性聚合和矛盾结果拒绝。
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  validateMatrixReport,
} from "../src/report.mjs";
import { runLocalMatrix } from "../src/matrix.mjs";
import {
  fakePorts,
  fixedClock,
  MATRIX_ID,
} from "./helpers.mjs";

const schema = JSON.parse(
  await readFile(
    path.resolve(
      import.meta.dirname,
      "..",
      "schema",
      "matrix-report.schema.json",
    ),
    "utf8",
  ),
);

test("完整矩阵报告通过严格 Schema", async () => {
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fakePorts().ports,
    clock: fixedClock(),
  });

  assert.deepEqual(validateMatrixReport(schema, report), []);
});

test("报告拒绝未知字段、计数矛盾、残留非零和自由异常", async () => {
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fakePorts().ports,
    clock: fixedClock(),
  });
  const invalid = structuredClone(report);
  invalid.privateMessage = "raw emulator failure";
  invalid.executedRuns = 299;
  invalid.profiles[0].runs[0].cleanup.logs = 1;
  invalid.profiles[0].routeCounts.semantic = 999;
  invalid.profiles[0].pageClassCounts.normal = 999;
  invalid.scenarioStatistics[0].durationMs.p95 += 1;
  invalid.scenarioStatistics[0].routeCounts.semantic += 1;
  invalid.scenarioStatistics[0].pageClassCounts.normal += 1;

  const errors = validateMatrixReport(schema, invalid);
  assert.equal(errors.some((error) => error.includes("privateMessage")), true);
  assert.equal(errors.some((error) => error.includes("executedRuns")), true);
  assert.equal(errors.some((error) => error.includes("cleanup.logs")), true);
  assert.equal(errors.some((error) => error.includes("routeCounts")), true);
  assert.equal(errors.some((error) => error.includes("pageClassCounts")), true);
  assert.equal(errors.some((error) => error.includes("aggregate drifted")), true);
});

test("报告拒绝 route 和页面分类计数中的任意内容键", async () => {
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fakePorts().ports,
    clock: fixedClock(),
  });
  const invalid = structuredClone(report);
  invalid.profiles[0].runs[0].routeCounts["secret-route"] = 1;
  invalid.profiles[0].runs[0].pageClassCounts["private-page"] = 1;

  const errors = validateMatrixReport(schema, invalid);
  assert.equal(errors.some((error) => error.includes("secret-route")), true);
  assert.equal(errors.some((error) => error.includes("private-page")), true);
  assert.equal(errors.some((error) => error.includes("unknown route")), true);
  assert.equal(errors.some((error) => error.includes("unknown page class")), true);
});

test("多步骤矩阵允许 profile 汇总超过三百并保持跨层重算一致", async () => {
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fakePorts().ports,
    clock: fixedClock(),
  });
  const expanded = structuredClone(report);
  for (const profile of expanded.profiles) {
    for (const run of profile.runs) {
      for (const key of Object.keys(run.routeCounts)) run.routeCounts[key] *= 4;
      for (const key of Object.keys(run.pageClassCounts)) {
        run.pageClassCounts[key] *= 4;
      }
    }
    for (const key of Object.keys(profile.routeCounts)) profile.routeCounts[key] *= 4;
    for (const key of Object.keys(profile.pageClassCounts)) {
      profile.pageClassCounts[key] *= 4;
    }
  }
  for (const statistic of expanded.scenarioStatistics) {
    for (const key of Object.keys(statistic.routeCounts)) {
      statistic.routeCounts[key] *= 4;
    }
    for (const key of Object.keys(statistic.pageClassCounts)) {
      statistic.pageClassCounts[key] *= 4;
    }
  }

  assert.equal(expanded.profiles[0].routeCounts.semantic > 300, true);
  assert.equal(expanded.profiles[0].pageClassCounts.normal, 400);
  assert.deepEqual(validateMatrixReport(schema, expanded), []);
});

test("报告拒绝重复轮次和不属于已完成二十轮组的陈旧统计", async () => {
  const report = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fakePorts().ports,
    clock: fixedClock(),
  });
  const invalid = structuredClone(report);
  invalid.profiles[0].runs[1].iteration = 1;
  invalid.profiles[0].runs.splice(20);
  invalid.executedRuns = invalid.profiles.reduce(
    (total, profile) => total + profile.runs.length,
    0,
  );
  invalid.cleanupPassedRuns = invalid.executedRuns;
  invalid.profiles[0].passed = invalid.profiles[0].runs.length;
  invalid.profiles[0].cleanupPassed = invalid.profiles[0].runs.length;
  invalid.profiles[0].routeCounts = { semantic: invalid.profiles[0].runs.length };
  invalid.profiles[0].pageClassCounts = { normal: invalid.profiles[0].runs.length };

  const errors = validateMatrixReport(schema, invalid);
  assert.equal(errors.some((error) => error.includes("duplicate iteration")), true);
  assert.equal(
    errors.some((error) => error.includes("completed group set or order drifted")),
    true,
  );
});

test("任意畸形报告只返回稳定校验错误而不抛出原始异常", () => {
  const malformed = [
    null,
    [],
    {},
    { profiles: [null], scenarioStatistics: [null] },
    { profiles: [{ runs: [null] }], scenarioStatistics: [] },
    { profiles: [{ runs: [{ routeCounts: null }] }], scenarioStatistics: [] },
  ];

  for (const report of malformed) {
    let errors;
    assert.doesNotThrow(() => {
      errors = validateMatrixReport(schema, report);
    });
    assert.equal(Array.isArray(errors), true);
    assert.ok(errors.length > 0);
    assert.equal(errors.every((error) => typeof error === "string"), true);
  }
});

test("固定端口结果与反序执行的统计 JSON 完全一致", async () => {
  const first = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fakePorts().ports,
    clock: fixedClock(),
  });
  const second = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fakePorts().ports,
    clock: fixedClock(),
  });

  assert.equal(
    JSON.stringify(first.scenarioStatistics),
    JSON.stringify(second.scenarioStatistics),
  );
});
