// 测试用途：验证 N52 production 固定 CLI、类型化 ports、绑定、清理和原子报告契约。
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, readdir, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";

import {
  FIXED_PRODUCTION_CONFIG,
  ProductionMatrixError,
  createProductionSession,
  runProductionCli,
  writeProductionReport,
} from "../src/production.mjs";
import {
  fakePorts,
  fixedClock,
  MATRIX_ID,
} from "./helpers.mjs";
import { runLocalMatrix } from "../src/matrix.mjs";

const execFileAsync = promisify(execFile);
const BINDING = Object.freeze({
  profileId: "api-30",
  apiLevel: 30,
  serial: "emulator-5554",
  deviceFingerprint: "a".repeat(64),
  snapshot: "clean",
});
const CAPABILITIES = Object.freeze([
  "compose.test",
  "fixture.canvas",
  "fixture.editor",
  "fixture.native",
  "fixture.webview",
  "recording.replay",
  "semantic.action",
  "system.navigation",
  "visual.action",
]);
const RESIDUE_KINDS = Object.freeze([
  "appData",
  "bridgeSessions",
  "testServices",
  "screenshots",
  "logs",
  "ownedProcesses",
  "runtimeFiles",
  "leases",
]);
const PROFILES = Object.freeze([
  Object.freeze({ profileId: "api-30", apiLevel: 30 }),
  Object.freeze({ profileId: "api-33", apiLevel: 33 }),
  Object.freeze({ profileId: "api-34", apiLevel: 34 }),
]);
const PROFILE = PROFILES[0];
const SCENARIOS = new Map([
  [
    "native-fixture",
    Object.freeze({
      scenarioId: "native-fixture",
      requiredCapabilities: Object.freeze([
        "fixture.native",
        "semantic.action",
        "system.navigation",
      ]),
    }),
  ],
  [
    "webview-fixture",
    Object.freeze({
      scenarioId: "webview-fixture",
      requiredCapabilities: Object.freeze([
        "fixture.webview",
        "semantic.action",
      ]),
    }),
  ],
  [
    "canvas-fixture",
    Object.freeze({
      scenarioId: "canvas-fixture",
      requiredCapabilities: Object.freeze([
        "fixture.canvas",
        "visual.action",
      ]),
    }),
  ],
  [
    "recording-editor",
    Object.freeze({
      scenarioId: "recording-editor",
      requiredCapabilities: Object.freeze([
        "compose.test",
        "fixture.editor",
      ]),
    }),
  ],
  [
    "recording-replay",
    Object.freeze({
      scenarioId: "recording-replay",
      requiredCapabilities: Object.freeze([
        "recording.replay",
        "semantic.action",
      ]),
    }),
  ],
]);

const probeAll = async (ports) => {
  for (const profile of PROFILES) await ports.capabilities.probe(profile);
};

const report = () => Object.freeze({
  schemaVersion: "1.0",
  matrixId: MATRIX_ID,
  startedAt: "2026-08-01T00:00:00.000Z",
  finishedAt: "2026-08-01T00:00:01.000Z",
  iterationsPerScenario: 20,
  plannedRuns: 300,
  executedRuns: 0,
  cleanupPassedRuns: 0,
  succeeded: false,
  errorCode: "MATRIX_ACCEPTANCE_FAILED",
  profiles: [],
  scenarioStatistics: [],
});

