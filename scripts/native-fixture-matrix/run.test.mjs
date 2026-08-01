// 脚本用途：验证原生设备矩阵的固定轮次、真机隔离、结果分类与安全清理契约。
import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  matrixEnvironment,
  parseJUnitResult,
  parseMatrixArguments,
  runNativeFixtureMatrix,
} from "./run.mjs";

const root = path.resolve(path.parse(process.cwd()).root, "aiauto-tools");
const environment = Object.freeze({
  AACTL_TOOLCHAIN_ROOT: root,
  ANDROID_SDK_ROOT: path.join(root, "android-sdk"),
  ANDROID_AVD_HOME: path.join(root, "android-avd"),
  JAVA_HOME: path.join(root, "jdk", "Contents", "Home"),
  AACTL_EMULATOR_STATE: path.join(root, "emulator-state"),
  GOCACHE: path.join(root, "go-cache"),
  GOMODCACHE: path.join(root, "go-mod-cache"),
});
const className = "dev.aiauto.fixture.NativeFixtureDeviceTest";

const junit = ({
  failures = 0,
  errors = 0,
  skipped = 0,
  duration = "1.25",
} = {}) => `<?xml version='1.0' encoding='UTF-8'?>
<testsuites tests="1" failures="${failures}" errors="${errors}" skipped="${skipped}">
  <testsuite name="${className}" tests="1" failures="${failures}" errors="${errors}" skipped="${skipped}" time="${duration}">
    <testcase name="runSelectedFixtureScenario" classname="${className}" time="${duration}" />
  </testsuite>
</testsuites>`;

const fixture = ({
  failures = 0,
  driftAt = null,
  secondEmulator = false,
  stopRetainedOnce = false,
  includePhysicalDevice = true,
} = {}) => {
  const calls = [];
  let online = false;
  let stopCalls = 0;
  let junitRound = 0;
  const deviceEnvelope = () => {
    const devices = [];
    if (includePhysicalDevice) {
      devices.push({
        serial: "physical-device",
        state: "device",
        transport: "usb",
      });
    }
    if (online) {
      devices.push({
        serial: "emulator-5554",
        state: "device",
        transport: "emulator",
      });
      if (secondEmulator) {
        devices.push({
          serial: "emulator-5556",
          state: "device",
          transport: "emulator",
        });
      }
    }
    return {
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
    };
  };
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
    command: async (executable, args, options = {}) => {
      calls.push(["command", executable, [...args], options]);
      if (args[0] === "devices") return deviceEnvelope();
      if (executable === "go") return { code: 0, stdout: "", stderr: "" };
      if (args.includes(":device-fixture:assembleDebug")) {
        return { code: 0, stdout: "", stderr: "" };
      }
      if (args.includes(":device-fixture:connectedDebugAndroidTest")) {
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
    createTemporaryRoot: async () => path.join(root, "emulator-state", "n33-matrix-test"),
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
  assert.equal(
    matrixEnvironment(environment).stateRoot,
    path.join(root, "emulator-state"),
  );
  assert.throws(
    () => matrixEnvironment({ ...environment, GOCACHE: path.resolve(root, "..", "go") }),
    (error) => error.code === "ENVIRONMENT_INVALID",
  );
});

test("严格解析唯一 N33 JUnit 结果且不返回失败详情", () => {
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
    junit().replace(`classname="${className}"`, 'classname="wrong.Class"'),
    junit().replace('tests="1"', 'tests="2"'),
    `${junit()}<testcase name="extra" classname="${className}"/>`,
  ]) {
    assert.throws(
      () => parseJUnitResult(invalid),
      (error) => error.code === "JUNIT_RESULT_INVALID",
    );
  }
});

test("真机共存时只选择唯一 emulator 且十九轮达到 95%", async () => {
  const { calls, value } = fixture({ failures: 1, stopRetainedOnce: true });
  const report = await runNativeFixtureMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });
  assert.equal(report.rounds.length, 20);
  assert.equal(report.passed, 19);
  assert.equal(report.failed, 1);
  assert.equal(report.successRate, 0.95);
  assert.equal(report.thresholdMet, true);
  assert.equal(calls.filter(([name]) => name === "restore").length, 21);
  assert.equal(calls.filter(([name]) => name === "clean-results").length, 20);
  assert.equal(calls.filter(([name]) => name === "stop").length, 2);
  const testCalls = calls.filter(
    ([name, , args]) =>
      name === "command"
      && args?.includes(":device-fixture:connectedDebugAndroidTest"),
  );
  assert.equal(testCalls.length, 20);
  for (const [, , args, options] of testCalls) {
    assert.ok(args.includes("-Pandroid.testInstrumentationRunnerArguments.fixtureScenario=all"));
    assert.equal(options.env.ANDROID_SERIAL, "emulator-5554");
  }
  const serialized = JSON.stringify(report);
  for (const forbidden of ["physical-device", "private stack", "stdout", "stderr", "xml"]) {
    assert.equal(serialized.includes(forbidden), false);
  }
});

test("十八轮通过不达 95% 门且仍完成全部轮次", async () => {
  const { calls, value } = fixture({ failures: 2 });
  const report = await runNativeFixtureMatrix({
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
    () => runNativeFixtureMatrix({
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
        && args?.includes(":device-fixture:connectedDebugAndroidTest"),
    ).length,
    1,
  );
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
  assert.equal(calls.filter(([name]) => name === "report").length, 0);
});

test("出现第二台 emulator 时停止且不运行设备测试", async () => {
  const { calls, value } = fixture({ secondEmulator: true });
  await assert.rejects(
    () => runNativeFixtureMatrix({
      argv: ["--profile", "api-34"],
      environment,
      dependencies: value,
    }),
    (error) => error.code === "DEVICE_SELECTION_MISMATCH",
  );
  assert.equal(
    calls.filter(
      ([name, , args]) =>
        name === "command"
        && args?.includes(":device-fixture:connectedDebugAndroidTest"),
    ).length,
    0,
  );
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
});
