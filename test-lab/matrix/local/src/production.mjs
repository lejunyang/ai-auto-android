// 功能用途：组装 N52 固定 production ports、校验设备绑定并原子发布仓库外报告。
import crypto from "node:crypto";
import {
  mkdir,
  open,
  rename,
  rm,
} from "node:fs/promises";
import path from "node:path";

import {
  LocalMatrixError,
  runLocalMatrix,
} from "./matrix.mjs";
import {
  loadMatrixReportSchema,
  validateMatrixReport,
} from "./report.mjs";
import {
  loadRunnerSchemas,
  validateRunnerDocument,
} from "../../../runner/src/schema-validator.mjs";

const TOOL_ROOT = "/Volumes/aigo S7 Media/SDK/android-tools";
const profileIdentity = new Map([
  ["api-30", 30],
  ["api-33", 33],
  ["api-34", 34],
]);
const fixedProfiles = Object.freeze(
  [...profileIdentity].map(([profileId, apiLevel]) =>
    Object.freeze({ profileId, apiLevel })),
);
const scenarioIds = Object.freeze([
  "native-fixture",
  "webview-fixture",
  "canvas-fixture",
  "recording-editor",
  "recording-replay",
]);
const scenarioCapabilities = new Map([
  [
    "native-fixture",
    Object.freeze([
      "fixture.native",
      "semantic.action",
      "system.navigation",
    ]),
  ],
  [
    "webview-fixture",
    Object.freeze([
      "fixture.webview",
      "semantic.action",
    ]),
  ],
  [
    "canvas-fixture",
    Object.freeze([
      "fixture.canvas",
      "visual.action",
    ]),
  ],
  [
    "recording-editor",
    Object.freeze([
      "compose.test",
      "fixture.editor",
    ]),
  ],
  [
    "recording-replay",
    Object.freeze([
      "recording.replay",
      "semantic.action",
    ]),
  ],
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
const requiredCapabilities = Object.freeze([
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
const stableId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const sha256 = /^[a-f0-9]{64}$/u;
const stableErrorCode = /^[A-Z][A-Z0-9_]{2,95}$/u;

export const FIXED_PRODUCTION_CONFIG = Object.freeze({
  toolRoot: TOOL_ROOT,
  sdkRoot: path.join(TOOL_ROOT, "android-sdk"),
  avdRoot: path.join(TOOL_ROOT, "android-avd"),
  stateRoot: path.join(TOOL_ROOT, "emulator-state"),
  javaHome: path.join(
    TOOL_ROOT,
    "jdk-temurin-21.0.7+6",
    "Contents",
    "Home",
  ),
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
  reportFile: path.join(
    TOOL_ROOT,
    "emulator-state",
    "reports",
    "n52-production-matrix.json",
  ),
});

export class ProductionMatrixError extends LocalMatrixError {
  constructor(code) {
    super(stableErrorCode.test(code ?? "") ? code : "PRODUCTION_INTERNAL_ERROR");
    this.name = "ProductionMatrixError";
  }
}

const fail = (code) => {
  throw new ProductionMatrixError(code);
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const frozenBinding = (value, code = "PRODUCTION_BINDING_INVALID") => {
  const binding = {
    profileId: value?.profileId,
    apiLevel: value?.apiLevel,
    serial: value?.serial,
    deviceFingerprint: value?.deviceFingerprint,
    snapshot: value?.snapshot,
  };
  if (
    profileIdentity.get(binding.profileId) !== binding.apiLevel
    || !stableId.test(binding.serial ?? "")
    || !sha256.test(binding.deviceFingerprint ?? "")
    || binding.snapshot !== "clean"
  ) {
    fail(code);
  }
  return Object.freeze(binding);
};

const frozenDevice = (value, capabilitiesByProfile) => {
  if (
    !exactKeys(value, [
      "apiLevel",
      "capabilities",
      "deviceFingerprint",
      "profileId",
      "serial",
      "snapshot",
    ])
    || !Array.isArray(value.capabilities)
  ) {
    fail("PRODUCTION_DEVICE_INVALID");
  }
  const binding = frozenBinding(value, "PRODUCTION_DEVICE_INVALID");
  const expectedCapabilities = capabilitiesByProfile.get(binding.profileId);
  if (
    expectedCapabilities === undefined
    || JSON.stringify([...value.capabilities].sort())
      !== JSON.stringify(expectedCapabilities)
  ) {
    fail("PRODUCTION_CAPABILITY_DRIFT");
  }
  return binding;
};

const sameBinding = (left, right) =>
  left.profileId === right.profileId
  && left.apiLevel === right.apiLevel
  && left.serial === right.serial
  && left.deviceFingerprint === right.deviceFingerprint
  && left.snapshot === right.snapshot;

const assertProfile = (profile) => {
  if (
    !exactKeys(profile, ["apiLevel", "profileId"])
    || profileIdentity.get(profile.profileId) !== profile.apiLevel
  ) {
    fail("PRODUCTION_PROFILE_INVALID");
  }
};

const assertAdapterFactory = (adapterFactory) => {
  if (
    !exactKeys(adapterFactory, ["create"])
    || typeof adapterFactory.create !== "function"
  ) {
    fail("PRODUCTION_ADAPTER_FACTORY_INVALID");
  }
};

const assertAdapters = (adapters) => {
  if (
    !exactKeys(adapters, ["close", "lifecycle", "residue", "scenario"])
    || typeof adapters.close !== "function"
    || !exactKeys(adapters.lifecycle, ["probe", "startClean", "stop"])
    || typeof adapters.lifecycle.probe !== "function"
    || typeof adapters.lifecycle.startClean !== "function"
    || typeof adapters.lifecycle.stop !== "function"
    || !exactKeys(adapters.scenario, ["probe", ...scenarioIds])
    || typeof adapters.scenario.probe !== "function"
    || scenarioIds.some(
      (scenarioId) => typeof adapters.scenario[scenarioId] !== "function",
    )
    || !exactKeys(adapters.residue, ["probe", ...residueKinds])
    || typeof adapters.residue.probe !== "function"
    || residueKinds.some(
      (kind) => typeof adapters.residue[kind] !== "function",
    )
  ) {
    fail("PRODUCTION_ADAPTER_CONTRACT_INVALID");
  }
};

const callProvider = async (operation, fallbackCode) => {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof ProductionMatrixError) throw error;
    fail(fallbackCode);
  }
};

const capabilityResult = (profile, value) => {
  if (
    !exactKeys(value, ["apiLevel", "capabilities", "profileId"])
    || value.profileId !== profile.profileId
    || value.apiLevel !== profile.apiLevel
    || !Array.isArray(value.capabilities)
    || value.capabilities.length > requiredCapabilities.length
    || value.capabilities.some(
      (capability) =>
        !stableId.test(capability ?? "")
        || !requiredCapabilities.includes(capability),
    )
    || new Set(value.capabilities).size !== value.capabilities.length
  ) {
    fail("PRODUCTION_CAPABILITY_INVALID");
  }
  return value.capabilities;
};

const assertScenario = (scenario) => {
  const expectedCapabilities = scenarioCapabilities.get(scenario?.scenarioId);
  if (
    !exactKeys(scenario, ["requiredCapabilities", "scenarioId"])
    || expectedCapabilities === undefined
    || !Array.isArray(scenario.requiredCapabilities)
    || JSON.stringify(scenario.requiredCapabilities)
      !== JSON.stringify(expectedCapabilities)
  ) {
    fail("PRODUCTION_SCENARIO_INVALID");
  }
};

const assertPlannedRun = (planned) => {
  if (
    !exactKeys(planned, ["iteration", "profile", "scenario"])
    || !Number.isInteger(planned.iteration)
    || planned.iteration < 1
    || planned.iteration > 20
  ) {
    fail("PRODUCTION_PLAN_INVALID");
  }
  assertProfile(planned.profile);
  assertScenario(planned.scenario);
};

const assertScenarioContext = (context) => {
  if (
    !exactKeys(context, ["device", "iteration", "profile", "scenario"])
    || !Number.isInteger(context.iteration)
    || context.iteration < 1
    || context.iteration > 20
  ) {
    fail("PRODUCTION_SCENARIO_INPUT_INVALID");
  }
  assertProfile(context.profile);
  assertScenario(context.scenario);
};

const validateScenarioOutput = async (context, expected, output) => {
  if (
    !exactKeys(output, ["binding", "report"])
    || !exactKeys(output.binding, [
      "apiLevel",
      "deviceFingerprint",
      "profileId",
      "serial",
      "snapshot",
    ])
  ) {
    fail("PRODUCTION_SCENARIO_RESULT_INVALID");
  }
  const actual = frozenBinding(output.binding);
  if (!sameBinding(expected, actual)) fail("PRODUCTION_BINDING_DRIFT");
  const schemas = await loadRunnerSchemas();
  if (
    validateRunnerDocument(schemas.runReport, output.report).length > 0
    || output.report.scenarioId !== context.scenario.scenarioId
    || output.report.iteration !== context.iteration
  ) {
    fail("PRODUCTION_SCENARIO_RESULT_INVALID");
  }
  return output.report;
};

export const createProductionSession = async ({ adapterFactory }) => {
  assertAdapterFactory(adapterFactory);
  const adapters = await callProvider(
    () => adapterFactory.create(FIXED_PRODUCTION_CONFIG),
    "PRODUCTION_ADAPTER_FACTORY_FAILED",
  );
  try {
    assertAdapters(adapters);
  } catch (error) {
    if (typeof adapters?.close === "function") {
      try {
        await adapters.close();
      } catch {
        fail("PRODUCTION_ADAPTER_FACTORY_CLEANUP_FAILED");
      }
    }
    throw error;
  }
  const capabilitiesByProfile = new Map();
  const probedProfiles = new Set();
  const bindingStates = new Map();

  const ports = Object.freeze({
    capabilities: Object.freeze({
      probe: async (profile) => {
        assertProfile(profile);
        const cached = capabilitiesByProfile.get(profile.profileId);
        if (cached !== undefined) {
          return Object.freeze({
            profileId: profile.profileId,
            apiLevel: profile.apiLevel,
            capabilities: Object.freeze([...cached]),
          });
        }
        const combined = [];
        for (const [name, adapter, allowedCapabilities] of [
          ["LIFECYCLE", adapters.lifecycle, []],
          ["SCENARIO", adapters.scenario, requiredCapabilities],
          ["RESIDUE", adapters.residue, []],
        ]) {
          const result = await callProvider(
            () => adapter.probe(Object.freeze({ ...profile })),
            `PRODUCTION_${name}_PROBE_FAILED`,
          );
          const capabilities = capabilityResult(profile, result);
          if (
            capabilities.some(
              (capability) => !allowedCapabilities.includes(capability),
            )
          ) {
            fail(`PRODUCTION_${name}_CAPABILITY_INVALID`);
          }
          combined.push(...capabilities);
        }
        const capabilities = [...new Set(combined)].sort();
        if (
          JSON.stringify(capabilities)
          !== JSON.stringify(requiredCapabilities)
        ) {
          fail("PRODUCTION_CAPABILITY_MISSING");
        }
        const previous = capabilitiesByProfile.get(profile.profileId);
        if (
          previous !== undefined
          && JSON.stringify(previous) !== JSON.stringify(capabilities)
        ) {
          fail("PRODUCTION_CAPABILITY_DRIFT");
        }
        capabilitiesByProfile.set(profile.profileId, capabilities);
        probedProfiles.add(profile.profileId);
        return Object.freeze({
          profileId: profile.profileId,
          apiLevel: profile.apiLevel,
          capabilities: Object.freeze([...capabilities]),
        });
      },
    }),
    lifecycle: Object.freeze({
      startClean: async (planned) => {
        assertPlannedRun(planned);
        if (probedProfiles.size !== profileIdentity.size) {
          fail("PRODUCTION_CAPABILITY_PREFLIGHT_INCOMPLETE");
        }
        if (bindingStates.has(planned.profile.profileId)) {
          fail("PRODUCTION_LIFECYCLE_ORDER_INVALID");
        }
        const output = await callProvider(
          () => adapters.lifecycle.startClean(Object.freeze({
            profile: Object.freeze({ ...planned.profile }),
            scenario: Object.freeze({
              scenarioId: planned.scenario.scenarioId,
              requiredCapabilities: Object.freeze([
                ...planned.scenario.requiredCapabilities,
              ]),
            }),
            iteration: planned.iteration,
          })),
          "PRODUCTION_LIFECYCLE_START_FAILED",
        );
        if (
          !exactKeys(output, [
            "apiLevel",
            "deviceFingerprint",
            "profileId",
            "serial",
            "snapshot",
          ])
        ) {
          fail("PRODUCTION_LIFECYCLE_START_INVALID");
        }
        const binding = frozenBinding(
          output,
          "PRODUCTION_LIFECYCLE_START_INVALID",
        );
        if (
          binding.profileId !== planned.profile.profileId
          || binding.apiLevel !== planned.profile.apiLevel
        ) {
          fail("PRODUCTION_BINDING_DRIFT");
        }
        const device = Object.freeze({
          ...binding,
          capabilities: Object.freeze([
            ...capabilitiesByProfile.get(binding.profileId),
          ]),
        });
        bindingStates.set(binding.profileId, Object.freeze({
          binding,
          iteration: planned.iteration,
          scenarioId: planned.scenario.scenarioId,
          state: "started",
        }));
        return device;
      },
      stop: async (device) => {
        const binding = frozenDevice(device, capabilitiesByProfile);
        const bindingState = bindingStates.get(binding.profileId);
        if (
          !["started", "scenario-attempted"].includes(bindingState?.state)
          || !sameBinding(bindingState.binding, binding)
        ) {
          fail("PRODUCTION_LIFECYCLE_ORDER_INVALID");
        }
        const output = await callProvider(
          () => adapters.lifecycle.stop(binding),
          "PRODUCTION_LIFECYCLE_STOP_FAILED",
        );
        if (
          !exactKeys(output, [
            "apiLevel",
            "deviceFingerprint",
            "profileId",
            "serial",
            "snapshot",
            "stopped",
          ])
          || output.stopped !== true
        ) {
          fail("PRODUCTION_LIFECYCLE_STOP_INVALID");
        }
        const stoppedBinding = frozenBinding(output);
        if (!sameBinding(binding, stoppedBinding)) {
          fail("PRODUCTION_BINDING_DRIFT");
        }
        bindingStates.set(binding.profileId, Object.freeze({
          binding,
          iteration: bindingState.iteration,
          scenarioId: bindingState.scenarioId,
          state: "stopped",
        }));
        return Object.freeze({ stopped: true });
      },
    }),
    scenario: Object.freeze({
      run: async (context) => {
        assertScenarioContext(context);
        const binding = frozenDevice(context.device, capabilitiesByProfile);
        if (
          binding.profileId !== context.profile.profileId
          || binding.apiLevel !== context.profile.apiLevel
        ) {
          fail("PRODUCTION_BINDING_DRIFT");
        }
        const bindingState = bindingStates.get(binding.profileId);
        if (
          bindingState?.state !== "started"
          || !sameBinding(bindingState.binding, binding)
        ) {
          fail("PRODUCTION_SCENARIO_BINDING_UNAVAILABLE");
        }
        if (
          bindingState.scenarioId !== context.scenario.scenarioId
          || bindingState.iteration !== context.iteration
        ) {
          fail("PRODUCTION_PLAN_BINDING_DRIFT");
        }
        bindingStates.set(binding.profileId, Object.freeze({
          binding,
          iteration: bindingState.iteration,
          scenarioId: bindingState.scenarioId,
          state: "scenario-attempted",
        }));
        const output = await callProvider(
          () => adapters.scenario[context.scenario.scenarioId](
            Object.freeze({
              profile: Object.freeze({ ...context.profile }),
              scenario: Object.freeze({
                scenarioId: context.scenario.scenarioId,
                requiredCapabilities: Object.freeze([
                  ...context.scenario.requiredCapabilities,
                ]),
              }),
              iteration: context.iteration,
              device: binding,
            }),
          ),
          "PRODUCTION_SCENARIO_RUN_FAILED",
        );
        return validateScenarioOutput(context, binding, output);
      },
    }),
    residue: Object.freeze({
      inspect: async (device) => {
        const binding = frozenDevice(device, capabilitiesByProfile);
        const bindingState = bindingStates.get(binding.profileId);
        if (
          bindingState?.state !== "stopped"
          || !sameBinding(bindingState.binding, binding)
        ) {
          fail("PRODUCTION_RESIDUE_BINDING_UNAVAILABLE");
        }
        const result = {};
        for (const kind of residueKinds) {
          const output = await callProvider(
            () => adapters.residue[kind](binding),
            "PRODUCTION_RESIDUE_INSPECTION_FAILED",
          );
          if (
            !exactKeys(output, [
              "apiLevel",
              "count",
              "deviceFingerprint",
              "kind",
              "profileId",
              "serial",
              "snapshot",
            ])
            || output.kind !== kind
            || !Number.isInteger(output.count)
            || output.count < 0
          ) {
            fail("PRODUCTION_RESIDUE_RESULT_INVALID");
          }
          const inspectedBinding = frozenBinding(output);
          if (!sameBinding(binding, inspectedBinding)) {
            fail("PRODUCTION_BINDING_DRIFT");
          }
          result[kind] = output.count;
        }
        bindingStates.delete(binding.profileId);
        return Object.freeze(result);
      },
    }),
  });

  return Object.freeze({
    ports,
    close: async () => callProvider(
      () => adapters.close(),
      "PRODUCTION_ADAPTER_CLOSE_FAILED",
    ),
  });
};

export const writeProductionReport = async (
  destination,
  report,
  dependencies = {},
) => {
  if (
    typeof destination !== "string"
    || !path.isAbsolute(destination)
    || !exactKeys(dependencies, dependencies.beforeRename === undefined
      ? []
      : ["beforeRename"])
    || (
      dependencies.beforeRename !== undefined
      && typeof dependencies.beforeRename !== "function"
    )
  ) {
    fail("PRODUCTION_REPORT_INPUT_INVALID");
  }
  const directory = path.dirname(destination);
  const temporary = path.join(
    directory,
    `.${path.basename(destination)}.${process.pid}.${crypto.randomUUID()}.tmp`,
  );
  let published = false;
  try {
    await mkdir(directory, { recursive: true });
    const handle = await open(temporary, "wx", 0o600);
    try {
      await handle.writeFile(`${JSON.stringify(report, null, 2)}\n`, "utf8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    if (dependencies.beforeRename !== undefined) {
      await dependencies.beforeRename();
    }
    await rename(temporary, destination);
    published = true;
  } catch {
    if (!published) await rm(temporary, { force: true });
    fail("PRODUCTION_REPORT_WRITE_FAILED");
  }
};

const productionClock = () => {
  let now = Date.now();
  return Object.freeze({
    now: () => new Date(now).toISOString(),
    tick: () => {
      now = Math.max(now + 1, Date.now());
    },
  });
};

const assertMatrixReport = async (report) => {
  const schema = await loadMatrixReportSchema();
  if (validateMatrixReport(schema, report).length > 0) {
    fail("PRODUCTION_REPORT_INVALID");
  }
};

const preflightCapabilities = async (ports) => {
  for (const profile of fixedProfiles) {
    await ports.capabilities.probe(profile);
  }
};

export const runProductionCli = async ({
  argv,
  adapterFactory,
  matrixIdFactory = crypto.randomUUID,
  clock = productionClock(),
  runMatrix = runLocalMatrix,
  reportWriter = writeProductionReport,
}) => {
  if (!Array.isArray(argv) || argv.some((value) => typeof value !== "string")) {
    fail("PRODUCTION_CLI_INPUT_INVALID");
  }
  if (argv.length > 0) fail("PRODUCTION_CLI_ARGUMENT_FORBIDDEN");
  if (
    typeof matrixIdFactory !== "function"
    || typeof runMatrix !== "function"
    || typeof reportWriter !== "function"
  ) {
    fail("PRODUCTION_CLI_DEPENDENCY_INVALID");
  }
  const session = await createProductionSession({ adapterFactory });
  let report = null;
  let primaryError = null;
  try {
    await preflightCapabilities(session.ports);
    report = await runMatrix({
      matrixId: matrixIdFactory(),
      ports: session.ports,
      clock,
    });
    await assertMatrixReport(report);
  } catch (error) {
    primaryError = error instanceof LocalMatrixError
      ? error
      : new ProductionMatrixError("PRODUCTION_MATRIX_RUN_FAILED");
  }
  let closeError = null;
  try {
    await session.close();
  } catch (error) {
    closeError = error;
  }
  if (closeError !== null) throw closeError;
  if (primaryError !== null) throw primaryError;
  await reportWriter(FIXED_PRODUCTION_CONFIG.reportFile, report);
  return report;
};