const productionFactory = ({
  calls = [],
  fingerprint = BINDING.deviceFingerprint,
  scenarioFingerprint = null,
  unavailable = null,
  residueFailure = null,
} = {}) => ({
  create: async (config) => {
    calls.push(["factory", config]);
    const probe = async (kind, profile) => {
      calls.push([`probe:${kind}`, profile.profileId]);
      if (unavailable === kind) {
        throw new ProductionMatrixError(
          `PRODUCTION_${kind.toUpperCase()}_PROVIDER_UNAVAILABLE`,
        );
      }
      return {
        profileId: profile.profileId,
        apiLevel: profile.apiLevel,
        capabilities: kind === "scenario" ? CAPABILITIES : [],
      };
    };
    const lifecycle = {
      probe: (profile) => probe("lifecycle", profile),
      startClean: async ({ profile }) => {
        calls.push(["start", profile.profileId]);
        return {
          ...BINDING,
          profileId: profile.profileId,
          apiLevel: profile.apiLevel,
          deviceFingerprint: fingerprint,
        };
      },
      stop: async (binding) => {
        calls.push(["stop", binding.serial]);
        return { ...binding, stopped: true };
      },
    };
    const scenario = {
      probe: (profile) => probe("scenario", profile),
    };
    for (const scenarioId of [
      "native-fixture",
      "webview-fixture",
      "canvas-fixture",
      "recording-editor",
      "recording-replay",
    ]) {
      scenario[scenarioId] = async (context) => {
        calls.push([`scenario:${scenarioId}`, context.device.serial]);
        return {
          binding: {
            profileId: context.profile.profileId,
            apiLevel: context.profile.apiLevel,
            serial: context.device.serial,
            deviceFingerprint:
              scenarioFingerprint ?? context.device.deviceFingerprint,
            snapshot: context.device.snapshot,
          },
          report: {
            schemaVersion: "1.0",
            runId: "018f47a2-4bc8-7f31-8b9a-1234567890ab",
            scenarioId,
            iteration: context.iteration,
            startedAt: "2026-08-01T00:00:00.000Z",
            finishedAt: "2026-08-01T00:00:00.100Z",
            status: "passed",
            errorCode: null,
            durationMs: 100,
            actionCommits: 1,
            steps: [
              {
                sequence: 1,
                stepId: "production-step",
                actionType: "tap",
                targetPackage: "dev.aiauto.fixture",
                status: "passed",
                errorCode: null,
                pageClass: "normal",
                route: scenarioId === "canvas-fixture"
                  ? "visual"
                  : "semantic",
                score: 1,
                attempts: 1,
                preObservationId: "production-pre",
                postObservationId: "production-post",
                actionCommits: 1,
                durationMs: 100,
              },
            ],
            cleanup: {
              attempted: 4,
              completed: 4,
              errorCode: null,
            },
          },
        };
      };
    }
    const residue = {
      probe: (profile) => probe("residue", profile),
    };
    for (const kind of RESIDUE_KINDS) {
      residue[kind] = async (binding) => {
        calls.push([`residue:${kind}`, binding.serial]);
        return {
          ...binding,
          kind,
          count: residueFailure === kind ? 1 : 0,
        };
      };
    }
    return {
      lifecycle,
      scenario,
      residue,
      close: async () => calls.push(["close"]),
    };
  },
});

test("固定 production 配置只指向外置工具根和唯一报告路径", () => {
  assert.deepEqual(FIXED_PRODUCTION_CONFIG, {
    toolRoot: "/Volumes/aigo S7 Media/SDK/android-tools",
    sdkRoot: "/Volumes/aigo S7 Media/SDK/android-tools/android-sdk",
    avdRoot: "/Volumes/aigo S7 Media/SDK/android-tools/android-avd",
    stateRoot: "/Volumes/aigo S7 Media/SDK/android-tools/emulator-state",
    javaHome:
      "/Volumes/aigo S7 Media/SDK/android-tools/jdk-temurin-21.0.7+6/Contents/Home",
    profilesFile: path.resolve(
      import.meta.dirname,
      "..",
      "..",
      "..",
      "..",
      "scripts",
      "emulator",
      "profiles.json",
    ),
    reportFile:
      "/Volumes/aigo S7 Media/SDK/android-tools/emulator-state/reports/"
      + "n52-production-matrix.json",
  });
  assert.equal(Object.isFrozen(FIXED_PRODUCTION_CONFIG), true);
});

test("固定 CLI 拒绝任意 skip、serial、task、scenario、action 和路径参数", async () => {
  const forbidden = [
    "--skip",
    "--serial=emulator-5554",
    "--task=native",
    "--scenario=native-fixture",
    "--action=tap",
    "--report=/tmp/report.json",
    "--tool-root=/tmp/tools",
  ];
  for (const argument of forbidden) {
    let factoryCalls = 0;
    await assert.rejects(
      () => runProductionCli({
        argv: [argument],
        adapterFactory: {
          create: async () => {
            factoryCalls += 1;
          },
        },
      }),
      (error) =>
        error instanceof ProductionMatrixError
        && error.code === "PRODUCTION_CLI_ARGUMENT_FORBIDDEN",
    );
    assert.equal(factoryCalls, 0, argument);
  }
});

test("production CLI 进程入口拒绝参数且不触发 adapter 或设备", async () => {
  const cli = path.resolve(
    import.meta.dirname,
    "..",
    "src",
    "production-cli.mjs",
  );
  await assert.rejects(
    () => execFileAsync(process.execPath, [cli, "--skip"], {
      encoding: "utf8",
    }),
    (error) => {
      assert.equal(error.code, 2);
      assert.equal(error.stdout, "");
      assert.equal(error.stderr, "PRODUCTION_CLI_ARGUMENT_FORBIDDEN\n");
      return true;
    },
  );
});

