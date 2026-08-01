// 功能用途：为 N47 固定 fixture 编排 debug 双签名授权、Bridge 打开和全路径 teardown。
import {
  access as nodeAccess,
} from "node:fs/promises";
import { execFile, spawn } from "node:child_process";
import net from "node:net";
import path from "node:path";

import {
  runCommand,
} from "../../../scripts/emulator/runner.mjs";
import {
  resolveToolchainEnvironment,
} from "../../../scripts/toolchain-environment.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..", "..", "..");
const allowedProfiles = new Map([
  ["api-30", 30],
  ["api-33", 33],
  ["api-34", 34],
]);
const allowedScenarios = new Map([
  ["production-native-fixture", ["dev.aiauto.android", "dev.aiauto.fixture"]],
  ["production-webview-fixture", ["dev.aiauto.android", "dev.aiauto.webfixture"]],
  ["production-canvas-fixture", ["dev.aiauto.android", "dev.aiauto.webfixture"]],
]);
const allowedFingerprints = new Set([
  "51ec5c4a7122b6894b752eae99ab48f280736f24ef148590222dc92e2ab72707",
  "082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f",
  "1ac57e687c4b30bdefdb296b1b79158e9e9b3d8297f13817a9edb153ecf5ea8f",
]);
const allowedBuildFingerprints = new Set([
  "google/sdk_gphone_arm64/emulator_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys",
  "google/sdk_gphone64_arm64/emu64a:13/TE1A.240213.009/12342917:userdebug/dev-keys",
  "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys",
]);
const serialPattern = /^emulator-[0-9]{4,5}$/u;
const runIdPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const sha256Pattern = /^[0-9a-f]{64}$/u;
const fixedSigner =
  "a1bb4a7cdfacab4e6cfd050225ae2d6dfd8b8194d172f4f0326eeb585e1aae36";
const controlResponseBudget = 4096;
const instrumentationOutputBudget = 4 * 1024 * 1024;

export const FIXED_AUTHORIZATION_TOKEN =
  "N47_FIXED_PRODUCTION_FIXTURE_AUTHORIZATION_V1";

export class ProductionFixtureAuthorizationError extends Error {
  constructor(code) {
    super(code);
    this.name = "ProductionFixtureAuthorizationError";
    this.code = code;
  }
}

const fail = (code) => {
  throw new ProductionFixtureAuthorizationError(code);
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
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_ENVIRONMENT_INVALID");
  }
};

const parseSigner = (output) => {
  const signerLines = output
    .split(/\r?\n/u)
    .filter((line) =>
      /^Signer #[1-9][0-9]* certificate SHA-256 digest:/u.test(line));
  const matches = [...signerLines.join("\n").matchAll(
    /^Signer #1 certificate SHA-256 digest:\s*((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{64})$/gmu,
  )];
  if (signerLines.length !== 1 || matches.length !== 1) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_SIGNER_REJECTED");
  }
  const digest = matches[0][1].replaceAll(":", "").toLowerCase();
  if (!sha256Pattern.test(digest)) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_SIGNER_REJECTED");
  }
  return digest;
};

const assertCommand = (result, code) => {
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

const assertInstall = (result) => {
  assertCommand(result, "PRODUCTION_FIXTURE_AUTHORIZATION_INSTALL_FAILED");
  const lines = `${result.stdout}\n${result.stderr}`
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean);
  if (!lines.includes("Success")) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_INSTALL_FAILED");
  }
};

export const runAuthorizationCommand = async (executable, argv, options = {}) => {
  if (options.stdin === undefined) {
    return runCommand(executable, argv, options);
  }
  return new Promise((resolve) => {
    let stdinFailed = false;
    const child = execFile(
      executable,
      argv,
      {
        cwd: options.cwd,
        env: options.env,
        encoding: "utf8",
        maxBuffer: options.maxBuffer ?? 4 * 1024 * 1024,
        shell: false,
        timeout: options.timeoutMs ?? 30_000,
        windowsHide: true,
      },
      (error, stdout, stderr) => {
        resolve({
          code: stdinFailed
            ? 1
            : error === null
              ? 0
              : Number.isInteger(error.code)
                ? error.code
                : 1,
          stdout: stdout ?? "",
          stderr: stdinFailed ? "STDIN_FAILED" : stderr ?? "",
          signal: error?.signal,
          timedOut: error?.killed === true,
        });
      },
    );
    child.stdin.once("error", () => {
      stdinFailed = true;
      child.kill();
    });
    child.stdin.end(options.stdin, "utf8");
  });
};

