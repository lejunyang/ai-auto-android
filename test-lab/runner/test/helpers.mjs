// 测试用途：构造确定性场景、观察和类型化端口，验证 Runner 安全门而不依赖设备或 APK。
import { readFile } from "node:fs/promises";
import path from "node:path";

export const RUN_ID = "018f47a2-4bc8-7f31-8b9a-1234567890ab";
export const NATIVE_PACKAGE = "dev.aiauto.fixture";
export const WEB_PACKAGE = "dev.aiauto.webfixture";
export const SECRET_INPUT = "fixture private input";

export const DEVICE = Object.freeze({
  serial: "emulator-5554",
  avdName: "ai-auto-api-33",
  fingerprint: "a".repeat(64),
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
});

const fixtureDirectory = path.resolve(import.meta.dirname, "..", "fixtures");

export const loadFixture = async (name) =>
  JSON.parse(await readFile(path.join(fixtureDirectory, name), "utf8"));

export const oneStepScenario = ({
  action = {
    type: "tap",
    target: {
      kind: "semantic-name",
      name: "Fixture click target",
    },
  },
  acceptedPageClasses = ["normal"],
  allowedRoutes = ["semantic"],
  minimumScore = 0.9,
  targetPackage = NATIVE_PACKAGE,
} = {}) => ({
  schemaVersion: "1.0",
  id: "fixture-one-step",
  title: "Fixture one step",
  sideEffectPolicy: "none",
  targetPackages: [NATIVE_PACKAGE, WEB_PACKAGE],
  defaultTimeoutMs: 3000,
  steps: [
    {
      id: "fixture-step",
      targetPackage,
      preconditions: [
        {
          id: "pre-ready",
          kind: "semantic-state",
        },
      ],
      action,
      postconditions: [
        {
          id: "post-complete",
          kind: "semantic-state",
        },
      ],
      timeoutMs: 3000,
      allowedRoutes,
      minimumScore,
      acceptedPageClasses,
      dynamicRegions: [],
    },
  ],
});

export const deterministicClock = () => {
  let value = Date.parse("2026-07-25T12:00:00.000Z");
  return {
    now: () => new Date(value).toISOString(),
    advance: (milliseconds = 10) => {
      value += milliseconds;
    },
  };
};

export const fakePorts = ({
  pageClass = "normal",
  route = "semantic",
  score = 1,
  routeAttempts = 1,
  prePackage = NATIVE_PACKAGE,
  postPackage = NATIVE_PACKAGE,
  preconditionsPass = true,
  postconditionsPass = true,
  failCleanup = null,
  executorFailure = null,
} = {}) => {
  const calls = [];
  const observations = [
    {
      observationId: "observation-pre",
      observedAt: "2026-07-25T12:00:00.000Z",
      expiresAt: "2026-07-25T12:00:30.000Z",
      foregroundPackage: prePackage,
      pageClass,
    },
    {
      observationId: "observation-post",
      observedAt: "2026-07-25T12:00:00.000Z",
      expiresAt: "2026-07-25T12:00:30.000Z",
      foregroundPackage: postPackage,
      pageClass: "normal",
    },
  ];
  let observationIndex = 0;
  const lifecycleCall = async (name) => {
    calls.push(name);
    if (failCleanup === name) throw new Error(`private-${name}`);
  };
  return {
    calls,
    ports: {
      observer: {
        observe: async () => {
          calls.push("observe");
          const result = observations[Math.min(observationIndex, 1)];
          observationIndex += 1;
          return result;
        },
      },
      conditions: {
        verify: async ({ phase }) => {
          calls.push(`verify:${phase}`);
          return phase === "pre" ? preconditionsPass : postconditionsPass;
        },
      },
      router: {
        resolve: async () => {
          calls.push("route");
          return {
            route,
            score,
            attempts: routeAttempts,
            observationId: "observation-pre",
            token: Object.freeze({ privateRouteToken: true }),
          };
        },
      },
      executor: {
        execute: async ({ value }) => {
          calls.push("execute");
          if (executorFailure !== null) throw new Error(executorFailure);
          if (value !== undefined && typeof value !== "string") {
            throw new Error("private-invalid-value");
          }
          return { committed: true };
        },
      },
      values: {
        resolve: async (valueRef) => {
          calls.push(`resolve:${valueRef}`);
          return SECRET_INPUT;
        },
      },
      artifacts: {
        collect: async ({ run }) => {
          calls.push("collect");
          return {
            retained: run.outcome.status === "failed",
            errorCode: run.outcome.errorCode,
          };
        },
      },
      lifecycle: {
        stopScenario: async () => lifecycleCall("stopScenario"),
        closeBridge: async () => lifecycleCall("closeBridge"),
        clearAppData: async () => lifecycleCall("clearAppData"),
        restoreSnapshot: async () => lifecycleCall("restoreSnapshot"),
      },
    },
  };
};