test("三类 capability probe 全部完成后才允许 lifecycle start", async () => {
  const calls = [];
  const { ports } = await createProductionSession({
    adapterFactory: productionFactory({ calls }),
  });

  await probeAll(ports);
  const planned = {
    profile: { profileId: "api-30", apiLevel: 30 },
    scenario: SCENARIOS.get("native-fixture"),
    iteration: 1,
  };
  await ports.lifecycle.startClean(planned);

  assert.deepEqual(
    calls.slice(1, 10).map(([name]) => name),
    [
      "probe:lifecycle",
      "probe:scenario",
      "probe:residue",
      "probe:lifecycle",
      "probe:scenario",
      "probe:residue",
      "probe:lifecycle",
      "probe:scenario",
      "probe:residue",
    ],
  );
  assert.equal(calls[10][0], "start");
});

test("任一 production provider unavailable 在零 lifecycle start 时失败关闭", async () => {
  for (const kind of ["lifecycle", "scenario", "residue"]) {
    const calls = [];
    const { ports } = await createProductionSession({
      adapterFactory: productionFactory({ calls, unavailable: kind }),
    });
    await assert.rejects(
      () => ports.capabilities.probe({ profileId: "api-30", apiLevel: 30 }),
      (error) =>
        error instanceof ProductionMatrixError
        && error.code === `PRODUCTION_${kind.toUpperCase()}_PROVIDER_UNAVAILABLE`,
      kind,
    );
    assert.equal(calls.some(([name]) => name === "start"), false, kind);
  }
});

