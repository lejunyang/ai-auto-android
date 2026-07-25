// 测试用途：构造固定失败输入与受控暂存目录，验证产物收集不会依赖真实设备或公网。
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

export const RUN_ID = "018f47a2-4bc8-7f31-8b9a-1234567890ab";
export const DEVICE_FINGERPRINT = "a".repeat(64);
export const SECRETS = {
  password: "CorrectHorseBatteryStaple",
  otp: "654321",
  token: "header-token-ABC123",
  pairingCode: "731945",
  apiKey: "sk-test-1234567890abcdef",
  targetText: "private account balance",
};

export const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

export const sha256 = (value) =>
  createHash("sha256").update(value).digest("hex");

const trustedScreenshotContext = Object.freeze({
  maskingRunId: RUN_ID,
  processorId: "android-ui-masker-v1",
  artifactSha256: sha256(PNG),
});

export const trustedScreenshotVerifier = async (content, proof) =>
  trustedScreenshotContext.maskingRunId === RUN_ID
  && proof.processorId === trustedScreenshotContext.processorId
  && proof.artifactSha256 === trustedScreenshotContext.artifactSha256
  && sha256(content) === trustedScreenshotContext.artifactSha256;

export const makeRun = ({
  scenarioId = "fixture-timeout",
  iteration = 1,
  errorCode = "SCENARIO_TIMEOUT",
  finishedAt = "2026-07-25T12:00:30.000Z",
} = {}) => ({
  schemaVersion: "1.0",
  runId: RUN_ID,
  scenarioId,
  iteration,
  startedAt: "2026-07-25T12:00:00.000Z",
  finishedAt,
  device: {
    serial: "emulator-5554",
    avdName: "ai-auto-api-33",
    fingerprint: DEVICE_FINGERPRINT,
    apiLevel: 33,
    androidVersion: "13",
    abi: "arm64-v8a",
    locale: "zh-CN",
    resolution: {
      widthPx: 1080,
      heightPx: 2400,
      densityDpi: 420,
    },
    webView: {
      packageName: "com.google.android.webview",
      version: "109.0.5414.123",
    },
  },
  timeline: [
    {
      sequence: 1,
      at: "2026-07-25T12:00:01.000Z",
      type: "ui.click",
      phase: "execute",
      status: "passed",
      durationMs: 50,
      targetPackage: "dev.aiauto.fixture",
    },
    {
      sequence: 2,
      at: "2026-07-25T12:00:30.000Z",
      type: "ui.assert",
      phase: "verify",
      status: "failed",
      durationMs: 29_000,
      targetPackage: "dev.aiauto.fixture",
      errorCode,
    },
  ],
  outcome: {
    status: "failed",
    errorCode,
    durationMs: 30_000,
    retries: 1,
  },
});

export const makeStaging = async (artifactRoot, run, overrides = {}) => {
  const stagingDir = path.join(
    artifactRoot,
    ".staging",
    run.runId,
    run.scenarioId,
  );
  await mkdir(stagingDir, { recursive: true });

  const encodedTarget = Buffer.from(SECRETS.targetText).toString("base64");
  const nestedToken = Buffer.from(
    JSON.stringify({ accessToken: SECRETS.token }),
  ).toString("base64");
  const files = {
    screenshot: PNG,
    hierarchy: Buffer.from(
      `<hierarchy><node password="true" text="${SECRETS.password}" />`
      + `<node text="Verification Code: ${SECRETS.otp}" />`
      + `<node content-desc="${SECRETS.targetText.toUpperCase()}" /></hierarchy>`,
    ),
    log: Buffer.from(
      `Authorization: Bearer ${SECRETS.token}\n`
      + `pairing_code=${SECRETS.pairingCode}\n`
      + `apiKey=${SECRETS.apiKey}\n`
      + `encoded=${nestedToken}\n`,
    ),
    report: Buffer.from(
      JSON.stringify({
        result: "failed",
        password: SECRETS.password,
        nested: {
          OTP: SECRETS.otp,
          encodedTarget,
        },
      }),
    ),
    ...overrides,
  };

  for (const [name, content] of Object.entries(files)) {
    await writeFile(path.join(stagingDir, `${name}.input`), content, {
      mode: 0o600,
    });
  }

  return {
    stagingDir,
    sources: {
      screenshot: {
        file: "screenshot.input",
        proof: {
          method: "verified-masked",
          processorId: "android-ui-masker-v1",
          artifactSha256: sha256(files.screenshot),
        },
      },
      hierarchy: { file: "hierarchy.input" },
      log: {
        file: "log.input",
        windowStartedAt: run.startedAt,
        windowEndedAt: run.finishedAt,
      },
      report: { file: "report.input" },
    },
  };
};

export const sensitivity = {
  declaration: "complete",
  values: Object.values(SECRETS),
  targetPackages: ["dev.aiauto.fixture"],
};
