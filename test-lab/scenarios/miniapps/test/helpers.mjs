// 测试用途：构造受控小程序探索报告，覆盖四级分类、fixture 排除与身份漂移回归场景。
export const SAMPLE_ID = "sample-weixin-contract-home-v1";
export const PAGE_ID = "page-contract-home-v1";
export const PAGE_FINGERPRINT =
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
export const HIERARCHY_SHA256 =
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
export const SCREENSHOT_SHA256 =
  "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

const host = {
  kind: "real",
  platform: "weixin",
  packageName: "com.tencent.mm",
  versionName: "8.0.76",
  versionCode: 3141,
};

const page = {
  pageId: PAGE_ID,
  sampleIdentity: "contract-page-revision-1",
  state: "known",
  fingerprintSha256: PAGE_FINGERPRINT,
};

const binding = () => ({
  sampleId: SAMPLE_ID,
  hostPackage: host.packageName,
  hostVersionName: host.versionName,
  hostVersionCode: host.versionCode,
  pageId: page.pageId,
  pageSampleIdentity: page.sampleIdentity,
  pageFingerprintSha256: page.fingerprintSha256,
});

const probe = (type, overrides = {}) => ({
  probeId: `probe-${type}`,
  type,
  confidence: "high",
  status: "verified",
  actionCommits: 1,
  binding: binding(),
  ...overrides,
});

const summaries = () => ({
  hierarchy: {
    sha256: HIERARCHY_SHA256,
    byteCount: 4096,
    retainedBytes: 0,
    nodeCount: 12,
    textNodeCount: 4,
    actionableNodeCount: 3,
    webViewNodeCount: 1,
    canvasNodeCount: 0,
    truncated: false,
  },
  screenshot: {
    sha256: SCREENSHOT_SHA256,
    byteCount: 8192,
    width: 1080,
    height: 1920,
    retainedBytes: 0,
    truncated: false,
  },
});

export const expectedDecision = (
  classification = "full-semantic",
  reasonCodes = [],
) => ({
  classification,
  reasonCodes,
  semanticVerifiedCount:
    classification === "full-semantic" ? 3 : classification === "hybrid" ? 2 : 0,
  visualVerified: classification === "hybrid" || classification === "visual-only",
  failClosed: classification === "unsupported",
});

export const makeSample = ({
  sampleId = SAMPLE_ID,
  sampleHost = host,
  samplePage = page,
  probes = [
    probe("semantic-click"),
    probe("semantic-input"),
    probe("semantic-scroll"),
  ],
  decision = expectedDecision(),
} = {}) => ({
  sampleId,
  source: sampleHost.kind,
  host: structuredClone(sampleHost),
  page: structuredClone(samplePage),
  observations: summaries(),
  probes: structuredClone(probes),
  decision: structuredClone(decision),
});

export const makeReport = (samples = [makeSample()]) => ({
  schemaVersion: "1.0",
  reportId: "miniapp-exploration-contract-1",
  samples: structuredClone(samples),
  statistics: {
    totalSamples: samples.length,
    fixtureSamples: samples.filter((sample) => sample.source === "fixture").length,
    realSamples: samples.filter((sample) => sample.source === "real").length,
    realHostCounts: {
      weixin: samples.filter(
        (sample) => sample.source === "real" && sample.host.platform === "weixin",
      ).length,
      alipay: samples.filter(
        (sample) => sample.source === "real" && sample.host.platform === "alipay",
      ).length,
    },
    classificationCounts: {
      "full-semantic": samples.filter(
        (sample) => sample.decision.classification === "full-semantic",
      ).length,
      hybrid: samples.filter(
        (sample) => sample.decision.classification === "hybrid",
      ).length,
      "visual-only": samples.filter(
        (sample) => sample.decision.classification === "visual-only",
      ).length,
      unsupported: samples.filter(
        (sample) => sample.decision.classification === "unsupported",
      ).length,
    },
    realClassificationCounts: {
      "full-semantic": samples.filter(
        (sample) =>
          sample.source === "real"
          && sample.decision.classification === "full-semantic",
      ).length,
      hybrid: samples.filter(
        (sample) =>
          sample.source === "real"
          && sample.decision.classification === "hybrid",
      ).length,
      "visual-only": samples.filter(
        (sample) =>
          sample.source === "real"
          && sample.decision.classification === "visual-only",
      ).length,
      unsupported: samples.filter(
        (sample) =>
          sample.source === "real"
          && sample.decision.classification === "unsupported",
      ).length,
    },
    failClosedSamples: samples.filter((sample) => sample.decision.failClosed).length,
  },
});

export const makeProbe = probe;

export const makeFixtureSample = () => {
  const fixtureHost = {
    kind: "fixture",
    platform: "n42-n43-web-fixture",
    packageName: "dev.aiauto.webfixture",
    versionName: "1.0",
    versionCode: 1,
    fixtureSource: "N42_N43",
  };
  const fixture = makeSample({
    sampleId: "fixture-n42-n43-full-semantic",
    sampleHost: fixtureHost,
  });
  for (const item of fixture.probes) {
    item.binding.sampleId = fixture.sampleId;
    item.binding.hostPackage = fixtureHost.packageName;
    item.binding.hostVersionName = fixtureHost.versionName;
    item.binding.hostVersionCode = fixtureHost.versionCode;
  }
  return fixture;
};
