// 测试用途：验证 N31 production lifecycle 接线、补偿清理与缺失 provider 失败关闭。
import assert from "node:assert/strict";
import test from "node:test";

import {
  createDefaultProductionAdapterFactory,
  createN31LifecycleAdapter,
} from "../src/production-adapters.mjs";
import {
  ProductionMatrixError,
  createProductionSession,
  resolveProductionConfig,
  runProductionCli,
} from "../src/production.mjs";
import {
  fixedClock,
  MATRIX_ID,
} from "./helpers.mjs";

const PROFILE = Object.freeze({
  profileId: "api-30",
  apiLevel: 30,
});
const TEST_ENVIRONMENT = Object.freeze({
  AACTL_TOOLCHAIN_ROOT: "/opt/aiauto-tools",
  ANDROID_SDK_ROOT: "/opt/aiauto-tools/android-sdk",
  ANDROID_AVD_HOME: "/opt/aiauto-tools/android-avd",
  AACTL_EMULATOR_STATE: "/opt/aiauto-tools/emulator-state",
  JAVA_HOME: "/opt/aiauto-tools/jdk/Contents/Home",
});
const TEST_CONFIG = resolveProductionConfig(TEST_ENVIRONMENT);
const BINDING = Object.freeze({
  profileId: "api-30",
  apiLevel: 30,
  serial: "emulator-5554",
  deviceFingerprint: "a".repeat(64),
  snapshot: "clean",
});

const fakeRunner = ({
  calls = [],
  marker = "clean",
  stopFingerprint = BINDING.deviceFingerprint,
  stopFailure = false,
} = {}) => ({
  verifyProfile: async (profileId) => {
    calls.push(["verify", profileId]);
    return {
      profile: {
        id: profileId,
        apiLevel: Number(profileId.slice(4)),
      },
    };
  },
  start: async (profileId) => {
    calls.push(["start", profileId]);
    return {
      profileId,
      apiLevel: 30,
      serial: BINDING.serial,
      deviceFingerprint: null,
      state: "starting",
    };
  },
  waitForBoot: async (profileId) => {
    calls.push(["wait", profileId]);
    return {
      profileId,
      apiLevel: 30,
      serial: BINDING.serial,
      deviceFingerprint: BINDING.deviceFingerprint,
      state: "booted",
    };
  },
  readSnapshotMarker: async (profileId) => {
    calls.push(["marker", profileId]);
    return marker;
  },
  stop: async (profileId) => {
    calls.push(["stop", profileId]);
    if (stopFailure) throw new Error("private stop failure");
    return {
      profileId,
      apiLevel: 30,
      serial: BINDING.serial,
      deviceFingerprint: stopFingerprint,
      state: "stopped",
    };
  },
});

const createLifecycle = async (options = {}) => {
  const runner = fakeRunner(options);
  const adapter = await createN31LifecycleAdapter(
    TEST_CONFIG,
    {
      loadProfiles: async (file) => {
        options.calls?.push(["profiles", file]);
        return { schemaVersion: "1.0", profiles: [] };
      },
      createRunner: (config) => {
        options.calls?.push(["runner", config]);
        return runner;
      },
    },
  );
  return { adapter, runner };
};

test("N31 capability probe 只验证固定 profile 且不启动 emulator", async () => {
  const calls = [];
  const { adapter } = await createLifecycle({ calls });
  const result = await adapter.probe(PROFILE);

  assert.deepEqual(result, {
    profileId: "api-30",
    apiLevel: 30,
    capabilities: [],
  });
  assert.equal(calls.some(([name]) => name === "start"), false);
  assert.equal(calls[0][0], "profiles");
  assert.equal(calls[1][0], "runner");
  assert.deepEqual(calls[2], ["verify", "api-30"]);
});

test("N31 lifecycle 按 start wait marker stop 顺序绑定 clean 设备身份", async () => {
  const calls = [];
  const { adapter } = await createLifecycle({ calls });
  const started = await adapter.startClean({ profile: PROFILE });
  const stopped = await adapter.stop(started);

  assert.deepEqual(started, BINDING);
  assert.deepEqual(stopped, { ...BINDING, stopped: true });
  assert.deepEqual(
    calls.slice(2).map(([name]) => name),
    ["start", "wait", "marker", "stop"],
  );
});

