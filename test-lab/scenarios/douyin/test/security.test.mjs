// 测试用途：验证 N49 报告拒绝互动提交、任意字段、坐标、secret、路径和原始内容。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadDouyinSchemas,
  validateDouyinReport,
} from "../src/contract-validator.mjs";
import {
  makeReport,
  makeStatistics,
} from "./helpers.mjs";

const validate = async (report) =>
  validateDouyinReport((await loadDouyinSchemas()).report, report);

test("点赞评论关注发送分享收藏下载在每轮和汇总均为零", async () => {
  for (const nodeType of [
    "like",
    "comment",
    "follow",
    "send",
    "share",
    "favorite",
    "download",
  ]) {
    const report = makeReport();
    report.runs[0].protectedNodeCommits[nodeType] = 1;
    report.statistics = makeStatistics(report.runs);
    assert.notDeepEqual(await validate(report), [], nodeType);
  }
});

test("报告拒绝任意 package、APK、action、坐标、secret、路径和 raw content", async () => {
  const cases = [
    ["packageName", "com.example.other"],
    ["observationPackage", "com.example.other"],
    ["apkPath", "/private/tmp/douyin.apk"],
    ["action", { type: "tap", x: 100, y: 200 }],
    ["coordinates", { x: 100, y: 200 }],
    ["secret", "private-token"],
    ["path", "artifacts/screenshot.png"],
    ["url", "https://example.invalid/douyin"],
    ["rawHierarchy", "<node text='private'/>"],
    ["screenshot", "base64-private-content"],
    ["ocrText", "private content"],
    ["message", "raw exception"],
  ];

  for (const [field, value] of cases) {
    const report = makeReport();
    report.runs[0][field] = value;
    const errors = await validate(report);
    assert.equal(
      errors.some((error) => error.includes(field)),
      true,
      field,
    );
  }
});

test("陈旧统计被 runs 重算结果拒绝", async () => {
  const report = makeReport();
  report.statistics.fixtureRuns = 0;
  report.statistics.realRuns = 1;
  report.statistics.classificationCounts["content-change-verified"] = 0;
  report.statistics.verifiedContentChanges = 0;
  report.statistics.unknownCommitRuns = 1;

  const errors = await validate(report);
  for (const fragment of [
    "statistics.fixtureRuns",
    "statistics.realRuns",
    "statistics.classificationCounts",
    "statistics.verifiedContentChanges",
    "statistics.unknownCommitRuns",
  ]) {
    assert.equal(errors.some((error) => error.includes(fragment)), true);
  }
});

test("畸形输入失败关闭且不回显原始值", async () => {
  const malformed = [
    null,
    [],
    {},
    { runs: [null], statistics: null },
    { runs: [{ secret: "private-token" }], statistics: {} },
  ];

  for (const report of malformed) {
    let errors;
    await assert.doesNotReject(async () => {
      errors = await validate(report);
    });
    assert.ok(errors.length > 0);
    assert.equal(errors.every((error) => typeof error === "string"), true);
    assert.equal(errors.some((error) => error.includes("private-token")), false);
  }
});
