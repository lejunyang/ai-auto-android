// 脚本用途：验证 N45 固定设备矩阵、身份参数、结果新鲜度、漂移拒绝和零残留清理。
import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { deflateRawSync } from "node:zlib";

import {
  configureFixedVisualCase,
  inspectReleaseApkBytes,
  matrixCases,
  matrixEnvironment,
  parseArguments,
  parseJUnitResult,
  parseWindowRotation,
  rotationCommands,
  runVisualDeviceMatrix,
  selectFreshJUnit,
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
const className =
  "dev.aiauto.android.automation.recording.replay.visual.N45VisualDeviceMatrixTest";

const junit = ({
  failures = 0,
  errors = 0,
  skipped = 0,
  duration = "1.25",
} = {}) => `<?xml version='1.0' encoding='UTF-8'?>
<testsuites tests="1" failures="${failures}" errors="${errors}" skipped="${skipped}">
  <testsuite name="${className}" tests="1" failures="${failures}" errors="${errors}" skipped="${skipped}" time="${duration}">
    <testcase name="runVisualActionMatrixCase" classname="${className}" time="${duration}" />
  </testsuite>
</testsuites>`;

const zipEntry = (name, content, compressed = false) => {
  const nameBytes = Buffer.from(name, "utf8");
  const source = Buffer.from(content);
  const payload = compressed ? deflateRawSync(source) : source;
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(compressed ? 8 : 0, 8);
  local.writeUInt32LE(payload.length, 18);
  local.writeUInt32LE(source.length, 22);
  local.writeUInt16LE(nameBytes.length, 26);
  return Buffer.concat([local, nameBytes, payload]);
};

const fixture = ({
  driftAt = null,
  fingerprintDriftAt = null,
  secondEmulator = false,
  staleJUnitAt = null,
} = {}) => {
  const calls = [];
  let online = false;
  let matrixIndex = 0;
  const booted = Object.freeze({
    profileId: "api-34",
    serial: "emulator-5554",
    state: "booted",
    deviceFingerprint: "a".repeat(64),
  });
  const runtime = Object.freeze({
    profileId: "api-34",
    serial: booted.serial,
    deviceFingerprint: booted.deviceFingerprint,
    metadata: {
      buildFingerprint:
        "google/sdk_gphone64_arm64/emu64a:14/example:userdebug/dev-keys",
    },
    lease: {
      avdLock: path.join(root, "emulator-state", "locks", "avd", "api-34.lock"),
      portLock: path.join(root, "emulator-state", "locks", "port", "5554.lock"),
    },
  });
  const devices = () => {
    const values = [{
      serial: "physical-device",
      state: "device",
      transport: "usb",
    }];
    if (online) {
      values.push({
        serial: booted.serial,
        state: "device",
        transport: "emulator",
      });
      if (secondEmulator) {
        values.push({
          serial: "emulator-5556",
          state: "device",
          transport: "emulator",
        });
      }
    }
    return values;
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
        return { ...booted, state: "starting" };
      },
      waitForBoot: async () => {
        calls.push(["wait"]);
        return booted;
      },
      restore: async () => {
        calls.push(["restore"]);
        return booted;
      },
      configureVisualMatrix: async (_profile, resolution, rotation) => {
        matrixIndex += 1;
        calls.push(["configure", resolution, rotation]);
        return {
          ...booted,
          serial: driftAt === matrixIndex ? "emulator-5556" : booted.serial,
          deviceFingerprint: fingerprintDriftAt === matrixIndex
            ? "b".repeat(64)
            : booted.deviceFingerprint,
          resolution,
          rotation,
        };
      },
      stop: async () => {
        calls.push(["stop"]);
        online = false;
      },
    },
    command: async (executable, args, options = {}) => {
      calls.push(["command", executable, [...args], options]);
      if (args[0] === "devices") {
        return {
          code: 0,
          stdout: `${JSON.stringify({
            schemaVersion: "1.0",
            requestId: "00000000-0000-4000-8000-000000000000",
            ok: true,
            data: { devices: devices(), count: devices().length },
            error: null,
            meta: { durationMs: 1 },
          })}\n`,
          stderr: "",
        };
      }
      return { code: 0, stdout: "", stderr: "" };
    },
    readRuntime: async () => runtime,
    cleanResults: async () => calls.push(["clean-results"]),
    readFreshJUnit: async (startedAtMs) => {
      if (staleJUnitAt === matrixIndex) {
        const error = new Error("stale");
        error.code = "JUNIT_RESULT_MISSING";
        throw error;
      }
      calls.push(["junit", startedAtMs]);
      return junit();
    },
    createTemporaryRoot: async () =>
      path.join(root, "emulator-state", "n45-matrix-test"),
    removeTemporaryRoot: async (temporary) => calls.push(["remove", temporary]),
    persistReport: async (report) => calls.push(["report", report]),
    assertNoResidue: async (_runtime) => calls.push(["zero-residue"]),
    inspectReleaseApk: async () => calls.push(["release-scan"]),
  };
  return { booted, calls, value };
};