const spawnInstrumentation = (executable, argv, options) => {
  const child = spawn(executable, argv, {
    env: options.env,
    shell: false,
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  let outputExceeded = false;
  const append = (current, chunk) => {
    const updated = current + chunk.toString("utf8");
    if (Buffer.byteLength(updated, "utf8") > instrumentationOutputBudget) {
      outputExceeded = true;
      child.kill();
      return current;
    }
    return updated;
  };
  child.stdout.on("data", (chunk) => {
    stdout = append(stdout, chunk);
  });
  child.stderr.on("data", (chunk) => {
    stderr = append(stderr, chunk);
  });
  const timer = setTimeout(() => child.kill(), options.timeoutMs);
  timer.unref();
  const exit = new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code, signal) => {
      clearTimeout(timer);
      resolve(Object.freeze({
        code: outputExceeded ? 1 : code,
        signal,
        stdout,
        stderr,
      }));
    });
  });
  return Object.freeze({
    exit,
    kill: () => child.kill(),
  });
};

const parseControlResponse = (line, command) => {
  const pattern = command === "setup"
    ? /^\{"ok":true,"runId":"([0-9a-f-]{36})","ready":true,"bridgePort":38383,"pairingCode":"([0-9]{6})","desktopDeviceFingerprint":"([0-9a-f]{64})"\}$/u
    : /^\{"ok":true,"runId":"([0-9a-f-]{36})","stopped":true\}$/u;
  const match = line.match(pattern);
  if (match === null) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_CONTROL_INVALID");
  }
  return command === "setup"
    ? Object.freeze({
        ok: true,
        runId: match[1],
        ready: true,
        bridgePort: 38383,
        pairingCode: match[2],
        desktopDeviceFingerprint: match[3],
      })
    : Object.freeze({
        ok: true,
        runId: match[1],
        stopped: true,
      });
};

const exchangeControlOnce = ({ command, runId, token, localPort }) =>
  new Promise((resolve, reject) => {
    const socket = net.createConnection({
      host: "127.0.0.1",
      port: localPort,
    });
    let buffer = Buffer.alloc(0);
    let completed = false;
    const finish = (error, value) => {
      if (completed) return;
      completed = true;
      socket.destroy();
      if (error !== null) reject(error);
      else resolve(value);
    };
    socket.setTimeout(30_000, () => {
      finish(new Error("CONTROL_TIMEOUT"));
    });
    let requestWritten = false;
    socket.once("error", (error) => {
      error.requestWritten = requestWritten;
      finish(error);
    });
    socket.on("data", (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length > controlResponseBudget) {
        finish(new Error("CONTROL_RESPONSE_TOO_LARGE"));
      }
    });
    socket.once("end", () => {
      const newline = buffer.indexOf(0x0a);
      if (
        newline < 0
        || newline !== buffer.length - 1
        || buffer.includes(0x0d)
      ) {
        finish(new Error("CONTROL_RESPONSE_TRAILING_DATA"));
        return;
      }
      try {
        finish(
          null,
          parseControlResponse(buffer.subarray(0, newline).toString("utf8"), command),
        );
      } catch (error) {
        finish(error);
      }
    });
    socket.once("connect", () => {
      requestWritten = true;
      socket.end(`${JSON.stringify({ command, runId, token })}\n`, "utf8");
    });
  });

const exchangeControl = async (request) => {
  const deadline = Date.now() + 15_000;
  do {
    try {
      return await exchangeControlOnce(request);
    } catch (error) {
      if (
        request.command !== "setup"
        || error?.requestWritten === true
        || !["ECONNREFUSED", "ECONNRESET"].includes(error?.code)
        || Date.now() >= deadline
      ) {
        throw error;
      }
      await new Promise((resolve) => {
        setTimeout(resolve, 100);
      });
    }
  } while (Date.now() < deadline);
  fail("PRODUCTION_FIXTURE_AUTHORIZATION_CONTROL_UNAVAILABLE");
};

