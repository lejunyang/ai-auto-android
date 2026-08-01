// 测试用途：验证 production 组合工厂只使用固定 N31、APK、授权和 N47 清理端口。
import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  createProductionFixturePorts,
} from "../src/production-fixture-ports.mjs";

const root = "/opt/aiauto-tools";
const repositoryRoot = path.resolve(import.meta.dirname, "..", "..", "..");
const environment = Object.freeze({
  AACTL_TOOLCHAIN_ROOT: root,
  ANDROID_SDK_ROOT: `${root}/android-sdk`,
  ANDROID_AVD_HOME: `${root}/android-avd`,
  JAVA_HOME: `${root}/jdk/Contents/Home`,
  AACTL_EMULATOR_STATE: `${root}/emulator-state`,
  GOCACHE: `${root}/go-cache`,
  GOMODCACHE: `${root}/go-mod-cache`,
});
const profile = Object.freeze({
  id: "api-33",
  apiLevel: 33,
  abi: "arm64-v8a",
  avdName: "ai-auto-api-33",
  densityDpi: 420,
  locale: "zh-CN",
  resolution: "1080x2400",
  snapshot: "clean",
  webViewPackage: "com.google.android.webview",
  webViewVersion: "109.0.5414.123",
});
const fingerprint = "a".repeat(64);
const buildFingerprint =
  "google/sdk_gphone64_arm64/emu64a:13/TE1A.240213.009/12342917:userdebug/dev-keys";

const reportFor = (scenarioId) => ({
  schemaVersion: "1.0",
  runId: "019fbdf0-0000-7000-8000-000000000047",
  scenarioId,
  iteration: 1,
  startedAt: "2026-08-01T12:00:00.000Z",
  finishedAt: "2026-08-01T12:00:01.000Z",
  status: "passed",
  errorCode: null,
  durationMs: 1000,
  actionCommits: 1,
  steps: [{
    sequence: 1,
    stepId: "fixed-step",
    actionType: "tap",
    targetPackage: "dev.aiauto.fixture",
    status: "passed",
    errorCode: null,
    pageClass: "normal",
    route: "semantic",
    score: 1,
    attempts: 1,
    preObservationId: "observation-pre",
    postObservationId: "observation-post",
    actionCommits: 1,
    durationMs: 1000,
  }],
  cleanup: {
    attempted: 4,
    completed: 4,
    errorCode: null,
  },
});

