// 功能用途：固定组装 N31、fixture APK、test-only 授权、N47 adapters 和八类残留端口。
import { execFile as nodeExecFile } from "node:child_process";
import {
  access,
  mkdtemp,
  readFile,
  rm,
} from "node:fs/promises";
import path from "node:path";

import {
  EmulatorRunner,
  loadProfiles,
  runCommand,
} from "../../../scripts/emulator/runner.mjs";
import { resolveToolchainEnvironment } from "../../../scripts/toolchain-environment.mjs";
import { createAactlScenarioPorts } from "../adapters/aactl.mjs";
import {
  createAttestedVisualScenarioPorts,
} from "../adapters/attested-visual.mjs";
import { runScenario } from "./runner.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..", "..", "..");
const profileFile = path.join(
  repositoryRoot,
  "scripts",
  "emulator",
  "profiles.json",
);
const gradlew = path.join(repositoryRoot, "android", "gradlew");
const fixedPackages = Object.freeze([
  "dev.aiauto.android",
  "dev.aiauto.fixture",
  "dev.aiauto.webfixture",
]);
const appPackage = fixedPackages[0];
const nativePackage = fixedPackages[1];
const webPackage = fixedPackages[2];
const apkFiles = Object.freeze({
  app: path.join(
    repositoryRoot,
    "android",
    "app",
    "build",
    "outputs",
    "apk",
    "debug",
    "app-debug.apk",
  ),
  native: path.join(
    repositoryRoot,
    "android",
    "device-fixture",
    "build",
    "outputs",
    "apk",
    "debug",
    "device-fixture-debug.apk",
  ),
  webview: path.join(
    repositoryRoot,
    "android",
    "web-fixture",
    "build",
    "outputs",
    "apk",
    "debug",
    "web-fixture-debug.apk",
  ),
});
const scenarioFiles = Object.freeze({
  "production-native-fixture": path.join(
    repositoryRoot,
    "test-lab",
    "runner",
    "fixtures",
    "production-native-fixture.scenario.json",
  ),
  "production-webview-fixture": path.join(
    repositoryRoot,
    "test-lab",
    "runner",
    "fixtures",
    "production-webview-fixture.scenario.json",
  ),
  "production-canvas-fixture": path.join(
    repositoryRoot,
    "test-lab",
    "runner",
    "fixtures",
    "production-canvas-fixture.scenario.json",
  ),
});
const requiredCapabilities = Object.freeze([
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
const authorizationMethods = Object.freeze([
  "probe",
  "setup",
  "stopScenario",
  "closeBridge",
  "inspect",
]);
const profilePattern = /^api-(?:30|33|34)$/u;
const serialPattern = /^emulator-[0-9]{4,5}$/u;
const fingerprintPattern = /^[a-f0-9]{64}$/u;
const androidVersions = Object.freeze(new Map([
  [30, "11"],
  [33, "13"],
  [34, "14"],
]));
const systemPackages = Object.freeze([
  "com.google.android.apps.nexuslauncher",
  "com.android.launcher3",
  "com.android.systemui",
]);

export class ProductionFixturePortsError extends Error {
  constructor(code) {
    super(code);
    this.name = "ProductionFixturePortsError";
    this.code = code;
  }
}

const fail = (code) => {
  throw new ProductionFixturePortsError(code);
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const resolveEnvironment = (environment) => {
  try {
    return resolveToolchainEnvironment(environment, {
      sdkRoot: "ANDROID_SDK_ROOT",
      avdRoot: "ANDROID_AVD_HOME",
      javaHome: "JAVA_HOME",
      stateRoot: "AACTL_EMULATOR_STATE",
      goCache: "GOCACHE",
      goModCache: "GOMODCACHE",
    });
  } catch {
    fail("PRODUCTION_FIXTURE_ENVIRONMENT_INVALID");
  }
};

const assertAuthorization = (authorization) => {
  if (
    authorization === null
    || typeof authorization !== "object"
    || authorizationMethods.some(
      (method) => typeof authorization[method] !== "function",
    )
  ) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_INVALID");
  }
};

const assertProfileRequest = (request) => {
  if (
    !exactKeys(request, ["apiLevel", "profileId"])
    || !profilePattern.test(request.profileId ?? "")
    || ![30, 33, 34].includes(request.apiLevel)
    || Number(request.profileId.slice(4)) !== request.apiLevel
  ) {
    fail("PRODUCTION_FIXTURE_PROFILE_INVALID");
  }
};

const assertCommandSuccess = (result, code) => {
  if (
    result === null
    || typeof result !== "object"
    || result.code !== 0
    || result.timedOut === true
    || typeof result.stdout !== "string"
    || typeof result.stderr !== "string"
  ) {
    fail(code);
  }
  return result;
};

const assertInstallSuccess = (result) => {
  assertCommandSuccess(result, "PRODUCTION_FIXTURE_INSTALL_FAILED");
  const lines = `${result.stdout}\n${result.stderr}`
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean);
  if (!lines.includes("Success")) {
    fail("PRODUCTION_FIXTURE_INSTALL_FAILED");
  }
};

const assertN31Context = async (emulator, profile, expected) => {
  const current = await emulator.waitForBoot(profile.id);
  const marker = await emulator.readSnapshotMarker(profile.id);
  if (
    current?.profileId !== profile.id
    || current?.apiLevel !== profile.apiLevel
    || current?.serial !== expected.serial
    || current?.deviceFingerprint !== expected.deviceFingerprint
    || current?.state !== "booted"
    || marker !== "clean"
  ) {
    fail("PRODUCTION_FIXTURE_CONTEXT_DRIFT");
  }
};

const assertRestore = async (emulator, profile, expected) => {
  const restored = await emulator.restore(profile.id);
  const marker = await emulator.readSnapshotMarker(profile.id);
  if (
    restored?.profileId !== profile.id
    || restored?.apiLevel !== profile.apiLevel
    || restored?.serial !== expected.serial
    || restored?.deviceFingerprint !== expected.deviceFingerprint
    || restored?.state !== "booted"
    || marker !== "clean"
  ) {
    fail("PRODUCTION_FIXTURE_CONTEXT_DRIFT");
  }
};

const deviceMetadata = (profile, started) => {
  const [width, height] = profile.resolution.split("x").map(Number);
  if (
    !Number.isInteger(width)
    || !Number.isInteger(height)
    || width < 1
    || height < 1
    || !androidVersions.has(profile.apiLevel)
  ) {
    fail("PRODUCTION_FIXTURE_PROFILE_INVALID");
  }
  return Object.freeze({
    serial: started.serial,
    avdName: profile.avdName,
    fingerprint: started.deviceFingerprint,
    apiLevel: profile.apiLevel,
    androidVersion: androidVersions.get(profile.apiLevel),
    abi: profile.abi,
    locale: profile.locale,
    resolution: Object.freeze({
      widthPx: width,
      heightPx: height,
      densityDpi: profile.densityDpi,
    }),
    webView: Object.freeze({
      packageName: profile.webViewPackage,
      version: profile.webViewVersion,
    }),
  });
};

const conditionCatalog = Object.freeze({
  "launcher-visible": Object.freeze({
    kind: "foreground-package",
    package: "com.google.android.apps.nexuslauncher",
  }),
  "native-main": Object.freeze({
    kind: "semantic-state",
    package: nativePackage,
    target: Object.freeze({ name: "Fixture page state" }),
    operator: "equals",
    expected: "PAGE:MAIN",
  }),
  "native-click-ready": Object.freeze({
    kind: "node-present",
    package: nativePackage,
    target: Object.freeze({ name: "Fixture click target" }),
  }),
  "native-clicked": Object.freeze({
    kind: "semantic-state",
    package: nativePackage,
    target: Object.freeze({ name: "Fixture click state" }),
    operator: "equals",
    expected: "CLICK:1",
  }),
  "native-input-ready": Object.freeze({
    kind: "node-present",
    package: nativePackage,
    target: Object.freeze({ name: "Fixture text input" }),
  }),
  "native-input-updated": Object.freeze({
    kind: "semantic-state",
    package: nativePackage,
    target: Object.freeze({ name: "Fixture input state" }),
    operator: "equals",
    expected: "INPUT:n47-local-input",
  }),
  "webview-ready": Object.freeze({
    kind: "semantic-state",
    package: webPackage,
    target: Object.freeze({ name: "Fixture state ready" }),
    operator: "equals",
    expected: "Fixture state ready",
  }),
  "webview-click-ready": Object.freeze({
    kind: "node-present",
    package: webPackage,
    target: Object.freeze({ name: "Fixture click button" }),
  }),
  "webview-clicked": Object.freeze({
    kind: "semantic-state",
    package: webPackage,
    target: Object.freeze({ name: "Fixture state clicked" }),
    operator: "equals",
    expected: "Fixture state clicked",
  }),
  "webview-input-ready": Object.freeze({
    kind: "node-present",
    package: webPackage,
    target: Object.freeze({ name: "Fixture text input" }),
  }),
  "webview-input-updated": Object.freeze({
    kind: "semantic-state",
    package: webPackage,
    target: Object.freeze({ name: "Fixture state input-n47-local-input" }),
    operator: "equals",
    expected: "Fixture state input-n47-local-input",
  }),
  "canvas-switch-ready": Object.freeze({
    kind: "node-present",
    package: webPackage,
    target: Object.freeze({ name: "Switch to single canvas mode" }),
  }),
  "canvas-ready": Object.freeze({
    kind: "node-present",
    package: webPackage,
    target: Object.freeze({ name: "Canvas fixture canvas-result ready" }),
  }),
  "canvas-tapped": Object.freeze({
    kind: "node-present",
    package: webPackage,
    target: Object.freeze({ name: "Canvas fixture canvas-result tap" }),
  }),
});

const defaultDependencies = Object.freeze({
  loadProfiles,
  createEmulator: ({ roots, profiles }) => new EmulatorRunner({
    stateRoot: roots.stateRoot,
    sdkRoot: roots.sdkRoot,
    avdRoot: roots.avdRoot,
    javaHome: roots.javaHome,
    profiles,
  }),
  command: runCommand,
  createTemporaryRoot: (stateRoot) =>
    mkdtemp(path.join(stateRoot, "n47-production-")),
  removeTemporaryRoot: (temporary) =>
    rm(temporary, { recursive: true, force: true }),
  assertBuildOutputs: async (outputs) => {
    for (const output of outputs) await access(output);
  },
  readRuntime: async (stateRoot, profile) => {
    const runtimeFile = path.join(
      stateRoot,
      "runtime",
      `${profile.avdName}.json`,
    );
    let runtime;
    try {
      runtime = JSON.parse(await readFile(runtimeFile, "utf8"));
    } catch {
      fail("PRODUCTION_FIXTURE_RUNTIME_INVALID");
    }
    return Object.freeze({ ...runtime, runtimeFile });
  },
  inspectOwnedPath: async (candidate) => {
    try {
      await access(candidate);
      return 1;
    } catch (error) {
      if (error?.code === "ENOENT") return 0;
      throw error;
    }
  },
  removeOwnedFile: (candidate) => rm(candidate, { force: true }),
  isProcessAlive: (pid) => {
    try {
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  },
  createAactlPorts: createAactlScenarioPorts,
  createVisualPorts: createAttestedVisualScenarioPorts,
  runScenario,
  randomUUID: crypto.randomUUID,
  now: () => new Date().toISOString(),
});

const mergeDependencies = (overrides) => Object.freeze({
  ...defaultDependencies,
  ...overrides,
});

const loadScenario = async (scenarioId) => {
  const file = scenarioFiles[scenarioId];
  if (file === undefined) fail("PRODUCTION_FIXTURE_SCENARIO_INVALID");
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch {
    fail("PRODUCTION_FIXTURE_SCENARIO_INVALID");
  }
};

export const createProductionFixturePorts = async (
  { environment, authorization },
  dependencyOverrides = {},
) => {
  assertAuthorization(authorization);
  const roots = resolveEnvironment(environment);
  const dependencies = mergeDependencies(dependencyOverrides);
  let loadedProfiles = null;
  let probedProfile = null;
  let capabilities = null;
  let emulator = null;
  let active = null;
  let runtime = null;
  let activeBuildFingerprint = null;
  let temporaryRoot = null;
  let aactlPath = null;
  let finalRestoreSucceeded = false;
  let logRemoved = false;

  const profileFor = async (request) => {
    assertProfileRequest(request);
    loadedProfiles ??= await dependencies.loadProfiles(profileFile);
    const profile = loadedProfiles?.profiles?.find(
      (candidate) => candidate.id === request.profileId,
    );
    if (
      profile === undefined
      || profile.apiLevel !== request.apiLevel
      || !profilePattern.test(profile.id ?? "")
    ) {
      fail("PRODUCTION_FIXTURE_PROFILE_INVALID");
    }
    return profile;
  };

  const capabilitiesPort = Object.freeze({
    probe: async (request) => {
      const profile = await profileFor(request);
      let authorizationResult;
      try {
        authorizationResult = await authorization.probe(request);
      } catch {
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_PROBE_FAILED");
      }
      const authorizationAvailable =
        exactKeys(authorizationResult, [
          "available",
          "bridgeAction",
          "disposableEmulator",
          "visualAction",
        ])
        && Object.values(authorizationResult).every(
          (value) => typeof value === "boolean",
        )
        && authorizationResult.available
        && authorizationResult.bridgeAction
        && authorizationResult.disposableEmulator
        && authorizationResult.visualAction;
      probedProfile = profile;
      capabilities = Object.freeze(
        requiredCapabilities.filter(
          (capability) =>
            capability !== "test.authorization" || authorizationAvailable,
        ),
      );
      return Object.freeze({
        profileId: profile.id,
        apiLevel: profile.apiLevel,
        capabilities,
      });
    },
  });

  const runFixedCommand = async (executable, argv, options, code) =>
    assertCommandSuccess(
      await dependencies.command(executable, argv, options),
      code,
    );

  const installApk = async (file) => {
    const result = await dependencies.command(
      path.join(roots.sdkRoot, "platform-tools", "adb"),
      [
        "-s",
        active.serial,
        "install",
        "--no-streaming",
        "-r",
        file,
      ],
      {
        maxBuffer: 1024 * 1024,
        timeoutMs: 5 * 60 * 1000,
      },
    );
    assertInstallSuccess(result);
  };

  const cleanupFailedStart = async (profile, started) => {
    let completed = true;
    if (
      emulator !== null
      && started !== null
      && serialPattern.test(started.serial ?? "")
    ) {
      try {
        await emulator.restore(profile.id);
      } catch {
        completed = false;
      }
      try {
        await emulator.stop(profile.id);
      } catch {
        completed = false;
      }
    }
    if (runtime?.logFile !== undefined) {
      try {
        await dependencies.removeOwnedFile(runtime.logFile);
        logRemoved = true;
      } catch {
        completed = false;
      }
    }
    if (temporaryRoot !== null) {
      try {
        await dependencies.removeTemporaryRoot(temporaryRoot);
      } catch {
        completed = false;
      }
    }
    if (!completed) fail("PRODUCTION_FIXTURE_START_CLEANUP_FAILED");
  };

  const lifecycle = Object.freeze({
    startClean: async (request) => {
      const profile = await profileFor(request);
      if (
        probedProfile?.id !== profile.id
        || capabilities === null
        || !capabilities.includes("test.authorization")
        || active !== null
      ) {
        fail("PRODUCTION_FIXTURE_START_NOT_AUTHORIZED");
      }
      let booted = null;
      let starting = null;
      try {
        temporaryRoot = await dependencies.createTemporaryRoot(roots.stateRoot);
        if (
          typeof temporaryRoot !== "string"
          || !path.isAbsolute(temporaryRoot)
        ) {
          fail("PRODUCTION_FIXTURE_TEMPORARY_INVALID");
        }
        aactlPath = path.join(temporaryRoot, "aactl");
        await runFixedCommand(
          "go",
          ["build", "-trimpath", "-o", aactlPath, "./cmd/aactl"],
          {
            cwd: repositoryRoot,
            env: environment,
            maxBuffer: 4 * 1024 * 1024,
            timeoutMs: 2 * 60 * 1000,
          },
          "PRODUCTION_FIXTURE_AACTL_BUILD_FAILED",
        );
        await runFixedCommand(
          gradlew,
          [
            "-p",
            "android",
            "--no-daemon",
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":device-fixture:assembleDebug",
            ":web-fixture:assembleDebug",
          ],
          {
            cwd: repositoryRoot,
            env: environment,
            maxBuffer: 16 * 1024 * 1024,
            timeoutMs: 6 * 60 * 1000,
          },
          "PRODUCTION_FIXTURE_ANDROID_BUILD_FAILED",
        );
        await dependencies.assertBuildOutputs([
          ...Object.values(apkFiles),
          path.join(
            repositoryRoot,
            "android",
            "app",
            "build",
            "outputs",
            "apk",
            "androidTest",
            "debug",
            "app-debug-androidTest.apk",
          ),
        ]);
        emulator = dependencies.createEmulator({ roots, profiles: loadedProfiles });
        starting = await emulator.start(profile.id);
        booted = await emulator.waitForBoot(profile.id);
        if (
          starting?.serial !== booted?.serial
          || booted?.profileId !== profile.id
          || booted?.apiLevel !== profile.apiLevel
          || booted?.state !== "booted"
          || !serialPattern.test(booted.serial ?? "")
          || !fingerprintPattern.test(booted.deviceFingerprint ?? "")
          || await emulator.readSnapshotMarker(profile.id) !== "clean"
        ) {
          fail("PRODUCTION_FIXTURE_START_INVALID");
        }
        runtime = await dependencies.readRuntime(roots.stateRoot, profile, booted);
        if (
          !Number.isInteger(runtime?.pid)
          || typeof runtime?.runtimeFile !== "string"
          || typeof runtime?.logFile !== "string"
          || typeof runtime?.lease?.avdLock !== "string"
          || typeof runtime?.lease?.portLock !== "string"
          || typeof runtime?.metadata?.buildFingerprint !== "string"
          || runtime.metadata.buildFingerprint.length < 1
        ) {
          fail("PRODUCTION_FIXTURE_RUNTIME_INVALID");
        }
        activeBuildFingerprint = runtime.metadata.buildFingerprint;
      } catch (error) {
        await cleanupFailedStart(profile, booted ?? starting);
        if (error instanceof ProductionFixturePortsError) throw error;
        fail("PRODUCTION_FIXTURE_START_FAILED");
      }
      active = Object.freeze({
        profileId: profile.id,
        apiLevel: profile.apiLevel,
        serial: booted.serial,
        deviceFingerprint: booted.deviceFingerprint,
        snapshot: "clean",
        capabilities,
        device: deviceMetadata(profile, booted),
      });
      return active;
    },

    assertContext: async (started) => {
      if (started !== active || active === null) {
        fail("PRODUCTION_FIXTURE_CONTEXT_DRIFT");
      }
      await assertN31Context(emulator, probedProfile, active);
      return active;
    },

    stop: async (started) => {
      if (started !== active || active === null) {
        fail("PRODUCTION_FIXTURE_STOP_INVALID");
      }
      try {
        await assertRestore(emulator, probedProfile, active);
        finalRestoreSucceeded = true;
        const stopped = await emulator.stop(probedProfile.id);
        if (
          stopped?.serial !== active.serial
          || stopped?.profileId !== active.profileId
          || stopped?.state !== "stopped"
        ) {
          fail("PRODUCTION_FIXTURE_STOP_FAILED");
        }
        await dependencies.removeOwnedFile(runtime.logFile);
        logRemoved = true;
        await dependencies.removeTemporaryRoot(temporaryRoot);
        return Object.freeze({ stopped: true });
      } catch (error) {
        if (error instanceof ProductionFixturePortsError) throw error;
        fail("PRODUCTION_FIXTURE_STOP_FAILED");
      }
    },
  });

  const runAdbShell = async (argv, code) =>
    runFixedCommand(
      path.join(roots.sdkRoot, "platform-tools", "adb"),
      ["-s", active.serial, "shell", ...argv],
      {
        maxBuffer: 1024 * 1024,
        timeoutMs: 30_000,
      },
      code,
    );

  const scenarioLifecycle = Object.freeze({
    stopScenario: async (context) => {
      await authorization.stopScenario(context);
      for (const targetPackage of context.targetPackages) {
        await runAdbShell(
          ["am", "force-stop", targetPackage],
          "PRODUCTION_FIXTURE_SCENARIO_STOP_FAILED",
        );
      }
    },
    closeBridge: async (context) => {
      await authorization.closeBridge(context);
    },
    clearAppData: async () => {
      for (const packageValue of fixedPackages) {
        const result = await runAdbShell(
          ["pm", "clear", packageValue],
          "PRODUCTION_FIXTURE_APP_DATA_CLEAR_FAILED",
        );
        const lines = `${result.stdout}\n${result.stderr}`
          .split(/\r?\n/u)
          .map((line) => line.trim())
          .filter(Boolean);
        if (lines.length > 0 && !lines.includes("Success")) {
          fail("PRODUCTION_FIXTURE_APP_DATA_CLEAR_FAILED");
        }
      }
    },
    restoreSnapshot: async () => {
      await assertRestore(emulator, probedProfile, active);
      finalRestoreSucceeded = true;
    },
  });

  const compensateScenarioSetup = async (context) => {
    let completed = 0;
    for (const method of [
      "stopScenario",
      "closeBridge",
      "clearAppData",
      "restoreSnapshot",
    ]) {
      try {
        await scenarioLifecycle[method](context);
        completed += 1;
      } catch {
        // 授权或 adapter 建立失败后仍必须尝试全部四步，最终只公开稳定清理错误。
      }
    }
    if (completed !== 4) fail("SCENARIO_CLEANUP_FAILED");
  };

  const scenarios = Object.freeze({
    run: async ({ scenarioId, iteration, started }) => {
      if (
        started !== active
        || active === null
        || iteration !== 1
        || !Object.hasOwn(scenarioFiles, scenarioId)
      ) {
        fail("PRODUCTION_FIXTURE_SCENARIO_INVALID");
      }
      await assertRestore(emulator, probedProfile, active);
      finalRestoreSucceeded = false;
      const cleanupContext = Object.freeze({
        runId: dependencies.randomUUID(),
        scenarioId,
        iteration,
        targetPackages: scenarioId === "production-native-fixture"
          ? Object.freeze([nativePackage])
          : Object.freeze([webPackage]),
      });
      let authorizationResult;
      let ports;
      try {
        await installApk(apkFiles.app);
        await installApk(
          scenarioId === "production-native-fixture"
            ? apkFiles.native
            : apkFiles.webview,
        );
        authorizationResult = await authorization.setup(Object.freeze({
          profileId: active.profileId,
          apiLevel: active.apiLevel,
          serial: active.serial,
          deviceFingerprint: active.deviceFingerprint,
          buildFingerprint: activeBuildFingerprint,
          snapshot: active.snapshot,
          scenarioId,
          runId: cleanupContext.runId,
          packages: scenarioId === "production-native-fixture"
            ? Object.freeze([appPackage, nativePackage])
            : Object.freeze([appPackage, webPackage]),
          aactlPath,
        }));
        if (
          !exactKeys(
            authorizationResult,
            ["desktopDeviceFingerprint", "ready"],
          )
          || !authorizationResult.ready
          || !fingerprintPattern.test(
            authorizationResult.desktopDeviceFingerprint ?? "",
          )
        ) {
          fail("PRODUCTION_FIXTURE_AUTHORIZATION_SETUP_FAILED");
        }
        const basePorts = await dependencies.createAactlPorts({
          executable: aactlPath,
          repositoryRoot,
          serial: active.serial,
          observationTtlMs: 30_000,
          maxDepth: 64,
          conditionCatalog,
          systemPackages,
        }, {
          execFile: nodeExecFile,
          lifecycle: scenarioLifecycle,
          values: Object.freeze({
            resolve: async (valueRef) => {
              if (valueRef !== "production-fixture-input") {
                fail("PRODUCTION_FIXTURE_VALUE_INVALID");
              }
              return "n47-local-input";
            },
          }),
          artifacts: Object.freeze({
            collect: async () => Object.freeze({
              retained: false,
              errorCode: null,
            }),
          }),
          now: dependencies.now,
        });
        ports = await dependencies.createVisualPorts({
          basePorts,
          executable: aactlPath,
          repositoryRoot,
          serial: active.serial,
          deviceFingerprint: authorizationResult.desktopDeviceFingerprint,
          targetPackages: [nativePackage, webPackage],
        });
      } catch (error) {
        await compensateScenarioSetup(cleanupContext);
        throw error;
      }
      let result;
      try {
        const scenario = await loadScenario(scenarioId);
        result = await dependencies.runScenario({
          scenario,
          iteration,
          device: active.device,
          ports,
          clock: Object.freeze({ now: dependencies.now }),
          runId: cleanupContext.runId,
        });
      } catch (error) {
        await compensateScenarioSetup(cleanupContext);
        throw error;
      }
      if (!exactKeys(result, ["artifact", "artifactRun", "report"])) {
        fail("PRODUCTION_FIXTURE_SCENARIO_RESULT_INVALID");
      }
      return result.report;
    },
  });

  const residuePort = Object.freeze({
    inspect: async (started) => {
      if (started !== active || runtime === null) {
        fail("PRODUCTION_FIXTURE_RESIDUE_INVALID");
      }
      const authorizationResidue = await authorization.inspect(Object.freeze({
        profileId: active.profileId,
        serial: active.serial,
      }));
      if (
        !exactKeys(
          authorizationResidue,
          ["bridgeSessions", "testServices"],
        )
        || !Number.isInteger(authorizationResidue.bridgeSessions)
        || authorizationResidue.bridgeSessions < 0
        || !Number.isInteger(authorizationResidue.testServices)
        || authorizationResidue.testServices < 0
      ) {
        fail("PRODUCTION_FIXTURE_RESIDUE_INVALID");
      }
      const [runtimeFiles, avdLease, portLease, screenshots] =
        await Promise.all([
          dependencies.inspectOwnedPath(runtime.runtimeFile),
          dependencies.inspectOwnedPath(runtime.lease.avdLock),
          dependencies.inspectOwnedPath(runtime.lease.portLock),
          dependencies.inspectOwnedPath(
            path.join(temporaryRoot, "screenshots"),
          ),
        ]);
      return Object.freeze({
        appData: finalRestoreSucceeded ? 0 : 1,
        bridgeSessions: authorizationResidue.bridgeSessions,
        testServices: authorizationResidue.testServices,
        screenshots,
        logs: logRemoved ? 0 : 1,
        ownedProcesses: dependencies.isProcessAlive(runtime.pid) ? 1 : 0,
        runtimeFiles,
        leases: avdLease + portLease,
      });
    },
  });

  return Object.freeze({
    capabilities: capabilitiesPort,
    lifecycle,
    scenarios,
    residue: residuePort,
  });
};
