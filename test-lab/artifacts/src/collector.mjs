// 功能用途：安全收集有界失败证据，统一执行脱敏、预算、原子发布和可验证保留期清理。
import { createHash, randomUUID } from "node:crypto";
import {
  access,
  constants,
  lstat,
  mkdir,
  open,
  readFile,
  readdir,
  rename,
  rmdir,
  unlink,
  writeFile,
} from "node:fs/promises";
import path from "node:path";

import { redactText, RedactionError } from "./redaction.mjs";
import {
  assertArtifactDocument,
  loadArtifactSchemas,
  validateArtifactDocument,
} from "./schema-validator.mjs";

const defaultBudgets = Object.freeze({
  fileBytes: 512 * 1024,
  scenarioBytes: 2 * 1024 * 1024,
  runBytes: 16 * 1024 * 1024,
});
const maximumSourceBytes = 8 * 1024 * 1024;
const maximumTtlMs = 30 * 24 * 60 * 60 * 1000;
const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const safeScenarioId = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u;
const safeUuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const safeSourceName = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u;

const artifactDefinitions = Object.freeze([
  {
    kind: "device-metadata",
    file: "device.json",
    mediaType: "application/json",
    source: "device",
  },
  {
    kind: "action-timeline",
    file: "timeline.json",
    mediaType: "application/json",
    source: "timeline",
  },
  {
    kind: "test-report",
    file: "report.txt",
    mediaType: "text/plain",
    source: "report",
  },
  {
    kind: "hierarchy",
    file: "hierarchy.xml",
    mediaType: "application/xml",
    source: "hierarchy",
  },
  {
    kind: "log",
    file: "log.txt",
    mediaType: "text/plain",
    source: "log",
  },
  {
    kind: "screenshot",
    file: "screenshot.png",
    mediaType: "image/png",
    source: "screenshot",
  },
]);

class CollectorError extends Error {
  constructor(code) {
    super(code);
    this.name = "CollectorError";
    this.code = code;
  }
}

const sha256 = (value) => createHash("sha256").update(value).digest("hex");

const jsonBytes = (value) =>
  Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");

const stableBlockedCode = (error) => {
  if (error instanceof RedactionError) return "REDACTION_UNVERIFIED";
  if (error instanceof CollectorError) return error.code;
  if (error?.code === "RUN_SCHEMA_INVALID") return "RUN_SCHEMA_INVALID";
  return "COLLECTION_FAILED";
};

const scenarioDirectoryName = (run) => `${run.scenarioId}-${run.iteration}`;

const assertSafeIdentity = (run) => {
  if (
    !run
    || typeof run !== "object"
    || !safeUuid.test(run.runId ?? "")
    || !safeScenarioId.test(run.scenarioId ?? "")
    || !Number.isInteger(run.iteration)
    || run.iteration < 1
    || run.iteration > 10000
  ) {
    throw new CollectorError("ARTIFACT_PATH_UNSAFE");
  }
};

const assertSafeSourceName = (file) => {
  if (
    typeof file !== "string"
    || path.isAbsolute(file)
    || path.basename(file) !== file
    || !safeSourceName.test(file)
  ) {
    throw new CollectorError("ARTIFACT_PATH_UNSAFE");
  }
};

const normalizeBudgets = (budgets = defaultBudgets) => {
  const normalized = {
    fileBytes: budgets.fileBytes ?? defaultBudgets.fileBytes,
    scenarioBytes: budgets.scenarioBytes ?? defaultBudgets.scenarioBytes,
    runBytes: budgets.runBytes ?? defaultBudgets.runBytes,
  };
  if (
    !Number.isInteger(normalized.fileBytes)
    || !Number.isInteger(normalized.scenarioBytes)
    || !Number.isInteger(normalized.runBytes)
    || normalized.fileBytes < 256
    || normalized.fileBytes > 8 * 1024 * 1024
    || normalized.scenarioBytes < 1024
    || normalized.scenarioBytes > 64 * 1024 * 1024
    || normalized.runBytes < 1024
    || normalized.runBytes > 512 * 1024 * 1024
    || normalized.fileBytes > normalized.scenarioBytes
    || normalized.scenarioBytes > normalized.runBytes
  ) {
    throw new CollectorError("BUDGET_INVALID");
  }
  return normalized;
};

