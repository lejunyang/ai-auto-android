// 测试用途：验证超时与断言失败产物齐全、脱敏受限，并在阻断、成功和 TTL 清理后无残留。
import assert from "node:assert/strict";
import {
  access,
  lstat,
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  symlink,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  cleanupExpiredArtifacts,
  collectFailureArtifacts as collectFailureArtifactsRaw,
} from "../src/collector.mjs";
import {
  makeRun,
  makeStaging,
  PNG,
  RUN_ID,
  sensitivity,
  sha256,
  trustedScreenshotVerifier,
} from "./helpers.mjs";

const temporaryRoot = os.tmpdir();
const directoryLinkType = process.platform === "win32" ? "junction" : "dir";
const collectFailureArtifacts = (options) =>
  collectFailureArtifactsRaw({ ...options, trustedScreenshotVerifier });

const collectFiles = async (root) => {
  const files = [];
  const visit = async (directory) => {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) await visit(file);
      else files.push(file);
    }
  };
  await visit(root);
  return files.sort();
};

const physicalBytes = async (root) =>
  (
    await Promise.all(
      (await collectFiles(root)).map(async (file) => (await stat(file)).size),
    )
  ).reduce((sum, bytes) => sum + bytes, 0);

const assertNoStaging = async (root) => {
  await assert.rejects(access(path.join(root, ".staging")), { code: "ENOENT" });
};

const assertStableBlockedSummary = async (artifactRoot, result, errorCode) => {
  assert.equal(result.uploadAllowed, false);
  assert.deepEqual(result.summary, {
    schemaVersion: "1.0",
    uploadAllowed: false,
    errorCode,
  });
  const files = await collectFiles(artifactRoot);
  assert.deepEqual(
    files.map((file) => path.relative(artifactRoot, file)),
    ["blocked/summary.json"],
  );
  assert.deepEqual(
    JSON.parse(await readFile(files[0], "utf8")),
    result.summary,
  );
};

for (const failure of [
  { scenarioId: "fixture-timeout", errorCode: "SCENARIO_TIMEOUT" },
  { scenarioId: "fixture-assertion", errorCode: "ASSERTION_FAILED" },
]) {
  test(`${failure.errorCode} produces complete redacted bounded artifacts`, async () => {
    const artifactRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-collector-"),
    );
    const run = makeRun(failure);
    const { sources } = await makeStaging(artifactRoot, run);

    const result = await collectFailureArtifacts({
      artifactRoot,
      run,
      sources,
      sensitivity,
      now: new Date("2026-07-25T12:01:00.000Z"),
      ttlMs: 60_000,
    });

    assert.equal(result.uploadAllowed, true);
    assert.equal(result.summary.result.errorCode, failure.errorCode);
    assert.deepEqual(
      result.summary.artifacts.map((artifact) => artifact.kind),
      ["device-metadata", "action-timeline", "test-report", "hierarchy", "log", "screenshot"],
    );
    assert.equal(result.summary.artifacts.every((artifact) => artifact.redacted), true);
    assert.equal(result.summary.artifacts.every((artifact) => artifact.bytes > 0), true);
    assert.equal(result.summary.budget.usedBytes <= result.summary.budget.scenarioBytes, true);
    assert.equal(
      result.summary.budget.usedBytes,
      await physicalBytes(result.scenarioDirectory),
    );
    const screenshot = await readFile(path.join(result.scenarioDirectory, "screenshot.png"));
    assert.equal(sha256(screenshot), sha256(PNG));

    const persisted = Buffer.concat(
      await Promise.all(
        (await collectFiles(result.scenarioDirectory)).map((file) => readFile(file)),
      ),
    ).toString("utf8");
    assert.equal(persisted.includes("CorrectHorseBatteryStaple"), false);
    assert.equal(persisted.includes("header-token-ABC123"), false);
    assert.equal(persisted.includes("private account balance"), false);
    await assertNoStaging(artifactRoot);
  });
}

