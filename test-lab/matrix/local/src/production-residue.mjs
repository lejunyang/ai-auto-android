// 功能用途：以固定 N31 身份、仓库外根目录和窄类型能力安全检查八类 production 残留。
import { constants as fsConstants } from "node:fs";
import {
  lstat as nodeLstat,
  open as nodeOpen,
  readdir as nodeReaddir,
  realpath as nodeRealpath,
} from "node:fs/promises";
import path from "node:path";

import { loadProfiles as loadN31Profiles } from "../../../../scripts/emulator/runner.mjs";
import {
  assertProductionConfig,
  ProductionMatrixError,
} from "./production.mjs";

const profileIdentity = new Map([
  ["api-30", 30],
  ["api-33", 33],
  ["api-34", 34],
]);
const typedKinds = Object.freeze([
  "appData",
  "bridgeSessions",
  "testServices",
  "ownedProcesses",
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
export const FIXED_RESIDUE_PACKAGES = Object.freeze([
  "dev.aiauto.android",
  "dev.aiauto.fixture",
  "dev.aiauto.webfixture",
]);
const filesystemKinds = Object.freeze(["screenshots", "logs"]);
const serialPattern = /^emulator-([0-9]{4})$/u;
const sha256Pattern = /^[a-f0-9]{64}$/u;
const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const isoTimestampPattern =
  /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{3})?Z$/u;
const maxJsonBytes = 64 * 1024;
const maxArtifactEntries = 256;
const maxArtifactBytes = 16 * 1024 * 1024;
const maxArtifactDepth = 4;
const repositoryRoot = path.resolve(import.meta.dirname, "..", "..", "..", "..");

const fail = (code) => {
  throw new ProductionMatrixError(code);
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const isContained = (root, candidate) => {
  const relative = path.relative(root, candidate);
  return relative === ""
    || !path.isAbsolute(relative)
    && relative !== ".."
    && !relative.startsWith(`..${path.sep}`);
};

const sameIdentity = (left, right) =>
  left !== null
  && right !== null
  && String(left.dev) === String(right.dev)
  && String(left.ino) === String(right.ino)
  && String(left.mode) === String(right.mode)
  && String(left.size) === String(right.size)
  && String(left.mtimeMs) === String(right.mtimeMs)
  && String(left.ctimeMs) === String(right.ctimeMs);

const sameOptionalIdentity = (left, right) =>
  left === null && right === null || sameIdentity(left, right);

const serialPort = (serial) => {
  const match = serialPattern.exec(serial ?? "");
  if (match === null) return null;
  const port = Number(match[1]);
  return port >= 5554 && port <= 5584 && port % 2 === 0 ? port : null;
};

const assertProfileRequest = (value) => {
  if (
    !exactKeys(value, ["apiLevel", "profileId"])
    || profileIdentity.get(value.profileId) !== value.apiLevel
  ) {
    fail("PRODUCTION_PROFILE_INVALID");
  }
};

const assertBinding = (value) => {
  if (
    !exactKeys(value, [
      "apiLevel",
      "deviceFingerprint",
      "profileId",
      "serial",
      "snapshot",
    ])
    || profileIdentity.get(value.profileId) !== value.apiLevel
    || serialPort(value.serial) === null
    || !sha256Pattern.test(value.deviceFingerprint ?? "")
    || value.snapshot !== "clean"
  ) {
    fail("PRODUCTION_BINDING_DRIFT");
  }
  return Object.freeze({ ...value });
};

const sameBinding = (left, right) =>
  left.profileId === right.profileId
  && left.apiLevel === right.apiLevel
  && left.serial === right.serial
  && left.deviceFingerprint === right.deviceFingerprint
  && left.snapshot === right.snapshot;

const resultFor = (binding, kind, count) =>
  Object.freeze({
    ...binding,
    kind,
    count,
  });

const assertDependencies = (dependencies) => {
  if (
    !exactKeys(dependencies, ["filesystem", "loadProfiles", "providers"])
    || typeof dependencies.loadProfiles !== "function"
    || dependencies.filesystem === null
    || typeof dependencies.filesystem !== "object"
    || ["lstat", "open", "readdir", "realpath"].some(
      (method) => typeof dependencies.filesystem[method] !== "function",
    )
    || dependencies.providers === null
    || typeof dependencies.providers !== "object"
    || Array.isArray(dependencies.providers)
    || Object.keys(dependencies.providers).some(
      (kind) => !typedKinds.includes(kind),
    )
  ) {
    fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
  }
};

const ordinaryDirectory = (stats) =>
  stats.isDirectory() && !stats.isSymbolicLink();

const ordinaryFile = (stats) =>
  stats.isFile()
  && !stats.isSymbolicLink()
  && Number(stats.nlink) === 1;

const expectedRealpath = async (filesystem, candidate) => {
  const [canonical, canonicalParent] = await Promise.all([
    filesystem.realpath(candidate),
    filesystem.realpath(path.dirname(candidate)),
  ]);
  if (canonical !== path.join(canonicalParent, path.basename(candidate))) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  return canonical;
};

const lstatOptional = async (filesystem, candidate) => {
  try {
    return await filesystem.lstat(candidate);
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
};

const secureReadJson = async (filesystem, candidate) => {
  const before = await lstatOptional(filesystem, candidate);
  if (before === null || !ordinaryFile(before) || before.size > maxJsonBytes) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  await expectedRealpath(filesystem, candidate);
  let handle;
  let content;
  try {
    handle = await filesystem.open(
      candidate,
      fsConstants.O_RDONLY | (fsConstants.O_NOFOLLOW ?? 0),
    );
    const opened = await handle.stat();
    if (!ordinaryFile(opened) || !sameIdentity(before, opened)) {
      fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
    }
    content = await handle.readFile({ encoding: "utf8" });
    const afterRead = await handle.stat();
    if (!sameIdentity(opened, afterRead)) {
      fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
    }
  } finally {
    await handle?.close();
  }
  const after = await filesystem.lstat(candidate);
  await expectedRealpath(filesystem, candidate);
  if (!sameIdentity(before, after) || Buffer.byteLength(content) > maxJsonBytes) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  try {
    return Object.freeze({
      identity: after,
      value: JSON.parse(content),
    });
  } catch {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
};

const validateDirectoryChain = async (
  filesystem,
  stateRoot,
  candidate,
) => {
  if (!isContained(stateRoot, candidate)) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  const relative = path.relative(stateRoot, candidate);
  const segments = relative === "" ? [] : relative.split(path.sep);
  const visited = [];
  const canonicalRoot = await filesystem.realpath(stateRoot);
  let current = stateRoot;
  for (const segment of segments) {
    current = path.join(current, segment);
    const stats = await lstatOptional(filesystem, current);
    if (stats === null) {
      return Object.freeze({ exists: false, visited });
    }
    if (!ordinaryDirectory(stats)) {
      fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
    }
    const canonical = await expectedRealpath(filesystem, current);
    if (!isContained(canonicalRoot, canonical)) {
      fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
    }
    visited.push(Object.freeze({ path: current, stats }));
  }
  return Object.freeze({ exists: true, visited });
};

const revalidateDirectories = async (filesystem, visited) => {
  for (const directory of [...visited].reverse()) {
    const current = await filesystem.lstat(directory.path);
    await expectedRealpath(filesystem, directory.path);
    if (!ordinaryDirectory(current) || !sameIdentity(directory.stats, current)) {
      fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
    }
  }
};

const countOrdinaryArtifacts = async (
  filesystem,
  stateRoot,
  targetRoot,
  extension,
) => {
  const target = await validateDirectoryChain(
    filesystem,
    stateRoot,
    targetRoot,
  );
  if (!target.exists) {
    await revalidateDirectories(filesystem, target.visited);
    return 0;
  }
  let entries = 0;
  let bytes = 0;
  let count = 0;
  const visit = async (directory, depth) => {
    if (depth > maxArtifactDepth) {
      fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
    }
    const names = await filesystem.readdir(directory);
    entries += names.length;
    if (entries > maxArtifactEntries) {
      fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
    }
    for (const name of names) {
      if (
        typeof name !== "string"
        || name === ""
        || path.basename(name) !== name
      ) {
        fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
      }
      const entry = path.join(directory, name);
      const before = await filesystem.lstat(entry);
      await expectedRealpath(filesystem, entry);
      if (ordinaryDirectory(before)) {
        await visit(entry, depth + 1);
      } else if (ordinaryFile(before) && name.endsWith(extension)) {
        bytes += Number(before.size);
        if (!Number.isSafeInteger(bytes) || bytes > maxArtifactBytes) {
          fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
        }
        count += 1;
      } else {
        fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
      }
      const after = await filesystem.lstat(entry);
      await expectedRealpath(filesystem, entry);
      if (!sameIdentity(before, after)) {
        fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
      }
    }
  };
  await visit(targetRoot, 1);
  await revalidateDirectories(filesystem, target.visited);
  return count;
};

const validateBindingDocument = (document, binding) => {
  if (
    !exactKeys(document, [
      "apiLevel",
      "deviceFingerprint",
      "profileId",
      "schemaVersion",
      "serial",
      "snapshot",
    ])
    || document.schemaVersion !== "1.0"
  ) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  const actual = assertBinding({
    profileId: document.profileId,
    apiLevel: document.apiLevel,
    serial: document.serial,
    deviceFingerprint: document.deviceFingerprint,
    snapshot: document.snapshot,
  });
  if (!sameBinding(actual, binding)) fail("PRODUCTION_BINDING_DRIFT");
};

const countBoundArtifacts = async (
  filesystem,
  stateRoot,
  root,
  binding,
  kind,
) => {
  const profileRoot = path.join(root, binding.profileId);
  const profile = await validateDirectoryChain(
    filesystem,
    stateRoot,
    profileRoot,
  );
  if (!profile.exists) {
    await revalidateDirectories(filesystem, profile.visited);
    return 0;
  }
  const bindingFile = path.join(profileRoot, "binding.json");
  const { value: document } = await secureReadJson(filesystem, bindingFile);
  validateBindingDocument(document, binding);
  const count = await countOrdinaryArtifacts(
    filesystem,
    stateRoot,
    path.join(profileRoot, kind),
    kind === "screenshots" ? ".png" : ".log",
  );
  await revalidateDirectories(filesystem, profile.visited);
  return count;
};

const assertLeaseShape = (
  value,
  binding,
  profile,
  stateRoot,
) => {
  const port = serialPort(binding.serial);
  const avdLock = path.join(
    stateRoot,
    "locks",
    "avd",
    `${profile.avdName}.lock`,
  );
  const portLock = path.join(stateRoot, "locks", "port", `${port}.lock`);
  if (
    !exactKeys(value, [
      "acquiredAtMs",
      "avdLock",
      "avdName",
      "emulatorPid",
      "id",
      "launchedAt",
      "ownerPid",
      "port",
      "portLock",
      "serial",
    ])
    || !uuidPattern.test(value.id ?? "")
    || !Number.isSafeInteger(value.ownerPid)
    || value.ownerPid < 1
    || !Number.isSafeInteger(value.emulatorPid)
    || value.emulatorPid < 1
    || !Number.isSafeInteger(value.acquiredAtMs)
    || value.acquiredAtMs < 0
    || !isoTimestampPattern.test(value.launchedAt ?? "")
  ) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  if (
    value.avdName !== profile.avdName
    || value.port !== port
    || value.serial !== binding.serial
  ) {
    fail("PRODUCTION_BINDING_DRIFT");
  }
  if (value.avdLock !== avdLock || value.portLock !== portLock) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  return Object.freeze({ ...value });
};

const runtimePath = (stateRoot, profile) =>
  path.join(stateRoot, "runtime", `${profile.avdName}.json`);

const n31LogPath = (stateRoot, profile, binding) =>
  path.join(
    stateRoot,
    "logs",
    `${profile.avdName}-${binding.serial}.log`,
  );

const readRuntime = async (
  filesystem,
  stateRoot,
  profile,
  binding,
) => {
  const file = runtimePath(stateRoot, profile);
  const parent = await validateDirectoryChain(
    filesystem,
    stateRoot,
    path.dirname(file),
  );
  if (!parent.exists) {
    await revalidateDirectories(filesystem, parent.visited);
    return null;
  }
  const stats = await lstatOptional(filesystem, file);
  if (stats === null) {
    await revalidateDirectories(filesystem, parent.visited);
    return null;
  }
  const { identity, value } = await secureReadJson(filesystem, file);
  const allowedKeys = new Set([
    "avdName",
    "bootedAt",
    "deviceFingerprint",
    "lease",
    "logFile",
    "metadata",
    "pid",
    "port",
    "profileId",
    "recoveryRequired",
    "schemaVersion",
    "serial",
    "startedAt",
    "statePersistenceError",
  ]);
  if (
    value === null
    || typeof value !== "object"
    || Array.isArray(value)
    || Object.keys(value).some((key) => !allowedKeys.has(key))
    || value.schemaVersion !== "1.0"
    || value.profileId !== binding.profileId
    || value.avdName !== profile.avdName
    || value.serial !== binding.serial
    || value.port !== serialPort(binding.serial)
    || value.deviceFingerprint !== binding.deviceFingerprint
  ) {
    fail("PRODUCTION_BINDING_DRIFT");
  }
  if (
    !Number.isSafeInteger(value.pid)
    || value.pid < 1
    || value.logFile !== n31LogPath(stateRoot, profile, binding)
    || !isoTimestampPattern.test(value.startedAt ?? "")
  ) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  const lease = assertLeaseShape(value.lease, binding, profile, stateRoot);
  if (
    lease.emulatorPid !== value.pid
    || lease.launchedAt !== value.startedAt
  ) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  const runtime = Object.freeze({
    file,
    identity,
    lease,
    pid: value.pid,
  });
  await revalidateDirectories(filesystem, parent.visited);
  return runtime;
};

const inspectOrdinaryFixedFile = async (
  filesystem,
  stateRoot,
  candidate,
) => {
  if (!isContained(stateRoot, candidate)) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  const parent = await validateDirectoryChain(
    filesystem,
    stateRoot,
    path.dirname(candidate),
  );
  if (!parent.exists) {
    await revalidateDirectories(filesystem, parent.visited);
    return 0;
  }
  const stats = await lstatOptional(filesystem, candidate);
  if (stats === null) {
    await revalidateDirectories(filesystem, parent.visited);
    return 0;
  }
  if (!ordinaryFile(stats)) fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  await expectedRealpath(filesystem, candidate);
  const after = await filesystem.lstat(candidate);
  await expectedRealpath(filesystem, candidate);
  if (!sameIdentity(stats, after)) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  await revalidateDirectories(filesystem, parent.visited);
  return 1;
};

const inspectLock = async (
  filesystem,
  stateRoot,
  lockPath,
  binding,
  profile,
  runtime,
) => {
  const lock = await validateDirectoryChain(filesystem, stateRoot, lockPath);
  if (!lock.exists) {
    await revalidateDirectories(filesystem, lock.visited);
    return Object.freeze({ count: 0, identity: null });
  }
  if (runtime === null) fail("PRODUCTION_BINDING_DRIFT");
  const ownerState = await secureReadJson(
    filesystem,
    path.join(lockPath, "owner.json"),
  );
  const owner = assertLeaseShape(
    ownerState.value,
    binding,
    profile,
    stateRoot,
  );
  if (
    JSON.stringify(owner) !== JSON.stringify(runtime.lease)
  ) {
    fail("PRODUCTION_BINDING_DRIFT");
  }
  await revalidateDirectories(filesystem, lock.visited);
  return Object.freeze({
    count: 1,
    identity: ownerState.identity,
  });
};

const validateRootContext = async (filesystem, config) => {
  const toolStats = await filesystem.lstat(config.toolRoot);
  const stateStats = await filesystem.lstat(config.stateRoot);
  if (!ordinaryDirectory(toolStats) || !ordinaryDirectory(stateStats)) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  const [toolReal, stateReal, repositoryReal] = await Promise.all([
    filesystem.realpath(config.toolRoot),
    filesystem.realpath(config.stateRoot),
    filesystem.realpath(repositoryRoot),
  ]);
  if (
    !isContained(toolReal, stateReal)
    || isContained(repositoryReal, toolReal)
    || isContained(repositoryReal, stateReal)
    || isContained(toolReal, repositoryReal)
    || isContained(stateReal, repositoryReal)
  ) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  return Object.freeze({
    stateIdentity: stateStats,
    stateReal,
    toolIdentity: toolStats,
    toolReal,
  });
};

const revalidateRootContext = async (filesystem, config, context) => {
  const [toolStats, stateStats, toolReal, stateReal] = await Promise.all([
    filesystem.lstat(config.toolRoot),
    filesystem.lstat(config.stateRoot),
    filesystem.realpath(config.toolRoot),
    filesystem.realpath(config.stateRoot),
  ]);
  if (
    !sameIdentity(context.toolIdentity, toolStats)
    || !sameIdentity(context.stateIdentity, stateStats)
    || context.toolReal !== toolReal
    || context.stateReal !== stateReal
  ) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
};

const guardInspection = async (operation) => {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof ProductionMatrixError) throw error;
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
};

const probeTypedProvider = async (provider, request) => {
  if (
    !exactKeys(provider, ["inspect", "probe"])
    || typeof provider.probe !== "function"
    || typeof provider.inspect !== "function"
  ) {
    fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
  }
  let result;
  try {
    result = await provider.probe(request);
  } catch {
    fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
  }
  if (
    !exactKeys(result, ["apiLevel", "available", "profileId"])
    || result.profileId !== request.profileId
    || result.apiLevel !== request.apiLevel
    || result.available !== true
  ) {
    fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
  }
};

const validateTypedResult = (value, binding, kind) => {
  if (
    !exactKeys(value, [
      "apiLevel",
      "count",
      "deviceFingerprint",
      "kind",
      "profileId",
      "serial",
      "snapshot",
    ])
    || value.kind !== kind
    || !Number.isSafeInteger(value.count)
    || value.count < 0
  ) {
    fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
  }
  const returned = assertBinding({
    profileId: value.profileId,
    apiLevel: value.apiLevel,
    serial: value.serial,
    deviceFingerprint: value.deviceFingerprint,
    snapshot: value.snapshot,
  });
  if (!sameBinding(returned, binding)) fail("PRODUCTION_BINDING_DRIFT");
  return value.count;
};

export const createProductionResidueAdapter = async (
  config,
  dependencies = {
    filesystem: Object.freeze({
      lstat: nodeLstat,
      open: nodeOpen,
      readdir: nodeReaddir,
      realpath: nodeRealpath,
    }),
    loadProfiles: loadN31Profiles,
    providers: Object.freeze({}),
  },
) => {
  assertProductionConfig(config);
  assertDependencies(dependencies);
  let profileDocument;
  try {
    profileDocument = await dependencies.loadProfiles(config.profilesFile);
  } catch {
    fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
  }
  if (
    profileDocument === null
    || typeof profileDocument !== "object"
    || !Array.isArray(profileDocument.profiles)
  ) {
    fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
  }
  const profiles = new Map();
  for (const profile of profileDocument.profiles) {
    if (
      profileIdentity.get(profile?.id) !== profile?.apiLevel
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{1,79}$/u.test(profile?.avdName ?? "")
      || profile?.snapshot !== "clean"
      || profiles.has(profile.id)
    ) {
      fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
    }
    profiles.set(profile.id, Object.freeze({
      apiLevel: profile.apiLevel,
      avdName: profile.avdName,
      id: profile.id,
      snapshot: profile.snapshot,
    }));
  }
  const probed = new Set();
  const filesystem = dependencies.filesystem;
  const reportRoot = path.join(config.stateRoot, "reports", "n52-production-matrix");
  const stagingRoot = path.join(
    config.stateRoot,
    "staging",
    "n52-production-matrix",
  );

  const profileFor = (request) => {
    assertProfileRequest(request);
    const profile = profiles.get(request.profileId);
    if (profile?.apiLevel !== request.apiLevel) {
      fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
    }
    return profile;
  };

  const assertReady = (value) => {
    const binding = assertBinding(value);
    if (!probed.has(binding.profileId)) {
      fail("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE");
    }
    const profile = profiles.get(binding.profileId);
    if (profile?.apiLevel !== binding.apiLevel) {
      fail("PRODUCTION_BINDING_DRIFT");
    }
    return Object.freeze({ binding, profile });
  };

  const inspect = async (value, kind, operation) => {
    const { binding, profile } = assertReady(value);
    return guardInspection(async () => {
      const rootContext = await validateRootContext(filesystem, config);
      const count = await operation(binding, profile);
      if (!Number.isSafeInteger(count) || count < 0) {
        fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
      }
      await revalidateRootContext(filesystem, config, rootContext);
      return resultFor(binding, kind, count);
    });
  };

  const adapter = {
    probe: async (request) => {
      const profile = profileFor(request);
      const providerRequest = Object.freeze({
        profileId: profile.id,
        apiLevel: profile.apiLevel,
        packages: FIXED_RESIDUE_PACKAGES,
      });
      for (const kind of typedKinds) {
        await probeTypedProvider(
          dependencies.providers[kind],
          providerRequest,
        );
      }
      await guardInspection(async () => {
        const rootContext = await validateRootContext(filesystem, config);
        await revalidateRootContext(filesystem, config, rootContext);
      });
      probed.add(profile.id);
      return Object.freeze({
        profileId: profile.id,
        apiLevel: profile.apiLevel,
        capabilities: Object.freeze([]),
      });
    },
    close: async () => {
      probed.clear();
    },
  };

  for (const kind of typedKinds) {
    adapter[kind] = async (value) =>
      inspect(value, kind, async (binding, profile) => {
        const runtime = kind === "ownedProcesses"
          ? await readRuntime(
            filesystem,
            config.stateRoot,
            profile,
            binding,
          )
          : null;
        let providerResult;
        try {
          providerResult = await dependencies.providers[kind].inspect(
            Object.freeze({
              ...binding,
              packages: FIXED_RESIDUE_PACKAGES,
              processes: kind === "ownedProcesses" && runtime !== null
                ? Object.freeze([
                  Object.freeze({
                    avdName: profile.avdName,
                    pid: runtime.pid,
                    profileId: binding.profileId,
                    serial: binding.serial,
                  }),
                ])
                : Object.freeze([]),
            }),
          );
        } catch (error) {
          if (error instanceof ProductionMatrixError) throw error;
          fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
        }
        const count = validateTypedResult(providerResult, binding, kind);
        if (kind === "ownedProcesses") {
          const currentRuntime = await readRuntime(
            filesystem,
            config.stateRoot,
            profile,
            binding,
          );
          if (JSON.stringify(currentRuntime) !== JSON.stringify(runtime)) {
            fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
          }
        }
        return count;
      });
  }

  for (const kind of filesystemKinds) {
    adapter[kind] = async (value) =>
      inspect(value, kind, async (binding, profile) => {
        const artifactCount =
          await countBoundArtifacts(
            filesystem,
            config.stateRoot,
            stagingRoot,
            binding,
            kind,
          )
          + await countBoundArtifacts(
            filesystem,
            config.stateRoot,
            reportRoot,
            binding,
            kind,
          );
        if (kind !== "logs") return artifactCount;
        const runtime = await readRuntime(
          filesystem,
          config.stateRoot,
          profile,
          binding,
        );
        const logFile = n31LogPath(config.stateRoot, profile, binding);
        const n31Log = await inspectOrdinaryFixedFile(
          filesystem,
          config.stateRoot,
          logFile,
        );
        const currentRuntime = await readRuntime(
          filesystem,
          config.stateRoot,
          profile,
          binding,
        );
        if (JSON.stringify(currentRuntime) !== JSON.stringify(runtime)) {
          fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
        }
        return artifactCount + n31Log;
      });
  }

  adapter.runtimeFiles = async (value) =>
    inspect(value, "runtimeFiles", async (binding, profile) => {
      const runtime = await readRuntime(
        filesystem,
        config.stateRoot,
        profile,
        binding,
      );
      const currentRuntime = await readRuntime(
        filesystem,
        config.stateRoot,
        profile,
        binding,
      );
      if (JSON.stringify(currentRuntime) !== JSON.stringify(runtime)) {
        fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
      }
      return runtime === null ? 0 : 1;
    });

  adapter.leases = async (value) =>
    inspect(value, "leases", async (binding, profile) => {
      const runtime = await readRuntime(
        filesystem,
        config.stateRoot,
        profile,
        binding,
      );
      const port = serialPort(binding.serial);
      const avdLock = path.join(
        config.stateRoot,
        "locks",
        "avd",
        `${profile.avdName}.lock`,
      );
      const portLock = path.join(
        config.stateRoot,
        "locks",
        "port",
        `${port}.lock`,
      );
      const firstAvd = await inspectLock(
        filesystem,
        config.stateRoot,
        avdLock,
        binding,
        profile,
        runtime,
      );
      const firstPort = await inspectLock(
        filesystem,
        config.stateRoot,
        portLock,
        binding,
        profile,
        runtime,
      );
      const currentRuntime = await readRuntime(
        filesystem,
        config.stateRoot,
        profile,
        binding,
      );
      const secondAvd = await inspectLock(
        filesystem,
        config.stateRoot,
        avdLock,
        binding,
        profile,
        currentRuntime,
      );
      const secondPort = await inspectLock(
        filesystem,
        config.stateRoot,
        portLock,
        binding,
        profile,
        currentRuntime,
      );
      if (
        firstAvd.count !== secondAvd.count
        || firstPort.count !== secondPort.count
        || !sameOptionalIdentity(firstAvd.identity, secondAvd.identity)
        || !sameOptionalIdentity(firstPort.identity, secondPort.identity)
        || JSON.stringify(currentRuntime) !== JSON.stringify(runtime)
      ) {
        fail("PRODUCTION_RESIDUE_INSPECTION_FAILED");
      }
      return firstAvd.count + firstPort.count;
    });

  return Object.freeze(Object.fromEntries([
    ["probe", adapter.probe],
    ...residueKinds.map((kind) => [kind, adapter[kind]]),
    ["close", adapter.close],
  ]));
};