const exactKeys = (value, expected) =>
  value !== null
  && typeof value === "object"
  && !Array.isArray(value)
  && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expected].sort());

const assertCollectionInputs = (sources, sensitivity, run, omitScreenshot) => {
  const expectedSourceKeys = omitScreenshot
    ? ["hierarchy", "log", "report"]
    : ["hierarchy", "log", "report", "screenshot"];
  if (
    typeof omitScreenshot !== "boolean"
    || !exactKeys(sources, expectedSourceKeys)
    || !exactKeys(sources.hierarchy, ["file"])
    || !exactKeys(sources.report, ["file"])
    || !exactKeys(sources.log, ["file", "windowEndedAt", "windowStartedAt"])
    || (
      !omitScreenshot
      && (
        !exactKeys(sources.screenshot, ["file", "proof"])
        || !exactKeys(
          sources.screenshot.proof,
          ["artifactSha256", "method", "processorId"],
        )
      )
    )
    || !exactKeys(sensitivity, ["declaration", "targetPackages", "values"])
    || sensitivity.declaration !== "complete"
    || !Array.isArray(sensitivity.values)
    || sensitivity.values.some((value) => typeof value !== "string" || !value.trim())
    || !Array.isArray(sensitivity.targetPackages)
    || sensitivity.targetPackages.length === 0
    || sensitivity.targetPackages.some((packageName) =>
      typeof packageName !== "string"
      || !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/u.test(packageName))
    || run.timeline.some((entry) =>
      !sensitivity.targetPackages.includes(entry.targetPackage))
  ) {
    throw new CollectorError("RUN_SCHEMA_INVALID");
  }
};

const assertTtl = (ttlMs) => {
  if (!Number.isInteger(ttlMs) || ttlMs < 1 || ttlMs > maximumTtlMs) {
    throw new CollectorError("BUDGET_INVALID");
  }
};

const isSameDirectory = (before, after) =>
  before.isDirectory()
  && after.isDirectory()
  && before.dev === after.dev
  && before.ino === after.ino;

const isSameFile = (before, after) =>
  before.isFile()
  && after.isFile()
  && before.dev === after.dev
  && before.ino === after.ino
  && before.size === after.size;

const assertDirectoryNoLinks = async (artifactRoot, directory) => {
  const relative = path.relative(artifactRoot, directory);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new CollectorError("ARTIFACT_PATH_UNSAFE");
  }
  let current = artifactRoot;
  for (const segment of relative.split(path.sep).filter(Boolean)) {
    current = path.join(current, segment);
    let metadata;
    try {
      metadata = await lstat(current);
    } catch (error) {
      if (error.code === "ENOENT") {
        throw new CollectorError("ARTIFACT_SOURCE_MISSING");
      }
      throw error;
    }
    if (metadata.isSymbolicLink() || !metadata.isDirectory()) {
      throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
    }
  }
};

const readSecureSource = async ({
  artifactRoot,
  stagingDirectory,
  kind,
  file,
  sourceIo,
}) => {
  assertSafeSourceName(file);
  await assertDirectoryNoLinks(artifactRoot, stagingDirectory);
  const sourceFile = path.join(stagingDirectory, file);
  let before;
  try {
    before = await lstat(sourceFile);
  } catch (error) {
    if (error.code === "ENOENT") {
      throw new CollectorError("ARTIFACT_SOURCE_MISSING");
    }
    throw error;
  }
  if (before.isSymbolicLink() || !before.isFile()) {
    throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
  }
  if (before.size > maximumSourceBytes) {
    throw new CollectorError("ARTIFACT_SOURCE_TOO_LARGE");
  }
  await sourceIo?.afterLstat?.({ kind, file: sourceFile });

  let handle;
  try {
    handle = await open(
      sourceFile,
      constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0),
    );
  } catch (error) {
    if (["ELOOP", "EMLINK"].includes(error.code)) {
      throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
    }
    throw error;
  }
  try {
    const after = await handle.stat();
    if (!isSameFile(before, after)) {
      throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
    }
    const content = await handle.readFile();
    const finalMetadata = await handle.stat();
    if (!isSameFile(after, finalMetadata) || content.length !== after.size) {
      throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
    }
    return content;
  } finally {
    await handle.close();
  }
};

