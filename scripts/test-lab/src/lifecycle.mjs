// 功能用途：以类型化端口约束 verified App 的 clean AVD 前置、安装后核验和最终恢复。
import path from "node:path";

import {
  ExternalAppError,
  validateExternalAppManifest,
} from "./manifest.mjs";
import {
  assertVerifiedExternalAppDescriptor,
  reverifyExternalAppDescriptor,
} from "./verifier.mjs";

const descriptorKeys = [
  "abi",
  "artifactPath",
  "kind",
  "licenseNote",
  "minSdk",
  "package",
  "schemaVersion",
  "sha256",
  "signingCertificateSha256",
  "sizeBytes",
  "source",
  "version",
  "versionCode",
];
const installedKeys = [
  "package",
  "signingCertificateSha256",
  "version",
  "versionCode",
];
const runnerMethods = [
  "clearAppData",
  "inspectInstalled",
  "installVerified",
  "restoreCleanSnapshot",
  "restoreFinalSnapshot",
];
const safeIdentity = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const assertDescriptor = (descriptor) => {
  if (
    !exactKeys(descriptor, descriptorKeys)
    || descriptor.kind !== "verified-external-app"
    || typeof descriptor.artifactPath !== "string"
    || !path.isAbsolute(descriptor.artifactPath)
  ) {
    throw new ExternalAppError("DESCRIPTOR_INVALID");
  }
  validateExternalAppManifest({
    schemaVersion: descriptor.schemaVersion,
    package: descriptor.package,
    version: descriptor.version,
    versionCode: descriptor.versionCode,
    abi: descriptor.abi,
    minSdk: descriptor.minSdk,
    source: descriptor.source,
    licenseNote: descriptor.licenseNote,
    sizeBytes: descriptor.sizeBytes,
    sha256: descriptor.sha256,
    signingCertificateSha256: descriptor.signingCertificateSha256,
  });
  return assertVerifiedExternalAppDescriptor(descriptor);
};

const assertRunner = (runner) => {
  if (
    runner === null
    || typeof runner !== "object"
    || runnerMethods.some((method) => typeof runner[method] !== "function")
  ) {
    throw new ExternalAppError("RUNNER_INVALID");
  }
};

const assertContext = (serial, snapshot) => {
  if (
    !safeIdentity.test(serial ?? "")
    || !safeIdentity.test(snapshot ?? "")
  ) {
    throw new ExternalAppError("RUNNER_INPUT_INVALID");
  }
};

const runnerContext = (descriptor, serial, snapshot) =>
  Object.freeze({
    serial,
    snapshot,
    package: descriptor.package,
    descriptor,
  });

const callRunner = async (operation, code) => {
  try {
    return await operation();
  } catch {
    throw new ExternalAppError(code);
  }
};

const assertInstalledMetadata = (descriptor, installed) => {
  if (
    !exactKeys(installed, installedKeys)
    || installed.package !== descriptor.package
    || installed.version !== descriptor.version
    || installed.versionCode !== descriptor.versionCode
    || installed.signingCertificateSha256
      !== descriptor.signingCertificateSha256
  ) {
    throw new ExternalAppError("INSTALLED_METADATA_MISMATCH");
  }
  return Object.freeze({ ...installed });
};

export const createAdbInstallArgv = ({ serial, descriptor }) => {
  assertContext(serial, "clean");
  assertDescriptor(descriptor);
  return Object.freeze([
    "-s",
    serial,
    "install",
    "--no-streaming",
    descriptor.artifactPath,
  ]);
};

export const runVerifiedExternalAppLifecycle = async ({
  descriptor,
  serial,
  snapshot,
  runner,
  scenario,
}) => {
  assertDescriptor(descriptor);
  assertContext(serial, snapshot);
  if (runner === undefined) {
    return Object.freeze({
      status: "verified-only",
      installed: false,
      descriptor,
    });
  }
  assertRunner(runner);
  if (scenario !== undefined && typeof scenario !== "function") {
    throw new ExternalAppError("SCENARIO_INVALID");
  }

  const context = runnerContext(descriptor, serial, snapshot);
  await callRunner(
    () => runner.restoreCleanSnapshot(context),
    "CLEAN_SNAPSHOT_RESTORE_FAILED",
  );

  let installAttempted = false;
  let primaryError = null;
  let installed = null;
  let scenarioResult = null;
  try {
    await reverifyExternalAppDescriptor(descriptor);
    installAttempted = true;
    await callRunner(
      () => runner.installVerified(context),
      "VERIFIED_INSTALL_FAILED",
    );
    installed = assertInstalledMetadata(
      descriptor,
      await callRunner(
        () => runner.inspectInstalled(context),
        "INSTALLED_INSPECTION_FAILED",
      ),
    );
    if (scenario !== undefined) {
      try {
        scenarioResult = await scenario(Object.freeze({
          serial,
          snapshot,
          descriptor,
          installed,
        }));
      } catch {
        throw new ExternalAppError("SCENARIO_FAILED");
      }
    }
  } catch (error) {
    primaryError = error instanceof ExternalAppError
      ? error
      : new ExternalAppError("LIFECYCLE_FAILED");
  }

  let clearError = null;
  if (installAttempted) {
    try {
      await runner.clearAppData(context);
    } catch {
      clearError = new ExternalAppError("APP_DATA_CLEAR_FAILED");
    }
  }
  let restoreError = null;
  try {
    await runner.restoreFinalSnapshot(context);
  } catch {
    restoreError = new ExternalAppError("FINAL_SNAPSHOT_RESTORE_FAILED");
  }

  if (restoreError !== null) throw restoreError;
  if (clearError !== null) throw clearError;
  if (primaryError !== null) throw primaryError;
  return Object.freeze({
    status: "completed",
    installed: true,
    descriptor,
    scenarioResult,
  });
};
