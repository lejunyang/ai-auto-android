#!/usr/bin/env node

// 脚本用途：按固定 profile 串行编排三个 production fixture，并严格绑定 N31 身份和残留。
import { fileURLToPath } from "node:url";

import { resolveToolchainEnvironment } from "../../../scripts/toolchain-environment.mjs";
import {
  loadRunnerSchemas,
  validateRunnerDocument,
} from "./schema-validator.mjs";
import { createProductionFixturePorts } from "./production-fixture-ports.mjs";

const profiles = Object.freeze(new Map([
  ["api-30", Object.freeze({ profileId: "api-30", apiLevel: 30 })],
  ["api-33", Object.freeze({ profileId: "api-33", apiLevel: 33 })],
  ["api-34", Object.freeze({ profileId: "api-34", apiLevel: 34 })],
]));
const scenarioIds = Object.freeze([
  "production-native-fixture",
  "production-webview-fixture",
  "production-canvas-fixture",
]);
const requiredCapabilities = Object.freeze([
  "artifact.n34",
  "bridge.action",
  "fixture.canvas",
  "fixture.native",
  "fixture.webview",
  "lifecycle.n31",
  "residue.eight",
  "test.authorization",
  "visual.attested",
]);
const residueKeys = Object.freeze([
  "appData",
  "bridgeSessions",
  "testServices",
  "screenshots",
  "logs",
  "ownedProcesses",
  "runtimeFiles",
  "leases",
]);
const portShape = Object.freeze({
  capabilities: ["probe"],
  lifecycle: ["assertContext", "startClean", "stop"],
  scenarios: ["run"],
  residue: ["inspect"],
});
const emulatorSerial = /^emulator-[0-9]{4,5}$/u;
const fingerprint = /^[a-f0-9]{64}$/u;

export class ProductionFixtureRunnerError extends Error {
  constructor(code) {
    super(code);
    this.name = "ProductionFixtureRunnerError";
    this.code = code;
  }
}