test("unverified screenshot fails closed and keeps only a stable summary", async () => {
  const artifactRoot = await mkdtemp(path.join(temporaryRoot, "n34-blocked-"));
  const run = makeRun();
  const { sources } = await makeStaging(artifactRoot, run);
  sources.screenshot.proof.artifactSha256 = "0".repeat(64);

  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "REDACTION_UNVERIFIED",
  );
  const summaryText = JSON.stringify(result.summary);
  assert.equal(summaryText.includes("screenshot"), false);
});

test("the same declared screenshot fails closed without a trusted verifier", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-no-verifier-"),
  );
  const run = makeRun();
  const { sources } = await makeStaging(artifactRoot, run);

  const result = await collectFailureArtifactsRaw({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "REDACTION_UNVERIFIED",
  );
});

test("self-declared processor and matching hash cannot establish screenshot trust", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-self-declared-"),
  );
  const run = makeRun();
  const { sources } = await makeStaging(artifactRoot, run);
  sources.screenshot.proof = {
    method: "verified-masked",
    processorId: "android-ui-masker-v1",
    artifactSha256: sha256(PNG),
  };

  const result = await collectFailureArtifactsRaw({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "REDACTION_UNVERIFIED",
  );
});

for (const verifierFailure of [
  {
    name: "returns false",
    verifier: async () => false,
  },
  {
    name: "throws",
    verifier: async () => {
      throw new Error("untrusted verifier detail");
    },
  },
]) {
  test(`screenshot verifier that ${verifierFailure.name} fails closed`, async () => {
    const artifactRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-verifier-failure-"),
    );
    const run = makeRun();
    const { sources } = await makeStaging(artifactRoot, run);

    const result = await collectFailureArtifactsRaw({
      artifactRoot,
      run,
      sources,
      sensitivity,
      trustedScreenshotVerifier: verifierFailure.verifier,
      now: new Date("2026-07-25T12:01:00.000Z"),
      ttlMs: 60_000,
    });

    await assertStableBlockedSummary(
      artifactRoot,
      result,
      "REDACTION_UNVERIFIED",
    );
  });
}

test("unknown screenshot masking processor fails closed", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-processor-"),
  );
  const run = makeRun();
  const { sources } = await makeStaging(artifactRoot, run);
  sources.screenshot.proof.processorId = "self-declared-masker";

  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "REDACTION_UNVERIFIED",
  );
});

test("invalid text encoding fails closed and removes every staged source", async () => {
  const artifactRoot = await mkdtemp(path.join(temporaryRoot, "n34-encoding-"));
  const run = makeRun();
  const { sources } = await makeStaging(artifactRoot, run, {
    log: Buffer.from([0xc3, 0x28]),
  });

  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "REDACTION_UNVERIFIED",
  );
});

for (const unsafeFile of ["../outside-secret.txt", "/tmp/outside-secret.txt"]) {
  test(`source filename ${unsafeFile} is rejected before external access`, async () => {
    const artifactRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-path-file-"),
    );
    const outsideRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-outside-"),
    );
    const sentinel = path.join(outsideRoot, "outside-secret.txt");
    await writeFile(sentinel, "OUTSIDE_SECRET_MUST_NOT_BE_READ", { mode: 0o600 });
    const run = makeRun();
    const { sources } = await makeStaging(artifactRoot, run);
    sources.log.file = unsafeFile === "/tmp/outside-secret.txt"
      ? sentinel
      : unsafeFile;

    const result = await collectFailureArtifacts({
      artifactRoot,
      run,
      sources,
      sensitivity,
      now: new Date("2026-07-25T12:01:00.000Z"),
      ttlMs: 60_000,
    });

    await assertStableBlockedSummary(
      artifactRoot,
      result,
      "ARTIFACT_PATH_UNSAFE",
    );
    assert.equal(await readFile(sentinel, "utf8"), "OUTSIDE_SECRET_MUST_NOT_BE_READ");
  });
}

