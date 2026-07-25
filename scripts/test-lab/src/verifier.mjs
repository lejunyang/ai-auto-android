// 功能用途：在仓库外按摘要定位并验证制品，使用类型化 inspector 建立可安装前的可信描述符。
import { createHash } from "node:crypto";
import {
  constants,
  lstat,
  open,
  realpath,
} from "node:fs/promises";
import path from "node:path";

import {
  ExternalAppError,
  validateExternalAppManifest,
} from "./manifest.mjs";

const inspectorResultKeys = [
  "abi",
  "minSdk",
  "package",
  "signingCertificateSha256",
  "version",
  "versionCode",
];
const digestPattern = /^[0-9a-f]{64}$/u;
const maximumArtifactBytes = 8 * 1024 * 1024 * 1024;
const verifiedDescriptors = new WeakMap();

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort())
    === JSON.stringify([...expected].sort());

const isWithin = (root, candidate) => {
  const relative = path.relative(root, candidate);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== "..");
};

const assertAbsoluteDirectory = async (directory, code) => {
  if (typeof directory !== "string" || !path.isAbsolute(directory)) {
    throw new ExternalAppError(code);
  }
  let info;
  try {
    info = await lstat(directory);
  } catch {
    throw new ExternalAppError("CACHE_ROOT_MISSING");
  }
  if (!info.isDirectory() || info.isSymbolicLink()) {
    throw new ExternalAppError("CACHE_PATH_UNSAFE");
  }
};

const assertSafeDirectoryChain = async (cacheRoot, artifactPath) => {
  const relative = path.relative(cacheRoot, artifactPath);
  if (
    relative.startsWith(`..${path.sep}`)
    || relative === ".."
    || path.isAbsolute(relative)
  ) {
    throw new ExternalAppError("CACHE_PATH_UNSAFE");
  }
  const segments = relative.split(path.sep).slice(0, -1);
  let current = cacheRoot;
  for (const segment of segments) {
    current = path.join(current, segment);
    let info;
    try {
      info = await lstat(current);
    } catch (error) {
      if (error?.code === "ENOENT") {
        throw new ExternalAppError("CACHE_ENTRY_MISSING");
      }
      throw new ExternalAppError("CACHE_PATH_UNSAFE");
    }
    if (!info.isDirectory() || info.isSymbolicLink()) {
      throw new ExternalAppError("CACHE_PATH_UNSAFE");
    }
  }
};

const readVerifiedBytes = async (
  artifactPath,
  expectedSize,
  expectedHash,
  expectedIdentity = null,
) => {
  let before;
  try {
    before = await lstat(artifactPath);
  } catch (error) {
    if (error?.code === "ENOENT") {
      throw new ExternalAppError("CACHE_ENTRY_MISSING");
    }
    throw new ExternalAppError("CACHE_ENTRY_UNSAFE");
  }
  if (
    !before.isFile()
    || before.isSymbolicLink()
    || before.size > maximumArtifactBytes
  ) {
    throw new ExternalAppError("CACHE_ENTRY_UNSAFE");
  }
  if (before.size !== expectedSize) {
    throw new ExternalAppError("ARTIFACT_SIZE_MISMATCH");
  }

  let handle;
  try {
    handle = await open(
      artifactPath,
      constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0),
    );
    const opened = await handle.stat();
    if (
      !opened.isFile()
      || opened.dev !== before.dev
      || opened.ino !== before.ino
      || opened.size !== before.size
    ) {
      throw new ExternalAppError("CACHE_ENTRY_UNSAFE");
    }
    if (
      expectedIdentity !== null
      && (
        opened.dev !== expectedIdentity.dev
        || opened.ino !== expectedIdentity.ino
      )
    ) {
      throw new ExternalAppError("CACHE_ENTRY_CHANGED");
    }
    const hash = createHash("sha256");
    const buffer = Buffer.alloc(1024 * 1024);
    let position = 0;
    while (position < opened.size) {
      const length = Math.min(buffer.length, opened.size - position);
      const { bytesRead } = await handle.read(buffer, 0, length, position);
      if (bytesRead <= 0) {
        throw new ExternalAppError("CACHE_ENTRY_UNSAFE");
      }
      hash.update(buffer.subarray(0, bytesRead));
      position += bytesRead;
    }
    const after = await handle.stat();
    if (
      after.dev !== opened.dev
      || after.ino !== opened.ino
      || after.size !== opened.size
      || after.mtimeMs !== opened.mtimeMs
      || after.ctimeMs !== opened.ctimeMs
    ) {
      throw new ExternalAppError("CACHE_ENTRY_CHANGED");
    }
    if (hash.digest("hex") !== expectedHash) {
      throw new ExternalAppError("ARTIFACT_HASH_MISMATCH");
    }
    return Object.freeze({
      dev: opened.dev,
      ino: opened.ino,
    });
  } catch (error) {
    if (error instanceof ExternalAppError) throw error;
    throw new ExternalAppError("CACHE_ENTRY_UNSAFE");
  } finally {
    await handle?.close().catch(() => {});
  }
};

const assertInspectorResult = (value) => {
  if (
    !exactKeys(value, inspectorResultKeys)
    || typeof value.package !== "string"
    || typeof value.version !== "string"
    || !Number.isSafeInteger(value.versionCode)
    || !Array.isArray(value.abi)
    || value.abi.some((abi) => typeof abi !== "string")
    || !Number.isInteger(value.minSdk)
    || !digestPattern.test(value.signingCertificateSha256 ?? "")
  ) {
    throw new ExternalAppError("INSPECTOR_RESULT_INVALID");
  }
  return value;
};