const fail = (code) => {
  throw new ProductionFixtureRunnerError(code);
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

export const parseProductionFixtureArguments = (argv) => {
  if (
    !Array.isArray(argv)
    || argv.length !== 2
    || argv[0] !== "--profile"
    || !profiles.has(argv[1])
  ) {
    fail("USAGE_ERROR");
  }
  return Object.freeze({ profile: argv[1] });
};

export const productionFixtureEnvironment = (environment) => {
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

const assertPorts = (ports) => {
  if (!exactKeys(ports, Object.keys(portShape))) {
    fail("PRODUCTION_FIXTURE_PORT_INVALID");
  }
  for (const [port, methods] of Object.entries(portShape)) {
    if (
      ports[port] === null
      || typeof ports[port] !== "object"
      || !exactKeys(ports[port], methods)
      || methods.some((method) => typeof ports[port][method] !== "function")
    ) {
      fail("PRODUCTION_FIXTURE_PORT_INVALID");
    }
  }
};

const assertCapabilities = (profile, value) => {
  if (
    !exactKeys(value, ["apiLevel", "capabilities", "profileId"])
    || value.profileId !== profile.profileId
    || value.apiLevel !== profile.apiLevel
    || !Array.isArray(value.capabilities)
    || value.capabilities.some(
      (capability) => !requiredCapabilities.includes(capability),
    )
    || new Set(value.capabilities).size !== value.capabilities.length
  ) {
    fail("PRODUCTION_FIXTURE_CAPABILITY_INVALID");
  }
  const available = [...value.capabilities].sort();
  if (!available.includes("test.authorization")) {
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE");
  }
  if (
    JSON.stringify(available)
    !== JSON.stringify([...requiredCapabilities].sort())
  ) {
    fail("PRODUCTION_FIXTURE_CAPABILITY_MISSING");
  }
  return Object.freeze(available);
};

const deviceMatches = (device, value) =>
  exactKeys(value, [
    "abi",
    "androidVersion",
    "apiLevel",
    "avdName",
    "fingerprint",
    "locale",
    "resolution",
    "serial",
    "webView",
  ])
  && value.serial === device.serial
  && value.fingerprint === device.deviceFingerprint
  && value.apiLevel === device.apiLevel
  && typeof value.avdName === "string"
  && value.avdName.length > 0
  && typeof value.androidVersion === "string"
  && value.androidVersion.length > 0
  && ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"].includes(value.abi)
  && typeof value.locale === "string"
  && exactKeys(value.resolution, ["densityDpi", "heightPx", "widthPx"])
  && Object.values(value.resolution).every(
    (number) => Number.isInteger(number) && number > 0,
  )
  && exactKeys(value.webView, ["packageName", "version"])
  && typeof value.webView.packageName === "string"
  && typeof value.webView.version === "string";

const assertStarted = (profile, capabilities, value) => {
  if (
    !exactKeys(value, [
      "apiLevel",
      "capabilities",
      "device",
      "deviceFingerprint",
      "profileId",
      "serial",
      "snapshot",
    ])
    || value.profileId !== profile.profileId
    || value.apiLevel !== profile.apiLevel
    || !emulatorSerial.test(value.serial ?? "")
    || !fingerprint.test(value.deviceFingerprint ?? "")
    || value.snapshot !== "clean"
    || !Array.isArray(value.capabilities)
    || JSON.stringify([...value.capabilities].sort())
      !== JSON.stringify([...capabilities].sort())
    || !deviceMatches(value, value.device)
  ) {
    fail("PRODUCTION_FIXTURE_START_INVALID");
  }
  return value;
};

const canStop = (profile, value) =>
  value !== null
  && typeof value === "object"
  && value.profileId === profile.profileId
  && value.apiLevel === profile.apiLevel
  && emulatorSerial.test(value.serial ?? "");

const assertContext = (started, value) => {
  if (
    !exactKeys(value, [
      "apiLevel",
      "capabilities",
      "device",
      "deviceFingerprint",
      "profileId",
      "serial",
      "snapshot",
    ])
    || value.profileId !== started.profileId
    || value.apiLevel !== started.apiLevel
    || value.serial !== started.serial
    || value.deviceFingerprint !== started.deviceFingerprint
    || value.snapshot !== "clean"
    || !deviceMatches(value, value.device)
  ) {
    fail("PRODUCTION_FIXTURE_CONTEXT_DRIFT");
  }
};

const assertStop = (value) => {
  if (!exactKeys(value, ["stopped"]) || value.stopped !== true) {
    fail("PRODUCTION_FIXTURE_STOP_FAILED");
  }
};

const assertResidue = (value) => {
  if (
    !exactKeys(value, residueKeys)
    || residueKeys.some(
      (key) => !Number.isInteger(value[key]) || value[key] < 0,
    )
  ) {
    fail("PRODUCTION_FIXTURE_RESIDUE_INVALID");
  }
  if (residueKeys.some((key) => value[key] !== 0)) {
    fail("PRODUCTION_FIXTURE_RESIDUE_DETECTED");
  }
  return Object.freeze({ ...value });
};

const assertReport = (schema, scenarioId, report) => {
  if (
    validateRunnerDocument(schema, report).length > 0
    || report.scenarioId !== scenarioId
    || report.iteration !== 1
  ) {
    fail("PRODUCTION_FIXTURE_RESULT_STALE");
  }
  return report;
};

const safeCode = (error, fallback) =>
  typeof error?.code === "string"
  && /^[A-Z][A-Z0-9_]{2,95}$/u.test(error.code)
    ? error.code
    : fallback;

export const runProductionFixture = async ({
  argv,
  environment,
  ports,
}) => {
  const options = parseProductionFixtureArguments(argv);
  productionFixtureEnvironment(environment);
  assertPorts(ports);
  const profile = profiles.get(options.profile);
  let capabilityResult;
  try {
    capabilityResult = await ports.capabilities.probe(profile);
  } catch {
    fail("PRODUCTION_FIXTURE_CAPABILITY_PROBE_FAILED");
  }
  const capabilities = assertCapabilities(profile, capabilityResult);
  const schemas = await loadRunnerSchemas();
  let started = null;
  let startedValid = false;
  let primaryError = null;
  let residue = null;
  const reports = [];

  try {
    try {
      started = await ports.lifecycle.startClean(profile);
      assertStarted(profile, capabilities, started);
      startedValid = true;
    } catch (error) {
      primaryError = new ProductionFixtureRunnerError(
        safeCode(error, "PRODUCTION_FIXTURE_START_FAILED"),
      );
    }
    if (startedValid) {
      for (const scenarioId of scenarioIds) {
        try {
          assertContext(
            started,
            await ports.lifecycle.assertContext(started),
          );
          const report = assertReport(
            schemas.runReport,
            scenarioId,
            await ports.scenarios.run({
              scenarioId,
              iteration: 1,
              started,
            }),
          );
          reports.push(report);
          assertContext(
            started,
            await ports.lifecycle.assertContext(started),
          );
          if (report.status !== "passed") {
            primaryError = new ProductionFixtureRunnerError(
              report.errorCode ?? "PRODUCTION_FIXTURE_SCENARIO_FAILED",
            );
            break;
          }
        } catch (error) {
          primaryError = new ProductionFixtureRunnerError(
            safeCode(error, "PRODUCTION_FIXTURE_SCENARIO_FAILED"),
          );
          break;
        }
      }
    }
  } finally {
    if (canStop(profile, started)) {
      try {
        assertStop(await ports.lifecycle.stop(started));
      } catch {
        primaryError = new ProductionFixtureRunnerError(
          "PRODUCTION_FIXTURE_STOP_FAILED",
        );
      }
      try {
        residue = assertResidue(await ports.residue.inspect(started));
      } catch (error) {
        primaryError = new ProductionFixtureRunnerError(
          safeCode(error, "PRODUCTION_FIXTURE_RESIDUE_INVALID"),
        );
      }
    }
  }

  if (primaryError !== null) throw primaryError;
  if (!startedValid || residue === null) {
    fail("PRODUCTION_FIXTURE_CLEANUP_UNVERIFIED");
  }
  return Object.freeze({
    schemaVersion: "1.0",
    profileId: profile.profileId,
    apiLevel: profile.apiLevel,
    serial: started.serial,
    deviceFingerprint: started.deviceFingerprint,
    snapshot: started.snapshot,
    scenarios: Object.freeze(reports.map((report) => Object.freeze({
      scenarioId: report.scenarioId,
      status: report.status,
      errorCode: report.errorCode,
      actionCommits: report.actionCommits,
      cleanup: report.cleanup,
    }))),
    residue,
    succeeded: reports.length === scenarioIds.length
      && reports.every((report) => report.status === "passed"),
  });
};

const unavailableAuthorization = Object.freeze({
  probe: async () => Object.freeze({
    available: false,
    bridgeAction: false,
    disposableEmulator: false,
    visualAction: false,
  }),
  setup: async () => fail("PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE"),
  stopScenario: async () =>
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE"),
  closeBridge: async () =>
    fail("PRODUCTION_FIXTURE_AUTHORIZATION_UNAVAILABLE"),
  inspect: async () => Object.freeze({
    bridgeSessions: 0,
    testServices: 0,
  }),
});

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const argv = process.argv.slice(2);
    parseProductionFixtureArguments(argv);
    const ports = await createProductionFixturePorts({
      environment: process.env,
      authorization: unavailableAuthorization,
    });
    const report = await runProductionFixture({
      argv,
      environment: process.env,
      ports,
    });
    process.stdout.write(
      `${JSON.stringify({ ok: true, data: report, error: null })}\n`,
    );
  } catch (error) {
    process.stdout.write(
      `${JSON.stringify({
        ok: false,
        data: null,
        error: {
          code: safeCode(error, "INTERNAL_ERROR"),
        },
      })}\n`,
    );
    process.exitCode = 1;
  }
}