for (const unsafeIdentity of [
  { name: "runId", value: "../outside-run" },
  { name: "scenarioId", value: "../../outside-scenario" },
]) {
  test(`${unsafeIdentity.name} cannot escape staging or output roots`, async () => {
    const artifactRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-path-id-"),
    );
    const outsideRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-outside-"),
    );
    const sentinel = path.join(outsideRoot, "sentinel.txt");
    await writeFile(sentinel, "OUTSIDE_DIRECTORY_UNTOUCHED", { mode: 0o600 });
    const run = {
      ...makeRun(),
      [unsafeIdentity.name]: unsafeIdentity.value,
    };

    const result = await collectFailureArtifacts({
      artifactRoot,
      run,
      sources: {},
      sensitivity,
      now: new Date("2026-07-25T12:01:00.000Z"),
      ttlMs: 60_000,
    });

    await assertStableBlockedSummary(
      artifactRoot,
      result,
      "ARTIFACT_PATH_UNSAFE",
    );
    assert.equal(await readFile(sentinel, "utf8"), "OUTSIDE_DIRECTORY_UNTOUCHED");
  });
}

test("symlink source file is blocked without reading or deleting its target", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-symlink-file-"),
  );
  const outsideRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-outside-"),
  );
  const sentinel = path.join(outsideRoot, "secret.log");
  await writeFile(sentinel, "SYMLINK_TARGET_MUST_SURVIVE", { mode: 0o600 });
  const run = makeRun();
  const { stagingDir, sources } = await makeStaging(artifactRoot, run);
  const stagedLog = path.join(stagingDir, sources.log.file);
  await rm(stagedLog);
  await symlink(sentinel, stagedLog, "file");

  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "ARTIFACT_SOURCE_UNSAFE",
  );
  assert.equal(await readFile(sentinel, "utf8"), "SYMLINK_TARGET_MUST_SURVIVE");
});

for (const linkedStagingLayer of ["staging-root", "staging-run"]) {
  test(`${linkedStagingLayer} symlink is unlinked without traversing its target`, async () => {
    const artifactRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-staging-link-"),
    );
    const outsideRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-outside-"),
    );
    const run = makeRun();
    const externalScenario = linkedStagingLayer === "staging-root"
      ? path.join(outsideRoot, run.runId, run.scenarioId)
      : path.join(outsideRoot, run.scenarioId);
    await mkdir(externalScenario, { recursive: true });
    const sentinel = path.join(externalScenario, "report.input");
    await writeFile(sentinel, "INTERMEDIATE_LINK_TARGET_SURVIVES", {
      mode: 0o600,
    });
    const stagingRoot = path.join(artifactRoot, ".staging");
    if (linkedStagingLayer === "staging-root") {
      await symlink(outsideRoot, stagingRoot, directoryLinkType);
    } else {
      await mkdir(stagingRoot, { recursive: true });
      await symlink(
        outsideRoot,
        path.join(stagingRoot, run.runId),
        directoryLinkType,
      );
    }
    const sources = {
      report: { file: "report.input" },
      hierarchy: { file: "hierarchy.input" },
      log: {
        file: "log.input",
        windowStartedAt: run.startedAt,
        windowEndedAt: run.finishedAt,
      },
      screenshot: {
        file: "screenshot.input",
        proof: {
          method: "verified-masked",
          processorId: "android-ui-masker-v1",
          artifactSha256: "0".repeat(64),
        },
      },
    };

    const result = await collectFailureArtifacts({
      artifactRoot,
      run,
      sources,
      sensitivity,
      now: new Date("2026-07-25T12:01:00.000Z"),
      ttlMs: 60_000,
    });

    await assertStableBlockedSummary(
      artifactRoot,
      result,
      "ARTIFACT_SOURCE_UNSAFE",
    );
    assert.equal(
      await readFile(sentinel, "utf8"),
      "INTERMEDIATE_LINK_TARGET_SURVIVES",
    );
  });
}