const compareInspectorResult = (manifest, inspected) => {
  const checks = [
    ["package", "ARTIFACT_PACKAGE_MISMATCH"],
    ["version", "ARTIFACT_VERSION_MISMATCH"],
    ["versionCode", "ARTIFACT_VERSION_CODE_MISMATCH"],
    ["minSdk", "ARTIFACT_MIN_SDK_MISMATCH"],
    ["signingCertificateSha256", "ARTIFACT_SIGNING_MISMATCH"],
  ];
  for (const [field, code] of checks) {
    if (manifest[field] !== inspected[field]) {
      throw new ExternalAppError(code);
    }
  }
  if (
    JSON.stringify([...manifest.abi].sort())
    !== JSON.stringify([...inspected.abi].sort())
  ) {
    throw new ExternalAppError("ARTIFACT_ABI_MISMATCH");
  }
};

export const cacheArtifactPath = (cacheRoot, digest) => {
  if (
    typeof cacheRoot !== "string"
    || !path.isAbsolute(cacheRoot)
    || !digestPattern.test(digest ?? "")
  ) {
    throw new ExternalAppError("CACHE_PATH_UNSAFE");
  }
  return path.join(
    cacheRoot,
    "sha256",
    digest.slice(0, 2),
    digest,
    "artifact",
  );
};

export const assertVerifiedExternalAppDescriptor = (descriptor) => {
  if (
    descriptor === null
    || typeof descriptor !== "object"
    || !verifiedDescriptors.has(descriptor)
  ) {
    throw new ExternalAppError("DESCRIPTOR_INVALID");
  }
  return descriptor;
};

export const reverifyExternalAppDescriptor = async (descriptor) => {
  assertVerifiedExternalAppDescriptor(descriptor);
  const verification = verifiedDescriptors.get(descriptor);
  await assertAbsoluteDirectory(
    verification.cacheRoot,
    "CACHE_ROOT_NOT_ABSOLUTE",
  );
  await assertSafeDirectoryChain(
    verification.cacheRoot,
    verification.artifactPath,
  );
  await readVerifiedBytes(
    verification.artifactPath,
    verification.sizeBytes,
    verification.sha256,
    verification.identity,
  );
  return descriptor;
};

export const verifyExternalAppArtifact = async ({
  manifest: manifestInput,
  cacheRoot,
  repositoryRoot,
  inspector,
}) => {
  const manifest = validateExternalAppManifest(manifestInput);
  if (cacheRoot === undefined || cacheRoot === null || cacheRoot === "") {
    throw new ExternalAppError("CACHE_ROOT_REQUIRED");
  }
  if (typeof cacheRoot !== "string" || !path.isAbsolute(cacheRoot)) {
    throw new ExternalAppError("CACHE_ROOT_NOT_ABSOLUTE");
  }
  if (typeof repositoryRoot !== "string" || !path.isAbsolute(repositoryRoot)) {
    throw new ExternalAppError("REPOSITORY_ROOT_INVALID");
  }
  const lexicalRepositoryRoot = path.resolve(repositoryRoot);
  const lexicalCacheRoot = path.resolve(cacheRoot);
  if (
    isWithin(lexicalRepositoryRoot, lexicalCacheRoot)
    || isWithin(lexicalCacheRoot, lexicalRepositoryRoot)
  ) {
    throw new ExternalAppError("CACHE_ROOT_INSIDE_REPOSITORY");
  }

  await assertAbsoluteDirectory(cacheRoot, "CACHE_ROOT_NOT_ABSOLUTE");
  let repositoryReal;
  let cacheReal;
  try {
    repositoryReal = await realpath(repositoryRoot);
    cacheReal = await realpath(cacheRoot);
  } catch {
    throw new ExternalAppError("CACHE_PATH_UNSAFE");
  }
  if (
    isWithin(repositoryReal, cacheReal)
    || isWithin(cacheReal, repositoryReal)
  ) {
    throw new ExternalAppError("CACHE_ROOT_INSIDE_REPOSITORY");
  }

  const artifactPath = cacheArtifactPath(cacheReal, manifest.sha256);
  await assertSafeDirectoryChain(cacheReal, artifactPath);
  const identity = await readVerifiedBytes(
    artifactPath,
    manifest.sizeBytes,
    manifest.sha256,
  );

  if (typeof inspector?.inspect !== "function") {
    throw new ExternalAppError("INSPECTOR_REQUIRED");
  }
  let inspected;
  try {
    inspected = await inspector.inspect(Object.freeze({
      artifactPath,
      sizeBytes: manifest.sizeBytes,
      sha256: manifest.sha256,
    }));
  } catch {
    throw new ExternalAppError("INSPECTION_FAILED");
  }
  inspected = assertInspectorResult(inspected);
  compareInspectorResult(manifest, inspected);

  // inspector 返回后再次打开并校验，避免验证与后续使用之间接受被替换的路径。
  await readVerifiedBytes(
    artifactPath,
    manifest.sizeBytes,
    manifest.sha256,
  );

  const descriptor = Object.freeze({
    kind: "verified-external-app",
    schemaVersion: manifest.schemaVersion,
    artifactPath,
    package: manifest.package,
    version: manifest.version,
    versionCode: manifest.versionCode,
    abi: manifest.abi,
    minSdk: manifest.minSdk,
    source: manifest.source,
    licenseNote: manifest.licenseNote,
    sizeBytes: manifest.sizeBytes,
    sha256: manifest.sha256,
    signingCertificateSha256: manifest.signingCertificateSha256,
  });
  verifiedDescriptors.set(descriptor, Object.freeze({
    cacheRoot: cacheReal,
    artifactPath,
    identity,
    sizeBytes: manifest.sizeBytes,
    sha256: manifest.sha256,
  }));
  return descriptor;
};
