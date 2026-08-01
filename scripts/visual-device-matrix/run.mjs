#!/usr/bin/env node

// 脚本用途：在 N31 owned emulator 上串行执行 N45 固定分辨率与旋转视觉动作矩阵。
import {
  access,
  mkdir,
  mkdtemp,
  readdir,
  readFile,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { inflateRawSync } from "node:zlib";

import {
  EmulatorRunner,
  loadProfiles,
  runCommand,
} from "../emulator/runner.mjs";
import { resolveToolchainEnvironment } from "../toolchain-environment.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..", "..");
const profileFile = path.join(repositoryRoot, "scripts", "emulator", "profiles.json");
const gradlew = path.join(repositoryRoot, "android", "gradlew");
const releaseApk = path.join(
  repositoryRoot,
  "android",
  "app",
  "build",
  "outputs",
  "apk",
  "release",
  "app-release-unsigned.apk",
);
const resultDirectory = path.join(
  repositoryRoot,
  "android",
  "app",
  "build",
  "outputs",
  "androidTest-results",
  "connected",
  "debug",
);
const testClass =
  "dev.aiauto.android.automation.recording.replay.visual.N45VisualDeviceMatrixTest";
const testMethod = "runVisualActionMatrixCase";
const fixedMarker = "AI_AUTO_TEST_ONLY_V1";
const profilePattern = /^api-(?:30|33|34)$/u;
const emulatorSerialPattern = /^emulator-[0-9]{4,5}$/u;
const sha256Pattern = /^[0-9a-f]{64}$/u;
const resolutions = Object.freeze(["720x1600", "1080x2400", "1440x3200"]);
const rotations = Object.freeze([0, 90]);
const ROTATION_SETTLE_TIMEOUT_MS = 10_000;
const ROTATION_SETTLE_POLL_MS = 250;
const forbiddenReleaseText = Object.freeze([
  "N45VisualDeviceHarness",
  "N45VisualDeviceMatrixTest",
  "N45VisualTestAction",
  "N45VisualEvidenceMutation",
  "n45MatrixResolution",
  "n45MatrixRotation",
  "AI_AUTO_TEST_ONLY_V1",
]);
const maxApkBytes = 512 * 1024 * 1024;
const maxEntryBytes = 64 * 1024 * 1024;

const fail = (code) => {
  const error = new Error(code);
  error.code = code;
  throw error;
};

export const parseArguments = (argv) => {
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
    fail("ENVIRONMENT_INVALID");
  }
};

export const matrixCases = () => Object.freeze(
  resolutions.flatMap((resolution) =>
    rotations.map((rotation) => Object.freeze({ resolution, rotation }))),
);

export const parseWindowRotation = (output) => {
  if (typeof output !== "string" || Buffer.byteLength(output, "utf8") > 4 * 1024 * 1024) {
    fail("DEVICE_CONFIG_DRIFT");
  }
  const values = [...output.matchAll(/^\s*mRotation=([0-3])\b.*$/gmu)];
  if (values.length !== 1) fail("DEVICE_CONFIG_DRIFT");
  return Number(values[0][1]);
};

export const rotationCommands = (apiLevel, rotationValue) => {
  if (![30, 33, 34].includes(apiLevel) || !["0", "1"].includes(rotationValue)) {
    fail("MATRIX_CASE_INVALID");
  }
  return apiLevel === 30
    ? Object.freeze([
      Object.freeze(["shell", "wm", "set-fix-to-user-rotation", "enabled"]),
      Object.freeze(["shell", "wm", "set-user-rotation", "lock", rotationValue]),
    ])
    : Object.freeze([
      Object.freeze(["shell", "wm", "fixed-to-user-rotation", "enabled"]),
      Object.freeze(["shell", "wm", "user-rotation", "lock", rotationValue]),
    ]);
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
    || !/^\s*<\?xml\b[^>]*\?>\s*<testsuites\b[\s\S]*<\/testsuites>\s*$/u.test(xml)
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
  const counts = {};
  for (const name of ["tests", "failures", "errors", "skipped"]) {
    if (!/^(?:0|[1-9][0-9]*)$/u.test(suite[name] ?? "")) {
      fail("JUNIT_RESULT_INVALID");
    }
    counts[name] = Number(suite[name]);
    if (
      root[name] !== undefined
      && (
        !/^(?:0|[1-9][0-9]*)$/u.test(root[name])
        || Number(root[name]) !== counts[name]
      )
    ) {
      fail("JUNIT_RESULT_INVALID");
    }
  }
  const durationSeconds = Number(suite.time);
  if (
    counts.tests !== 1
    || counts.failures + counts.errors + counts.skipped > 1
    || testCase.classname !== expectedClass
    || testCase.name !== testMethod
    || !Number.isFinite(durationSeconds)
    || durationSeconds < 0
    || durationSeconds > 300
  ) {
    fail("JUNIT_RESULT_INVALID");
  }
  const status = counts.failures === 0
    && counts.errors === 0
    && counts.skipped === 0
    ? "passed"
    : counts.failures === 1
      ? "product-failure"
      : "infrastructure-failure";
  return Object.freeze({
    status,
    durationMs: Math.round(durationSeconds * 1_000),
    ...counts,
  });
};