test("symlink staging directory is blocked without traversing or deleting its target", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-symlink-dir-"),
  );
  const outsideRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-outside-"),
  );
  const sentinel = path.join(outsideRoot, "sentinel.txt");
  await writeFile(sentinel, "SYMLINK_DIRECTORY_TARGET_SURVIVES", { mode: 0o600 });
  const run = makeRun();
  const runStaging = path.join(artifactRoot, ".staging", run.runId);
  await mkdir(runStaging, { recursive: true });
  await symlink(
    outsideRoot,
    path.join(runStaging, run.scenarioId),
    directoryLinkType,
  );
  const sources = {
    report: { file: "report.input" },
    hierarchy: { file: "hierarchy.input" },
    log: {
      file: "sentinel.txt",
      windowStartedAt: run.startedAt,
      windowEndedAt: run.finishedAt,
    },
    screenshot: {
      file: "screenshot.input",
      proof: {
        method: "verified-masked",
        processorId: "android-ui-masker-v1",
        artifactSha256: "0".repeat(64),
      },
    },
  };

  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "ARTIFACT_SOURCE_UNSAFE",
  );
  assert.equal(
    await readFile(sentinel, "utf8"),
    "SYMLINK_DIRECTORY_TARGET_SURVIVES",
  );
  assert.equal((await lstat(outsideRoot)).isDirectory(), true);
});

test("inode replacement after lstat is blocked by the open and fstat check", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-race-"),
  );
  const outsideRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-outside-"),
  );
  const sentinel = path.join(outsideRoot, "sentinel.txt");
  await writeFile(sentinel, "RACE_EXTERNAL_TARGET_SURVIVES", { mode: 0o600 });
  const run = makeRun();
  const { sources } = await makeStaging(artifactRoot, run);
  let raced = false;

  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
    sourceIo: {
      afterLstat: async ({ kind, file }) => {
        if (kind !== "log" || raced) return;
        raced = true;
        const replacement = `${file}.replacement`;
        await writeFile(replacement, "RACE_REPLACEMENT_MUST_NOT_BE_READ", {
          mode: 0o600,
        });
        await rename(replacement, file);
      },
    },
  });

  assert.equal(raced, true);
  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "ARTIFACT_SOURCE_UNSAFE",
  );
  assert.equal(await readFile(sentinel, "utf8"), "RACE_EXTERNAL_TARGET_SURVIVES");
});

for (const linkedOutput of ["runs", "run-directory"]) {
  test(`${linkedOutput} symlink cannot redirect published artifacts`, async () => {
    const artifactRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-output-link-"),
    );
    const outsideRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-outside-"),
    );
    const sentinel = path.join(outsideRoot, "sentinel.txt");
    await writeFile(sentinel, "OUTPUT_TARGET_MUST_SURVIVE", { mode: 0o600 });
    const run = makeRun();
    const staging = await makeStaging(artifactRoot, run);
    if (linkedOutput === "runs") {
      await symlink(
        outsideRoot,
        path.join(artifactRoot, "runs"),
        directoryLinkType,
      );
    } else {
      const runsRoot = path.join(artifactRoot, "runs");
      await mkdir(runsRoot, { recursive: true });
      await symlink(
        outsideRoot,
        path.join(runsRoot, run.runId),
        directoryLinkType,
      );
    }

    const result = await collectFailureArtifacts({
      artifactRoot,
      run,
      sources: staging.sources,
      sensitivity,
      now: new Date("2026-07-25T12:01:00.000Z"),
      ttlMs: 60_000,
    });

    await assertStableBlockedSummary(
      artifactRoot,
      result,
      "ARTIFACT_SOURCE_UNSAFE",
    );
    assert.equal(await readFile(sentinel, "utf8"), "OUTPUT_TARGET_MUST_SURVIVE");
    assert.deepEqual(await readdir(outsideRoot), ["sentinel.txt"]);
  });
}

