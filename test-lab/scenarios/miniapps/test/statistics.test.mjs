// 测试用途：验证报告统计由样例内容重算，并确保 N42/N43 fixture 不计入真实宿主证据。
import assert from "node:assert/strict";
import test from "node:test";

import {
  loadMiniappReportSchema,
  recomputeMiniappStatistics,
  validateMiniappReport,
} from "../src/report-validator.mjs";
import {
  expectedDecision,
  makeFixtureSample,
  makeProbe,
  makeReport,
  makeSample,
} from "./helpers.mjs";

const validate = async (report) =>
  validateMiniappReport(await loadMiniappReportSchema(), report);

test("fixture 参与契约分类但不计入微信或支付宝真实样例", async () => {
  const fixture = makeFixtureSample();
  const real = makeSample({
    sampleId: "sample-weixin-contract-canvas-v1",
    probes: [makeProbe("visual-candidate")],
    decision: expectedDecision("visual-only"),
  });
  for (const probe of real.probes) probe.binding.sampleId = real.sampleId;
  const report = makeReport([fixture, real]);

  assert.deepEqual(recomputeMiniappStatistics(report.samples), report.statistics);
  assert.deepEqual(report.statistics.realHostCounts, {
    weixin: 1,
    alipay: 0,
  });
  assert.deepEqual(report.statistics.realClassificationCounts, {
    "full-semantic": 0,
    hybrid: 0,
    "visual-only": 1,
    unsupported: 0,
  });
  assert.deepEqual(await validate(report), []);
});

test("validator 拒绝陈旧总数、宿主数、分类数与 fail-closed 数", async () => {
  const report = makeReport();
  report.statistics.totalSamples = 2;
  report.statistics.realHostCounts.weixin = 9;
  report.statistics.classificationCounts["full-semantic"] = 0;
  report.statistics.realClassificationCounts["full-semantic"] = 0;
  report.statistics.failClosedSamples = 1;

  const errors = await validate(report);
  for (const fragment of [
    "statistics.totalSamples",
    "statistics.realHostCounts",
    "statistics.classificationCounts",
    "statistics.realClassificationCounts",
    "statistics.failClosedSamples",
  ]) {
    assert.equal(errors.some((error) => error.includes(fragment)), true, fragment);
  }
});

test("fixture 伪装为 real 或重复 sample identity 会被拒绝", async () => {
  const fixture = makeFixtureSample();
  const disguised = makeReport([fixture]);
  disguised.samples[0].source = "real";

  assert.equal(
    (await validate(disguised)).some((error) =>
      error.includes("source does not match host kind")),
    true,
  );

  const duplicate = makeReport([makeSample(), makeSample()]);
  assert.equal(
    (await validate(duplicate)).some((error) =>
      error.includes("duplicate sample identity")),
    true,
  );
});

test("宿主 platform/package 错配与重复 probe ID 会被拒绝", async () => {
  const mismatched = makeReport();
  mismatched.samples[0].host.packageName = "com.eg.android.AlipayGphone";
  for (const probe of mismatched.samples[0].probes) {
    probe.binding.hostPackage = "com.eg.android.AlipayGphone";
  }
  assert.equal(
    (await validate(mismatched)).some((error) =>
      error.includes("platform and package identity mismatch")),
    true,
  );

  const duplicateProbe = makeReport();
  duplicateProbe.samples[0].probes[1].probeId =
    duplicateProbe.samples[0].probes[0].probeId;
  assert.equal(
    (await validate(duplicateProbe)).some((error) =>
      error.includes("duplicate probe ID")),
    true,
  );
});
