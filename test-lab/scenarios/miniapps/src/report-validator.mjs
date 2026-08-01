// 功能用途：严格验证小程序探索报告，并从受控探针重算四级分类和真实宿主统计。
import { readFile } from "node:fs/promises";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import {
  validateRunnerDocument,
} from "../../../runner/src/schema-validator.mjs";

const semanticProbeTypes = new Set([
  "semantic-click",
  "semantic-input",
  "semantic-scroll",
]);
const classifications = [
  "full-semantic",
  "hybrid",
  "visual-only",
  "unsupported",
];
const realPlatforms = ["weixin", "alipay"];

const sameJson = (left, right) => isDeepStrictEqual(left, right);

const isRecord = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const verified = (probe) =>
  probe?.confidence === "high"
  && probe?.status === "verified"
  && probe?.actionCommits === 1;

const bindingReasons = (sample, probe) => {
  const reasons = [];
  const binding = probe?.binding;
  if (!isRecord(binding)) return reasons;
  if (binding.sampleId !== sample.sampleId) {
    reasons.push("SAMPLE_IDENTITY_DRIFT");
  }
  if (
    binding.hostPackage !== sample.host?.packageName
    || binding.hostVersionName !== sample.host?.versionName
    || binding.hostVersionCode !== sample.host?.versionCode
  ) {
    reasons.push("HOST_IDENTITY_DRIFT");
  }
  if (
    binding.pageId !== sample.page?.pageId
    || binding.pageSampleIdentity !== sample.page?.sampleIdentity
    || binding.pageFingerprintSha256 !== sample.page?.fingerprintSha256
  ) {
    reasons.push("PAGE_IDENTITY_DRIFT");
  }
  return reasons;
};

export const classifyMiniappSample = (sample) => {
  if (!isRecord(sample)) {
    return {
      classification: "unsupported",
      reasonCodes: ["INSUFFICIENT_ROUTE_EVIDENCE"],
      semanticVerifiedCount: 0,
      visualVerified: false,
      failClosed: true,
    };
  }
  const probes = Array.isArray(sample.probes) ? sample.probes : [];
  const reasons = [];
  if (sample.page?.state !== "known") reasons.push("PAGE_UNKNOWN");
  if (probes.some((probe) => probe?.confidence === "low")) {
    reasons.push("LOW_CONFIDENCE_PROBE");
  }
  for (const probe of probes) reasons.push(...bindingReasons(sample, probe));
  const probeTypes = probes.map((probe) => probe?.type);
  if (new Set(probeTypes).size !== probeTypes.length) {
    reasons.push("DUPLICATE_PROBE_TYPE");
  }

  const semanticVerifiedCount = new Set(
    probes
      .filter((probe) => semanticProbeTypes.has(probe?.type) && verified(probe))
      .map((probe) => probe.type),
  ).size;
  const visualVerified = probes.some((probe) =>
    probe?.type === "visual-candidate" && verified(probe));
  const failClosedReasons = [...new Set(reasons)].sort();
  if (failClosedReasons.length > 0) {
    return {
      classification: "unsupported",
      reasonCodes: failClosedReasons,
      semanticVerifiedCount,
      visualVerified,
      failClosed: true,
    };
  }

  let classification;
  let reasonCodes = [];
  if (semanticVerifiedCount === 3) {
    classification = "full-semantic";
  } else if (semanticVerifiedCount > 0 && visualVerified) {
    classification = "hybrid";
  } else if (semanticVerifiedCount === 0 && visualVerified) {
    classification = "visual-only";
  } else {
    classification = "unsupported";
    reasonCodes = [
      semanticVerifiedCount === 0
        ? "NO_VERIFIED_ROUTE"
        : "INSUFFICIENT_ROUTE_EVIDENCE",
    ];
  }
  return {
    classification,
    reasonCodes,
    semanticVerifiedCount,
    visualVerified,
    failClosed: classification === "unsupported",
  };
};

const emptyClassificationCounts = () =>
  Object.fromEntries(classifications.map((classification) => [classification, 0]));

export const recomputeMiniappStatistics = (samples) => {
  const safeSamples = Array.isArray(samples) ? samples.filter(isRecord) : [];
  const classificationCounts = emptyClassificationCounts();
  const realClassificationCounts = emptyClassificationCounts();
  const realHostCounts = Object.fromEntries(
    realPlatforms.map((platform) => [platform, 0]),
  );
  let fixtureSamples = 0;
  let realSamples = 0;
  let failClosedSamples = 0;
  for (const sample of safeSamples) {
    const decision = classifyMiniappSample(sample);
    classificationCounts[decision.classification] += 1;
    if (decision.failClosed) failClosedSamples += 1;
    if (sample.source === "fixture") {
      fixtureSamples += 1;
      continue;
    }
    if (sample.source === "real") {
      realSamples += 1;
      if (Object.hasOwn(realHostCounts, sample.host?.platform)) {
        realHostCounts[sample.host.platform] += 1;
      }
      realClassificationCounts[decision.classification] += 1;
    }
  }
  return {
    totalSamples: safeSamples.length,
    fixtureSamples,
    realSamples,
    realHostCounts,
    classificationCounts,
    realClassificationCounts,
    failClosedSamples,
  };
};