test("per-file and scenario budgets truncate deterministically", async () => {
  const artifactRoot = await mkdtemp(path.join(temporaryRoot, "n34-budget-"));
  const run = makeRun();
  const largeText = Buffer.from("safe-line\n".repeat(4_000));
  const { sources } = await makeStaging(artifactRoot, run, {
    hierarchy: largeText,
    log: largeText,
    report: largeText,
  });

  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources,
    sensitivity,
    budgets: {
      fileBytes: 1_024,
      scenarioBytes: 4_096,
      runBytes: 8_192,
    },
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  assert.equal(result.summary.budget.usedBytes <= 4_096, true);
  assert.equal(result.summary.artifacts.some((artifact) => artifact.truncated), true);
  for (const artifact of result.summary.artifacts) {
    const artifactStat = await stat(path.join(result.scenarioDirectory, artifact.file));
    assert.equal(artifactStat.size <= 1_024, true, artifact.file);
  }

  const secondRoot = await mkdtemp(path.join(temporaryRoot, "n34-budget-"));
  const secondStaging = await makeStaging(secondRoot, run, {
    hierarchy: largeText,
    log: largeText,
    report: largeText,
  });
  const second = await collectFailureArtifacts({
    artifactRoot: secondRoot,
    run,
    sources: secondStaging.sources,
    sensitivity,
    budgets: {
      fileBytes: 1_024,
      scenarioBytes: 4_096,
      runBytes: 8_192,
    },
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });
  assert.deepEqual(second.summary.artifacts, result.summary.artifacts);
  assert.deepEqual(second.summary.budget, result.summary.budget);
});

test("run budget truncates later scenarios in deterministic order", async () => {
  const artifactRoot = await mkdtemp(path.join(temporaryRoot, "n34-run-budget-"));
  const budgets = {
    fileBytes: 1_024,
    scenarioBytes: 3_500,
    runBytes: 4_000,
  };
  const firstRun = makeRun({ scenarioId: "scenario-a", iteration: 1 });
  const firstStaging = await makeStaging(artifactRoot, firstRun);
  const first = await collectFailureArtifacts({
    artifactRoot,
    run: firstRun,
    sources: firstStaging.sources,
    sensitivity,
    budgets,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });
  const secondRun = makeRun({ scenarioId: "scenario-b", iteration: 2 });
  const secondStaging = await makeStaging(artifactRoot, secondRun);
  const second = await collectFailureArtifacts({
    artifactRoot,
    run: secondRun,
    sources: secondStaging.sources,
    sensitivity,
    budgets,
    now: new Date("2026-07-25T12:02:00.000Z"),
    ttlMs: 60_000,
  });

  assert.equal(first.summary.budget.usedBytes > 0, true);
  assert.equal(second.summary.budget.runTruncated, true);
  const runFiles = await collectFiles(path.join(artifactRoot, "runs", RUN_ID));
  const totalBytes = (
    await Promise.all(runFiles.map(async (file) => (await stat(file)).size))
  ).reduce((sum, bytes) => sum + bytes, 0);
  assert.equal(totalBytes <= budgets.runBytes, true);
});

test("successful scenario removes previous failure evidence immediately", async () => {
  const artifactRoot = await mkdtemp(path.join(temporaryRoot, "n34-success-"));
  const failedRun = makeRun();
  const staging = await makeStaging(artifactRoot, failedRun);
  const failed = await collectFailureArtifacts({
    artifactRoot,
    run: failedRun,
    sources: staging.sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });
  await access(failed.scenarioDirectory);

  const passedRun = {
    ...failedRun,
    finishedAt: "2026-07-25T12:02:00.000Z",
    outcome: {
      status: "passed",
      errorCode: null,
      durationMs: 2_000,
      retries: 0,
    },
  };
  const result = await collectFailureArtifacts({
    artifactRoot,
    run: passedRun,
    sources: {},
    sensitivity,
    now: new Date("2026-07-25T12:02:01.000Z"),
    ttlMs: 60_000,
  });

  assert.equal(result.cleaned, true);
  await assert.rejects(access(failed.scenarioDirectory), { code: "ENOENT" });
});