export const selectFreshJUnit = (entries, startedAtMs) => {
  const fresh = entries.filter(
    (entry) =>
      typeof entry?.file === "string"
      && Number.isFinite(entry?.mtimeMs)
      && entry.mtimeMs + 1_000 >= startedAtMs,
  );
  if (fresh.length !== 1) fail("JUNIT_RESULT_MISSING");
  return fresh[0].file;
};

export const inspectReleaseApkBytes = (apk) => {
  if (!Buffer.isBuffer(apk) || apk.length <= 4 || apk.length > maxApkBytes) {
    fail("RELEASE_APK_INVALID");
  }
  const findings = [];
  let offset = 0;
  let entries = 0;
  while (offset + 4 <= apk.length && apk.readUInt32LE(offset) === 0x04034b50) {
    if (offset + 30 > apk.length) fail("RELEASE_APK_INVALID");
    const flags = apk.readUInt16LE(offset + 6);
    const method = apk.readUInt16LE(offset + 8);
    const compressedSize = apk.readUInt32LE(offset + 18);
    const uncompressedSize = apk.readUInt32LE(offset + 22);
    const nameLength = apk.readUInt16LE(offset + 26);
    const extraLength = apk.readUInt16LE(offset + 28);
    if (
      flags & 0x08
      || ![0, 8].includes(method)
      || compressedSize > maxEntryBytes
      || uncompressedSize > maxEntryBytes
    ) {
      fail("RELEASE_APK_INVALID");
    }
    const nameStart = offset + 30;
    const contentStart = nameStart + nameLength + extraLength;
    const contentEnd = contentStart + compressedSize;
    if (contentEnd > apk.length) fail("RELEASE_APK_INVALID");
    const name = apk.subarray(nameStart, nameStart + nameLength).toString("utf8");
    let content;
    try {
      content = method === 8
        ? inflateRawSync(apk.subarray(contentStart, contentEnd), {
          maxOutputLength: maxEntryBytes,
        })
        : apk.subarray(contentStart, contentEnd);
    } catch {
      fail("RELEASE_APK_INVALID");
    }
    if (content.length !== uncompressedSize) fail("RELEASE_APK_INVALID");
    for (const forbidden of forbiddenReleaseText) {
      if (
        content.includes(Buffer.from(forbidden, "utf8"))
        || content.includes(Buffer.from(forbidden, "utf16le"))
      ) {
        findings.push(`${name}:${forbidden}`);
      }
    }
    entries += 1;
    offset = contentEnd;
  }
  if (entries === 0) fail("RELEASE_APK_INVALID");
  return Object.freeze(findings);
};

const inspectReleaseApk = async () => {
  let apk;
  try {
    apk = await readFile(releaseApk);
  } catch {
    fail("RELEASE_APK_INVALID");
  }
  if (inspectReleaseApkBytes(apk).length !== 0) {
    fail("RELEASE_SCAN_FAILED");
  }
};