const sampleSemanticErrors = (sample, index) => {
  if (!isRecord(sample)) return [];
  const errors = [];
  const prefix = `$.samples[${index}]`;
  if (sample.source !== sample.host?.kind) {
    errors.push(`${prefix}.source: source does not match host kind`);
  }
  if (
    sample.host?.kind === "real"
    && (
      sample.host.platform === "weixin"
        && sample.host.packageName !== "com.tencent.mm"
      || sample.host.platform === "alipay"
        && sample.host.packageName !== "com.eg.android.AlipayGphone"
    )
  ) {
    errors.push(`${prefix}.host: platform and package identity mismatch`);
  }
  if (
    sample.host?.kind === "real"
    && Object.hasOwn(sample.host, "fixtureSource")
  ) {
    errors.push(`${prefix}.host.fixtureSource: real host cannot be fixture`);
  }
  if (
    sample.host?.kind === "fixture"
    && (
      sample.host.platform !== "n42-n43-web-fixture"
      || sample.host.packageName !== "dev.aiauto.webfixture"
      || sample.host.fixtureSource !== "N42_N43"
    )
  ) {
    errors.push(`${prefix}.host: fixture identity is not N42/N43`);
  }
  const hierarchy = sample.observations?.hierarchy;
  if (
    isRecord(hierarchy)
    && Number.isInteger(hierarchy.nodeCount)
    && [
      "textNodeCount",
      "actionableNodeCount",
      "webViewNodeCount",
      "canvasNodeCount",
    ].some((key) =>
      Number.isInteger(hierarchy[key]) && hierarchy[key] > hierarchy.nodeCount)
  ) {
    errors.push(`${prefix}.observations.hierarchy: subset count exceeds nodes`);
  }
  const probes = Array.isArray(sample.probes) ? sample.probes : [];
  const probeIds = new Set();
  for (const [probeIndex, probe] of probes.entries()) {
    if (probeIds.has(probe?.probeId)) {
      errors.push(`${prefix}.probes[${probeIndex}].probeId: duplicate probe ID`);
    }
    probeIds.add(probe?.probeId);
    if (
      probe?.confidence === "low"
      && probe?.actionCommits !== 0
    ) {
      errors.push(
        `${prefix}.probes[${probeIndex}]: low-confidence probe committed action`,
      );
    }
    if (
      probe?.status !== "verified"
      && probe?.actionCommits !== 0
    ) {
      errors.push(
        `${prefix}.probes[${probeIndex}]: unverified probe committed action`,
      );
    }
    if (
      probe?.status === "verified"
      && probe?.confidence === "high"
      && probe?.actionCommits !== 1
    ) {
      errors.push(
        `${prefix}.probes[${probeIndex}]: verified probe has no committed action`,
      );
    }
  }
  const expectedDecision = classifyMiniappSample(sample);
  if (!sameJson(sample.decision, expectedDecision)) {
    errors.push(`${prefix}.decision: decision does not match probes`);
  }
  return errors;
};

const statisticsErrors = (actual, expected) => {
  if (!isRecord(actual)) return [];
  const errors = [];
  for (const key of [
    "totalSamples",
    "fixtureSamples",
    "realSamples",
    "realHostCounts",
    "classificationCounts",
    "realClassificationCounts",
    "failClosedSamples",
  ]) {
    if (!sameJson(actual[key], expected[key])) {
      errors.push(`$.statistics.${key}: does not match recomputed content`);
    }
  }
  return errors;
};

const semanticErrors = (report) => {
  if (!isRecord(report)) return [];
  const errors = [];
  const samples = Array.isArray(report.samples) ? report.samples : [];
  const identities = new Set();
  const sampleIds = new Set();
  for (const [index, sample] of samples.entries()) {
    errors.push(...sampleSemanticErrors(sample, index));
    if (!isRecord(sample)) continue;
    if (sampleIds.has(sample.sampleId)) {
      errors.push(`$.samples[${index}].sampleId: duplicate sample identity`);
    }
    sampleIds.add(sample.sampleId);
    const identity = [
      sample.source,
      sample.host?.packageName,
      sample.host?.versionName,
      sample.host?.versionCode,
      sample.sampleId,
      sample.page?.pageId,
      sample.page?.sampleIdentity,
    ].join(":");
    if (identities.has(identity)) {
      errors.push(`$.samples[${index}]: duplicate sample identity`);
    }
    identities.add(identity);
  }
  errors.push(
    ...statisticsErrors(
      report.statistics,
      recomputeMiniappStatistics(samples),
    ),
  );
  return errors;
};

export const validateMiniappReport = (schema, report) => {
  let schemaErrors;
  try {
    schemaErrors = validateRunnerDocument(schema, report);
  } catch {
    return ["$: miniapp schema validation failed closed"];
  }
  try {
    return [...schemaErrors, ...semanticErrors(report)];
  } catch {
    return [...schemaErrors, "$: miniapp semantic validation failed closed"];
  }
};

export const loadMiniappReportSchema = async () =>
  JSON.parse(
    await readFile(
      path.resolve(
        import.meta.dirname,
        "..",
        "schema",
        "exploration-report.schema.json",
      ),
      "utf8",
    ),
  );
