#!/usr/bin/env node

// 脚本用途：解析受限参数并以 JSON 结果调用固定 Android 模拟器生命周期，不接受任意外部命令。
import path from "node:path";
import { fileURLToPath } from "node:url";

import { EmulatorError, EmulatorRunner, loadProfiles } from "./runner.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const defaultProfileFile = path.join(scriptDirectory, "profiles.json");
const commands = new Set([
  "create",
  "start",
  "start-cold",
  "wait",
  "probe-webview",
  "restore",
  "save-snapshot",
  "stop",
  "delete",
  "verify",
]);

const parseArguments = (argv) => {
  const [command, ...rest] = argv;
  if (!commands.has(command)) {
    throw new EmulatorError("USAGE_ERROR", `unsupported command: ${command ?? "<missing>"}`);
  }
  const options = {};
  for (let index = 0; index < rest.length; index += 2) {
    const flag = rest[index];
    const value = rest[index + 1];
    if (!flag?.startsWith("--") || value === undefined || value.startsWith("--")) {
      throw new EmulatorError("USAGE_ERROR", `invalid argument near ${flag ?? "<end>"}`);
    }
    const name = flag.slice(2);
    if (
      !["profile", "sdk-root", "avd-root", "state-root", "java-home", "profiles"].includes(name)
    ) {
      throw new EmulatorError("USAGE_ERROR", `unsupported option: ${flag}`);
    }
    if (options[name] !== undefined) {
      throw new EmulatorError("USAGE_ERROR", `duplicate option: ${flag}`);
    }
    options[name] = value;
  }
  for (const name of ["profile", "sdk-root", "avd-root", "state-root", "java-home"]) {
    if (!options[name]) throw new EmulatorError("USAGE_ERROR", `--${name} is required`);
  }
  return { command, options };
};

const execute = async () => {
  const { command, options } = parseArguments(process.argv.slice(2));
  const profiles = await loadProfiles(path.resolve(options.profiles ?? defaultProfileFile));
  const runner = new EmulatorRunner({
    stateRoot: path.resolve(options["state-root"]),
    sdkRoot: path.resolve(options["sdk-root"]),
    avdRoot: path.resolve(options["avd-root"]),
    javaHome: path.resolve(options["java-home"]),
    profiles,
  });
  switch (command) {
    case "create":
      return runner.create(options.profile);
    case "start":
      return runner.start(options.profile);
    case "start-cold":
      return runner.start(options.profile, { restoreSnapshot: false });
    case "wait":
      return runner.waitForBoot(options.profile);
    case "probe-webview":
      return runner.probeWebView(options.profile);
    case "restore":
      return runner.restore(options.profile);
    case "save-snapshot":
      return runner.saveSnapshot(options.profile);
    case "stop":
      return runner.stop(options.profile);
    case "delete":
      return runner.delete(options.profile);
    case "verify": {
      const { profile, imageDirectory } = await runner.verifyProfile(options.profile);
      return runner.result(profile, null, { state: "verified", imageDirectory });
    }
    default:
      throw new EmulatorError("USAGE_ERROR", `unsupported command: ${command}`);
  }
};

try {
  const result = await execute();
  process.stdout.write(`${JSON.stringify({ ok: true, data: result, error: null })}\n`);
} catch (error) {
  const normalized = error instanceof EmulatorError
    ? error
    : new EmulatorError("INTERNAL_ERROR", error.message);
  process.stdout.write(
    `${JSON.stringify({
      ok: false,
      data: null,
      error: {
        code: normalized.code,
        message: normalized.message,
        details: normalized.details ?? null,
      },
    })}\n`,
  );
  process.exitCode = normalized.code === "USAGE_ERROR" ? 2 : 1;
}