const findFreshJUnit = async (startedAtMs) => {
  const pending = [resultDirectory];
  const candidates = [];
  while (pending.length > 0) {
    const directory = pending.shift();
    let entries;
    try {
      entries = await readdir(directory, { withFileTypes: true });
    } catch {
      fail("JUNIT_RESULT_MISSING");
    }
    for (const entry of entries) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        pending.push(file);
      } else if (entry.isFile() && /^TEST-.*\.xml$/u.test(entry.name)) {
        candidates.push({ file, mtimeMs: (await stat(file)).mtimeMs });
      }
    }
  }
  return readFile(selectFreshJUnit(candidates, startedAtMs), "utf8");
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

const readDevices = async (command, aactlPath, environment) => {
  const result = await command(aactlPath, ["devices", "list", "--json"], {
    cwd: repositoryRoot,
    env: environment,
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

const assertEmulatorState = async (
  command,
  aactlPath,
  environment,
  expectedSerial,
) => {
  const deadline = Date.now() + (expectedSerial === null ? 20_000 : 1);
  do {
    const devices = await readDevices(command, aactlPath, environment);
    const emulators = devices.filter((device) => device?.transport === "emulator");
    const matched = expectedSerial === null
      ? emulators.length === 0
      : emulators.length === 1
        && emulators[0]?.serial === expectedSerial
        && emulators[0]?.state === "device";
    if (matched) return;
    if (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  } while (Date.now() < deadline);
  fail("DEVICE_SELECTION_MISMATCH");
};

const defaultReadRuntime = async (stateRoot, profile, booted) => {
  let runtime;
  try {
    runtime = JSON.parse(await readFile(
      path.join(stateRoot, "runtime", `${profile.avdName}.json`),
      "utf8",
    ));
  } catch {
    fail("RUNNER_CONTEXT_DRIFT");
  }
  if (
    runtime.profileId !== profile.id
    || runtime.serial !== booted.serial
    || runtime.deviceFingerprint !== booted.deviceFingerprint
    || typeof runtime.metadata?.buildFingerprint !== "string"
    || typeof runtime.lease?.avdLock !== "string"
    || typeof runtime.lease?.portLock !== "string"
  ) {
    fail("RUNNER_CONTEXT_DRIFT");
  }
  return runtime;
};

const assertContext = (value, booted) => {
  if (
    value?.serial !== booted.serial
    || value?.deviceFingerprint !== booted.deviceFingerprint
    || value?.state !== "booted"
  ) {
    fail("RUNNER_CONTEXT_DRIFT");
  }
};

export const configureFixedVisualCase = async (
  emulator,
  profile,
  booted,
  resolution,
  rotation,
) => {
  if (typeof emulator.configureVisualMatrix === "function") {
    return emulator.configureVisualMatrix(profile.id, resolution, rotation);
  }
  if (!resolutions.includes(resolution) || !rotations.includes(rotation)) {
    fail("MATRIX_CASE_INVALID");
  }
  const marker = await emulator.readSnapshotMarker(profile.id);
  if (marker !== "clean") fail("SNAPSHOT_MARKER_MISMATCH");
  const adb = emulator.tools().adb;
  const environment = emulator.environment();
  const rotationValue = rotation === 90 ? "1" : "0";
  const commands = [
    ["shell", "wm", "size", resolution],
    ["shell", "wm", "density", String(profile.densityDpi)],
    ...rotationCommands(profile.apiLevel, rotationValue),
  ];
  // 这里只接受上方枚举生成的固定矩阵命令，调用方不能传入 serial、shell 或额外参数。
  for (const args of commands) {
    assertSuccess(
      await emulator.command(
        adb,
        ["-s", booted.serial, ...args],
        { env: environment, timeoutMs: 10_000 },
      ),
      "DEVICE_CONFIG_FAILED",
    );
  }
  const rotationDeadline = Date.now() + ROTATION_SETTLE_TIMEOUT_MS;
  while (true) {
    const [size, density, currentRotation] = await Promise.all([
      emulator.command(
        adb,
        ["-s", booted.serial, "shell", "wm", "size"],
        { env: environment, timeoutMs: 10_000 },
      ),
      emulator.command(
        adb,
        ["-s", booted.serial, "shell", "wm", "density"],
        { env: environment, timeoutMs: 10_000 },
      ),
      emulator.command(
        adb,
        ["-s", booted.serial, "shell", "dumpsys", "window", "displays"],
        { env: environment, timeoutMs: 10_000 },
      ),
    ]);
    for (const result of [size, density, currentRotation]) {
      assertSuccess(result, "DEVICE_CONFIG_FAILED");
    }
    const actualSize = [...size.stdout.matchAll(/[0-9]+x[0-9]+/gu)].at(-1)?.[0];
    const actualDensity = Number(
      [...density.stdout.matchAll(/[0-9]+/gu)].at(-1)?.[0],
    );
    let actualRotation = null;
    try {
      actualRotation = parseWindowRotation(currentRotation.stdout);
    } catch (error) {
      if (error?.code !== "DEVICE_CONFIG_DRIFT") throw error;
    }
    if (
      actualSize === resolution
      && actualDensity === profile.densityDpi
      && actualRotation === Number(rotationValue)
    ) {
      break;
    }
    if (Date.now() >= rotationDeadline) fail("DEVICE_CONFIG_DRIFT");
    if (actualRotation !== Number(rotationValue)) {
      for (const args of rotationCommands(profile.apiLevel, rotationValue)) {
        assertSuccess(
          await emulator.command(
            adb,
            ["-s", booted.serial, ...args],
            { env: environment, timeoutMs: 10_000 },
          ),
          "DEVICE_CONFIG_FAILED",
        );
      }
    }
    await new Promise((resolve) => setTimeout(resolve, ROTATION_SETTLE_POLL_MS));
  }
  return Object.freeze({
    ...booted,
    resolution,
    rotation,
  });
};

const runInstrumentation = async ({
  command,
  environment,
  profile,
  runtime,
  booted,
  resolution,
  rotation,
}) => command(gradlew, [
  "-p",
  "android",
  "--no-daemon",
  ":app:connectedDebugAndroidTest",
  `-Pandroid.testInstrumentationRunnerArguments.class=${testClass}`,
  `-Pandroid.testInstrumentationRunnerArguments.n32EmulatorSerial=${booted.serial}`,
  `-Pandroid.testInstrumentationRunnerArguments.n32ProfileId=${profile.id}`,
  `-Pandroid.testInstrumentationRunnerArguments.n32AvdFingerprint=${runtime.deviceFingerprint}`,
  `-Pandroid.testInstrumentationRunnerArguments.n32ExpectedBuildFingerprint=${runtime.metadata.buildFingerprint}`,
  `-Pandroid.testInstrumentationRunnerArguments.n32TestOnlyMarker=${fixedMarker}`,
  `-Pandroid.testInstrumentationRunnerArguments.n45MatrixResolution=${resolution}`,
  `-Pandroid.testInstrumentationRunnerArguments.n45MatrixRotation=${rotation}`,
], {
  cwd: repositoryRoot,
  env: { ...environment, ANDROID_SERIAL: booted.serial },
  maxBuffer: 16 * 1024 * 1024,
  timeoutMs: 5 * 60 * 1_000,
});

const defaultAssertNoResidue = async (runtime) => {
  const paths = [
    runtime.runtimeFile,
    runtime.lease.avdLock,
    runtime.lease.portLock,
  ];
  for (const residue of paths) {
    try {
      await access(residue);
      fail("EMULATOR_RESIDUE_FOUND");
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
  }
};

export const runVisualDeviceMatrix = async ({
  argv,
  environment,
  dependencies = {},
}) => {
  const options = parseArguments(argv);
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
    ?? (() => mkdtemp(path.join(roots.stateRoot, "n45-matrix-")));
  const removeTemporaryRoot = dependencies.removeTemporaryRoot
    ?? ((temporary) => rm(temporary, { recursive: true, force: true }));
  const cleanResults = dependencies.cleanResults
    ?? (() => rm(resultDirectory, { recursive: true, force: true }));
  const readFreshJUnit = dependencies.readFreshJUnit ?? findFreshJUnit;
  const readRuntime = dependencies.readRuntime ?? defaultReadRuntime;
  const assertNoResidue = dependencies.assertNoResidue ?? defaultAssertNoResidue;
  const persistReport = dependencies.persistReport ?? (async (report) => {
    const reportDirectory = path.join(roots.stateRoot, "reports");
    await mkdir(reportDirectory, { recursive: true });
    await writeFile(
      path.join(reportDirectory, `n45-visual-matrix-${profile.id}.json`),
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
  let runtime = null;
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
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        ":app:assembleRelease",
      ], {
        cwd: repositoryRoot,
        env: environment,
        maxBuffer: 16 * 1024 * 1024,
        timeoutMs: 6 * 60 * 1_000,
      }),
      "ANDROID_BUILD_FAILED",
    );
    assertSuccess(
      await command(gradlew, [
        "-p",
        "android",
        "--no-daemon",
        "--no-configuration-cache",
        ":test-control-core:verifyReleaseApk",
        `-PreleaseApk=${releaseApk}`,
      ], {
        cwd: repositoryRoot,
        env: environment,
        maxBuffer: 8 * 1024 * 1024,
        timeoutMs: 3 * 60 * 1_000,
      }),
      "RELEASE_SCAN_FAILED",
    );
    await (dependencies.inspectReleaseApk ?? inspectReleaseApk)();
    await assertEmulatorState(command, aactlPath, environment, null);
    const starting = await emulator.start(profile.id);
    started = true;
    const booted = await emulator.waitForBoot(profile.id);
    if (
      starting.serial !== booted.serial
      || booted.state !== "booted"
      || !emulatorSerialPattern.test(booted.serial ?? "")
      || !sha256Pattern.test(booted.deviceFingerprint ?? "")
    ) {
      fail("RUNNER_CONTEXT_DRIFT");
    }
    runtime = await readRuntime(roots.stateRoot, profile, booted);
    if (
      runtime?.profileId !== profile.id
      || runtime?.serial !== booted.serial
      || runtime?.deviceFingerprint !== booted.deviceFingerprint
      || typeof runtime?.metadata?.buildFingerprint !== "string"
      || typeof runtime?.lease?.avdLock !== "string"
      || typeof runtime?.lease?.portLock !== "string"
    ) {
      fail("RUNNER_CONTEXT_DRIFT");
    }
    runtime = Object.freeze({
      ...runtime,
      runtimeFile: path.join(
        roots.stateRoot,
        "runtime",
        `${profile.avdName}.json`,
      ),
    });
    await assertEmulatorState(command, aactlPath, environment, booted.serial);
    const cases = [];
    for (const matrixCase of matrixCases()) {
      const restored = await emulator.restore(profile.id);
      assertContext(restored, booted);
      const configured = await configureFixedVisualCase(
        emulator,
        profile,
        booted,
        matrixCase.resolution,
        matrixCase.rotation,
      );
      if (
        configured?.serial !== booted.serial
        || configured?.deviceFingerprint !== booted.deviceFingerprint
        || configured?.resolution !== matrixCase.resolution
        || configured?.rotation !== matrixCase.rotation
      ) {
        fail("RUNNER_CONTEXT_DRIFT");
      }
      await assertEmulatorState(command, aactlPath, environment, booted.serial);
      await cleanResults();
      const startedAtMs = Date.now();
      const result = await runInstrumentation({
        command,
        environment,
        profile,
        runtime,
        booted,
        ...matrixCase,
      });
      let junit;
      try {
        junit = parseJUnitResult(await readFreshJUnit(startedAtMs), testClass);
      } catch (error) {
        if (result.code === 0) throw error;
        fail("INSTRUMENTATION_FAILED");
      }
      if (result.code !== 0 || junit.status !== "passed") {
        fail(
          junit.status === "product-failure"
            ? "MATRIX_CASE_FAILED"
            : "INSTRUMENTATION_FAILED",
        );
      }
      cases.push(Object.freeze({ ...matrixCase, ...junit }));
    }
    report = Object.freeze({
      schemaVersion: "1.0",
      profileId: profile.id,
      apiLevel: profile.apiLevel,
      serial: booted.serial,
      deviceFingerprint: booted.deviceFingerprint,
      cases: Object.freeze(cases),
      passed: cases.length,
      failed: 0,
      succeeded: cases.length === matrixCases().length,
      cleaned: true,
    });
  } catch (error) {
    primaryError = error;
  }

  let cleanupError = null;
  if (started) {
    try {
      await emulator.restore(profile.id);
      await emulator.stop(profile.id);
      await assertEmulatorState(command, aactlPath, environment, null);
      await assertNoResidue(runtime);
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
    const report = await runVisualDeviceMatrix({
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