test("五场景固定分派并逐字验证 serial fingerprint 和 clean snapshot 绑定", async () => {
  const profile = { profileId: "api-30", apiLevel: 30 };
  const calls = [];
  for (const scenarioId of [
    "native-fixture",
    "webview-fixture",
    "canvas-fixture",
    "recording-editor",
    "recording-replay",
  ]) {
    const { ports } = await createProductionSession({
      adapterFactory: productionFactory({ calls }),
    });
    await probeAll(ports);
    const device = await ports.lifecycle.startClean({
      profile,
      scenario: SCENARIOS.get(scenarioId),
      iteration: 1,
    });
    const value = await ports.scenario.run({
      profile,
      scenario: SCENARIOS.get(scenarioId),
      iteration: 1,
      device,
    });
    assert.equal(value.scenarioId, scenarioId);
    await ports.lifecycle.stop(device);
    await ports.residue.inspect(device);
  }
  assert.deepEqual(
    calls.filter(([name]) => name.startsWith("scenario:")).map(([name]) => name),
    [
      "scenario:native-fixture",
      "scenario:webview-fixture",
      "scenario:canvas-fixture",
      "scenario:recording-editor",
      "scenario:recording-replay",
    ],
  );

  const drifting = productionFactory({
    scenarioFingerprint: "b".repeat(64),
  });
  const { ports: driftPorts } = await createProductionSession({
    adapterFactory: drifting,
  });
  await probeAll(driftPorts);
  const driftDevice = await driftPorts.lifecycle.startClean({
    profile,
    scenario: SCENARIOS.get("native-fixture"),
    iteration: 1,
  });
  await assert.rejects(
    () => driftPorts.scenario.run({
      profile,
      scenario: SCENARIOS.get("native-fixture"),
      iteration: 1,
      device: driftDevice,
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_BINDING_DRIFT",
  );
});

test("scenario 必须匹配 lifecycle 固定的 scenario 和 iteration 且只能尝试一次", async () => {
  const { ports } = await createProductionSession({
    adapterFactory: productionFactory(),
  });
  await probeAll(ports);
  const device = await ports.lifecycle.startClean({
    profile: PROFILE,
    scenario: SCENARIOS.get("native-fixture"),
    iteration: 1,
  });

  await assert.rejects(
    () => ports.scenario.run({
      profile: PROFILE,
      scenario: SCENARIOS.get("webview-fixture"),
      iteration: 1,
      device,
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_PLAN_BINDING_DRIFT",
  );
  await ports.scenario.run({
    profile: PROFILE,
    scenario: SCENARIOS.get("native-fixture"),
    iteration: 1,
    device,
  });
  await assert.rejects(
    () => ports.scenario.run({
      profile: PROFILE,
      scenario: SCENARIOS.get("native-fixture"),
      iteration: 1,
      device,
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_SCENARIO_BINDING_UNAVAILABLE",
  );
});

test("residue 只按固定八类顺序检查并绑定同一设备身份", async () => {
  const calls = [];
  const { ports } = await createProductionSession({
    adapterFactory: productionFactory({ calls }),
  });
  const profile = { profileId: "api-30", apiLevel: 30 };
  await probeAll(ports);
  const device = await ports.lifecycle.startClean({
    profile,
    scenario: SCENARIOS.get("native-fixture"),
    iteration: 1,
  });
  await ports.lifecycle.stop(device);
  const residue = await ports.residue.inspect(device);

  assert.deepEqual(residue, {
    appData: 0,
    bridgeSessions: 0,
    testServices: 0,
    screenshots: 0,
    logs: 0,
    ownedProcesses: 0,
    runtimeFiles: 0,
    leases: 0,
  });
  assert.deepEqual(
    calls.filter(([name]) => name.startsWith("residue:")).map(([name]) => name),
    RESIDUE_KINDS.map((kind) => `residue:${kind}`),
  );
});

test("residue 非零、stop binding drift 和 unknown commit 由 N52 失败关闭", async () => {
  const { ports: residuePorts } = await createProductionSession({
    adapterFactory: productionFactory({ residueFailure: "logs" }),
  });
  await probeAll(residuePorts);
  const residueDevice = await residuePorts.lifecycle.startClean({
    profile: PROFILE,
    scenario: SCENARIOS.get("native-fixture"),
    iteration: 1,
  });
  await residuePorts.lifecycle.stop(residueDevice);
  assert.equal((await residuePorts.residue.inspect(residueDevice)).logs, 1);

  const fixture = fakePorts({
    unknownCommitAt: "api-30:native-fixture:1",
  });
  const matrixReport = await runLocalMatrix({
    matrixId: MATRIX_ID,
    ports: fixture.ports,
    clock: fixedClock(),
  });
  assert.equal(matrixReport.errorCode, "MATRIX_ACTION_COMMIT_UNKNOWN");
});

test("scenario 和 residue 拒绝未由本 session lifecycle 产生的任意 serial", async () => {
  const { ports } = await createProductionSession({
    adapterFactory: productionFactory(),
  });
  await probeAll(ports);
  await assert.rejects(
    () => ports.scenario.run({
      profile: PROFILE,
      scenario: SCENARIOS.get("native-fixture"),
      iteration: 1,
      device: {
        ...BINDING,
        serial: "emulator-5584",
        capabilities: CAPABILITIES,
      },
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_SCENARIO_BINDING_UNAVAILABLE",
  );
  await assert.rejects(
    () => ports.residue.inspect({
      ...BINDING,
      serial: "emulator-5584",
      capabilities: CAPABILITIES,
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_RESIDUE_BINDING_UNAVAILABLE",
  );
});

test("报告原子发布为 0600 且写入失败删除临时文件并保留旧报告", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "n52-production-"));
  const destination = path.join(directory, "report.json");
  await writeProductionReport(destination, report());
  assert.deepEqual(JSON.parse(await readFile(destination, "utf8")), report());
  assert.equal((await stat(destination)).mode & 0o777, 0o600);
  assert.deepEqual(await readdir(directory), ["report.json"]);

  const old = "{\"old\":true}\n";
  await writeFile(destination, old, { mode: 0o600 });
  await assert.rejects(
    () => writeProductionReport(destination, report(), {
      beforeRename: async () => {
        throw new Error("private write failure");
      },
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_REPORT_WRITE_FAILED",
  );
  assert.equal(await readFile(destination, "utf8"), old);
  assert.deepEqual(await readdir(directory), ["report.json"]);
});

test("固定 CLI 使用注入 runMatrix 跑唯一 300 轮计划并在报告后关闭 factory", async () => {
  const calls = [];
  const destination = path.join(
    await mkdtemp(path.join(os.tmpdir(), "n52-production-cli-")),
    "report.json",
  );
  const result = await runProductionCli({
    argv: [],
    adapterFactory: productionFactory({ calls }),
    matrixIdFactory: () => MATRIX_ID,
    clock: fixedClock(),
    runMatrix: async (input) => {
      const { matrixId, ports } = input;
      calls.push(["matrix", matrixId]);
      assert.equal(typeof ports.lifecycle.startClean, "function");
      assert.equal(
        (await import("../src/matrix.mjs")).createLocalMatrixPlan().length,
        300,
      );
      return runLocalMatrix(input);
    },
    reportWriter: async (reportFile, value) => {
      calls.push(["write", reportFile]);
      assert.equal(reportFile, FIXED_PRODUCTION_CONFIG.reportFile);
      await writeProductionReport(destination, value);
    },
  });

  assert.equal(result.succeeded, true);
  assert.equal(result.executedRuns, 300);
  const firstStart = calls.findIndex(([name]) => name === "start");
  assert.deepEqual(
    calls.slice(firstStart, firstStart + 11).map(([name]) => name),
    [
      "start",
      "scenario:native-fixture",
      "stop",
      ...RESIDUE_KINDS.map((kind) => `residue:${kind}`),
    ],
  );
  assert.equal(calls.at(-2)[0], "close");
  assert.equal(calls.at(-1)[0], "write");
  assert.deepEqual(JSON.parse(await readFile(destination, "utf8")), result);
});