test("只接受固定 profile 和统一外置工具根", () => {
  assert.deepEqual(parseArguments(["--profile", "api-30"]), {
    profile: "api-30",
  });
  for (const argv of [
    [],
    ["--profile", "api-35"],
    ["--profile", "api-30", "--resolution", "720x1600"],
    ["--serial", "emulator-5554"],
  ]) {
    assert.throws(() => parseArguments(argv), { code: "USAGE_ERROR" });
  }
  assert.equal(matrixEnvironment(environment).stateRoot, environment.AACTL_EMULATOR_STATE);
  assert.throws(
    () => matrixEnvironment({
      ...environment,
      GOCACHE: path.resolve(root, "..", "escaped-cache"),
    }),
    { code: "ENVIRONMENT_INVALID" },
  );
});

test("矩阵固定为三分辨率和两种 rotation", () => {
  assert.deepEqual(matrixCases(), [
    { resolution: "720x1600", rotation: 0 },
    { resolution: "720x1600", rotation: 90 },
    { resolution: "1080x2400", rotation: 0 },
    { resolution: "1080x2400", rotation: 90 },
    { resolution: "1440x3200", rotation: 0 },
    { resolution: "1440x3200", rotation: 90 },
  ]);
});

test("只接受 dumpsys window 的唯一实际 mRotation", () => {
  assert.equal(
    parseWindowRotation("DisplayRotation\n  mRotation=1 mDeferredRotationPauseCount=0\n"),
    1,
  );
  for (const output of [
    "",
    "mRotation=9\n",
    "mRotation=0\nmRotation=1\n",
    "mUserRotation=ROTATION_90\n",
  ]) {
    assert.throws(() => parseWindowRotation(output), {
      code: "DEVICE_CONFIG_DRIFT",
    });
  }
});

test("rotation 命令按固定 API 选择平台语法", () => {
  assert.deepEqual(rotationCommands(30, "1"), [
    ["shell", "wm", "set-fix-to-user-rotation", "enabled"],
    ["shell", "wm", "set-user-rotation", "lock", "1"],
  ]);
  for (const apiLevel of [33, 34]) {
    assert.deepEqual(rotationCommands(apiLevel, "0"), [
      ["shell", "wm", "fixed-to-user-rotation", "enabled"],
      ["shell", "wm", "user-rotation", "lock", "0"],
    ]);
  }
  assert.throws(() => rotationCommands(31, "0"), { code: "MATRIX_CASE_INVALID" });
  assert.throws(() => rotationCommands(33, "2"), { code: "MATRIX_CASE_INVALID" });
});

test("rotation 配置调用 WindowManager lock 并验证实际 surface 方向", async () => {
  const calls = [];
  let rotationReads = 0;
  const rotationSequence = [0, 0, 1];
  const emulator = {
    readSnapshotMarker: async () => "clean",
    tools: () => ({ adb: "/external/android-sdk/platform-tools/adb" }),
    environment: () => environment,
    command: async (_executable, args) => {
      calls.push(args);
      const command = args.slice(2);
      if (command.join(" ") === "shell wm size") {
        return { code: 0, stdout: "Physical size: 1080x2400\nOverride size: 720x1600\n" };
      }
      if (command.join(" ") === "shell wm density") {
        return { code: 0, stdout: "Physical density: 420\nOverride density: 420\n" };
      }
      if (command.join(" ") === "shell dumpsys window displays") {
        rotationReads += 1;
        const rotation = rotationSequence[Math.min(
          rotationReads - 1,
          rotationSequence.length - 1,
        )];
        return {
          code: 0,
          stdout: `DisplayRotation
  mRotation=${rotation} mDeferredRotationPauseCount=0
`,
        };
      }
      return { code: 0, stdout: "", stderr: "" };
    },
  };

  const configured = await configureFixedVisualCase(
    emulator,
    { id: "api-33", apiLevel: 33, densityDpi: 420 },
    { serial: "emulator-5554", deviceFingerprint: "a".repeat(64), state: "booted" },
    "720x1600",
    90,
  );

  assert.equal(configured.rotation, 90);
  assert.equal(rotationReads, 3);
  assert.equal(
    calls.some((args) =>
      args.join(" ") ===
      "-s emulator-5554 shell wm fixed-to-user-rotation enabled"),
    true,
  );
  assert.equal(
    calls.filter((args) =>
      args.join(" ") ===
      "-s emulator-5554 shell wm user-rotation lock 1").length,
    3,
  );
  assert.equal(
    calls.some((args) => args.includes("user_rotation")),
    false,
  );
});