const assertSetupContext = (context) => {
  const packages = allowedScenarios.get(context?.scenarioId);
  if (
    !exactKeys(context, [
      "aactlPath",
      "apiLevel",
      "buildFingerprint",
      "deviceFingerprint",
      "packages",
      "profileId",
      "runId",
      "scenarioId",
      "serial",
      "snapshot",
    ])
    || allowedProfiles.get(context.profileId) !== context.apiLevel
    || !serialPattern.test(context.serial ?? "")
    || !allowedFingerprints.has(context.deviceFingerprint)
    || !allowedBuildFingerprints.has(context.buildFingerprint)
    || context.snapshot !== "clean"
    || !runIdPattern.test(context.runId ?? "")
    || !path.isAbsolute(context.aactlPath ?? "")
    || !Array.isArray(context.packages)
    || JSON.stringify(context.packages) !== JSON.stringify(packages)
  ) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_IDENTITY_REJECTED");
  }
};

const assertLifecycleContext = (context, current) => {
  if (
    current === null
    || !exactKeys(context, [
      "iteration",
      "runId",
      "scenarioId",
      "targetPackages",
    ])
    || context.runId !== current.runId
    || context.scenarioId !== current.scenarioId
    || context.iteration !== 1
    || !Array.isArray(context.targetPackages)
  ) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_CONTEXT_DRIFT");
  }
};

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
  test: path.join(
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
});

const defaultDependencies = Object.freeze({
  access: nodeAccess,
  command: runAuthorizationCommand,
  spawnInstrumentation,
  exchangeControl,
});

