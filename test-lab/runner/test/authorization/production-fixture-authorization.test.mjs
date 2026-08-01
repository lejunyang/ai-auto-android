// 测试用途：验证 N47 host 授权 provider 的固定身份、命令数组、秘密传输与全路径清理。
import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  FIXED_AUTHORIZATION_TOKEN,
  createProductionFixtureAuthorization,
  runAuthorizationCommand,
} from "../../src/production-fixture-authorization.mjs";

const root = "/opt/aiauto-tools";
const repositoryRoot = path.resolve(import.meta.dirname, "..", "..", "..", "..");
const environment = Object.freeze({
  AACTL_TOOLCHAIN_ROOT: root,
  ANDROID_SDK_ROOT: `${root}/android-sdk`,
  ANDROID_AVD_HOME: `${root}/android-avd`,
  JAVA_HOME: `${root}/jdk/Contents/Home`,
  AACTL_EMULATOR_STATE: `${root}/emulator-state`,
  GOCACHE: `${root}/go-cache`,
  GOMODCACHE: `${root}/go-mod-cache`,
});
const trustedSigner =
  "a1bb4a7cdfacab4e6cfd050225ae2d6dfd8b8194d172f4f0326eeb585e1aae36";
const colonDigest = (value) => value.match(/../gu).join(":");
const context = Object.freeze({
  profileId: "api-33",
  apiLevel: 33,
  serial: "emulator-5554",
  deviceFingerprint:
    "082695b5aabb55e9a57136f949d3dc6a9df634b6ae943ef79dd580b5a1f0f86f",
  buildFingerprint:
    "google/sdk_gphone64_arm64/emu64a:13/TE1A.240213.009/12342917:userdebug/dev-keys",
  snapshot: "clean",
  scenarioId: "production-native-fixture",
  runId: "019fbdf0-0000-7000-8000-000000000047",
  packages: Object.freeze([
    "dev.aiauto.android",
    "dev.aiauto.fixture",
  ]),
  aactlPath: `${root}/emulator-state/n47-fixed/aactl`,
});

const successfulCommand = (argv, stdout = "") => ({
  code: 0,
  stdout: argv.includes("install") ? "Success\n" : stdout,
  stderr: "",
  timedOut: false,
});

test("默认命令执行器将配对码仅写入 stdin", async () => {
  const result = await runAuthorizationCommand(
    process.execPath,
    [
      "-e",
      "process.stdin.setEncoding('utf8');let value='';"
        + "process.stdin.on('data',(chunk)=>value+=chunk);"
        + "process.stdin.on('end',()=>process.stdout.write(value));",
    ],
    {
      stdin: "731945\n",
      timeoutMs: 5_000,
      maxBuffer: 1024,
    },
  );

  assert.deepEqual(result, {
    code: 0,
    stdout: "731945\n",
    stderr: "",
    signal: undefined,
    timedOut: false,
  });
});

const fixture = ({
  setupResponse = null,
  instrumentationExit = {
    code: 0,
    stdout: "\nOK (1 test)\n",
    stderr: "",
  },
} = {}) => {
  const calls = [];
  let childResolve;
  const childExit = new Promise((resolve) => {
    childResolve = resolve;
  });
  const dependencies = {
    access: async (file) => {
      calls.push(["access", file]);
    },
    command: async (executable, argv, options) => {
      calls.push(["command", executable, [...argv], options]);
      if (argv.includes("--print-certs")) {
        return successfulCommand(
          argv,
          `Signer #1 certificate SHA-256 digest: ${colonDigest(trustedSigner)}\n`,
        );
      }
      if (argv[2] === "forward" && argv[3] === "tcp:0") {
        return successfulCommand(argv, "41247\n");
      }
      return successfulCommand(argv);
    },
    spawnInstrumentation: (executable, argv, options) => {
      calls.push(["spawn", executable, [...argv], options]);
      return {
        exit: childExit,
        kill: () => calls.push(["kill"]),
      };
    },
    exchangeControl: async (request) => {
      calls.push(["control", request]);
      if (request.command === "setup") {
        return setupResponse ?? {
          ok: true,
          runId: context.runId,
          ready: true,
          bridgePort: 38383,
          pairingCode: "731945",
          desktopDeviceFingerprint: context.deviceFingerprint,
        };
      }
      childResolve(instrumentationExit);
      return {
        ok: true,
        runId: context.runId,
        stopped: true,
      };
    },
  };
  return { calls, dependencies };
};