const safeRemoveTree = async (target) => {
  let metadata;
  try {
    metadata = await lstat(target);
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (metadata.isSymbolicLink() || !metadata.isDirectory()) {
    await unlink(target);
    return;
  }
  for (const entry of await readdir(target)) {
    await safeRemoveTree(path.join(target, entry));
  }
  await rmdir(target);
};

const pruneEmptyDirectories = async (directories) => {
  for (const directory of directories) {
    try {
      await rmdir(directory);
    } catch (error) {
      if (!["ENOENT", "ENOTEMPTY"].includes(error.code)) throw error;
    }
  }
};

const cleanupStaging = async (artifactRoot, run) => {
  if (
    !safeUuid.test(run?.runId ?? "")
    || !safeScenarioId.test(run?.scenarioId ?? "")
  ) {
    return;
  }
  const stagingRoot = path.join(artifactRoot, ".staging");
  const runStaging = path.join(stagingRoot, run.runId);
  let stagingMetadata;
  try {
    stagingMetadata = await lstat(stagingRoot);
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (stagingMetadata.isSymbolicLink() || !stagingMetadata.isDirectory()) {
    await unlink(stagingRoot);
    return;
  }
  let runMetadata;
  try {
    runMetadata = await lstat(runStaging);
  } catch (error) {
    if (error.code === "ENOENT") {
      await pruneEmptyDirectories([stagingRoot]);
      return;
    }
    throw error;
  }
  if (runMetadata.isSymbolicLink() || !runMetadata.isDirectory()) {
    await unlink(runStaging);
    await pruneEmptyDirectories([stagingRoot]);
    return;
  }
  await safeRemoveTree(path.join(runStaging, run.scenarioId));
  await pruneEmptyDirectories([runStaging, stagingRoot]);
};

const ensureOrdinaryDirectory = async (directory) => {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const metadata = await lstat(directory);
  if (metadata.isSymbolicLink() || !metadata.isDirectory()) {
    throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
  }
};

const createOrdinaryChildDirectory = async (parent, name) => {
  const directory = path.join(parent, name);
  try {
    await mkdir(directory, { mode: 0o700 });
  } catch (error) {
    if (error.code !== "EEXIST") throw error;
  }
  const metadata = await lstat(directory);
  if (metadata.isSymbolicLink() || !metadata.isDirectory()) {
    throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
  }
  return directory;
};

const prepareOutputDirectories = async (artifactRoot, runId) => {
  const runsRoot = await createOrdinaryChildDirectory(artifactRoot, "runs");
  const runRoot = await createOrdinaryChildDirectory(runsRoot, runId);
  return { runsRoot, runRoot };
};

const removeUnsafeOutputLinks = async (artifactRoot, run) => {
  const runsRoot = path.join(artifactRoot, "runs");
  let runsMetadata;
  try {
    runsMetadata = await lstat(runsRoot);
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (runsMetadata.isSymbolicLink() || !runsMetadata.isDirectory()) {
    await unlink(runsRoot);
    return;
  }
  if (!safeUuid.test(run?.runId ?? "")) return;
  const runRoot = path.join(runsRoot, run.runId);
  try {
    const runMetadata = await lstat(runRoot);
    if (runMetadata.isSymbolicLink() || !runMetadata.isDirectory()) {
      await unlink(runRoot);
    }
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  await pruneEmptyDirectories([runsRoot]);
};

const writeAtomicFile = async (directory, file, content) => {
  await ensureOrdinaryDirectory(directory);
  const temporary = path.join(directory, `.${file}.${randomUUID()}.tmp`);
  await writeFile(temporary, content, { mode: 0o600, flag: "wx" });
  await rename(temporary, path.join(directory, file));
};

const writeBlockedSummary = async (artifactRoot, errorCode) => {
  const summary = {
    schemaVersion: "1.0",
    uploadAllowed: false,
    errorCode,
  };
  const schemas = await loadArtifactSchemas();
  assertArtifactDocument(schemas.blockedSummary, summary, "BLOCKED_SCHEMA_INVALID");
  const blockedDirectory = path.join(artifactRoot, "blocked");
  await safeRemoveTree(blockedDirectory);
  await writeAtomicFile(blockedDirectory, "summary.json", jsonBytes(summary));
  return {
    uploadAllowed: false,
    summary,
    scenarioDirectory: blockedDirectory,
  };
};

const truncateUtf8 = (buffer, maximumBytes) => {
  if (buffer.length <= maximumBytes) {
    return { content: buffer, truncated: false };
  }
  let end = maximumBytes;
  while (end > 0 && (buffer[end] & 0xc0) === 0x80) end -= 1;
  const suffix = Buffer.from("\n[TRUNCATED]\n", "utf8");
  const available = Math.max(0, end - suffix.length);
  let safeEnd = available;
  while (safeEnd > 0 && (buffer[safeEnd] & 0xc0) === 0x80) safeEnd -= 1;
  return {
    content: Buffer.concat([buffer.subarray(0, safeEnd), suffix]),
    truncated: true,
  };
};

const verifyScreenshot = async (
  content,
  proof,
  trustedScreenshotVerifier,
) => {
  if (
    !proof
    || proof.method !== "verified-masked"
    || typeof proof.processorId !== "string"
    || !proof.processorId
    || typeof proof.artifactSha256 !== "string"
    || !/^[a-f0-9]{64}$/u.test(proof.artifactSha256)
    || !content.subarray(0, pngSignature.length).equals(pngSignature)
    || sha256(content) !== proof.artifactSha256
    || typeof trustedScreenshotVerifier !== "function"
  ) {
    throw new RedactionError("REDACTION_UNVERIFIED");
  }
  let verified = false;
  try {
    verified = await trustedScreenshotVerifier(
      Buffer.from(content),
      Object.freeze({ ...proof }),
    );
  } catch {
    throw new RedactionError("REDACTION_UNVERIFIED");
  }
  if (verified !== true) {
    throw new RedactionError("REDACTION_UNVERIFIED");
  }
};

const assertLogWindow = (source, run) => {
  const startedAt = Date.parse(source.windowStartedAt);
  const endedAt = Date.parse(source.windowEndedAt);
  if (
    !Number.isFinite(startedAt)
    || !Number.isFinite(endedAt)
    || source.windowStartedAt !== run.startedAt
    || source.windowEndedAt !== run.finishedAt
    || endedAt < startedAt
    || endedAt - startedAt > 5 * 60 * 1000
  ) {
    throw new CollectorError("RUN_SCHEMA_INVALID");
  }
};

const collectCandidates = async ({
  artifactRoot,
  run,
  sources,
  sensitivity,
  budgets,
  sourceIo,
  trustedScreenshotVerifier,
  omitScreenshot,
}) => {
  const stagingDirectory = path.join(
    artifactRoot,
    ".staging",
    run.runId,
    run.scenarioId,
  );
  const candidates = [];
  for (const definition of artifactDefinitions) {
    if (definition.source === "screenshot" && omitScreenshot) continue;
    let content;
    let truncated = false;
    if (definition.source === "device") {
      content = jsonBytes(run.device);
    } else if (definition.source === "timeline") {
      content = jsonBytes(run.timeline);
    } else {
      const source = sources[definition.source];
      if (!source || typeof source !== "object") {
        throw new CollectorError("ARTIFACT_SOURCE_MISSING");
      }
      if (definition.source === "log") assertLogWindow(source, run);
      content = await readSecureSource({
        artifactRoot,
        stagingDirectory,
        kind: definition.source,
        file: source.file,
        sourceIo,
      });
      if (definition.source === "screenshot") {
        await verifyScreenshot(
          content,
          source.proof,
          trustedScreenshotVerifier,
        );
      } else {
        content = Buffer.from(
          `${redactText(content, sensitivity).text}\n`,
          "utf8",
        );
      }
    }

    if (content.length > budgets.fileBytes) {
      if (definition.mediaType === "image/png") {
        candidates.push({ ...definition, omitted: true, truncated: true });
        continue;
      }
      const bounded = truncateUtf8(content, budgets.fileBytes);
      content = bounded.content;
      truncated = bounded.truncated;
    }
    candidates.push({
      ...definition,
      content,
      truncated,
      omitted: false,
    });
  }
  return candidates;
};

const classifyFailure = (errorCode) => {
  if (
    errorCode.startsWith("DEVICE_")
    || errorCode.startsWith("ADB_")
    || errorCode === "WEBVIEW_VERSION_DRIFT"
  ) {
    return "device";
  }
  if (
    errorCode.startsWith("EMULATOR_")
    || errorCode.startsWith("SDK_")
    || errorCode.startsWith("INFRASTRUCTURE_")
  ) {
    return "infrastructure";
  }
  return "product";
};

const createSummary = ({
  run,
  now,
  ttlMs,
  artifacts,
  budgets,
  usedBytes,
  scenarioTruncated,
  runTruncated,
}) => ({
  schemaVersion: "1.0",
  runId: run.runId,
  scenarioId: run.scenarioId,
  iteration: run.iteration,
  retainedAt: now.toISOString(),
  expiresAt: new Date(now.getTime() + ttlMs).toISOString(),
  uploadAllowed: true,
  result: {
    status: "failed",
    category: classifyFailure(run.outcome.errorCode),
    errorCode: run.outcome.errorCode,
  },
  artifacts,
  budget: {
    ...budgets,
    usedBytes,
    scenarioTruncated,
    runTruncated,
  },
});

const stabilizeSummary = (options) => {
  let usedBytes = options.artifactBytes;
  let summary;
  let serialized;
  for (let iteration = 0; iteration < 8; iteration += 1) {
    summary = createSummary({ ...options, usedBytes });
    serialized = jsonBytes(summary);
    const nextUsedBytes = options.artifactBytes + serialized.length;
    if (nextUsedBytes === usedBytes) break;
    usedBytes = nextUsedBytes;
  }
  summary = createSummary({ ...options, usedBytes });
  serialized = jsonBytes(summary);
  return {
    summary,
    serialized,
    totalBytes: options.artifactBytes + serialized.length,
  };
};

const sizeOfTree = async (root) => {
  let metadata;
  try {
    metadata = await lstat(root);
  } catch (error) {
    if (error.code === "ENOENT") return 0;
    throw error;
  }
  if (metadata.isSymbolicLink()) {
    throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
  }
  if (!metadata.isDirectory()) return metadata.size;
  let total = 0;
  for (const entry of await readdir(root)) {
    total += await sizeOfTree(path.join(root, entry));
  }
  return total;
};

const applyBudgets = ({
  run,
  now,
  ttlMs,
  candidates,
  budgets,
  existingRunBytes,
}) => {
  const selected = [];
  let artifactBytes = 0;
  let scenarioTruncated = candidates.some((candidate) =>
    candidate.truncated || candidate.omitted);
  let runTruncated = false;
  const runRemaining = budgets.runBytes - existingRunBytes;

  for (const candidate of candidates) {
    if (candidate.omitted) continue;
    const artifact = {
      kind: candidate.kind,
      file: candidate.file,
      mediaType: candidate.mediaType,
      bytes: candidate.content.length,
      sha256: sha256(candidate.content),
      redacted: true,
      truncated: candidate.truncated,
    };
    const tentative = [...selected, { artifact, content: candidate.content }];
    const stabilized = stabilizeSummary({
      run,
      now,
      ttlMs,
      artifacts: tentative.map((item) => item.artifact),
      budgets,
      artifactBytes: artifactBytes + candidate.content.length,
      scenarioTruncated,
      runTruncated,
    });
    if (
      stabilized.totalBytes > budgets.scenarioBytes
      || stabilized.totalBytes > runRemaining
    ) {
      scenarioTruncated = true;
      if (stabilized.totalBytes > runRemaining) runTruncated = true;
      break;
    }
    selected.push({ artifact, content: candidate.content });
    artifactBytes += candidate.content.length;
  }

  let stabilized = stabilizeSummary({
    run,
    now,
    ttlMs,
    artifacts: selected.map((item) => item.artifact),
    budgets,
    artifactBytes,
    scenarioTruncated,
    runTruncated,
  });
  while (
    selected.length > 0
    && (
      stabilized.totalBytes > budgets.scenarioBytes
      || stabilized.totalBytes > runRemaining
    )
  ) {
    const removed = selected.pop();
    artifactBytes -= removed.content.length;
    scenarioTruncated = true;
    if (stabilized.totalBytes > runRemaining) runTruncated = true;
    stabilized = stabilizeSummary({
      run,
      now,
      ttlMs,
      artifacts: selected.map((item) => item.artifact),
      budgets,
      artifactBytes,
      scenarioTruncated,
      runTruncated,
    });
  }
  if (stabilized.totalBytes > Math.min(budgets.scenarioBytes, runRemaining)) {
    throw new CollectorError("RUN_BUDGET_EXCEEDED");
  }
  return { selected, ...stabilized };
};

const publishScenario = async ({
  runRoot,
  scenarioDirectory,
  selected,
  summary,
  serialized,
}) => {
  const temporaryDirectory = path.join(
    runRoot,
    `.tmp-${randomUUID()}`,
  );
  await createOrdinaryChildDirectory(runRoot, path.basename(temporaryDirectory));
  try {
    for (const item of selected) {
      await writeFile(path.join(temporaryDirectory, item.artifact.file), item.content, {
        mode: 0o600,
        flag: "wx",
      });
    }
    await writeFile(path.join(temporaryDirectory, "summary.json"), serialized, {
      mode: 0o600,
      flag: "wx",
    });
    assertArtifactDocument(
      (await loadArtifactSchemas()).artifactSummary,
      summary,
      "SUMMARY_SCHEMA_INVALID",
    );
    await safeRemoveTree(scenarioDirectory);
    await rename(temporaryDirectory, scenarioDirectory);
  } catch (error) {
    await safeRemoveTree(temporaryDirectory);
    throw error;
  }
};

const removeSuccessfulScenario = async (artifactRoot, run) => {
  const runsRoot = path.join(artifactRoot, "runs");
  const runRoot = path.join(runsRoot, run.runId);
  const scenarioDirectory = path.join(runRoot, scenarioDirectoryName(run));
  let safeToRemove = false;
  try {
    const runsMetadata = await lstat(runsRoot);
    const runMetadata = await lstat(runRoot);
    safeToRemove =
      !runsMetadata.isSymbolicLink()
      && runsMetadata.isDirectory()
      && !runMetadata.isSymbolicLink()
      && runMetadata.isDirectory();
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (safeToRemove) await safeRemoveTree(scenarioDirectory);
  else await removeUnsafeOutputLinks(artifactRoot, run);
  await cleanupStaging(artifactRoot, run);
  await pruneEmptyDirectories([runRoot, runsRoot]);
  try {
    await access(scenarioDirectory);
    throw new CollectorError("COLLECTION_FAILED");
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  return {
    cleaned: true,
    uploadAllowed: false,
    scenarioDirectory,
  };
};

export const collectFailureArtifacts = async ({
  artifactRoot: rawArtifactRoot,
  run,
  sources,
  sensitivity,
  budgets: rawBudgets,
  now = new Date(),
  ttlMs,
  sourceIo,
  trustedScreenshotVerifier,
  omitScreenshot = false,
}) => {
  const artifactRoot = path.resolve(rawArtifactRoot);
  await ensureOrdinaryDirectory(artifactRoot);
  try {
    assertSafeIdentity(run);
    const schemas = await loadArtifactSchemas();
    const runErrors = validateArtifactDocument(schemas.scenarioResult, run);
    if (runErrors.length > 0) {
      throw new CollectorError(
        runErrors.some((error) =>
          error.includes("runId") || error.includes("scenarioId"))
          ? "ARTIFACT_PATH_UNSAFE"
          : "RUN_SCHEMA_INVALID",
      );
    }
    const budgets = normalizeBudgets(rawBudgets);
    assertTtl(ttlMs);
    if (run.outcome.status === "passed") {
      return await removeSuccessfulScenario(artifactRoot, run);
    }
    assertCollectionInputs(sources, sensitivity, run, omitScreenshot);
    for (const source of Object.values(sources)) {
      assertSafeSourceName(source.file);
    }
    const candidates = await collectCandidates({
      artifactRoot,
      run,
      sources,
      sensitivity,
      budgets,
      sourceIo,
      trustedScreenshotVerifier,
      omitScreenshot,
    });
    const { runRoot } = await prepareOutputDirectories(artifactRoot, run.runId);
    const scenarioDirectory = path.join(runRoot, scenarioDirectoryName(run));
    const existingRunBytes =
      await sizeOfTree(runRoot) - await sizeOfTree(scenarioDirectory);
    const bounded = applyBudgets({
      run,
      now,
      ttlMs,
      candidates,
      budgets,
      existingRunBytes,
    });
    await publishScenario({
      runRoot,
      scenarioDirectory,
      selected: bounded.selected,
      summary: bounded.summary,
      serialized: bounded.serialized,
    });
    await cleanupStaging(artifactRoot, run);
    return {
      uploadAllowed: true,
      summary: bounded.summary,
      scenarioDirectory,
    };
  } catch (error) {
    await cleanupStaging(artifactRoot, run);
    await removeUnsafeOutputLinks(artifactRoot, run);
    return writeBlockedSummary(artifactRoot, stableBlockedCode(error));
  }
};

export const cleanupExpiredArtifacts = async ({
  artifactRoot: rawArtifactRoot,
  now = new Date(),
}) => {
  const artifactRoot = path.resolve(rawArtifactRoot);
  const runsRoot = path.join(artifactRoot, "runs");
  const deletedScenarioIds = [];
  const retainedScenarioIds = [];
  let runEntries = [];
  try {
    const runsMetadata = await lstat(runsRoot);
    if (runsMetadata.isSymbolicLink() || !runsMetadata.isDirectory()) {
      await unlink(runsRoot);
      return { deletedScenarioIds, retainedScenarioIds };
    }
    runEntries = await readdir(runsRoot, { withFileTypes: true });
    const runsAfterRead = await lstat(runsRoot);
    if (!isSameDirectory(runsMetadata, runsAfterRead)) {
      throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
    }
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const schemas = await loadArtifactSchemas();

  for (const runEntry of runEntries.sort((left, right) =>
    left.name.localeCompare(right.name))) {
    const runDirectory = path.join(runsRoot, runEntry.name);
    if (runEntry.isSymbolicLink() || !runEntry.isDirectory()) {
      await safeRemoveTree(runDirectory);
      continue;
    }
    let runMetadata;
    let scenarios;
    try {
      runMetadata = await lstat(runDirectory);
      if (runMetadata.isSymbolicLink() || !runMetadata.isDirectory()) {
        await safeRemoveTree(runDirectory);
        continue;
      }
      scenarios = await readdir(runDirectory, { withFileTypes: true });
      const runAfterRead = await lstat(runDirectory);
      if (!isSameDirectory(runMetadata, runAfterRead)) {
        throw new CollectorError("ARTIFACT_SOURCE_UNSAFE");
      }
    } catch (error) {
      if (error.code === "ENOENT") continue;
      throw error;
    }
    for (const scenarioEntry of scenarios.sort((left, right) =>
      left.name.localeCompare(right.name))) {
      const scenarioDirectory = path.join(runDirectory, scenarioEntry.name);
      let summary;
      let valid = scenarioEntry.isDirectory() && !scenarioEntry.isSymbolicLink();
      if (valid) {
        try {
          summary = JSON.parse(
            await readFile(path.join(scenarioDirectory, "summary.json"), "utf8"),
          );
          valid =
            validateArtifactDocument(schemas.artifactSummary, summary).length === 0;
        } catch {
          valid = false;
        }
      }
      const expired = valid && Date.parse(summary.expiresAt) <= now.getTime();
      if (!valid || expired) {
        deletedScenarioIds.push(valid ? summary.scenarioId : scenarioEntry.name);
        await safeRemoveTree(scenarioDirectory);
      } else {
        retainedScenarioIds.push(summary.scenarioId);
      }
    }
    await pruneEmptyDirectories([runDirectory]);
  }
  await pruneEmptyDirectories([runsRoot]);
  return {
    deletedScenarioIds: deletedScenarioIds.sort(),
    retainedScenarioIds: retainedScenarioIds.sort(),
  };
};
