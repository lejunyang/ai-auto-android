#!/usr/bin/env node

// 脚本用途：串行验证五个固定官方 APK 的安装、被动启动与一次脱敏 hierarchy 观察。
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  EmulatorRunner,
  loadProfiles,
  runCommand,
} from "../../emulator/runner.mjs";
import { createAndroidApkInspector } from "./apk-inspector.mjs";
import { runVerifiedExternalAppLifecycle } from "./lifecycle.mjs";
import { loadExternalAppManifest } from "./manifest.mjs";
import { createN31InstallRunner } from "./n31-install-runner.mjs";
import { createPassiveSmokeScenario } from "./passive-smoke.mjs";
import { verifyExternalAppArtifact } from "./verifier.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..", "..", "..");
const manifestRoot = path.join(repositoryRoot, "test-lab", "apps", "manifests");
const profileFile = path.join(repositoryRoot, "scripts", "emulator", "profiles.json");
const expectedRoot = "/Volumes/aigo S7 Media/SDK/android-tools";
const profilePattern = /^api-(?:30|33|34)$/u;
const fixedManifests = Object.freeze([
  "bilibili-9.5.0-arm64.json",
  "douyin-39.8.0-arm64.json",
  "xiaoheihe-1.3.392-arm64.json",
  "weixin-8.0.76-arm64.json",
  "alipay-12.12.10.8000-arm64.json",
]);

const fail = (code) => {
  const error = new Error(code);
  error.code = code;
  throw error;
};

export const parsePassiveSmokeArguments = (argv) => {
  if (
    argv.length !== 2
    || argv[0] !== "--profile"
    || !profilePattern.test(argv[1] ?? "")
  ) {
    fail("USAGE_ERROR");
  }
  return Object.freeze({ profile: argv[1] });
};

export const passiveSmokeEnvironment = (environment) => {
  const roots = {
    sdkRoot: environment.ANDROID_SDK_ROOT,
    avdRoot: environment.ANDROID_AVD_HOME,
    javaHome: environment.JAVA_HOME,
    stateRoot: environment.AACTL_EMULATOR_STATE,
    cacheRoot: environment.AACTL_ANDROID_APK_CACHE,
    goCache: environment.GOCACHE,
    goModCache: environment.GOMODCACHE,
  };
  for (const value of Object.values(roots)) {
    if (
      typeof value !== "string"
      || !path.isAbsolute(value)
      || (value !== expectedRoot && !value.startsWith(`${expectedRoot}${path.sep}`))
    ) {
      fail("ENVIRONMENT_INVALID");
    }
  }
  return Object.freeze(roots);
};

const assertSuccess = (result, code) => {
  if (
    result === null
    || typeof result !== "object"
    || result.code !== 0
    || result.timedOut === true
  ) {
    fail(code);
  }
};

const readDevices = async (command, aactlPath) => {
  const result = await command(aactlPath, ["devices", "list", "--json"], {
    maxBuffer: 1024 * 1024,
    timeoutMs: 30_000,
  });
  assertSuccess(result, "DEVICE_DISCOVERY_FAILED");
  let envelope;
  try {
    envelope = JSON.parse(result.stdout);
  } catch {
    fail("DEVICE_DISCOVERY_FAILED");
  }
  if (
    envelope?.ok !== true
    || !Array.isArray(envelope.data?.devices)
    || envelope.data.count !== envelope.data.devices.length
  ) {
    fail("DEVICE_DISCOVERY_FAILED");
  }
  return envelope.data.devices;
};

