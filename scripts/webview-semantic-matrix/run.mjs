#!/usr/bin/env node

// 脚本用途：在 N31 固定 profile 上按每轮 clean snapshot 运行二十次 N43 语义设备测试。
import { mkdir, mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  EmulatorRunner,
  loadProfiles,
  runCommand,
} from "../emulator/runner.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..", "..");
const profileFile = path.join(repositoryRoot, "scripts", "emulator", "profiles.json");
const gradlew = path.join(repositoryRoot, "android", "gradlew");
const resultDirectory = path.join(
  repositoryRoot,
  "android",
  "web-fixture",
  "build",
  "outputs",
  "androidTest-results",
  "connected",
  "debug",
);
const testClass =
  "dev.aiauto.webfixture.N43SemanticReplayDeviceTest";
const expectedRoot = "/Volumes/aigo S7 Media/SDK/android-tools";
const profilePattern = /^api-(?:30|33|34)$/u;
const serialPattern = /^emulator-[0-9]{4,5}$/u;
const sha256Pattern = /^[0-9a-f]{64}$/u;
const roundsPerProfile = 20;

const fail = (code) => {
  const error = new Error(code);
  error.code = code;
  throw error;
};

export const parseMatrixArguments = (argv) => {
  if (
    argv.length !== 2
    || argv[0] !== "--profile"
    || !profilePattern.test(argv[1] ?? "")
  ) {
    fail("USAGE_ERROR");
  }
  return Object.freeze({ profile: argv[1] });
};

