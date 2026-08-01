// 测试用途：验证固定 production fixture CLI 的能力门、N31 身份绑定和全路径清理。
import assert from "node:assert/strict";
import test from "node:test";

import {
  parseProductionFixtureArguments,
  productionFixtureEnvironment,
  runProductionFixture,
} from "../src/production-fixture-runner.mjs";

const root = "/opt/aiauto-tools";
const environment = Object.freeze({
  AACTL_TOOLCHAIN_ROOT: root,
  ANDROID_SDK_ROOT: `${root}/android-sdk`,
  ANDROID_AVD_HOME: `${root}/android-avd`,
  JAVA_HOME: `${root}/jdk/Contents/Home`,
  AACTL_EMULATOR_STATE: `${root}/emulator-state`,
  GOCACHE: `${root}/go-cache`,
  GOMODCACHE: `${root}/go-mod-cache`,
});
const capabilities = Object.freeze([
  "artifact.n34",
  "bridge.action",
  "fixture.canvas",
  "fixture.native",
  "fixture.webview",
  "lifecycle.n31",
  "residue.eight",
  "test.authorization",
  "visual.attested",
]);
const profile = Object.freeze({ profileId: "api-33", apiLevel: 33 });
const device = Object.freeze({
  profileId: "api-33",
  apiLevel: 33,
  serial: "emulator-5554",
  deviceFingerprint: "a".repeat(64),
  snapshot: "clean",
  capabilities,
  device: Object.freeze({
    serial: "emulator-5554",
    avdName: "ai-auto-api-33",
    fingerprint: "a".repeat(64),
    apiLevel: 33,
    androidVersion: "13",
    abi: "arm64-v8a",
    locale: "zh-CN",
    resolution: Object.freeze({
      widthPx: 1080,
      heightPx: 2400,
      densityDpi: 420,
    }),
    webView: Object.freeze({
      packageName: "com.google.android.webview",
      version: "109.0.5414.123",
    }),
  }),
});
const cleanResidue = () => ({
  appData: 0,
  bridgeSessions: 0,
  testServices: 0,
  screenshots: 0,
  logs: 0,
  ownedProcesses: 0,
  runtimeFiles: 0,
  leases: 0,
});

const fakePorts = ({
  missingCapability = null,
  startOverride = {},
  contextDriftAt = null,
  scenarioStatus = "passed",
  scenarioErrorCode = null,
  scenarioActionCommits = 1,
  residueOverride = {},
} = {}) => {
  const calls = [];
  let contextChecks = 0;
  const available = capabilities.filter((value) => value !== missingCapability);
  return {
    calls,
    ports: {
      capabilities: {
        probe: async (requested) => {
          calls.push(["probe", requested]);
          return {
            profileId: requested.profileId,
            apiLevel: requested.apiLevel,
            capabilities: available,
          };
        },
      },
      lifecycle: {
        startClean: async (requested) => {
          calls.push(["start", requested]);
          return { ...device, ...startOverride, capabilities: available };
        },
        assertContext: async (started) => {
          contextChecks += 1;
          calls.push(["context", started.serial]);
          return contextDriftAt === contextChecks
            ? { ...started, deviceFingerprint: "b".repeat(64) }
            : started;
        },
        stop: async (started) => {
          calls.push(["stop", started.serial]);
          return { stopped: true };
        },
      },
      scenarios: {
        run: async ({ scenarioId, iteration, started }) => {
          calls.push(["scenario", scenarioId, iteration, started.serial]);
          const postObservationId = scenarioActionCommits === 1
            ? "observation-post"
            : null;
          return {
            schemaVersion: "1.0",
            runId: "019fbdf0-0000-7000-8000-000000000047",
            scenarioId,
            iteration,
            startedAt: "2026-08-01T12:00:00.000Z",
            finishedAt: "2026-08-01T12:00:01.000Z",
            status: scenarioStatus,
            errorCode: scenarioErrorCode,
            durationMs: 1000,
            actionCommits: scenarioActionCommits,
            steps: [{
              sequence: 1,
              stepId: "fixture-step",
              actionType: "tap",
              targetPackage: "dev.aiauto.fixture",
              status: scenarioStatus,
              errorCode: scenarioErrorCode,
              pageClass: "normal",
              route: "semantic",
              score: 1,
              attempts: 1,
              preObservationId: "observation-pre",
              postObservationId,
              actionCommits: scenarioActionCommits,
              durationMs: 1000,
            }],
            cleanup: {
              attempted: 4,
              completed: 4,
              errorCode: null,
            },
          };
        },
      },
      residue: {
        inspect: async (started) => {
          calls.push(["residue", started.serial]);
          return { ...cleanResidue(), ...residueOverride };
        },
      },
    },
  };
};

test("CLI 只接受固定 profile，拒绝 serial/package/APK/task/action", () => {
  assert.deepEqual(
    parseProductionFixtureArguments(["--profile", "api-30"]),
    { profile: "api-30" },
  );
  for (const argv of [
    [],
    ["--profile", "api-35"],
    ["--serial", "emulator-5554"],
    ["--profile", "api-33", "--package", "dev.aiauto.fixture"],
    ["--profile", "api-33", "--apk", "/tmp/app.apk"],
    ["--profile", "api-33", "--task", ":app:test"],
    ["--profile", "api-33", "--action", "tap"],
  ]) {
    assert.throws(
      () => parseProductionFixtureArguments(argv),
      (error) => error.code === "USAGE_ERROR",
    );
  }
  assert.equal(
    productionFixtureEnvironment(environment).stateRoot,
    `${root}/emulator-state`,
  );
});

