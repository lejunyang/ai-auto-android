// 脚本用途：测试 N43 矩阵固定二十轮 clean restore、JUnit 分类、成功率门和安全清理。
import assert from "node:assert/strict";
import test from "node:test";

import {
  matrixEnvironment,
  parseJUnitResult,
  parseMatrixArguments,
  runWebViewSemanticMatrix,
} from "./run.mjs";

const root = "/Volumes/aigo S7 Media/SDK/android-tools";
const environment = Object.freeze({
  ANDROID_SDK_ROOT: `${root}/android-sdk`,
  ANDROID_AVD_HOME: `${root}/android-avd`,
  JAVA_HOME: `${root}/jdk/Contents/Home`,
  AACTL_EMULATOR_STATE: `${root}/emulator-state`,
  GOCACHE: `${root}/go-cache`,
  GOMODCACHE: `${root}/go-mod-cache`,
});
const className = "dev.aiauto.webfixture.N43SemanticReplayDeviceTest";

const junit = ({
  failures = 0,
  errors = 0,
  skipped = 0,
  duration = "1.25",
} = {}) => `<?xml version='1.0' encoding='UTF-8'?>
<testsuites tests="1" failures="${failures}" errors="${errors}" skipped="${skipped}">
  <testsuite name="${className}" tests="1" failures="${failures}" errors="${errors}" skipped="${skipped}" time="${duration}">
    <testcase name="reportSemanticCoverageAndHybridBoundaries" classname="${className}" time="${duration}" />
  </testsuite>
</testsuites>`;

const fixture = ({
  failures = 0,
  driftAt = null,
  stopRetainedOnce = false,
} = {}) => {
  const calls = [];
  let online = false;
  let stopCalls = 0;
  let junitRound = 0;
  const deviceEnvelope = (devices) => ({
    code: 0,
    stdout: `${JSON.stringify({
      schemaVersion: "1.0",
      requestId: "00000000-0000-4000-8000-000000000000",
      ok: true,
      data: { devices, count: devices.length },
      error: null,
      meta: { durationMs: 1 },
    })}\n`,
    stderr: "",
  });
  const value = {
    loadProfiles: async () => ({
      profiles: [{
        id: "api-34",
        apiLevel: 34,
        avdName: "ai-auto-api-34",
      }],
    }),
    emulator: {
      start: async () => {
        calls.push(["start"]);
        online = true;
        return { serial: "emulator-5554" };
      },
      waitForBoot: async () => {
        calls.push(["wait"]);
        return {
          serial: "emulator-5554",
          profileId: "api-34",
          state: "booted",
          deviceFingerprint: "a".repeat(64),
        };
      },
      restore: async () => {
        const restoreIndex = calls.filter(([name]) => name === "restore").length + 1;
        calls.push(["restore", restoreIndex]);
        return {
          serial: driftAt === restoreIndex ? "emulator-5556" : "emulator-5554",
          profileId: "api-34",
          state: "booted",
          deviceFingerprint: "a".repeat(64),
        };
      },
      stop: async () => {
        calls.push(["stop"]);
        stopCalls += 1;
        if (stopRetainedOnce && stopCalls === 1) {
          const error = new Error("retained");
          error.code = "STOP_FAILED_LOCK_RETAINED";
          throw error;
        }
        online = false;
      },
    },
    command: async (executable, args) => {
      calls.push(["command", executable, [...args]]);
      if (args[0] === "devices") {
        return deviceEnvelope(online ? [{
          serial: "emulator-5554",
          state: "device",
          transport: "emulator",
        }] : []);
      }
      if (executable === "go") return { code: 0, stdout: "", stderr: "" };
      if (args.includes(":web-fixture:assembleDebug")) {
        return { code: 0, stdout: "", stderr: "" };
      }
      if (args.includes(":web-fixture:connectedDebugAndroidTest")) {
        const next = junitRound + 1;
        return {
          code: next <= failures ? 1 : 0,
          stdout: "",
          stderr: next <= failures ? "private stack" : "",
        };
      }
      throw new Error(`unexpected command ${executable} ${args.join(" ")}`);
    },
    cleanResults: async () => calls.push(["clean-results"]),
    readFreshJUnit: async () => {
      junitRound += 1;
      return junit({ failures: junitRound <= failures ? 1 : 0 });
    },
    createTemporaryRoot: async () => "/private/tmp/n43-matrix-test",
    removeTemporaryRoot: async (temporary) => calls.push(["remove", temporary]),
    persistReport: async (report) => calls.push(["report", report]),
  };
  return { calls, value };
};