test("probe 只接受固定 N31 profile 且不启动、不安装、不读取设备", async () => {
  const current = fixture();
  const provider = createProductionFixtureAuthorization(
    { environment },
    current.dependencies,
  );

  assert.deepEqual(
    await provider.probe({ profileId: "api-33", apiLevel: 33 }),
    {
      available: true,
      bridgeAction: true,
      disposableEmulator: true,
      visualAction: true,
    },
  );
  assert.equal(
    current.calls.every(([name]) => name === "access"),
    true,
  );
  await assert.rejects(
    () => provider.probe({ profileId: "api-35", apiLevel: 35 }),
    (error) => error.code === "PRODUCTION_FIXTURE_AUTHORIZATION_PROFILE_INVALID",
  );
});

test("setup 固定安装双签名 APK 并以显式 serial 启动唯一 instrumentation", async () => {
  const current = fixture();
  const provider = createProductionFixtureAuthorization(
    { environment },
    current.dependencies,
  );
  await provider.probe({ profileId: "api-33", apiLevel: 33 });
  const result = await provider.setup(context);

  assert.deepEqual(result, {
    ready: true,
    desktopDeviceFingerprint: context.deviceFingerprint,
  });
  const commands = current.calls.filter(([name]) => name === "command");
  const installs = commands.filter(([, , argv]) => argv[2] === "install");
  assert.equal(installs.length, 2);
  assert.deepEqual(
    installs.map(([, , argv]) => argv.slice(0, 5)),
    [
      ["-s", context.serial, "install", "--no-streaming", "-r"],
      ["-s", context.serial, "install", "--no-streaming", "-r"],
    ],
  );
  assert.equal(installs[0][2].at(-1).endsWith("/app-debug.apk"), true);
  assert.equal(
    installs[1][2].at(-1).endsWith("/app-debug-androidTest.apk"),
    true,
  );
  const spawn = current.calls.find(([name]) => name === "spawn");
  assert.deepEqual(spawn[2].slice(0, 4), [
    "-s",
    context.serial,
    "shell",
    "am",
  ]);
  assert.equal(spawn[2].includes("dev.aiauto.android.test/androidx.test.runner.AndroidJUnitRunner"), true);
  assert.equal(spawn[2].includes(FIXED_AUTHORIZATION_TOKEN), false);
  assert.equal(
    current.calls.some(
      ([name, request]) =>
        name === "control"
        && request.command === "setup"
        && request.token === FIXED_AUTHORIZATION_TOKEN,
    ),
    true,
  );
  assert.equal(
    commands.some(([, , argv]) => argv.includes(FIXED_AUTHORIZATION_TOKEN)),
    false,
  );
  assert.equal(
    commands.some(([, , argv]) => argv.includes("731945")),
    false,
  );
  const bridgeOpen = commands.find(([, executable, argv]) =>
    executable === context.aactlPath && argv[0] === "bridge" && argv[1] === "open");
  assert.deepEqual(bridgeOpen[2], [
    "bridge",
    "open",
    "--device",
    context.serial,
    "--json",
  ]);
  assert.equal(bridgeOpen[3].stdin, "731945\n");
});