for (const linkedSuccessOutput of ["runs", "run-directory"]) {
  test(`successful cleanup does not traverse a ${linkedSuccessOutput} symlink`, async () => {
    const artifactRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-success-link-"),
    );
    const outsideRoot = await mkdtemp(
      path.join(temporaryRoot, "n34-outside-"),
    );
    const passedRun = {
      ...makeRun(),
      outcome: {
        status: "passed",
        errorCode: null,
        durationMs: 2_000,
        retries: 0,
      },
    };
    const externalScenario = linkedSuccessOutput === "runs"
      ? path.join(
          outsideRoot,
          passedRun.runId,
          `${passedRun.scenarioId}-${passedRun.iteration}`,
        )
      : path.join(
          outsideRoot,
          `${passedRun.scenarioId}-${passedRun.iteration}`,
        );
    await mkdir(externalScenario, { recursive: true });
    const sentinel = path.join(externalScenario, "summary.json");
    await writeFile(sentinel, "EXTERNAL_SUCCESS_TARGET_SURVIVES", { mode: 0o600 });
    if (linkedSuccessOutput === "runs") {
      await symlink(
        outsideRoot,
        path.join(artifactRoot, "runs"),
        directoryLinkType,
      );
    } else {
      const runsRoot = path.join(artifactRoot, "runs");
      await mkdir(runsRoot, { recursive: true });
      await symlink(
        outsideRoot,
        path.join(runsRoot, passedRun.runId),
        directoryLinkType,
      );
    }

    const result = await collectFailureArtifacts({
      artifactRoot,
      run: passedRun,
      sources: {},
      sensitivity,
      now: new Date("2026-07-25T12:02:01.000Z"),
      ttlMs: 60_000,
    });

    assert.equal(result.cleaned, true);
    assert.equal(
      await readFile(sentinel, "utf8"),
      "EXTERNAL_SUCCESS_TARGET_SURVIVES",
    );
  });
}

test("TTL cleanup deletes expired and malformed retention directories", async () => {
  const artifactRoot = await mkdtemp(path.join(temporaryRoot, "n34-ttl-"));
  const run = makeRun();
  const staging = await makeStaging(artifactRoot, run);
  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources: staging.sources,
    sensitivity,
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 1_000,
  });
  const malformed = path.join(artifactRoot, "runs", RUN_ID, "malformed");
  await mkdir(malformed, { recursive: true });
  await writeFile(path.join(malformed, "summary.json"), "{not-json", { mode: 0o600 });

  const cleanup = await cleanupExpiredArtifacts({
    artifactRoot,
    now: new Date("2026-07-25T12:01:02.000Z"),
  });

  assert.deepEqual(cleanup.deletedScenarioIds, ["fixture-timeout", "malformed"]);
  assert.deepEqual(cleanup.retainedScenarioIds, []);
  await assert.rejects(access(result.scenarioDirectory), { code: "ENOENT" });
  await assert.rejects(access(malformed), { code: "ENOENT" });
});

test("TTL cleanup unlinks a runs symlink without traversing its target", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-cleanup-link-"),
  );
  const outsideRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-outside-"),
  );
  const sentinelDirectory = path.join(outsideRoot, "external-run", "external-scenario");
  await mkdir(sentinelDirectory, { recursive: true });
  const sentinel = path.join(sentinelDirectory, "summary.json");
  await writeFile(sentinel, "EXTERNAL_CLEANUP_TARGET_SURVIVES", { mode: 0o600 });
  await symlink(
    outsideRoot,
    path.join(artifactRoot, "runs"),
    directoryLinkType,
  );

  const cleanup = await cleanupExpiredArtifacts({
    artifactRoot,
    now: new Date("2026-07-25T12:01:02.000Z"),
  });

  assert.deepEqual(cleanup, {
    deletedScenarioIds: [],
    retainedScenarioIds: [],
  });
  assert.equal(
    await readFile(sentinel, "utf8"),
    "EXTERNAL_CLEANUP_TARGET_SURVIVES",
  );
  await assert.rejects(access(path.join(artifactRoot, "runs")), { code: "ENOENT" });
});

test("unknown source and malformed sensitivity fields fail closed", async () => {
  const artifactRoot = await mkdtemp(
    path.join(temporaryRoot, "n34-input-schema-"),
  );
  const run = makeRun();
  const staging = await makeStaging(artifactRoot, run);
  const result = await collectFailureArtifacts({
    artifactRoot,
    run,
    sources: {
      ...staging.sources,
      ignoredUploadPath: { file: "report.input" },
    },
    sensitivity: {
      declaration: "complete",
      values: [123],
      targetPackages: ["dev.aiauto.fixture"],
    },
    now: new Date("2026-07-25T12:01:00.000Z"),
    ttlMs: 60_000,
  });

  await assertStableBlockedSummary(
    artifactRoot,
    result,
    "RUN_SCHEMA_INVALID",
  );
});