test("只接受一个固定 profile 和统一外置环境", () => {
  assert.deepEqual(parseMatrixArguments(["--profile", "api-30"]), {
    profile: "api-30",
  });
  for (const argv of [
    [],
    ["--profile", "api-35"],
    ["--profile", "api-30", "--rounds", "20"],
    ["--serial", "emulator-5554"],
  ]) {
    assert.throws(
      () => parseMatrixArguments(argv),
      (error) => error.code === "USAGE_ERROR",
    );
  }
  assert.equal(matrixEnvironment(environment).stateRoot, `${root}/emulator-state`);
  assert.throws(
    () => matrixEnvironment({ ...environment, GOCACHE: "/tmp/go" }),
    (error) => error.code === "ENVIRONMENT_INVALID",
  );
});

test("严格解析唯一 N43 JUnit 结果且不返回失败 stack", () => {
  assert.deepEqual(parseJUnitResult(junit()), {
    status: "passed",
    durationMs: 1250,
    tests: 1,
    failures: 0,
    errors: 0,
    skipped: 0,
  });
  assert.deepEqual(parseJUnitResult(junit({ failures: 1 })), {
    status: "product-failure",
    durationMs: 1250,
    tests: 1,
    failures: 1,
    errors: 0,
    skipped: 0,
  });
  for (const invalid of [
    "<testsuite/>",
    junit().replace('classname="' + className + '"', 'classname="wrong.Class"'),
    junit().replace('tests="1"', 'tests="2"'),
    `${junit()}<testcase name="extra" classname="${className}"/>`,
  ]) {
    assert.throws(
      () => parseJUnitResult(invalid),
      (error) => error.code === "JUNIT_RESULT_INVALID",
    );
  }
});

test("十九轮通过恰好达到 95% 且每轮先 restore clean", async () => {
  const { calls, value } = fixture({ failures: 1, stopRetainedOnce: true });
  const report = await runWebViewSemanticMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });
  assert.equal(report.rounds.length, 20);
  assert.equal(report.passed, 19);
  assert.equal(report.failed, 1);
  assert.equal(report.successRate, 0.95);
  assert.equal(report.thresholdMet, true);
  assert.equal(
    calls.filter(([name]) => name === "restore").length,
    21,
  );
  assert.equal(
    calls.filter(
      ([name, , args]) =>
        name === "command"
        && args?.includes(":web-fixture:connectedDebugAndroidTest"),
    ).length,
    20,
  );
  assert.equal(calls.filter(([name]) => name === "clean-results").length, 20);
  assert.equal(calls.filter(([name]) => name === "stop").length, 2);
  const serialized = JSON.stringify(report);
  for (const forbidden of ["private stack", "stdout", "stderr", "xml", "hierarchy"]) {
    assert.equal(serialized.includes(forbidden), false);
  }
});

test("十八轮通过不达 95% 门且仍完成全部二十轮", async () => {
  const { calls, value } = fixture({ failures: 2 });
  const report = await runWebViewSemanticMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });
  assert.equal(report.rounds.length, 20);
  assert.equal(report.passed, 18);
  assert.equal(report.successRate, 0.9);
  assert.equal(report.thresholdMet, false);
  assert.equal(calls.filter(([name]) => name === "report").length, 1);
});

test("restore serial 漂移立即停止后续轮次并执行 finally stop", async () => {
  const { calls, value } = fixture({ driftAt: 2 });
  await assert.rejects(
    () => runWebViewSemanticMatrix({
      argv: ["--profile", "api-34"],
      environment,
      dependencies: value,
    }),
    (error) => error.code === "RUNNER_CONTEXT_DRIFT",
  );
  assert.equal(
    calls.filter(
      ([name, , args]) =>
        name === "command"
        && args?.includes(":web-fixture:connectedDebugAndroidTest"),
    ).length,
    1,
  );
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
  assert.equal(calls.filter(([name]) => name === "report").length, 0);
});