const fixture = ({
  authorizationAvailable = true,
  authorizationSetupFailure = false,
  residue = {},
  useDefaultRandomUUID = false,
} = {}) => {
  const calls = [];
  let online = false;
  let cleanupMethods = null;
  const authorization = {
    probe: async (requested) => {
      calls.push(["authorization-probe", requested.profileId]);
      return {
        available: authorizationAvailable,
        bridgeAction: authorizationAvailable,
        disposableEmulator: authorizationAvailable,
        visualAction: authorizationAvailable,
      };
    },
    setup: async (context) => {
      calls.push([
        "authorization-setup",
        context.serial,
        context.scenarioId,
        context.runId,
        context.aactlPath,
        context.buildFingerprint,
      ]);
      if (authorizationSetupFailure) {
        throw new Error("private authorization failure");
      }
      return {
        ready: true,
        desktopDeviceFingerprint: fingerprint,
      };
    },
    stopScenario: async (context) => {
      calls.push(["authorization-stop", context.scenarioId]);
    },
    closeBridge: async (context) => {
      calls.push(["authorization-close", context.scenarioId]);
    },
    inspect: async () => ({
      bridgeSessions: residue.bridgeSessions ?? 0,
      testServices: residue.testServices ?? 0,
    }),
  };
  const value = {
    authorization,
    dependencies: {
      loadProfiles: async () => ({ profiles: [profile] }),
      createEmulator: () => ({
        start: async (profileId) => {
          calls.push(["start", profileId]);
          online = true;
          return { serial: "emulator-5554" };
        },
        waitForBoot: async (profileId) => {
          calls.push(["wait", profileId]);
          return {
            profileId,
            apiLevel: 33,
            avdName: profile.avdName,
            serial: "emulator-5554",
            deviceFingerprint: fingerprint,
            state: "booted",
          };
        },
        restore: async (profileId) => {
          calls.push(["restore", profileId]);
          return {
            profileId,
            apiLevel: 33,
            avdName: profile.avdName,
            serial: "emulator-5554",
            deviceFingerprint: fingerprint,
            state: "booted",
          };
        },
        readSnapshotMarker: async () => "clean",
        stop: async (profileId) => {
          calls.push(["stop", profileId]);
          online = false;
          return {
            profileId,
            serial: "emulator-5554",
            deviceFingerprint: fingerprint,
            state: "stopped",
          };
        },
      }),
      command: async (executable, argv) => {
        calls.push(["command", executable, [...argv]]);
        return {
          code: 0,
          stdout: argv.includes("install") ? "Success\n" : "",
          stderr: "",
          timedOut: false,
        };
      },
      createTemporaryRoot: async () => `${root}/emulator-state/n47-fixed`,
      removeTemporaryRoot: async (temporary) => {
        calls.push(["remove", temporary]);
      },
      assertBuildOutputs: async (outputs) => {
        calls.push(["outputs", [...outputs]]);
      },
      readRuntime: async () => ({
        pid: 4711,
        logFile: `${root}/emulator-state/logs/n47.log`,
        runtimeFile: `${root}/emulator-state/runtime/ai-auto-api-33.json`,
        metadata: {
          buildFingerprint,
        },
        lease: {
          avdLock: `${root}/emulator-state/locks/avd`,
          portLock: `${root}/emulator-state/locks/port`,
        },
      }),
      inspectOwnedPath: async (file) =>
        residue[file.split("/").at(-1)] ?? 0,
      isProcessAlive: () => residue.ownedProcesses === 1 && online,
      createAactlPorts: async (_config, dependencies) => {
        cleanupMethods = dependencies.lifecycle;
        calls.push(["aactl-ports"]);
        return {
          observer: { observe: async () => ({}) },
          conditions: { verify: async () => true },
          router: { resolve: async () => ({}) },
          executor: { execute: async () => ({ committed: true }) },
          values: dependencies.values,
          artifacts: dependencies.artifacts,
          lifecycle: dependencies.lifecycle,
        };
      },
      createVisualPorts: async ({ basePorts }) => {
        calls.push(["visual-ports"]);
        return basePorts;
      },
      runScenario: async ({ scenario }) => {
        for (const name of [
          "stopScenario",
          "closeBridge",
          "clearAppData",
          "restoreSnapshot",
        ]) {
          await cleanupMethods[name]({
            runId: "019fbdf0-0000-7000-8000-000000000047",
            scenarioId: scenario.id,
            iteration: 1,
            targetPackages: scenario.targetPackages,
          });
        }
        return {
          report: reportFor(scenario.id),
          artifactRun: null,
          artifact: null,
        };
      },
      randomUUID: () => "019fbdf0-0000-7000-8000-000000000047",
      now: () => "2026-08-01T12:00:00.000Z",
    },
  };
  if (useDefaultRandomUUID) {
    delete value.dependencies.randomUUID;
  }
  return { calls, value };
};

test("授权 capability 缺失时只探测且不构建、不启动 emulator", async () => {
  const current = fixture({ authorizationAvailable: false });
  const ports = await createProductionFixturePorts({
    environment,
    authorization: current.value.authorization,
  }, current.value.dependencies);
  const result = await ports.capabilities.probe({
    profileId: "api-33",
    apiLevel: 33,
  });

  assert.equal(result.capabilities.includes("test.authorization"), false);
  assert.deepEqual(
    current.calls.map(([name]) => name),
    ["authorization-probe"],
  );
});