export const matrixEnvironment = (environment) => {
  const roots = {
    sdkRoot: environment.ANDROID_SDK_ROOT,
    avdRoot: environment.ANDROID_AVD_HOME,
    javaHome: environment.JAVA_HOME,
    stateRoot: environment.AACTL_EMULATOR_STATE,
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

const exactAttributes = (source) => {
  const attributes = {};
  for (const match of source.matchAll(/\s([A-Za-z][A-Za-z0-9_-]*)="([^"]*)"/gu)) {
    if (attributes[match[1]] !== undefined) fail("JUNIT_RESULT_INVALID");
    attributes[match[1]] = match[2];
  }
  return attributes;
};

export const parseJUnitResult = (xml, expectedClass = testClass) => {
  if (
    typeof xml !== "string"
    || Buffer.byteLength(xml, "utf8") < 128
    || Buffer.byteLength(xml, "utf8") > 1024 * 1024
  ) {
    fail("JUNIT_RESULT_INVALID");
  }
  if (
    !/^\s*<\?xml\b[^>]*\?>\s*<testsuites\b[\s\S]*<\/testsuites>\s*$/u.test(xml)
    || [...xml.matchAll(/<testsuites\b/gu)].length !== 1
    || [...xml.matchAll(/<testsuite\b/gu)].length !== 1
  ) {
    fail("JUNIT_RESULT_INVALID");
  }
  const rootMatch = xml.match(/<testsuites\b([^>]*)>/u);
  const suiteMatch = xml.match(/<testsuite\b([^>]*)>/u);
  const caseMatches = [...xml.matchAll(/<testcase\b([^>]*)(?:\/>|>)/gu)];
  if (rootMatch === null || suiteMatch === null || caseMatches.length !== 1) {
    fail("JUNIT_RESULT_INVALID");
  }
  const root = exactAttributes(rootMatch[1]);
  const suite = exactAttributes(suiteMatch[1]);
  const testCase = exactAttributes(caseMatches[0][1]);
  const integers = {};
  for (const name of ["tests", "failures", "errors", "skipped"]) {
    if (!/^(?:0|[1-9][0-9]*)$/u.test(suite[name] ?? "")) {
      fail("JUNIT_RESULT_INVALID");
    }
    integers[name] = Number(suite[name]);
    if (
      root[name] !== undefined
      && (
        !/^(?:0|[1-9][0-9]*)$/u.test(root[name])
        || Number(root[name]) !== integers[name]
      )
    ) {
      fail("JUNIT_RESULT_INVALID");
    }
  }
  const durationSeconds = Number(suite.time);
  if (
    integers.tests !== 1
    || integers.failures + integers.errors + integers.skipped > 1
    || testCase.classname !== expectedClass
    || testCase.name !== "reportSemanticCoverageAndHybridBoundaries"
    || !Number.isFinite(durationSeconds)
    || durationSeconds < 0
    || durationSeconds > 300
  ) {
    fail("JUNIT_RESULT_INVALID");
  }
  const status = integers.failures === 0
    && integers.errors === 0
    && integers.skipped === 0
    ? "passed"
    : integers.failures === 1
      ? "product-failure"
      : "infrastructure-failure";
  return Object.freeze({
    status,
    durationMs: Math.round(durationSeconds * 1_000),
    tests: integers.tests,
    failures: integers.failures,
    errors: integers.errors,
    skipped: integers.skipped,
  });
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

const findFreshJUnit = async (startedAtMs) => {
  let entries;
  try {
    entries = await readdir(resultDirectory, { withFileTypes: true });
  } catch {
    fail("JUNIT_RESULT_MISSING");
  }
  const candidates = [];
  for (const entry of entries) {
    if (!entry.isFile() || !/^TEST-.*\.xml$/u.test(entry.name)) continue;
    const file = path.join(resultDirectory, entry.name);
    const info = await stat(file);
    if (info.mtimeMs + 1_000 < startedAtMs) continue;
    candidates.push(file);
  }
  if (candidates.length !== 1) fail("JUNIT_RESULT_MISSING");
  return readFile(candidates[0], "utf8");
};

export const runWebViewSemanticMatrix = async ({
  argv,
  environment,
  dependencies = {},
}) => {
  const options = parseMatrixArguments(argv);
  const roots = matrixEnvironment(environment);
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
  const createTemporaryRoot = dependencies.createTemporaryRoot
    ?? (() => mkdtemp(path.join(roots.stateRoot, "n43-matrix-")));
  const removeTemporaryRoot = dependencies.removeTemporaryRoot
    ?? ((temporary) => rm(temporary, { recursive: true, force: true }));
  const cleanResults = dependencies.cleanResults
    ?? (() => rm(resultDirectory, { recursive: true, force: true }));
  const readFreshJUnit = dependencies.readFreshJUnit ?? findFreshJUnit;
  const persistReport = dependencies.persistReport ?? (async (report) => {
    const reportDirectory = path.join(roots.stateRoot, "reports");
    await mkdir(reportDirectory, { recursive: true });
    await writeFile(
      path.join(reportDirectory, `n43-semantic-matrix-${profile.id}.json`),
      `${JSON.stringify(report, null, 2)}\n`,
      { mode: 0o600 },
    );
  });
  const temporaryRoot = await createTemporaryRoot();
  if (typeof temporaryRoot !== "string" || !path.isAbsolute(temporaryRoot)) {
    fail("TEMPORARY_ROOT_INVALID");
  }
  const aactlPath = path.join(temporaryRoot, "aactl");
  let started = false;
  let primaryError = null;
  let report = null;
  try {
    assertSuccess(
      await command("go", ["build", "-trimpath", "-o", aactlPath, "./cmd/aactl"], {
        cwd: repositoryRoot,
        env: environment,
        maxBuffer: 4 * 1024 * 1024,
        timeoutMs: 2 * 60 * 1_000,
      }),
      "AACTL_BUILD_FAILED",
    );
    assertSuccess(
      await command(gradlew, [
        "-p",
        "android",
        "--no-daemon",
        ":web-fixture:assembleDebug",
        ":web-fixture:assembleDebugAndroidTest",
      ], {
        cwd: repositoryRoot,
        env: environment,
        maxBuffer: 16 * 1024 * 1024,
        timeoutMs: 5 * 60 * 1_000,
      }),
      "ANDROID_BUILD_FAILED",
    );
    const starting = await emulator.start(profile.id);
    started = true;
    const booted = await emulator.waitForBoot(profile.id);
    if (
      starting.serial !== booted.serial
      || booted.state !== "booted"
      || !serialPattern.test(booted.serial ?? "")
      || !sha256Pattern.test(booted.deviceFingerprint ?? "")
    ) {
      fail("RUNNER_CONTEXT_DRIFT");
    }
    await assertDeviceState(command, aactlPath, booted.serial);
    const rounds = [];
    for (let round = 1; round <= roundsPerProfile; round += 1) {
      const restored = await emulator.restore(profile.id);
      if (
        restored.serial !== booted.serial
        || restored.deviceFingerprint !== booted.deviceFingerprint
        || restored.state !== "booted"
      ) {
        fail("RUNNER_CONTEXT_DRIFT");
      }
      await assertDeviceState(command, aactlPath, booted.serial);
      await cleanResults();
      const startedAtMs = Date.now();
      const result = await command(gradlew, [
        "-p",
        "android",
        "--no-daemon",
        ":web-fixture:connectedDebugAndroidTest",
        `-Pandroid.testInstrumentationRunnerArguments.class=${testClass}`,
        "-Pandroid.testInstrumentationRunnerArguments.n43Repeat=1",
      ], {
        cwd: repositoryRoot,
        env: { ...environment, ANDROID_SERIAL: booted.serial },
        maxBuffer: 16 * 1024 * 1024,
        timeoutMs: 4 * 60 * 1_000,
      });
      let junit;
      try {
        junit = parseJUnitResult(
          await readFreshJUnit(startedAtMs),
          testClass,
        );
      } catch (error) {
        if (result.code === 0) throw error;
        junit = Object.freeze({
          status: "infrastructure-failure",
          durationMs: 0,
          tests: 0,
          failures: 0,
          errors: 1,
          skipped: 0,
        });
      }
      if (result.code === 0 && junit.status !== "passed") {
        fail("JUNIT_RESULT_CONFLICT");
      }
      rounds.push(Object.freeze({ round, ...junit }));
    }
    const passed = rounds.filter((round) => round.status === "passed").length;
    const successRate = passed / roundsPerProfile;
    report = Object.freeze({
      schemaVersion: "1.0",
      profileId: profile.id,
      apiLevel: profile.apiLevel,
      serial: booted.serial,
      deviceFingerprint: booted.deviceFingerprint,
      rounds: Object.freeze(rounds),
      passed,
      failed: roundsPerProfile - passed,
      successRate,
      threshold: 0.95,
      thresholdMet: successRate >= 0.95,
      cleaned: true,
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
  if (primaryError !== null) throw primaryError;
  await persistReport(report);
  return report;
};

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const report = await runWebViewSemanticMatrix({
      argv: process.argv.slice(2),
      environment: process.env,
    });
    process.stdout.write(
      `${JSON.stringify({ ok: true, data: report, error: null })}\n`,
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
