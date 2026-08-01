#!/usr/bin/env node

// 脚本用途：串行启动 N31 clean AVD，运行 verified APK 安装同字节验证，并在所有路径停止 owned emulator。
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  EmulatorRunner,
  loadProfiles,
  runCommand,
} from "../../emulator/runner.mjs";
import { resolveToolchainEnvironment } from "../../toolchain-environment.mjs";
import { createAndroidApkInspector } from "./apk-inspector.mjs";
import { runVerifiedExternalAppLifecycle } from "./lifecycle.mjs";
import { loadExternalAppManifest } from "./manifest.mjs";
import { createN31InstallRunner } from "./n31-install-runner.mjs";
import { verifyExternalAppArtifact } from "./verifier.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..", "..", "..");
const manifestRoot = path.join(repositoryRoot, "test-lab", "apps", "manifests");
const profileFile = path.join(repositoryRoot, "scripts", "emulator", "profiles.json");
const safeManifest = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.json$/u;
const safeProfile = /^api-(?:30|33|34)$/u;

const fail = (code) => {
  const error = new Error(code);
  error.code = code;
  throw error;
};

const parseArguments = (argv) => {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (
      !["--manifest", "--profile"].includes(flag)
      || typeof value !== "string"
      || value === ""
      || value.startsWith("--")
    ) {
      fail("USAGE_ERROR");
    }
    const name = flag.slice(2);
    if (options[name] !== undefined) fail("USAGE_ERROR");
    options[name] = value;
  }
  if (
    Object.keys(options).length !== 2
    || !safeManifest.test(options.manifest ?? "")
    || !safeProfile.test(options.profile ?? "")
  ) {
    fail("USAGE_ERROR");
  }
  return options;
};

const requiredEnvironment = (environment) => {
  try {
    return resolveToolchainEnvironment(environment, {
      sdkRoot: "ANDROID_SDK_ROOT",
      avdRoot: "ANDROID_AVD_HOME",
      javaHome: "JAVA_HOME",
      stateRoot: "AACTL_EMULATOR_STATE",
      cacheRoot: "AACTL_ANDROID_APK_CACHE",
    });
  } catch {
    fail("ENVIRONMENT_INVALID");
  }
};

export const runVerifiedInstall = async ({
  argv,
  environment,
  dependencies = {},
}) => {
  const options = parseArguments(argv);
  const roots = requiredEnvironment(environment);
  const profiles = await (dependencies.loadProfiles ?? loadProfiles)(profileFile);
  const emulator = dependencies.emulator ?? new EmulatorRunner({
    stateRoot: roots.stateRoot,
    sdkRoot: roots.sdkRoot,
    avdRoot: roots.avdRoot,
    javaHome: roots.javaHome,
    profiles,
  });
  const manifest = await (dependencies.loadManifest ?? loadExternalAppManifest)(
    path.join(manifestRoot, options.manifest),
  );
  const inspector = dependencies.inspector ?? await createAndroidApkInspector({
    repositoryRoot,
    buildToolsDirectory: path.join(roots.sdkRoot, "build-tools", "36.0.0"),
    javaPath: path.join(roots.javaHome, "bin", "java"),
  });
  const descriptor = await (dependencies.verify ?? verifyExternalAppArtifact)({
    manifest,
    cacheRoot: roots.cacheRoot,
    repositoryRoot,
    inspector,
  });

  let started = null;
  let primaryError = null;
  let lifecycleResult = null;
  try {
    started = await emulator.start(options.profile);
    const booted = await emulator.waitForBoot(options.profile);
    if (
      booted.serial !== started.serial
      || booted.deviceFingerprint === null
      || booted.state !== "booted"
    ) {
      fail("RUNNER_CONTEXT_DRIFT");
    }
    const runner = createN31InstallRunner({
      emulatorRunner: emulator,
      command: dependencies.command ?? runCommand,
      adbPath: path.join(roots.sdkRoot, "platform-tools", "adb"),
      inspector,
      profileId: options.profile,
      serial: booted.serial,
      deviceFingerprint: booted.deviceFingerprint,
      tempRoot: path.join(roots.cacheRoot, "temporary"),
    });
    lifecycleResult = await runVerifiedExternalAppLifecycle({
      descriptor,
      serial: booted.serial,
      snapshot: "clean",
      runner,
    });
  } catch (error) {
    primaryError = error;
  }

  let stopError = null;
  if (started !== null) {
    try {
      await emulator.stop(options.profile);
    } catch (error) {
      stopError = error;
    }
  }
  if (stopError !== null) throw stopError;
  if (primaryError !== null) throw primaryError;
  return Object.freeze({
    profileId: options.profile,
    package: descriptor.package,
    version: descriptor.version,
    versionCode: descriptor.versionCode,
    sha256: descriptor.sha256,
    status: lifecycleResult.status,
    installed: lifecycleResult.installed,
  });
};

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const result = await runVerifiedInstall({
      argv: process.argv.slice(2),
      environment: process.env,
    });
    process.stdout.write(`${JSON.stringify({ ok: true, data: result, error: null })}\n`);
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
