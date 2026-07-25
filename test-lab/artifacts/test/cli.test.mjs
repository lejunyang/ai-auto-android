// 测试用途：验证 collector、二十轮统计和 TTL 清理命令可独立调用且只输出受控 JSON。
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import {
  access,
  mkdtemp,
  readFile,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";

import {
  makeRun,
  makeStaging,
  RUN_ID,
  sensitivity,
} from "./helpers.mjs";

const execute = promisify(execFile);
const cli = path.resolve(import.meta.dirname, "..", "src", "cli.mjs");

const writeJson = async (directory, name, value) => {
  const file = path.join(directory, name);
  await writeFile(file, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  return file;
};

test("collect command fails closed when no screenshot verifier is configured", async () => {
  const artifactRoot = await mkdtemp(path.join(os.tmpdir(), "n34-cli-collect-"));
  const inputRoot = await mkdtemp(path.join(os.tmpdir(), "n34-cli-input-"));
  const run = makeRun();
  const staging = await makeStaging(artifactRoot, run);
  const runFile = await writeJson(inputRoot, "run.json", run);
  const sourcesFile = await writeJson(inputRoot, "sources.json", staging.sources);
  const sensitivityFile = await writeJson(
    inputRoot,
    "sensitivity.json",
    sensitivity,
  );

  let failure;
  try {
    await execute(process.execPath, [
      cli,
      "collect",
      "--artifact-root",
      artifactRoot,
      "--run",
      runFile,
      "--sources",
      sourcesFile,
      "--sensitivity",
      sensitivityFile,
      "--ttl-ms",
      "60000",
      "--now",
      "2026-07-25T12:01:00.000Z",
    ]);
  } catch (error) {
    failure = error;
  }
  const result = JSON.parse(failure.stdout);

  assert.equal(failure.code, 1);
  assert.equal(failure.stderr, "");
  assert.equal(result.ok, false);
  assert.equal(result.uploadAllowed, false);
  assert.equal(result.errorCode, "REDACTION_UNVERIFIED");
  assert.equal(path.isAbsolute(result.summaryFile), true);
  assert.equal(
    path.relative(artifactRoot, result.summaryFile).startsWith(".."),
    false,
  );
  await access(result.summaryFile);
});

test("collect command explicitly omits screenshots and keeps other evidence", async () => {
  const artifactRoot = await mkdtemp(path.join(os.tmpdir(), "n34-cli-omit-"));
  const inputRoot = await mkdtemp(path.join(os.tmpdir(), "n34-cli-input-"));
  const run = makeRun();
  const staging = await makeStaging(artifactRoot, run);
  const { screenshot: _screenshot, ...sourcesWithoutScreenshot } = staging.sources;
  const runFile = await writeJson(inputRoot, "run.json", run);
  const sourcesFile = await writeJson(
    inputRoot,
    "sources.json",
    sourcesWithoutScreenshot,
  );
  const sensitivityFile = await writeJson(
    inputRoot,
    "sensitivity.json",
    sensitivity,
  );

  const { stdout, stderr } = await execute(process.execPath, [
    cli,
    "collect",
    "--artifact-root",
    artifactRoot,
    "--run",
    runFile,
    "--sources",
    sourcesFile,
    "--sensitivity",
    sensitivityFile,
    "--ttl-ms",
    "60000",
    "--omit-screenshot",
    "--now",
    "2026-07-25T12:01:00.000Z",
  ]);
  const result = JSON.parse(stdout);
  const summary = JSON.parse(await readFile(result.summaryFile, "utf8"));

  assert.equal(stderr, "");
  assert.equal(result.ok, true);
  assert.equal(result.uploadAllowed, true);
  assert.equal(result.errorCode, null);
  assert.equal(
    summary.artifacts.some((artifact) => artifact.kind === "screenshot"),
    false,
  );
});

test("stats command writes deterministic twenty-run JSON", async () => {
  const inputRoot = await mkdtemp(path.join(os.tmpdir(), "n34-cli-stats-"));
  const outcomes = Array.from({ length: 20 }, (_, index) => ({
    runId: RUN_ID,
    scenarioId: "fixture-repeat",
    iteration: index + 1,
    status: "passed",
    category: null,
    errorCode: null,
    durationMs: 1_000 + index,
    retries: 0,
  }));
  const input = await writeJson(inputRoot, "outcomes.json", outcomes);
  const output = path.join(inputRoot, "statistics.json");

  const { stdout } = await execute(process.execPath, [
    cli,
    "stats",
    "--input",
    input,
    "--output",
    output,
  ]);

  assert.equal(JSON.parse(stdout).classification, "passed");
  assert.equal(JSON.parse(await readFile(output, "utf8")).total, 20);
});

test("cleanup command removes expired evidence without device access", async () => {
  const artifactRoot = await mkdtemp(path.join(os.tmpdir(), "n34-cli-cleanup-"));
  const inputRoot = await mkdtemp(path.join(os.tmpdir(), "n34-cli-input-"));
  const run = makeRun();
  const staging = await makeStaging(artifactRoot, run);
  const { screenshot: _screenshot, ...sourcesWithoutScreenshot } = staging.sources;
  const runFile = await writeJson(inputRoot, "run.json", run);
  const sourcesFile = await writeJson(
    inputRoot,
    "sources.json",
    sourcesWithoutScreenshot,
  );
  const sensitivityFile = await writeJson(
    inputRoot,
    "sensitivity.json",
    sensitivity,
  );
  await execute(process.execPath, [
    cli,
    "collect",
    "--artifact-root",
    artifactRoot,
    "--run",
    runFile,
    "--sources",
    sourcesFile,
    "--sensitivity",
    sensitivityFile,
    "--ttl-ms",
    "1000",
    "--omit-screenshot",
    "--now",
    "2026-07-25T12:01:00.000Z",
  ]);

  const { stdout } = await execute(process.execPath, [
    cli,
    "cleanup",
    "--artifact-root",
    artifactRoot,
    "--now",
    "2026-07-25T12:01:02.000Z",
  ]);
  const result = JSON.parse(stdout);

  assert.deepEqual(result.deletedScenarioIds, ["fixture-timeout"]);
  assert.deepEqual(result.retainedScenarioIds, []);
});
