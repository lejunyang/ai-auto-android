#!/usr/bin/env node

// 脚本用途：按固定 profile 自动建立 clean 快照并重复运行生命周期，生成可审计且失败即停止的矩阵报告。
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { EmulatorError, EmulatorRunner, loadProfiles } from "./runner.mjs";

const parseArguments = (argv) => {
  const options = {
    iterations: 10,
    profiles: path.join(import.meta.dirname, "profiles.json"),
    useExistingBaseline: false,
    keepAvds: false,
  };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (!flag?.startsWith("--") || value === undefined) {
      throw new EmulatorError("USAGE_ERROR", `invalid argument near ${flag ?? "<end>"}`);
    }
    const name = flag.slice(2);
    if (
      ![
        "sdk-root",
        "avd-root",
        "state-root",
        "java-home",
        "report",
        "profiles",
        "iterations",
        "use-existing-baseline",
        "keep-avds",
      ].includes(name)
    ) {
      throw new EmulatorError("USAGE_ERROR", `unsupported option: ${flag}`);
    }
    options[name] = value;
  }
  for (const name of ["sdk-root", "avd-root", "state-root", "java-home", "report"]) {
    if (!options[name]) throw new EmulatorError("USAGE_ERROR", `--${name} is required`);
  }
  options.iterations = Number(options.iterations);
  if (!Number.isInteger(options.iterations) || options.iterations < 1 || options.iterations > 100) {
    throw new EmulatorError("USAGE_ERROR", "--iterations must be an integer from 1 to 100");
  }
  for (const name of ["use-existing-baseline", "keep-avds"]) {
    const value = options[name];
    if (value === undefined) continue;
    if (!["true", "false"].includes(value)) {
      throw new EmulatorError("USAGE_ERROR", `--${name} must be true or false`);
    }
    options[name === "use-existing-baseline" ? "useExistingBaseline" : "keepAvds"] =
      value === "true";
  }
  return options;
};

const runProfile = async (runner, profile, iterations, options) => {
  const evidence = {
    profileId: profile.id,
    apiLevel: profile.apiLevel,
    systemImage: profile.systemImage,
    systemImageRevision: profile.packageRevision,
    systemImageSha256: profile.packageSha256,
    expectedWebView: {
      packageName: profile.webViewPackage,
      version: profile.webViewVersion,
    },
    baseline: null,
    iterations: [],
    succeeded: false,
  };
  let active = false;
  try {
    if (options.useExistingBaseline) {
      await runner.assertOwnedAvd(profile);
      evidence.baseline = { state: "pre-existing-owned-snapshot" };
    } else {
      await runner.create(profile.id);
      await runner.start(profile.id, { restoreSnapshot: false });
      active = true;
      const baseline = await runner.waitForBoot(profile.id);
      await runner.writeSnapshotMarker(profile.id, "clean");
      await runner.saveSnapshot(profile.id);
      await runner.stop(profile.id);
      active = false;
      evidence.baseline = baseline;
    }

    for (let index = 1; index <= iterations; index += 1) {
      const startedAtMs = Date.now();
      await runner.start(profile.id);
      active = true;
      const booted = await runner.waitForBoot(profile.id);
      const markerBefore = await runner.readSnapshotMarker(profile.id);
      await runner.writeSnapshotMarker(profile.id, `dirty-${index}`);
      const restored = await runner.restore(profile.id);
      const markerAfter = await runner.readSnapshotMarker(profile.id);
      await runner.stop(profile.id);
      active = false;
      evidence.iterations.push({
        iteration: index,
        serial: booted.serial,
        deviceFingerprint: booted.deviceFingerprint,
        restoredFingerprint: restored.deviceFingerprint,
        durationMs: Date.now() - startedAtMs,
        markerBefore,
        markerAfter,
        snapshotConsistent:
          booted.deviceFingerprint === restored.deviceFingerprint
          && markerBefore === "clean"
          && markerAfter === "clean",
      });
    }
    if (!options.keepAvds) await runner.delete(profile.id);
    evidence.succeeded =
      evidence.iterations.length === iterations
      && new Set(evidence.iterations.map((item) => item.serial)).size === iterations
      && evidence.iterations.every((item) => item.snapshotConsistent);
    if (!evidence.succeeded) {
      throw new EmulatorError(
        "MATRIX_ASSERTION_FAILED",
        `${profile.id} did not preserve a consistent snapshot fingerprint`,
      );
    }
    return evidence;
  } catch (error) {
    evidence.error = {
      code: error.code ?? "INTERNAL_ERROR",
      message: error.message,
    };
    if (active) {
      try {
        await runner.stop(profile.id);
      } catch (cleanupError) {
        evidence.cleanupError = {
          code: cleanupError.code ?? "INTERNAL_ERROR",
          message: cleanupError.message,
        };
      }
    }
    try {
      if (!options.keepAvds) await runner.delete(profile.id);
    } catch {
      // stop 失败时必须保留锁和 AVD，避免清理掩盖仍存活的设备。
    }
    return evidence;
  }
};

const main = async () => {
  const options = parseArguments(process.argv.slice(2));
  const profiles = await loadProfiles(path.resolve(options.profiles));
  const runner = new EmulatorRunner({
    sdkRoot: path.resolve(options["sdk-root"]),
    avdRoot: path.resolve(options["avd-root"]),
    javaHome: path.resolve(options["java-home"]),
    stateRoot: path.resolve(options["state-root"]),
    profiles,
  });
  const report = {
    schemaVersion: "1.0",
    startedAt: new Date().toISOString(),
    iterationsPerProfile: options.iterations,
    host: {
      platform: process.platform,
      arch: process.arch,
    },
    toolchain: profiles.toolchain,
    profiles: [],
  };
  for (const profile of profiles.profiles) {
    const result = await runProfile(runner, profile, options.iterations, options);
    report.profiles.push(result);
    if (!result.succeeded) break;
  }
  report.finishedAt = new Date().toISOString();
  report.succeeded =
    report.profiles.length === profiles.profiles.length
    && report.profiles.every((profile) => profile.succeeded);
  const reportFile = path.resolve(options.report);
  await mkdir(path.dirname(reportFile), { recursive: true });
  await writeFile(reportFile, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
  process.stdout.write(`${JSON.stringify(report)}\n`);
  if (!report.succeeded) process.exitCode = 1;
};

try {
  await main();
} catch (error) {
  process.stderr.write(`${error.code ?? "INTERNAL_ERROR"}: ${error.message}\n`);
  process.exitCode = error.code === "USAGE_ERROR" ? 2 : 1;
}