export const createProductionFixtureAuthorization = (
  { environment },
  dependencyOverrides = {},
) => {
  const roots = resolveEnvironment(environment);
  const dependencies = Object.freeze({
    ...defaultDependencies,
    ...dependencyOverrides,
  });
  if (
    Object.keys(dependencies).some(
      (key) => !Object.hasOwn(defaultDependencies, key),
    )
    || Object.values(dependencies).some((value) => typeof value !== "function")
  ) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_DEPENDENCY_INVALID");
  }
  let probedProfile = null;
  let current = null;
  let attempted = null;
  let lastInspection = null;

  const command = (executable, argv, options = {}) =>
    dependencies.command(executable, argv, {
      ...options,
      env: environment,
      shell: false,
    });

  const closeCurrent = async () => {
    if (current === null) return;
    let failed = false;
    const closing = current;
    if (current.bridgeOpened) {
      try {
        assertCommand(
          await command(
            closing.aactlPath,
            ["bridge", "close", "--device", closing.serial, "--json"],
            { timeoutMs: 30_000, maxBuffer: 1024 * 1024 },
          ),
          "PRODUCTION_FIXTURE_AUTHORIZATION_CLOSE_FAILED",
        );
      } catch {
        failed = true;
      }
      closing.bridgeOpened = false;
    }
    try {
      const response = await dependencies.exchangeControl({
        command: "stop",
        runId: closing.runId,
        token: FIXED_AUTHORIZATION_TOKEN,
        localPort: closing.controlPort,
      });
      if (
        !exactKeys(response, ["ok", "runId", "stopped"])
        || response.ok !== true
        || response.runId !== closing.runId
        || response.stopped !== true
      ) {
        failed = true;
      }
    } catch {
      failed = true;
    }
    try {
      const exited = await closing.instrumentation.exit;
      if (
        exited?.code !== 0
        || typeof exited.stdout !== "string"
        || !exited.stdout.includes("OK (1 test)")
        || exited.stdout.includes("FAILURES!!!")
        || exited.stdout.includes("INSTRUMENTATION_FAILED")
        || exited.stderr !== ""
      ) {
        failed = true;
      }
    } catch {
      failed = true;
      closing.instrumentation.kill();
    }
    try {
      assertCommand(
        await command(
          path.join(roots.sdkRoot, "platform-tools", "adb"),
          [
            "-s",
            closing.serial,
            "forward",
            "--remove",
            `tcp:${closing.controlPort}`,
          ],
          { timeoutMs: 10_000, maxBuffer: 1024 * 1024 },
        ),
        "PRODUCTION_FIXTURE_AUTHORIZATION_CLOSE_FAILED",
      );
    } catch {
      failed = true;
    }
    try {
      const forwards = assertCommand(
        await command(
          path.join(roots.sdkRoot, "platform-tools", "adb"),
          ["-s", closing.serial, "forward", "--list"],
          { timeoutMs: 10_000, maxBuffer: 1024 * 1024 },
        ),
        "PRODUCTION_FIXTURE_AUTHORIZATION_CLOSE_FAILED",
      );
      if (forwards.stderr !== "") failed = true;
      const remaining = forwards.stdout
        .split(/\r?\n/u)
        .filter(Boolean)
        .map((line) => line.trim().split(/\s+/u))
        .filter(([serial]) => serial === closing.serial);
      if (
        remaining.some(
          ([, , remote]) => ["tcp:38383", "tcp:38484"].includes(remote),
        )
      ) {
        failed = true;
      }
    } catch {
      failed = true;
    }
    current = null;
    lastInspection = Object.freeze({
      profileId: closing.profileId,
      serial: closing.serial,
      bridgeSessions: failed ? 1 : 0,
      testServices: failed ? 1 : 0,
    });
    if (failed) fail("PRODUCTION_FIXTURE_AUTHORIZATION_CLOSE_FAILED");
  };

  return Object.freeze({
    probe: async (profile) => {
      if (
        !exactKeys(profile, ["apiLevel", "profileId"])
        || allowedProfiles.get(profile.profileId) !== profile.apiLevel
      ) {
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_PROFILE_INVALID");
      }
      await dependencies.access(
        path.join(roots.sdkRoot, "build-tools", "36.0.0", "apksigner"),
      );
      probedProfile = Object.freeze({ ...profile });
      return Object.freeze({
        available: true,
        bridgeAction: true,
        disposableEmulator: true,
        visualAction: true,
      });
    },
    setup: async (context) => {
      assertSetupContext(context);
      if (
        probedProfile?.profileId !== context.profileId
        || probedProfile.apiLevel !== context.apiLevel
        || current !== null
        || attempted !== null
      ) {
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_IDENTITY_REJECTED");
      }
      attempted = Object.freeze({
        profileId: context.profileId,
        serial: context.serial,
        runId: context.runId,
        scenarioId: context.scenarioId,
      });
      const apksigner = path.join(
        roots.sdkRoot,
        "build-tools",
        "36.0.0",
        "apksigner",
      );
      for (const file of Object.values(apkFiles)) await dependencies.access(file);
      const signers = [];
      for (const file of Object.values(apkFiles)) {
        const result = assertCommand(
          await command(
            apksigner,
            ["verify", "--print-certs", file],
            { timeoutMs: 30_000, maxBuffer: 1024 * 1024 },
          ),
          "PRODUCTION_FIXTURE_AUTHORIZATION_SIGNER_REJECTED",
        );
        signers.push(parseSigner(result.stdout));
      }
      if (
        signers.some((digest) => digest !== fixedSigner)
        || new Set(signers).size !== 1
      ) {
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_SIGNER_REJECTED");
      }
      const adb = path.join(roots.sdkRoot, "platform-tools", "adb");
      for (const file of Object.values(apkFiles)) {
        assertInstall(
          await command(
            adb,
            [
              "-s",
              context.serial,
              "install",
              "--no-streaming",
              "-r",
              file,
            ],
            { timeoutMs: 5 * 60 * 1000, maxBuffer: 4 * 1024 * 1024 },
          ),
        );
      }
      const forward = assertCommand(
        await command(
          adb,
          [
            "-s",
            context.serial,
            "forward",
            "tcp:0",
            "tcp:38484",
          ],
          { timeoutMs: 10_000, maxBuffer: 1024 * 1024 },
        ),
        "PRODUCTION_FIXTURE_AUTHORIZATION_FORWARD_FAILED",
      );
      const controlPort = Number(forward.stdout.trim());
      if (!Number.isInteger(controlPort) || controlPort < 1024 || controlPort > 65535) {
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_FORWARD_FAILED");
      }
      let instrumentation;
      try {
        instrumentation = dependencies.spawnInstrumentation(
          adb,
          [
            "-s",
            context.serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            "dev.aiauto.android.testcontrol.ProductionFixtureAuthorizationDeviceTest",
            "-e",
            "n32EmulatorSerial",
            context.serial,
            "-e",
            "n32ProfileId",
            context.profileId,
            "-e",
            "n32AvdFingerprint",
            context.deviceFingerprint,
            "-e",
            "n32ExpectedBuildFingerprint",
            context.buildFingerprint,
            "-e",
            "n32TestOnlyMarker",
            "AI_AUTO_TEST_ONLY_V1",
            "-e",
            "n47ScenarioId",
            context.scenarioId,
            "-e",
            "n47RunId",
            context.runId,
            "dev.aiauto.android.test/androidx.test.runner.AndroidJUnitRunner",
          ],
          {
            env: environment,
            shell: false,
            timeoutMs: 10 * 60 * 1000,
            maxBuffer: 4 * 1024 * 1024,
          },
        );
      } catch {
        await command(
          adb,
          [
            "-s",
            context.serial,
            "forward",
            "--remove",
            `tcp:${controlPort}`,
          ],
          { timeoutMs: 10_000, maxBuffer: 1024 * 1024 },
        );
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_ENDPOINT_UNAVAILABLE");
      }
      current = {
        profileId: context.profileId,
        serial: context.serial,
        runId: context.runId,
        scenarioId: context.scenarioId,
        aactlPath: context.aactlPath,
        controlPort,
        instrumentation,
        bridgeOpened: false,
      };
      try {
        const response = await dependencies.exchangeControl({
          command: "setup",
          runId: context.runId,
          token: FIXED_AUTHORIZATION_TOKEN,
          localPort: controlPort,
        });
        if (
          !exactKeys(response, [
            "bridgePort",
            "desktopDeviceFingerprint",
            "ok",
            "pairingCode",
            "ready",
            "runId",
          ])
          || response.ok !== true
          || response.runId !== context.runId
          || response.ready !== true
          || response.bridgePort !== 38383
          || response.desktopDeviceFingerprint !== context.deviceFingerprint
          || !/^[0-9]{6}$/u.test(response.pairingCode ?? "")
        ) {
          fail("PRODUCTION_FIXTURE_AUTHORIZATION_SETUP_FAILED");
        }
        current.bridgeOpened = true;
        assertCommand(
          await command(
            context.aactlPath,
            ["bridge", "open", "--device", context.serial, "--json"],
            {
              stdin: `${response.pairingCode}\n`,
              timeoutMs: 30_000,
              maxBuffer: 1024 * 1024,
            },
          ),
          "PRODUCTION_FIXTURE_AUTHORIZATION_SETUP_FAILED",
        );
        return Object.freeze({
          ready: true,
          desktopDeviceFingerprint: context.deviceFingerprint,
        });
      } catch (error) {
        if (error instanceof ProductionFixtureAuthorizationError) throw error;
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_SETUP_FAILED");
      }
    },
    stopScenario: async (context) => {
      assertLifecycleContext(context, current ?? attempted);
    },
    closeBridge: async (context) => {
      assertLifecycleContext(context, current ?? attempted);
      if (current === null) {
        lastInspection = Object.freeze({
          profileId: attempted.profileId,
          serial: attempted.serial,
          bridgeSessions: 0,
          testServices: 0,
        });
        attempted = null;
        return;
      }
      await closeCurrent();
      attempted = null;
    },
    inspect: async (request) => {
      if (
        !exactKeys(request, ["profileId", "serial"])
        || allowedProfiles.get(request.profileId) === undefined
        || !serialPattern.test(request.serial ?? "")
        || (
          current !== null
          && (
            current.profileId !== request.profileId
            || current.serial !== request.serial
          )
        )
        || (
          current === null
          && lastInspection !== null
          && (
            lastInspection.profileId !== request.profileId
            || lastInspection.serial !== request.serial
          )
        )
      ) {
        fail("PRODUCTION_FIXTURE_AUTHORIZATION_CONTEXT_DRIFT");
      }
      return Object.freeze({
        bridgeSessions: current?.bridgeOpened
          ? 1
          : lastInspection?.bridgeSessions ?? 0,
        testServices: current === null
          ? lastInspection?.testServices ?? 0
          : 1,
      });
    },
  });
};
