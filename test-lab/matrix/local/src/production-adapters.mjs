// 功能用途：将固定 N31 lifecycle 与安全残留检查接入 N52，并对缺失 typed capability 失败关闭。
import {
  EmulatorRunner,
  loadProfiles,
} from "../../../../scripts/emulator/runner.mjs";

import {
  assertProductionConfig,
  ProductionMatrixError,
} from "./production.mjs";
import {
  createProductionResidueAdapter,
} from "./production-residue.mjs";

const profileIdentity = new Map([
  ["api-30", 30],
  ["api-33", 33],
  ["api-34", 34],
]);
const scenarioIds = Object.freeze([
  "native-fixture",
  "webview-fixture",
  "canvas-fixture",
  "recording-editor",
  "recording-replay",
]);
const residueKinds = Object.freeze([
  "appData",
  "bridgeSessions",
  "testServices",
  "screenshots",
  "logs",
  "ownedProcesses",
  "runtimeFiles",
  "leases",
]);
const stableId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const sha256 = /^[a-f0-9]{64}$/u;

const fail = (code) => {
  throw new ProductionMatrixError(code);
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const assertFixedConfig = (config) => {
  assertProductionConfig(config);
};

const assertProfile = (profile) => {
  if (
    !exactKeys(profile, ["apiLevel", "profileId"])
    || profileIdentity.get(profile.profileId) !== profile.apiLevel
  ) {
    fail("PRODUCTION_PROFILE_INVALID");
  }
};

const bindingFromBoot = (profile, value) => {
  if (
    value?.profileId !== profile.profileId
    || value?.apiLevel !== profile.apiLevel
    || !stableId.test(value?.serial ?? "")
    || !sha256.test(value?.deviceFingerprint ?? "")
  ) {
    fail("PRODUCTION_LIFECYCLE_START_INVALID");
  }
  return Object.freeze({
    profileId: profile.profileId,
    apiLevel: profile.apiLevel,
    serial: value.serial,
    deviceFingerprint: value.deviceFingerprint,
    snapshot: "clean",
  });
};

const sameBinding = (left, right) =>
  left.profileId === right.profileId
  && left.apiLevel === right.apiLevel
  && left.serial === right.serial
  && left.deviceFingerprint === right.deviceFingerprint
  && left.snapshot === right.snapshot;

const assertLifecycleDependencies = (dependencies) => {
  if (
    !exactKeys(dependencies, ["createRunner", "loadProfiles"])
    || typeof dependencies.createRunner !== "function"
    || typeof dependencies.loadProfiles !== "function"
  ) {
    fail("PRODUCTION_LIFECYCLE_DEPENDENCY_INVALID");
  }
};

export const createN31LifecycleAdapter = async (
  config,
  dependencies = {
    loadProfiles,
    createRunner: (options) => new EmulatorRunner(options),
  },
) => {
  assertFixedConfig(config);
  assertLifecycleDependencies(dependencies);
  let profiles;
  try {
    profiles = await dependencies.loadProfiles(config.profilesFile);
  } catch {
    fail("PRODUCTION_LIFECYCLE_PROFILE_LOAD_FAILED");
  }
  let runner;
  try {
    runner = dependencies.createRunner({
      sdkRoot: config.sdkRoot,
      avdRoot: config.avdRoot,
      stateRoot: config.stateRoot,
      javaHome: config.javaHome,
      profiles,
    });
  } catch {
    fail("PRODUCTION_LIFECYCLE_PROVIDER_UNAVAILABLE");
  }
  if (
    runner === null
    || typeof runner !== "object"
    || [
      "readSnapshotMarker",
      "start",
      "stop",
      "verifyProfile",
      "waitForBoot",
    ].some((method) => typeof runner[method] !== "function")
  ) {
    fail("PRODUCTION_LIFECYCLE_PROVIDER_UNAVAILABLE");
  }
  const active = new Map();

  const stopProfile = async (binding) => {
    let stopped;
    try {
      stopped = await runner.stop(binding.profileId);
    } catch {
      fail("PRODUCTION_LIFECYCLE_STOP_FAILED");
    }
    active.delete(binding.profileId);
    const returned = {
      profileId: stopped?.profileId,
      apiLevel: stopped?.apiLevel,
      serial: stopped?.serial,
      deviceFingerprint: stopped?.deviceFingerprint,
      snapshot: binding.snapshot,
    };
    if (stopped?.state !== "stopped" || !sameBinding(binding, returned)) {
      fail("PRODUCTION_BINDING_DRIFT");
    }
    return Object.freeze({ ...binding, stopped: true });
  };

  return Object.freeze({
    probe: async (profile) => {
      assertProfile(profile);
      let verified;
      try {
        verified = await runner.verifyProfile(profile.profileId);
      } catch {
        fail("PRODUCTION_LIFECYCLE_PROVIDER_UNAVAILABLE");
      }
      if (
        verified?.profile?.id !== profile.profileId
        || verified.profile.apiLevel !== profile.apiLevel
      ) {
        fail("PRODUCTION_LIFECYCLE_CAPABILITY_INVALID");
      }
      return Object.freeze({
        profileId: profile.profileId,
        apiLevel: profile.apiLevel,
        capabilities: Object.freeze([]),
      });
    },
    startClean: async (planned) => {
      assertProfile(planned?.profile);
      const profile = planned.profile;
      if (active.has(profile.profileId)) {
        fail("PRODUCTION_LIFECYCLE_ALREADY_ACTIVE");
      }
      let launched = false;
      let binding = null;
      try {
        const started = await runner.start(profile.profileId);
        launched = true;
        if (
          started?.profileId !== profile.profileId
          || started?.apiLevel !== profile.apiLevel
          || !stableId.test(started?.serial ?? "")
          || started?.state !== "starting"
        ) {
          fail("PRODUCTION_LIFECYCLE_START_INVALID");
        }
        const booted = await runner.waitForBoot(profile.profileId);
        binding = bindingFromBoot(profile, booted);
        if (binding.serial !== started.serial || booted.state !== "booted") {
          fail("PRODUCTION_BINDING_DRIFT");
        }
        active.set(profile.profileId, binding);
        const marker = await runner.readSnapshotMarker(profile.profileId);
        if (marker !== "clean") {
          fail("PRODUCTION_SNAPSHOT_NOT_CLEAN");
        }
        return binding;
      } catch (error) {
        if (launched) {
          try {
            if (binding === null) {
              await runner.stop(profile.profileId);
              active.delete(profile.profileId);
            } else {
              await stopProfile(binding);
            }
          } catch {
            fail("PRODUCTION_LIFECYCLE_START_CLEANUP_FAILED");
          }
        }
        if (error instanceof ProductionMatrixError) throw error;
        fail("PRODUCTION_LIFECYCLE_START_FAILED");
      }
    },
    stop: async (binding) => {
      const expected = active.get(binding?.profileId);
      if (expected === undefined || !sameBinding(expected, binding)) {
        fail("PRODUCTION_BINDING_DRIFT");
      }
      return stopProfile(expected);
    },
    close: async () => {
      let cleanupFailed = false;
      for (const binding of [...active.values()]) {
        try {
          await stopProfile(binding);
        } catch {
          cleanupFailed = true;
        }
      }
      if (cleanupFailed) fail("PRODUCTION_LIFECYCLE_CLOSE_FAILED");
    },
  });
};

const unavailableScenarioAdapter = () => {
  const adapter = {
    probe: async () => fail("PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE"),
    close: async () => {},
  };
  for (const scenarioId of scenarioIds) {
    adapter[scenarioId] = async () =>
      fail("PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE");
  }
  return Object.freeze(adapter);
};

const assertFactoryDependencies = (dependencies) => {
  if (
    !exactKeys(
      dependencies,
      ["lifecycleFactory", "residueFactory", "scenarioFactory"],
    )
    || Object.values(dependencies).some((value) => typeof value !== "function")
  ) {
    fail("PRODUCTION_ADAPTER_FACTORY_INVALID");
  }
};

const assertProviderAdapter = (adapter, methods, code) => {
  if (
    adapter === null
    || typeof adapter !== "object"
    || [...methods, "close"].some(
      (method) => typeof adapter[method] !== "function",
    )
  ) {
    fail(code);
  }
};

export const createDefaultProductionAdapterFactory = (
  dependencies = {
    lifecycleFactory: createN31LifecycleAdapter,
    scenarioFactory: async () => unavailableScenarioAdapter(),
    residueFactory: createProductionResidueAdapter,
  },
) => {
  assertFactoryDependencies(dependencies);
  return Object.freeze({
    create: async (config) => {
      assertFixedConfig(config);
      const created = [];
      const createProvider = async (factory, methods, unavailableCode) => {
        const adapter = await factory(config);
        if (typeof adapter?.close === "function") created.push(adapter);
        assertProviderAdapter(adapter, methods, unavailableCode);
        return adapter;
      };
      const closeCreated = async () => {
        let failed = false;
        for (const adapter of [...created].reverse()) {
          try {
            await adapter.close();
          } catch {
            failed = true;
          }
        }
        if (failed) fail("PRODUCTION_ADAPTER_FACTORY_CLEANUP_FAILED");
      };
      let lifecycle;
      let scenario;
      let residue;
      try {
        lifecycle = await createProvider(
          dependencies.lifecycleFactory,
          ["probe", "startClean", "stop"],
          "PRODUCTION_LIFECYCLE_PROVIDER_UNAVAILABLE",
        );
        scenario = await createProvider(
          dependencies.scenarioFactory,
          ["probe", ...scenarioIds],
          "PRODUCTION_SCENARIO_PROVIDER_UNAVAILABLE",
        );
        residue = await createProvider(
          dependencies.residueFactory,
          ["probe", ...residueKinds],
          "PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE",
        );
      } catch (error) {
        await closeCreated();
        if (error instanceof ProductionMatrixError) throw error;
        fail("PRODUCTION_ADAPTER_FACTORY_FAILED");
      }
      return Object.freeze({
        lifecycle: Object.freeze({
          probe: lifecycle.probe,
          startClean: lifecycle.startClean,
          stop: lifecycle.stop,
        }),
        scenario: Object.freeze(Object.fromEntries([
          ["probe", scenario.probe],
          ...scenarioIds.map((scenarioId) => [
            scenarioId,
            scenario[scenarioId],
          ]),
        ])),
        residue: Object.freeze(Object.fromEntries([
          ["probe", residue.probe],
          ...residueKinds.map((kind) => [kind, residue[kind]]),
        ])),
        close: async () => {
          try {
            await closeCreated();
          } catch {
            fail("PRODUCTION_ADAPTER_CLOSE_FAILED");
          }
        },
      });
    },
  });
};
