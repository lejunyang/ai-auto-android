// 测试用途：验证小程序探针内容确定性推导四级分类，并对低置信度与身份漂移失败关闭。
import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyMiniappSample,
  loadMiniappReportSchema,
  validateMiniappReport,
} from "../src/report-validator.mjs";
import {
  expectedDecision,
  makeProbe,
  makeReport,
  makeSample,
  PAGE_FINGERPRINT,
} from "./helpers.mjs";

const validate = async (report) =>
  validateMiniappReport(await loadMiniappReportSchema(), report);

test("探针内容重算 full-semantic、hybrid、visual-only 与 unsupported", async () => {
  const samples = [
    makeSample(),
    makeSample({
      sampleId: "sample-weixin-contract-list-v1",
      probes: [
        makeProbe("semantic-click"),
        makeProbe("semantic-scroll"),
        makeProbe("visual-candidate"),
      ],
      decision: expectedDecision("hybrid"),
    }),
    makeSample({
      sampleId: "sample-weixin-contract-canvas-v1",
      probes: [makeProbe("visual-candidate")],
      decision: expectedDecision("visual-only"),
    }),
    makeSample({
      sampleId: "sample-weixin-contract-blocked-v1",
      probes: [
        makeProbe("semantic-click", {
          status: "unavailable",
          actionCommits: 0,
        }),
      ],
      decision: expectedDecision("unsupported", ["NO_VERIFIED_ROUTE"]),
    }),
  ];
  for (const sample of samples) {
    for (const probe of sample.probes) probe.binding.sampleId = sample.sampleId;
  }
  const report = makeReport(samples);

  assert.deepEqual(
    samples.map((sample) => classifyMiniappSample(sample).classification),
    ["full-semantic", "hybrid", "visual-only", "unsupported"],
  );
  assert.deepEqual(await validate(report), []);
});

test("低置信度探针必须零提交并强制 unsupported", async () => {
  const sample = makeSample();
  sample.probes[0].confidence = "low";
  sample.probes[0].actionCommits = 0;
  sample.decision = expectedDecision(
    "unsupported",
    ["LOW_CONFIDENCE_PROBE"],
  );
  sample.decision.semanticVerifiedCount = 2;
  const report = makeReport([sample]);

  assert.deepEqual(classifyMiniappSample(sample), sample.decision);
  assert.deepEqual(await validate(report), []);

  const committed = structuredClone(report);
  committed.samples[0].probes[0].actionCommits = 1;
  assert.equal(
    (await validate(committed)).some((error) =>
      error.includes("low-confidence probe committed action")),
    true,
  );
});

test("未知页面与 sample、宿主、页面 fingerprint 漂移均失败关闭", async () => {
  const cases = [
    {
      mutate(sample) {
        sample.page.state = "unknown";
      },
      reason: "PAGE_UNKNOWN",
    },
    {
      mutate(sample) {
        sample.probes[0].binding.sampleId = "sample-drifted";
      },
      reason: "SAMPLE_IDENTITY_DRIFT",
    },
    {
      mutate(sample) {
        sample.probes[0].binding.hostVersionCode += 1;
      },
      reason: "HOST_IDENTITY_DRIFT",
    },
    {
      mutate(sample) {
        sample.probes[0].binding.pageFingerprintSha256 =
          PAGE_FINGERPRINT.replace(/^a/u, "d");
      },
      reason: "PAGE_IDENTITY_DRIFT",
    },
  ];

  for (const { mutate, reason } of cases) {
    const sample = makeSample();
    mutate(sample);
    const decision = classifyMiniappSample(sample);
    assert.equal(decision.classification, "unsupported");
    assert.equal(decision.failClosed, true);
    assert.equal(decision.reasonCodes.includes(reason), true);
  }
});

test("重复 probe 类型按唯一语义能力计数并失败关闭", async () => {
  const sample = makeSample();
  sample.probes[1].probeId = "probe-semantic-click-second";
  sample.probes[1].type = "semantic-click";
  sample.decision = classifyMiniappSample(sample);
  const report = makeReport([sample]);

  assert.deepEqual(sample.decision, {
    classification: "unsupported",
    reasonCodes: ["DUPLICATE_PROBE_TYPE"],
    semanticVerifiedCount: 2,
    visualVerified: false,
    failClosed: true,
  });
  assert.deepEqual(await validate(report), []);
});

test("报告中的陈旧分类会被内容重算结果拒绝", async () => {
  const report = makeReport();
  report.samples[0].decision.classification = "visual-only";
  report.samples[0].decision.semanticVerifiedCount = 0;
  report.samples[0].decision.visualVerified = true;

  const errors = await validate(report);
  assert.equal(
    errors.some((error) => error.includes("decision does not match probes")),
    true,
  );
});

test("支付宝宿主 package/version 与页面身份可以独立通过契约", async () => {
  const alipayHost = {
    kind: "real",
    platform: "alipay",
    packageName: "com.eg.android.AlipayGphone",
    versionName: "12.12.10.8000",
    versionCode: 212110,
  };
  const sample = makeSample({
    sampleId: "sample-alipay-contract-home-v1",
    sampleHost: alipayHost,
  });
  for (const probe of sample.probes) {
    probe.binding.sampleId = sample.sampleId;
    probe.binding.hostPackage = alipayHost.packageName;
    probe.binding.hostVersionName = alipayHost.versionName;
    probe.binding.hostVersionCode = alipayHost.versionCode;
  }
  const report = makeReport([sample]);
  report.statistics.realHostCounts = { weixin: 0, alipay: 1 };

  assert.deepEqual(await validate(report), []);
});