test("固定 build/install argv 组装 aactl visual 并执行四步清理", async () => {
  const current = fixture();
  const ports = await createProductionFixturePorts({
    environment,
    authorization: current.value.authorization,
  }, current.value.dependencies);
  const capability = await ports.capabilities.probe({
    profileId: "api-33",
    apiLevel: 33,
  });
  const started = await ports.lifecycle.startClean({
    profileId: "api-33",
    apiLevel: 33,
  });
  assert.deepEqual(
    capability.capabilities,
    started.capabilities,
  );

  const report = await ports.scenarios.run({
    scenarioId: "production-native-fixture",
    iteration: 1,
    started,
  });
  assert.equal(report.status, "passed");

  const commands = current.calls
    .filter(([name]) => name === "command")
    .map(([, executable, argv]) => [executable, argv]);
  assert.deepEqual(commands[0], [
    "go",
    ["build", "-trimpath", "-o", `${root}/emulator-state/n47-fixed/aactl`, "./cmd/aactl"],
  ]);
  assert.deepEqual(commands[1], [
    path.join(repositoryRoot, "android", "gradlew"),
    [
      "-p",
      "android",
      "--no-daemon",
      ":app:assembleDebug",
      ":app:assembleDebugAndroidTest",
      ":device-fixture:assembleDebug",
      ":web-fixture:assembleDebug",
    ],
  ]);
  assert.deepEqual(
    current.calls.find(([name]) => name === "authorization-setup"),
    [
      "authorization-setup",
      "emulator-5554",
      "production-native-fixture",
      "019fbdf0-0000-7000-8000-000000000047",
      `${root}/emulator-state/n47-fixed/aactl`,
      buildFingerprint,
    ],
  );
  const installArgv = commands
    .filter(([, argv]) => argv[2] === "install")
    .map(([, argv]) => argv);
  assert.equal(installArgv.length, 2);
  assert.deepEqual(installArgv.map((argv) => argv.slice(0, 5)), [
    ["-s", "emulator-5554", "install", "--no-streaming", "-r"],
    ["-s", "emulator-5554", "install", "--no-streaming", "-r"],
  ]);
  assert.equal(installArgv[0].at(-1).endsWith("/app-debug.apk"), true);
  assert.equal(
    installArgv[1].at(-1).endsWith("/device-fixture-debug.apk"),
    true,
  );

  const lifecycleOrder = current.calls
    .filter(([name]) => [
      "authorization-stop",
      "authorization-close",
      "restore",
    ].includes(name)
      || name === "command")
    .filter(([name, executable, argv]) =>
      name !== "command"
      || executable.endsWith("/adb")
      && argv[2] === "shell"
      && ["am", "pm"].includes(argv[3]))
    .map(([name, _executable, argv]) =>
      name === "command" ? argv[3] : name);
  assert.deepEqual(lifecycleOrder.slice(-6), [
    "authorization-stop",
    "am",
    "authorization-close",
    "pm",
    "pm",
    "restore",
  ]);
  assert.equal(
    current.calls.filter(([name]) => name === "aactl-ports").length,
    1,
  );
  assert.equal(
    current.calls.filter(([name]) => name === "visual-ports").length,
    1,
  );
});

test("默认 UUID provider 在 Node 24 可直接生成场景 run ID", async () => {
  const current = fixture({ useDefaultRandomUUID: true });
  const ports = await createProductionFixturePorts({
    environment,
    authorization: current.value.authorization,
  }, current.value.dependencies);
  await ports.capabilities.probe({ profileId: "api-33", apiLevel: 33 });
  const started = await ports.lifecycle.startClean({
    profileId: "api-33",
    apiLevel: 33,
  });

  await ports.scenarios.run({
    scenarioId: "production-native-fixture",
    iteration: 1,
    started,
  });

  const setup = current.calls.find(([name]) => name === "authorization-setup");
  assert.match(
    setup[3],
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u,
  );
});

test("授权 setup 失败仍按固定顺序执行四步补偿且不运行场景", async () => {
  const current = fixture({ authorizationSetupFailure: true });
  const ports = await createProductionFixturePorts({
    environment,
    authorization: current.value.authorization,
  }, current.value.dependencies);
  await ports.capabilities.probe({ profileId: "api-33", apiLevel: 33 });
  const started = await ports.lifecycle.startClean({
    profileId: "api-33",
    apiLevel: 33,
  });

  await assert.rejects(
    () => ports.scenarios.run({
      scenarioId: "production-native-fixture",
      iteration: 1,
      started,
    }),
    /private authorization failure/u,
  );
  assert.deepEqual(
    current.calls
      .filter(([name]) => [
        "authorization-stop",
        "authorization-close",
        "restore",
      ].includes(name)
        || name === "command")
      .filter(([name, executable, argv]) =>
        name !== "command"
        || executable.endsWith("/adb")
        && argv[2] === "shell"
        && ["am", "pm"].includes(argv[3]))
      .map(([name, _executable, argv]) =>
        name === "command" ? argv[3] : name)
      .slice(-6),
    [
      "authorization-stop",
      "am",
      "authorization-close",
      "pm",
      "pm",
      "restore",
    ],
  );
  assert.equal(
    current.calls.filter(([name]) => name === "aactl-ports").length,
    0,
  );
});

test("stop 后返回八类 residue，Bridge 或 lease 非零不会被清零伪报", async () => {
  const current = fixture({
    residue: {
      bridgeSessions: 1,
      "port": 1,
    },
  });
  const ports = await createProductionFixturePorts({
    environment,
    authorization: current.value.authorization,
  }, current.value.dependencies);
  await ports.capabilities.probe({ profileId: "api-33", apiLevel: 33 });
  const started = await ports.lifecycle.startClean({
    profileId: "api-33",
    apiLevel: 33,
  });
  await ports.lifecycle.stop(started);
  const residue = await ports.residue.inspect(started);

  assert.deepEqual(Object.keys(residue).sort(), [
    "appData",
    "bridgeSessions",
    "leases",
    "logs",
    "ownedProcesses",
    "runtimeFiles",
    "screenshots",
    "testServices",
  ].sort());
  assert.equal(residue.bridgeSessions, 1);
  assert.equal(residue.leases, 1);
});