test("严格解析唯一 N45 JUnit 且筛除旧结果", () => {
  assert.deepEqual(parseJUnitResult(junit()), {
    status: "passed",
    durationMs: 1250,
    tests: 1,
    failures: 0,
    errors: 0,
    skipped: 0,
  });
  assert.throws(
    () => parseJUnitResult(junit().replace(`classname="${className}"`, 'classname="wrong.Class"')),
    { code: "JUNIT_RESULT_INVALID" },
  );
  assert.equal(
    selectFreshJUnit([
      { file: "/result/old.xml", mtimeMs: 1_000 },
      { file: "/result/new.xml", mtimeMs: 4_000 },
    ], 3_500),
    "/result/new.xml",
  );
  assert.throws(
    () => selectFreshJUnit([{ file: "/result/old.xml", mtimeMs: 1_000 }], 3_500),
    { code: "JUNIT_RESULT_MISSING" },
  );
});

test("release APK 扫描解压条目并拒绝 N45 harness 与 marker", () => {
  assert.deepEqual(
    inspectReleaseApkBytes(Buffer.concat([
      zipEntry("classes.dex", "production-only", true),
      Buffer.from("PK\u0005\u0006"),
    ])),
    [],
  );
  for (const forbidden of [
    "N45VisualDeviceHarness",
    "N45VisualDeviceMatrixTest",
    "AI_AUTO_TEST_ONLY_V1",
  ]) {
    const findings = inspectReleaseApkBytes(Buffer.concat([
      zipEntry("classes.dex", `prefix-${forbidden}-suffix`, true),
      Buffer.from("PK\u0005\u0006"),
    ]));
    assert.equal(findings.length, 1);
    assert.ok(findings[0].includes(forbidden));
  }
  assert.throws(
    () => inspectReleaseApkBytes(Buffer.from("not-an-apk")),
    { code: "RELEASE_APK_INVALID" },
  );
});

test("单 profile 串行运行六个环境并只把 owned emulator 传给 instrumentation", async () => {
  const { calls, value } = fixture();
  const report = await runVisualDeviceMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });
  assert.equal(report.cases.length, 6);
  assert.equal(report.passed, 6);
  assert.equal(report.succeeded, true);
  assert.equal(calls.filter(([name]) => name === "configure").length, 6);
  assert.equal(calls.filter(([name]) => name === "clean-results").length, 6);
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
  assert.equal(calls.filter(([name]) => name === "zero-residue").length, 1);
  const tests = calls.filter(
    ([name, , args]) =>
      name === "command"
      && args?.includes(":app:connectedDebugAndroidTest"),
  );
  assert.equal(tests.length, 6);
  for (const [, , args, options] of tests) {
    assert.equal(options.env.ANDROID_SERIAL, "emulator-5554");
    assert.ok(args.some((value) => value.includes("n32TestOnlyMarker=AI_AUTO_TEST_ONLY_V1")));
    assert.ok(args.some((value) => value.startsWith(
      "-Pandroid.testInstrumentationRunnerArguments.n45MatrixResolution=",
    )));
    assert.ok(args.some((value) => value.startsWith(
      "-Pandroid.testInstrumentationRunnerArguments.n45MatrixRotation=",
    )));
  }
  assert.equal(
    calls.some(
      ([name, , args]) =>
        name === "command"
        && args?.includes(":test-control-core:verifyReleaseApk")
        && args?.includes("--no-configuration-cache")
        && args?.some(
          (value) =>
            value.endsWith(
              `${path.sep}app${path.sep}build${path.sep}outputs${path.sep}`
              + `apk${path.sep}release${path.sep}app-release-unsigned.apk`,
            ),
        ),
    ),
    true,
  );
});

test("serial、fingerprint 漂移或第二台 emulator 时停止且执行清理", async () => {
  for (const options of [
    { driftAt: 2 },
    { fingerprintDriftAt: 2 },
    { secondEmulator: true },
  ]) {
    const { calls, value } = fixture(options);
    await assert.rejects(
      () => runVisualDeviceMatrix({
        argv: ["--profile", "api-34"],
        environment,
        dependencies: value,
      }),
      (error) => [
        "RUNNER_CONTEXT_DRIFT",
        "DEVICE_SELECTION_MISMATCH",
      ].includes(error.code),
    );
    assert.equal(calls.filter(([name]) => name === "stop").length, 1);
    assert.equal(calls.filter(([name]) => name === "report").length, 0);
  }
});

test("旧 JUnit 不能被当作本轮成功且仍确认零残留", async () => {
  const { calls, value } = fixture({ staleJUnitAt: 1 });
  await assert.rejects(
    () => runVisualDeviceMatrix({
      argv: ["--profile", "api-34"],
      environment,
      dependencies: value,
    }),
    { code: "JUNIT_RESULT_MISSING" },
  );
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
  assert.equal(calls.filter(([name]) => name === "zero-residue").length, 1);
});
