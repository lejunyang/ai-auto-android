#!/usr/bin/env node

// 脚本用途：在 N31 owned emulator 上串行编排生产 Go/Kotlin LAN RPC 的真实 TCP 互操作矩阵。
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import readline from "node:readline";
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
const fixedTestClass =
  "dev.aiauto.android.bridge.lan.LanCrossRuntimeDeviceTest";
const fixedMarker = "AI_AUTO_TEST_ONLY_V1";
const externalRoot = "/Volumes/aigo S7 Media/SDK/android-tools";
const profilePattern = /^api-(?:30|33|34)$/u;
const interfacePattern = /^if-[1-9][0-9]{0,5}-[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/u;
const addressPattern =
  /^(?:10(?:\.(?:0|[1-9][0-9]{0,2})){3}|192\.168(?:\.(?:0|[1-9][0-9]{0,2})){2}|172\.(?:1[6-9]|2[0-9]|3[01])(?:\.(?:0|[1-9][0-9]{0,2})){2})$/u;

const fail = (code) => {
  const error = new Error(code);
  error.code = code;
  throw error;
};

export const parseArguments = (argv) => {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (
      !["--profile", "--interface", "--address"].includes(flag)
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
    Object.keys(options).length !== 3
    || !profilePattern.test(options.profile ?? "")
    || !interfacePattern.test(options.interface ?? "")
    || !addressPattern.test(options.address ?? "")
  ) {
    fail("USAGE_ERROR");
  }
  const octets = options.address.split(".").map(Number);
  if (octets.length !== 4 || octets.some((value) => value > 255)) {
    fail("USAGE_ERROR");
  }
  return Object.freeze(options);
};

export const requiredEnvironment = (environment) => {
  const values = {
    sdkRoot: environment.ANDROID_SDK_ROOT,
    avdRoot: environment.ANDROID_AVD_HOME,
    javaHome: environment.JAVA_HOME,
    stateRoot: environment.AACTL_EMULATOR_STATE,
    goCache: environment.GOCACHE,
    goModCache: environment.GOMODCACHE,
  };
  for (const value of Object.values(values)) {
    if (
      typeof value !== "string"
      || !path.isAbsolute(value)
      || (value !== externalRoot && !value.startsWith(`${externalRoot}${path.sep}`))
    ) {
      fail("ENVIRONMENT_INVALID");
    }
  }
  return Object.freeze(values);
};

const exactKeys = (value, keys) => {
  if (
    value === null
    || typeof value !== "object"
    || Array.isArray(value)
    || JSON.stringify(Object.keys(value).sort()) !== JSON.stringify([...keys].sort())
  ) {
    fail("HOST_OUTPUT_INVALID");
  }
  return value;
};

export const parseInvitationLine = (line, kind) => {
  let parsed;
  try {
    parsed = JSON.parse(line);
  } catch {
    fail("HOST_OUTPUT_INVALID");
  }
  const data = kind === "cli"
    ? exactKeys(parsed, ["schemaVersion", "requestId", "ok", "data", "error", "meta"]).data
    : parsed;
  if (kind === "cli") {
    if (parsed.schemaVersion !== "1.0" || parsed.ok !== true || parsed.error !== null) {
      fail("HOST_OUTPUT_INVALID");
    }
    exactKeys(data, [
      "phase",
      "invitationJson",
      "manualCode",
      "fingerprint",
      "endpoint",
      "expiresAt",
      "qrGenerated",
      "qrFormat",
    ]);
  } else {
    exactKeys(data, ["phase", "invitationJson", "fingerprint"]);
  }
  if (
    data.phase !== "invitation"
    || typeof data.invitationJson !== "object"
    || data.invitationJson === null
    || typeof data.fingerprint !== "string"
  ) {
    fail("HOST_OUTPUT_INVALID");
  }
  const bytes = Buffer.from(JSON.stringify(data.invitationJson), "utf8");
  if (bytes.length < 128 || bytes.length > 24_576) fail("HOST_OUTPUT_INVALID");
  return Object.freeze({
    argument: bytes.toString("base64url"),
    fingerprint: data.fingerprint,
  });
};

export const validateCliCompletion = (lines, expectedApi) => {
  if (lines.length !== 4) fail("HOST_OUTPUT_INVALID");
  const phases = lines.map((line) => {
    let envelope;
    try {
      envelope = JSON.parse(line);
    } catch {
      fail("HOST_OUTPUT_INVALID");
    }
    exactKeys(envelope, ["schemaVersion", "requestId", "ok", "data", "error", "meta"]);
    if (envelope.schemaVersion !== "1.0" || !envelope.ok || envelope.error !== null) {
      fail("HOST_OUTPUT_INVALID");
    }
    return envelope.data;
  });
  phases.slice(0, 3).forEach((data, index) => {
    exactKeys(data, ["phase", "method", "iteration", "result"]);
    if (
      data.phase !== "rpc"
      || data.method !== "device.info"
      || data.iteration !== index + 1
      || data.result?.apiLevel !== expectedApi
    ) {
      fail("HOST_OUTPUT_INVALID");
    }
  });
  const closed = exactKeys(
    phases[3],
    ["phase", "authenticated", "endpoint", "capabilities", "expiresAt"],
  );
  if (closed.phase !== "closed" || closed.authenticated !== true) {
    fail("HOST_OUTPUT_INVALID");
  }
};

export const validateAuthCompletion = (lines) => {
  if (lines.length !== 1) fail("HOST_OUTPUT_INVALID");
  let result;
  try {
    result = JSON.parse(lines[0]);
  } catch {
    fail("HOST_OUTPUT_INVALID");
  }
  exactKeys(result, ["phase", "errorCode", "peerClosed"]);
  if (
    result.phase !== "result"
    || result.errorCode !== "AUTH_INVALID"
    || result.peerClosed !== true
  ) {
    fail("HOST_OUTPUT_INVALID");
  }
};

class HostProcess {
  constructor(command, args, environment) {
    this.child = spawn(command, args, {
      cwd: repositoryRoot,
      env: environment,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    });
    this.lines = readline.createInterface({ input: this.child.stdout });
    this.iterator = this.lines[Symbol.asyncIterator]();
    this.stderr = "";
    this.child.stderr.on("data", (chunk) => {
      if (this.stderr.length < 128 * 1024) this.stderr += chunk.toString("utf8");
    });
    this.exit = new Promise((resolve, reject) => {
      this.child.once("error", reject);
      this.child.once("exit", (code, signal) => resolve({ code, signal }));
    });
  }

  async invitation(kind) {
    let timeout;
    const next = await Promise.race([
      this.iterator.next(),
      new Promise((_, reject) => {
        timeout = setTimeout(
          () => {
            reject(Object.assign(new Error("HOST_INVITATION_TIMEOUT"), {
              code: "HOST_INVITATION_TIMEOUT",
            }));
          },
          30_000,
        );
      }),
    ]).finally(() => clearTimeout(timeout));
    if (next.done) fail("HOST_OUTPUT_INVALID");
    return parseInvitationLine(next.value, kind);
  }

  async finish() {
    const collect = async () => {
      const lines = [];
      for await (const line of this.iterator) {
        if (lines.length >= 16 || line.length > 2 * 1024 * 1024) {
          fail("HOST_OUTPUT_INVALID");
        }
        lines.push(line);
      }
      return lines;
    };
    let timeout;
    const lines = await Promise.race([
      collect(),
      new Promise((_, reject) => {
        timeout = setTimeout(
          () => reject(Object.assign(new Error("HOST_COMPLETION_TIMEOUT"), {
            code: "HOST_COMPLETION_TIMEOUT",
          })),
          30_000,
        );
      }),
    ]).finally(() => clearTimeout(timeout));
    let exitTimeout;
    const exit = await Promise.race([
      this.exit,
      new Promise((_, reject) => {
        exitTimeout = setTimeout(
          () => reject(Object.assign(new Error("HOST_EXIT_TIMEOUT"), {
            code: "HOST_EXIT_TIMEOUT",
          })),
          5_000,
        );
      }),
    ]).finally(() => clearTimeout(exitTimeout));
    if (exit.code !== 0 || exit.signal !== null || this.stderr.trim() !== "") {
      fail("HOST_FAILED");
    }
    return lines;
  }

  stop() {
    if (this.child.exitCode === null && this.child.signalCode === null) {
      this.child.kill("SIGTERM");
    }
  }
}

const assertCommandSuccess = (result, code) => {
  if (
    result === null
    || typeof result !== "object"
    || result.code !== 0
    || result.timedOut === true
  ) {
    fail(code);
  }
};

const assertSelectedDevice = async (command, aactl, expectedSerial) => {
  const deadline = Date.now() + (expectedSerial === null ? 20_000 : 1);
  do {
    const result = await command(aactl, ["devices", "list", "--json"], {
      cwd: repositoryRoot,
      env: process.env,
      maxBuffer: 1024 * 1024,
      timeoutMs: 30_000,
    });
    assertCommandSuccess(result, "DEVICE_DISCOVERY_FAILED");
    let envelope;
    try {
      envelope = JSON.parse(result.stdout);
    } catch {
      fail("DEVICE_DISCOVERY_FAILED");
    }
    const devices = envelope?.ok === true && Array.isArray(envelope.data?.devices)
      ? envelope.data.devices
      : null;
    const matched = devices !== null
      && devices.length === (expectedSerial === null ? 0 : 1)
      && (
        expectedSerial === null
        || (
          devices[0]?.serial === expectedSerial
          && devices[0]?.state === "device"
        )
      );
    if (matched) return;
    if (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  } while (Date.now() < deadline);
  fail("DEVICE_SELECTION_MISMATCH");
};

const runGradle = async (command, serial, runtime, profile, invitation, scenario) => {
  const args = [
    "-p",
    "android",
    "--no-daemon",
    ":app:connectedDebugAndroidTest",
    `-Pandroid.testInstrumentationRunnerArguments.class=${fixedTestClass}`,
    `-Pandroid.testInstrumentationRunnerArguments.n32EmulatorSerial=${serial}`,
    `-Pandroid.testInstrumentationRunnerArguments.n32ProfileId=${profile.id}`,
    `-Pandroid.testInstrumentationRunnerArguments.n32AvdFingerprint=${runtime.deviceFingerprint}`,
    `-Pandroid.testInstrumentationRunnerArguments.n32ExpectedBuildFingerprint=${runtime.metadata.buildFingerprint}`,
    `-Pandroid.testInstrumentationRunnerArguments.n32TestOnlyMarker=${fixedMarker}`,
    `-Pandroid.testInstrumentationRunnerArguments.lanInteropInvitation=${invitation.argument}`,
    `-Pandroid.testInstrumentationRunnerArguments.lanInteropScenario=${scenario}`,
    `-Pandroid.testInstrumentationRunnerArguments.lanInteropExpectedApi=${profile.apiLevel}`,
  ];
  const result = await command(gradlew, args, {
    cwd: repositoryRoot,
    env: { ...process.env, ANDROID_SERIAL: serial },
    maxBuffer: 16 * 1024 * 1024,
    timeoutMs: 4 * 60 * 1000,
  });
  assertCommandSuccess(result, "INSTRUMENTATION_FAILED");
};

const readRuntime = async (stateRoot, profile, booted) => {
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
  ) {
    fail("RUNNER_CONTEXT_DRIFT");
  }
  return runtime;
};

const runScenario = async ({
  hostCommand,
  hostArgs,
  hostKind,
  scenario,
  emulator,
  profile,
  booted,
  runtime,
  command,
}) => {
  const host = new HostProcess(hostCommand, hostArgs, process.env);
  try {
    const invitation = await host.invitation(hostKind);
    await runGradle(
      command,
      booted.serial,
      runtime,
      profile,
      invitation,
      scenario,
    );
    const lines = await host.finish();
    if (hostKind === "cli") {
      validateCliCompletion(lines, profile.apiLevel);
    } else {
      validateAuthCompletion(lines);
    }
    return Object.freeze({
      scenario,
      fingerprint: invitation.fingerprint,
      status: "passed",
    });
  } finally {
    host.stop();
    const restored = await emulator.restore(profile.id);
    if (
      restored.serial !== booted.serial
      || restored.deviceFingerprint !== runtime.deviceFingerprint
      || restored.state !== "booted"
    ) {
      fail("RUNNER_CONTEXT_DRIFT");
    }
  }
};

export const runInterop = async ({
  argv,
  environment,
  dependencies = {},
}) => {
  const options = parseArguments(argv);
  const roots = requiredEnvironment(environment);
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
  const temporaryRoot = await mkdtemp(path.join(roots.stateRoot, "lan-interop-"));
  const aactl = path.join(temporaryRoot, "aactl");
  const authHost = path.join(temporaryRoot, "auth-invalid-host");
  let started = false;
  let primaryError = null;
  let report = null;
  try {
    assertCommandSuccess(
      await command("go", ["build", "-trimpath", "-o", aactl, "./cmd/aactl"], {
        cwd: repositoryRoot,
        env: process.env,
        maxBuffer: 4 * 1024 * 1024,
        timeoutMs: 2 * 60 * 1000,
      }),
      "HOST_BUILD_FAILED",
    );
    assertCommandSuccess(
      await command(
        "go",
        ["build", "-trimpath", "-o", authHost, "./scripts/lan-interop/auth-invalid-host"],
        {
          cwd: repositoryRoot,
          env: process.env,
          maxBuffer: 4 * 1024 * 1024,
          timeoutMs: 2 * 60 * 1000,
        },
      ),
      "HOST_BUILD_FAILED",
    );
    assertCommandSuccess(
      await command(gradlew, [
        "-p",
        "android",
        "--no-daemon",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
      ], {
        cwd: repositoryRoot,
        env: process.env,
        maxBuffer: 16 * 1024 * 1024,
        timeoutMs: 5 * 60 * 1000,
      }),
      "ANDROID_BUILD_FAILED",
    );

    await emulator.start(profile.id);
    started = true;
    const booted = await emulator.waitForBoot(profile.id);
    if (booted.state !== "booted" || booted.deviceFingerprint === null) {
      fail("RUNNER_CONTEXT_DRIFT");
    }
    const runtime = await readRuntime(roots.stateRoot, profile, booted);
    await assertSelectedDevice(command, aactl, booted.serial);
    const commonArgs = [
      "--interface",
      options.interface,
      "--address",
      options.address,
    ];
    const scenarios = [];
    scenarios.push(await runScenario({
      hostCommand: aactl,
      hostArgs: [
        "bridge",
        "lan",
        "listen",
        ...commonArgs,
        "--ttl",
        "120s",
        "--accept-timeout",
        "90s",
        "--rpc-method",
        "device.info",
        "--rpc-count",
        "3",
        "--json",
      ],
      hostKind: "cli",
      scenario: "multi-rpc-close",
      emulator,
      profile,
      booted,
      runtime,
      command,
    }));
    scenarios.push(await runScenario({
      hostCommand: authHost,
      hostArgs: commonArgs,
      hostKind: "auth",
      scenario: "auth-invalid",
      emulator,
      profile,
      booted,
      runtime,
      command,
    }));
    report = Object.freeze({
      schemaVersion: "1.0",
      profileId: profile.id,
      apiLevel: profile.apiLevel,
      serial: booted.serial,
      deviceFingerprint: booted.deviceFingerprint,
      interfaceId: options.interface,
      address: options.address,
      scenarios,
      succeeded: true,
    });
  } catch (error) {
    primaryError = error;
  }

  let cleanupError = null;
  if (started) {
    try {
      await emulator.restore(profile.id);
      await emulator.stop(profile.id);
      await assertSelectedDevice(command, aactl, null);
    } catch (error) {
      cleanupError = error;
    }
  }
  await rm(temporaryRoot, { recursive: true, force: true });
  if (cleanupError !== null) throw cleanupError;
  if (primaryError !== null) throw primaryError;
  const reportDirectory = path.join(roots.stateRoot, "reports");
  await mkdir(reportDirectory, { recursive: true });
  await writeFile(
    path.join(reportDirectory, `n37-n38-interop-${profile.id}.json`),
    `${JSON.stringify(report, null, 2)}\n`,
    { mode: 0o600 },
  );
  return report;
};

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const result = await runInterop({
      argv: process.argv.slice(2),
      environment: process.env,
    });
    process.stdout.write(`${JSON.stringify({ ok: true, data: result, error: null })}\n`);
  } catch (error) {
    process.stdout.write(`${JSON.stringify({
      ok: false,
      data: null,
      error: { code: error?.code ?? "INTERNAL_ERROR" },
    })}\n`);
    process.exitCode = 1;
  }
}