test("真机 release 未知 fingerprint marker signer 与任意 package 均拒绝", async () => {
  for (const mutation of [
    { serial: "R58M123ABC" },
    { deviceFingerprint: "c".repeat(64) },
    { snapshot: "dirty" },
    { buildFingerprint: "unknown/device:user/release-keys" },
    { packages: ["dev.aiauto.android", "com.example.injected"] },
  ]) {
    const current = fixture();
    const provider = createProductionFixtureAuthorization(
      { environment },
      current.dependencies,
    );
    await provider.probe({ profileId: "api-33", apiLevel: 33 });
    await assert.rejects(
      () => provider.setup({ ...context, ...mutation }),
      (error) => error.code === "PRODUCTION_FIXTURE_AUTHORIZATION_IDENTITY_REJECTED",
    );
    assert.equal(
      current.calls.some(([name]) => ["spawn", "control"].includes(name)),
      false,
    );
  }

  const signer = fixture();
  signer.dependencies.command = async (executable, argv, options) => {
    signer.calls.push(["command", executable, [...argv], options]);
    if (argv.includes("--print-certs")) {
      const digest = argv.at(-1).endsWith("androidTest.apk")
        ? colonDigest("cd".repeat(32))
        : colonDigest(trustedSigner);
      return successfulCommand(
        argv,
        `Signer #1 certificate SHA-256 digest: ${digest}\n`,
      );
    }
    return successfulCommand(argv);
  };
  const provider = createProductionFixtureAuthorization(
    { environment },
    signer.dependencies,
  );
  await provider.probe({ profileId: "api-33", apiLevel: 33 });
  await assert.rejects(
    () => provider.setup(context),
    (error) => error.code === "PRODUCTION_FIXTURE_AUTHORIZATION_SIGNER_REJECTED",
  );
  assert.equal(signer.calls.some(([name]) => name === "spawn"), false);
  await provider.stopScenario({
    runId: context.runId,
    scenarioId: context.scenarioId,
    iteration: 1,
    targetPackages: ["dev.aiauto.fixture"],
  });
  await provider.closeBridge({
    runId: context.runId,
    scenarioId: context.scenarioId,
    iteration: 1,
    targetPackages: ["dev.aiauto.fixture"],
  });
  assert.deepEqual(await provider.inspect({
    profileId: context.profileId,
    serial: context.serial,
  }), {
    bridgeSessions: 0,
    testServices: 0,
  });
});

test("closeBridge 总是关闭 aactl、停止 endpoint、等待 instrumentation 并移除 control forward", async () => {
  const current = fixture();
  const provider = createProductionFixtureAuthorization(
    { environment },
    current.dependencies,
  );
  await provider.probe({ profileId: "api-33", apiLevel: 33 });
  await provider.setup(context);
  await provider.stopScenario({
    runId: context.runId,
    scenarioId: context.scenarioId,
    iteration: 1,
    targetPackages: ["dev.aiauto.fixture"],
  });
  await provider.closeBridge({
    runId: context.runId,
    scenarioId: context.scenarioId,
    iteration: 1,
    targetPackages: ["dev.aiauto.fixture"],
  });

  const lifecycle = current.calls
    .filter(([name, executable, argv, request]) =>
      name === "control"
      || name === "command"
      && (
        executable === context.aactlPath
        || argv[2] === "forward" && argv[3] === "--remove"
      ))
    .map(([name, executable, argv, request]) =>
      name === "control"
        ? executable.command
        : executable === context.aactlPath
          ? argv[1]
          : argv[2]);
  assert.deepEqual(lifecycle.slice(-3), ["close", "stop", "forward"]);
  assert.deepEqual(await provider.inspect({
    profileId: context.profileId,
    serial: context.serial,
  }), {
    bridgeSessions: 0,
    testServices: 0,
  });
});

test("setup 中途失败后 closeBridge 幂等补偿且 inspect 不伪报零", async () => {
  const current = fixture({
    setupResponse: {
      ok: false,
      runId: context.runId,
      errorCode: "TOKEN_INVALID",
    },
  });
  const provider = createProductionFixtureAuthorization(
    { environment },
    current.dependencies,
  );
  await provider.probe({ profileId: "api-33", apiLevel: 33 });
  await assert.rejects(
    () => provider.setup(context),
    (error) => error.code === "PRODUCTION_FIXTURE_AUTHORIZATION_SETUP_FAILED",
  );
  assert.deepEqual(await provider.inspect({
    profileId: context.profileId,
    serial: context.serial,
  }), {
    bridgeSessions: 0,
    testServices: 1,
  });
  await provider.closeBridge({
    runId: context.runId,
    scenarioId: context.scenarioId,
    iteration: 1,
    targetPackages: ["dev.aiauto.fixture"],
  });
  assert.deepEqual(await provider.inspect({
    profileId: context.profileId,
    serial: context.serial,
  }), {
    bridgeSessions: 0,
    testServices: 0,
  });
});