test("默认真实授权 capability 缺失时 N31 启动和动作均为零", async () => {
  const fixture = fakePorts({ missingCapability: "test.authorization" });
  await assert.rejects(
    () => runProductionFixture({
      argv: ["--profile", "api-33"],
      environment,
      ports: fixture.ports,
    }),
    (error) => error.code === "PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE",
  );
  assert.deepEqual(
    fixture.calls.map(([name]) => name),
    ["probe"],
  );
});

test("固定 native WebView Canvas 顺序绑定 serial fingerprint 和 clean snapshot", async () => {
  const fixture = fakePorts();
  const report = await runProductionFixture({
    argv: ["--profile", "api-33"],
    environment,
    ports: fixture.ports,
  });

  assert.deepEqual(
    report.scenarios.map(({ scenarioId }) => scenarioId),
    [
      "production-native-fixture",
      "production-webview-fixture",
      "production-canvas-fixture",
    ],
  );
  assert.equal(report.serial, "emulator-5554");
  assert.equal(report.deviceFingerprint, "a".repeat(64));
  assert.equal(report.snapshot, "clean");
  assert.deepEqual(
    fixture.calls.filter(([name]) => name === "scenario")
      .map(([, scenarioId, iteration]) => [scenarioId, iteration]),
    [
      ["production-native-fixture", 1],
      ["production-webview-fixture", 1],
      ["production-canvas-fixture", 1],
    ],
  );
  assert.equal(
    fixture.calls.filter(([name]) => name === "context").length,
    6,
  );
  assert.deepEqual(fixture.calls.at(-2), ["stop", "emulator-5554"]);
  assert.deepEqual(fixture.calls.at(-1), ["residue", "emulator-5554"]);
  assert.deepEqual(report.residue, cleanResidue());
});

test("serial fingerprint snapshot 漂移和旧结果均失败关闭并停止 owned emulator", async () => {
  for (const startOverride of [
    { serial: "device-user-owned" },
    { deviceFingerprint: "b".repeat(64) },
    { snapshot: "dirty" },
  ]) {
    const fixture = fakePorts({ startOverride });
    await assert.rejects(
      () => runProductionFixture({
        argv: ["--profile", "api-33"],
        environment,
        ports: fixture.ports,
      }),
      (error) => error.code === "PRODUCTION_FIXTURE_START_INVALID",
    );
    assert.equal(
      fixture.calls.some(([name]) => name === "scenario"),
      false,
    );
  }

  const drift = fakePorts({ contextDriftAt: 2 });
  await assert.rejects(
    () => runProductionFixture({
      argv: ["--profile", "api-33"],
      environment,
      ports: drift.ports,
    }),
    (error) => error.code === "PRODUCTION_FIXTURE_CONTEXT_DRIFT",
  );
  assert.equal(
    drift.calls.filter(([name]) => name === "scenario").length,
    1,
  );
  assert.equal(drift.calls.some(([name]) => name === "stop"), true);
  assert.equal(drift.calls.some(([name]) => name === "residue"), true);

  const stale = fakePorts();
  stale.ports.scenarios.run = async ({ scenarioId }) => ({
    schemaVersion: "1.0",
    runId: "019fbdf0-0000-7000-8000-000000000047",
    scenarioId: `${scenarioId}-stale`,
    iteration: 1,
    startedAt: "2026-08-01T12:00:00.000Z",
    finishedAt: "2026-08-01T12:00:00.000Z",
    status: "passed",
    errorCode: null,
    durationMs: 0,
    actionCommits: 0,
    steps: [],
    cleanup: { attempted: 4, completed: 4, errorCode: null },
  });
  await assert.rejects(
    () => runProductionFixture({
      argv: ["--profile", "api-33"],
      environment,
      ports: stale.ports,
    }),
    (error) => error.code === "PRODUCTION_FIXTURE_RESULT_STALE",
  );
});

test("失败取消和 unknown commit 不重试，仍 stop 并检查八类 residue", async () => {
  for (const value of [
    {
      scenarioStatus: "failed",
      scenarioErrorCode: "POSTCONDITION_FAILED",
      scenarioActionCommits: 1,
    },
    {
      scenarioStatus: "cancelled",
      scenarioErrorCode: "SCENARIO_CANCELLED",
      scenarioActionCommits: 0,
    },
    {
      scenarioStatus: "failed",
      scenarioErrorCode: "ACTION_COMMIT_UNKNOWN",
      scenarioActionCommits: null,
    },
  ]) {
    const fixture = fakePorts(value);
    await assert.rejects(
      () => runProductionFixture({
        argv: ["--profile", "api-33"],
        environment,
        ports: fixture.ports,
      }),
      (error) => error.code === value.scenarioErrorCode,
    );
    assert.equal(
      fixture.calls.filter(([name]) => name === "scenario").length,
      1,
    );
    assert.equal(fixture.calls.some(([name]) => name === "stop"), true);
    assert.equal(fixture.calls.some(([name]) => name === "residue"), true);
  }

  const residue = fakePorts({ residueOverride: { bridgeSessions: 1 } });
  await assert.rejects(
    () => runProductionFixture({
      argv: ["--profile", "api-33"],
      environment,
      ports: residue.ports,
    }),
    (error) => error.code === "PRODUCTION_FIXTURE_RESIDUE_DETECTED",
  );
});
