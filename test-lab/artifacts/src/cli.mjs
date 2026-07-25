#!/usr/bin/env node

// 脚本用途：以严格参数调用失败产物收集、TTL 清理和二十轮统计，不接受任意设备命令。
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import {
  cleanupExpiredArtifacts,
  collectFailureArtifacts,
} from "./collector.mjs";
import { aggregateTwentyRuns } from "./statistics.mjs";

const commands = new Set(["collect", "cleanup", "stats"]);

const usageError = (message) => {
  const error = new Error(message);
  error.code = "USAGE_ERROR";
  return error;
};

const parseArguments = (argv) => {
  const [command, ...rest] = argv;
  if (!commands.has(command)) {
    throw usageError(`unsupported command: ${command ?? "<missing>"}`);
  }
  const options = {};
  for (let index = 0; index < rest.length;) {
    const flag = rest[index];
    if (flag === "--omit-screenshot") {
      if (options["omit-screenshot"] !== undefined) {
        throw usageError(`duplicate option: ${flag}`);
      }
      options["omit-screenshot"] = true;
      index += 1;
      continue;
    }
    const value = rest[index + 1];
    if (!flag?.startsWith("--") || value === undefined || value.startsWith("--")) {
      throw usageError(`invalid argument near ${flag ?? "<end>"}`);
    }
    const name = flag.slice(2);
    if (
      ![
        "artifact-root",
        "run",
        "sources",
        "sensitivity",
        "ttl-ms",
        "file-bytes",
        "scenario-bytes",
        "run-bytes",
        "input",
        "output",
        "now",
      ].includes(name)
    ) {
      throw usageError(`unsupported option: ${flag}`);
    }
    if (options[name] !== undefined) throw usageError(`duplicate option: ${flag}`);
    options[name] = value;
    index += 2;
  }
  return { command, options };
};

const requireOptions = (options, names) => {
  for (const name of names) {
    if (!options[name]) throw usageError(`--${name} is required`);
  }
};

const parseInteger = (value, name) => {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) throw usageError(`--${name} must be an integer`);
  return parsed;
};

const parseNow = (value) => {
  if (value === undefined) return new Date();
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString() !== value) {
    throw usageError("--now must be a canonical UTC timestamp");
  }
  return parsed;
};

const readJson = async (file) => JSON.parse(await readFile(path.resolve(file), "utf8"));

const emit = async (result, output) => {
  const serialized = `${JSON.stringify(result, null, 2)}\n`;
  if (output) {
    await writeFile(path.resolve(output), serialized, { mode: 0o600, flag: "wx" });
  }
  process.stdout.write(serialized);
};

const run = async () => {
  const { command, options } = parseArguments(process.argv.slice(2));
  if (command === "collect") {
    requireOptions(options, [
      "artifact-root",
      "run",
      "sources",
      "sensitivity",
      "ttl-ms",
    ]);
    const budgets = {};
    for (const name of ["file-bytes", "scenario-bytes", "run-bytes"]) {
      if (options[name] !== undefined) {
        const property = name.replace(/-([a-z])/gu, (_match, letter) =>
          letter.toUpperCase());
        budgets[property] = parseInteger(options[name], name);
      }
    }
    const result = await collectFailureArtifacts({
      artifactRoot: options["artifact-root"],
      run: await readJson(options.run),
      sources: await readJson(options.sources),
      sensitivity: await readJson(options.sensitivity),
      ttlMs: parseInteger(options["ttl-ms"], "ttl-ms"),
      budgets,
      now: parseNow(options.now),
      omitScreenshot: options["omit-screenshot"] === true,
    });
    await emit({
      ok: result.uploadAllowed === true || result.cleaned === true,
      uploadAllowed: result.uploadAllowed,
      cleaned: result.cleaned ?? false,
      errorCode: result.summary?.errorCode ?? null,
      summaryFile: result.summary
        ? path.join(result.scenarioDirectory, "summary.json")
        : null,
    }, options.output);
    if (result.uploadAllowed === false && result.cleaned !== true) {
      process.exitCode = 1;
    }
    return;
  }
  if (command === "cleanup") {
    requireOptions(options, ["artifact-root"]);
    await emit(
      await cleanupExpiredArtifacts({
        artifactRoot: options["artifact-root"],
        now: parseNow(options.now),
      }),
      options.output,
    );
    return;
  }
  requireOptions(options, ["input"]);
  await emit(
    aggregateTwentyRuns(await readJson(options.input)),
    options.output,
  );
};

try {
  await run();
} catch (error) {
  const code = error.code ?? "INTERNAL_ERROR";
  process.stderr.write(`${code}\n`);
  process.exitCode = code === "USAGE_ERROR" ? 2 : 1;
}