const assertDeviceState = async (command, aactlPath, expectedSerial) => {
  const deadline = Date.now() + (expectedSerial === null ? 20_000 : 1);
  do {
    const devices = await readDevices(command, aactlPath);
    const matched = expectedSerial === null
      ? devices.length === 0
      : devices.length === 1
        && devices[0]?.serial === expectedSerial
        && devices[0]?.state === "device"
        && devices[0]?.transport === "emulator";
    if (matched) return;
    if (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  } while (Date.now() < deadline);
  fail("DEVICE_SELECTION_MISMATCH");
};

export const runPassiveSmokeMatrix = async ({
  argv,
  environment,
  dependencies = {},
}) => {
  const options = parsePassiveSmokeArguments(argv);
  const roots = passiveSmokeEnvironment(environment);
  const profiles = await (dependencies.loadProfiles ?? loadProfiles)(profileFile);
  const profile = profiles.profiles.find((candidate) => candidate.id === options.profile);
  if (profile === undefined) fail("PROFILE_NOT_FOUND");
  const command = dependencies.command ?? runCommand;
  const emulator = dependencies.emulator ?? new EmulatorRunner({
    stateRoot: roots.stateRoot,
    sdkRoot: roots.sdkRoot,
    avdRoot: roots.avdRoot,
    javaHome: roots.javaHome,
    profiles,
  });
  const inspector = dependencies.inspector ?? await createAndroidApkInspector({
    repositoryRoot,
    buildToolsDirectory: path.join(roots.sdkRoot, "build-tools", "36.0.0"),
    javaPath: path.join(roots.javaHome, "bin", "java"),
  });
  const descriptors = [];
  for (const name of fixedManifests) {
    const manifest = await (dependencies.loadManifest ?? loadExternalAppManifest)(
      path.join(manifestRoot, name),
    );
    descriptors.push(await (dependencies.verify ?? verifyExternalAppArtifact)({
      manifest,
      cacheRoot: roots.cacheRoot,
      repositoryRoot,
      inspector,
    }));
  }

  const createTemporaryRoot = dependencies.createTemporaryRoot
    ?? (() => mkdtemp(path.join(roots.stateRoot, "passive-smoke-")));
  const removeTemporaryRoot = dependencies.removeTemporaryRoot
    ?? ((temporary) => rm(temporary, { recursive: true, force: true }));
  const persistReport = dependencies.persistReport ?? (async (value) => {
    const reportDirectory = path.join(roots.stateRoot, "reports");
    await mkdir(reportDirectory, { recursive: true });
    await rm(
      path.join(reportDirectory, `passive-app-smoke-${profile.id}-failure.json`),
      { force: true },
    );
    await writeFile(
      path.join(reportDirectory, `passive-app-smoke-${profile.id}.json`),
      `${JSON.stringify(value, null, 2)}\n`,
      { mode: 0o600 },
    );
  });
  const persistFailure = dependencies.persistFailure ?? (async (value) => {
    const reportDirectory = path.join(roots.stateRoot, "reports");
    await mkdir(reportDirectory, { recursive: true });
    await writeFile(
      path.join(reportDirectory, `passive-app-smoke-${profile.id}-failure.json`),
      `${JSON.stringify(value, null, 2)}\n`,
      { mode: 0o600 },
    );
  });
  const temporaryRoot = await createTemporaryRoot();
  if (typeof temporaryRoot !== "string" || !path.isAbsolute(temporaryRoot)) {
    fail("TEMPORARY_ROOT_INVALID");
  }
  const aactlPath = path.join(temporaryRoot, "aactl");
  const createRunner = dependencies.createRunner ?? createN31InstallRunner;
  const lifecycle = dependencies.lifecycle ?? runVerifiedExternalAppLifecycle;
  const createScenario = dependencies.createScenario ?? createPassiveSmokeScenario;
  let started = false;
  let primaryError = null;
  let report = null;
  let currentPackage = null;
  let currentStage = "setup";
  let completedApps = [];
  try {
    currentStage = "build-aactl";
    assertSuccess(
      await command("go", ["build", "-trimpath", "-o", aactlPath, "./cmd/aactl"], {
        cwd: repositoryRoot,
        env: environment,
        maxBuffer: 4 * 1024 * 1024,
        timeoutMs: 2 * 60 * 1000,
      }),
      "AACTL_BUILD_FAILED",
    );
    currentStage = "start-emulator";
    const starting = await emulator.start(profile.id);
    started = true;
    currentStage = "wait-for-boot";
    const booted = await emulator.waitForBoot(profile.id);
    if (
      starting.serial !== booted.serial
      || booted.state !== "booted"
      || typeof booted.deviceFingerprint !== "string"
    ) {
      fail("RUNNER_CONTEXT_DRIFT");
    }
    currentStage = "select-device";
    await assertDeviceState(command, aactlPath, booted.serial);
    const apps = [];
    for (const descriptor of descriptors) {
      currentPackage = descriptor.package;
      currentStage = "install-and-inspect";
      const runner = createRunner({
        emulatorRunner: emulator,
        command,
        adbPath: path.join(roots.sdkRoot, "platform-tools", "adb"),
        inspector,
        profileId: profile.id,
        serial: booted.serial,
        deviceFingerprint: booted.deviceFingerprint,
        tempRoot: path.join(roots.cacheRoot, "temporary"),
      });
      try {
        const lifecycleResult = await lifecycle({
          descriptor,
          serial: booted.serial,
          snapshot: "clean",
          runner,
          scenario: createScenario({
            command,
            aactlPath,
            serial: booted.serial,
            apiLevel: profile.apiLevel,
            targetPackage: descriptor.package,
            settle: dependencies.settle,
            onStage: (stage) => {
              currentStage = stage;
            },
          }),
        });
        apps.push(Object.freeze({
          package: descriptor.package,
          version: descriptor.version,
          versionCode: descriptor.versionCode,
          sha256: descriptor.sha256,
          installed: lifecycleResult.installed,
          observation: Object.freeze({
            status: "observed",
            ...lifecycleResult.scenarioResult,
          }),
        }));
      } catch (error) {
        if (
          error?.code !== "SCENARIO_FAILED"
          && error?.code !== "INSTALLED_INSPECTION_FAILED"
        ) {
          throw error;
        }
        apps.push(Object.freeze({
          package: descriptor.package,
          version: descriptor.version,
          versionCode: descriptor.versionCode,
          sha256: descriptor.sha256,
          installed: error.code === "SCENARIO_FAILED",
          observation: Object.freeze({
            status: "unavailable",
            stage: currentStage,
            errorCode: error.code,
          }),
        }));
      }
      currentStage = "restored";
      completedApps = apps.map((app) => Object.freeze({
        package: app.package,
        status: app.observation.status,
      }));
    }
    report = Object.freeze({
      schemaVersion: "1.0",
      profileId: profile.id,
      apiLevel: profile.apiLevel,
      serial: booted.serial,
      deviceFingerprint: booted.deviceFingerprint,
      apps: Object.freeze(apps),
      succeeded: apps.every((app) => app.observation.status === "observed"),
    });
  } catch (error) {
    primaryError = error;
  }

  let cleanupError = null;
  if (started) {
    try {
      await emulator.restore(profile.id);
      try {
        await emulator.stop(profile.id);
      } catch (error) {
        if (error?.code !== "STOP_FAILED_LOCK_RETAINED") throw error;
        await new Promise((resolve) => setTimeout(resolve, 1_000));
        await emulator.stop(profile.id);
      }
      await assertDeviceState(command, aactlPath, null);
    } catch (error) {
      cleanupError = error;
    }
  }
  await removeTemporaryRoot(temporaryRoot);
  if (cleanupError !== null) throw cleanupError;
  if (primaryError !== null) {
    await persistFailure(Object.freeze({
      schemaVersion: "1.0",
      profileId: profile.id,
      apiLevel: profile.apiLevel,
      package: currentPackage,
      stage: currentStage,
      errorCode: typeof primaryError?.code === "string"
        ? primaryError.code
        : "INTERNAL_ERROR",
      completedApps: Object.freeze(completedApps),
      cleaned: true,
    }));
    throw primaryError;
  }
  await persistReport(report);
  return report;
};

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const result = await runPassiveSmokeMatrix({
      argv: process.argv.slice(2),
      environment: process.env,
    });
    process.stdout.write(
      `${JSON.stringify({ ok: true, data: result, error: null })}\n`,
    );
  } catch (error) {
    process.stdout.write(
      `${JSON.stringify({
        ok: false,
        data: null,
        error: { code: error?.code ?? "INTERNAL_ERROR" },
      })}\n`,
    );
    process.exitCode = 1;
  }
}