test("N31 start 后发现 snapshot 非 clean 时补偿 stop 并返回稳定错误", async () => {
  const calls = [];
  const { adapter } = await createLifecycle({ calls, marker: "dirty" });

  await assert.rejects(
    () => adapter.startClean({ profile: PROFILE }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_SNAPSHOT_NOT_CLEAN",
  );
  assert.deepEqual(
    calls.slice(2).map(([name]) => name),
    ["start", "wait", "marker", "stop"],
  );
});

test("N31 start 后补偿 stop 失败优先暴露 cleanup failed", async () => {
  const calls = [];
  const { adapter } = await createLifecycle({
    calls,
    marker: "dirty",
    stopFailure: true,
  });

  await assert.rejects(
    () => adapter.startClean({ profile: PROFILE }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_LIFECYCLE_START_CLEANUP_FAILED",
  );
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
});

test("N31 stop 回显 fingerprint 漂移时失败关闭且 close 清理活动实例", async () => {
  const driftCalls = [];
  const { adapter: driftAdapter } = await createLifecycle({
    calls: driftCalls,
    stopFingerprint: "b".repeat(64),
  });
  const started = await driftAdapter.startClean({ profile: PROFILE });
  await assert.rejects(
    () => driftAdapter.stop(started),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_BINDING_DRIFT",
  );

  const closeCalls = [];
  const { adapter: closeAdapter } = await createLifecycle({ calls: closeCalls });
  await closeAdapter.startClean({ profile: PROFILE });
  await closeAdapter.close();
  assert.equal(closeCalls.filter(([name]) => name === "stop").length, 1);
});

test("默认 factory 通过 CLI 明确关闭尚未接入的 N47 scenario provider", async () => {
  const calls = [];
  const lifecycle = {
    probe: async (profile) => ({
      ...profile,
      capabilities: [],
    }),
    startClean: async () => {
      calls.push(["start"]);
      return BINDING;
    },
    stop: async () => ({ ...BINDING, stopped: true }),
    close: async () => calls.push(["close:lifecycle"]),
  };
  const unavailableScenario = {
    probe: async () => {
      throw new ProductionMatrixError(
        "PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE",
      );
    },
    close: async () => calls.push(["close:scenario"]),
  };
  for (const scenarioId of [
    "native-fixture",
    "webview-fixture",
    "canvas-fixture",
    "recording-editor",
    "recording-replay",
  ]) {
    unavailableScenario[scenarioId] = unavailableScenario.probe;
  }
  const residue = {
    probe: async (profile) => ({
      ...profile,
      capabilities: [],
    }),
    close: async () => calls.push(["close:residue"]),
  };
  for (const kind of [
    "appData",
    "bridgeSessions",
    "testServices",
    "screenshots",
    "logs",
    "ownedProcesses",
    "runtimeFiles",
    "leases",
  ]) {
    residue[kind] = async () => ({ ...BINDING, kind, count: 0 });
  }
  const factory = createDefaultProductionAdapterFactory({
    lifecycleFactory: async () => lifecycle,
    scenarioFactory: async () => unavailableScenario,
    residueFactory: async () => residue,
  });
  let reportWrites = 0;

  await assert.rejects(
    () => runProductionCli({
      argv: [],
      environment: TEST_ENVIRONMENT,
      adapterFactory: factory,
      matrixIdFactory: () => MATRIX_ID,
      clock: fixedClock(),
      reportWriter: async () => {
        reportWrites += 1;
      },
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE",
  );
  assert.equal(calls.some(([name]) => name === "start"), false);
  assert.equal(reportWrites, 0);
  assert.deepEqual(
    calls.filter(([name]) => name.startsWith("close:")).map(([name]) => name),
    ["close:residue", "close:scenario", "close:lifecycle"],
  );
});

test("provider factory 中途失败时关闭已创建 lifecycle 且不创建 ports", async () => {
  const calls = [];
  const lifecycle = {
    probe: async () => ({ ...PROFILE, capabilities: [] }),
    startClean: async () => BINDING,
    stop: async () => ({ ...BINDING, stopped: true }),
    close: async () => calls.push(["close:lifecycle"]),
  };
  const factory = createDefaultProductionAdapterFactory({
    lifecycleFactory: async () => lifecycle,
    scenarioFactory: async () => {
      throw new ProductionMatrixError(
        "PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE",
      );
    },
    residueFactory: async () => {
      calls.push(["residue:unexpected"]);
    },
  });

  await assert.rejects(
    () => createProductionSession({
      adapterFactory: factory,
      config: TEST_CONFIG,
    }),
    (error) =>
      error instanceof ProductionMatrixError
      && error.code === "PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE",
  );
  assert.deepEqual(calls, [["close:lifecycle"]]);
});
