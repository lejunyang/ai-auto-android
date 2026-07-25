// 脚本用途：以显式 SDK、AVD、serial 和跨进程租约管理固定 Android 模拟器的完整生命周期。
import { execFile, spawn } from "node:child_process";
import crypto from "node:crypto";
import {
  access,
  mkdir,
  open,
  readFile,
  rename,
  rm,
  statfs,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const PROFILE_ID = /^[a-z0-9][a-z0-9-]{1,63}$/u;
const AVD_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{1,79}$/u;
const SHA256 = /^[a-f0-9]{64}$/u;
const PACKAGE_REVISION = /^[0-9]+(?:\.[0-9]+){0,2}$/u;
const SERIAL_PORT_MIN = 5554;
const SERIAL_PORT_MAX = 5682;

export class EmulatorError extends Error {
  constructor(code, message, details = undefined) {
    super(message);
    this.name = "EmulatorError";
    this.code = code;
    this.details = details;
  }
}

const fail = (code, message, details = undefined) => {
  throw new EmulatorError(code, message, details);
};

const ensureObject = (value, name) => {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    fail("CONFIG_INVALID", `${name} must be an object`);
  }
};

const validateProfileDocument = (document) => {
  ensureObject(document, "profile document");
  if (document.schemaVersion !== "1.0") {
    fail("CONFIG_INVALID", "profile schemaVersion must be 1.0");
  }
  ensureObject(document.portRange, "portRange");
  const { start, end } = document.portRange;
  if (
    !Number.isInteger(start)
    || !Number.isInteger(end)
    || start < SERIAL_PORT_MIN
    || end > SERIAL_PORT_MAX
    || start > end
    || start % 2 !== 0
    || end % 2 !== 0
  ) {
    fail("CONFIG_INVALID", "portRange must contain ordered even emulator console ports");
  }
  if (!Array.isArray(document.profiles) || document.profiles.length === 0) {
    fail("CONFIG_INVALID", "profiles must be a non-empty array");
  }
  ensureObject(document.toolchain, "toolchain");
  for (const name of [
    "commandLineToolsRevision",
    "platformToolsRevision",
    "emulatorRevision",
    "platformRevision",
    "buildToolsRevision",
  ]) {
    if (!PACKAGE_REVISION.test(document.toolchain[name])) {
      fail("CONFIG_INVALID", `toolchain.${name} is invalid`);
    }
  }
  const ids = new Set();
  const avdNames = new Set();
  for (const profile of document.profiles) {
    ensureObject(profile, "profile");
    if (!PROFILE_ID.test(profile.id) || ids.has(profile.id)) {
      fail("CONFIG_INVALID", `profile id is invalid or duplicated: ${profile.id}`);
    }
    if (!AVD_NAME.test(profile.avdName) || avdNames.has(profile.avdName)) {
      fail("CONFIG_INVALID", `AVD name is invalid or duplicated: ${profile.avdName}`);
    }
    if (![30, 33, 34].includes(profile.apiLevel)) {
      fail("CONFIG_INVALID", `unsupported API level: ${profile.apiLevel}`);
    }
    if (profile.abi !== "arm64-v8a" || profile.tag !== "google_apis") {
      fail("CONFIG_INVALID", `${profile.id} must use google_apis arm64-v8a`);
    }
    const expectedImage =
      `system-images;android-${profile.apiLevel};${profile.tag};${profile.abi}`;
    if (profile.systemImage !== expectedImage) {
      fail("CONFIG_INVALID", `${profile.id} systemImage does not match its API/tag/ABI`);
    }
    if (!PACKAGE_REVISION.test(profile.packageRevision)) {
      fail("CONFIG_INVALID", `${profile.id} packageRevision is invalid`);
    }
    if (!SHA256.test(profile.packageSha256)) {
      fail("CONFIG_INVALID", `${profile.id} packageSha256 must be a lowercase SHA-256`);
    }
    if (!/^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$/u.test(profile.resolution)) {
      fail("CONFIG_INVALID", `${profile.id} resolution is invalid`);
    }
    if (!Number.isInteger(profile.densityDpi) || profile.densityDpi < 120) {
      fail("CONFIG_INVALID", `${profile.id} densityDpi is invalid`);
    }
    if (
      profile.locale !== "zh-CN"
      || profile.timezone !== "Asia/Shanghai"
      || profile.navigationMode !== "gestural"
      || profile.snapshot !== "clean"
      || !/^[A-Za-z][A-Za-z0-9_.]+$/u.test(profile.webViewPackage)
      || !/^[0-9]+(?:\.[0-9]+){2,3}$/u.test(profile.webViewVersion)
    ) {
      fail("CONFIG_INVALID", `${profile.id} deterministic device settings are invalid`);
    }
    ids.add(profile.id);
    avdNames.add(profile.avdName);
  }
  return document;
};

export const loadProfiles = async (profileFile) => {
  let document;
  try {
    document = JSON.parse(await readFile(profileFile, "utf8"));
  } catch (error) {
    fail("CONFIG_INVALID", `unable to read emulator profiles: ${error.message}`);
  }
  return validateProfileDocument(document);
};

const safeSegment = (value, label) => {
  if (!/^[A-Za-z0-9._-]+$/u.test(value)) {
    fail("CONFIG_INVALID", `${label} contains unsafe path characters`);
  }
  return value;
};

const writeJsonAtomically = async (destination, value) => {
  await mkdir(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.${process.pid}.${crypto.randomUUID()}.tmp`;
  try {
    const handle = await open(temporary, "wx", 0o600);
    try {
      await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(temporary, destination);
  } catch (error) {
    await rm(temporary, { force: true });
    throw error;
  }
};

const readJson = async (file, missingCode = "RUNTIME_NOT_FOUND") => {
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") {
      fail(missingCode, `required state file does not exist: ${file}`);
    }
    fail("STATE_INVALID", `unable to read state file ${file}: ${error.message}`);
  }
};

export class FileLeaseStore {
  constructor(stateRoot, options = {}) {
    this.stateRoot = path.resolve(stateRoot);
    this.pid = options.pid ?? process.pid;
    this.now = options.now ?? Date.now;
  }

  async acquire(avdName, port) {
    safeSegment(avdName, "AVD name");
    if (
      !Number.isInteger(port)
      || port < SERIAL_PORT_MIN
      || port > SERIAL_PORT_MAX
      || port % 2 !== 0
    ) {
      fail("CONFIG_INVALID", `invalid emulator console port: ${port}`);
    }
    const avdLock = path.join(this.stateRoot, "locks", "avd", `${avdName}.lock`);
    const portLock = path.join(this.stateRoot, "locks", "port", `${port}.lock`);
    await mkdir(path.dirname(avdLock), { recursive: true });
    await mkdir(path.dirname(portLock), { recursive: true });
    try {
      await mkdir(avdLock);
    } catch (error) {
      if (error.code === "EEXIST") {
        fail("AVD_LOCKED", `AVD ${avdName} already has an active lease`, { avdName });
      }
      throw error;
    }
    try {
      await mkdir(portLock);
    } catch (error) {
      await rm(avdLock, { recursive: true, force: true });
      if (error.code === "EEXIST") {
        fail("PORT_CONFLICT", `emulator port ${port} already has an active lease`, { port });
      }
      throw error;
    }
    const lease = {
      id: crypto.randomUUID(),
      avdName,
      port,
      avdLock,
      portLock,
      ownerPid: this.pid,
      acquiredAtMs: this.now(),
    };
    try {
      await Promise.all([
        writeJsonAtomically(path.join(avdLock, "owner.json"), lease),
        writeJsonAtomically(path.join(portLock, "owner.json"), lease),
      ]);
      return lease;
    } catch (error) {
      await Promise.all([
        rm(avdLock, { recursive: true, force: true }),
        rm(portLock, { recursive: true, force: true }),
      ]);
      throw error;
    }
  }

  async allocate(avdName, portRange, isPortBusy) {
    const allocatorLock = path.join(this.stateRoot, "locks", "allocator.lock");
    await mkdir(path.dirname(allocatorLock), { recursive: true });
    try {
      await mkdir(allocatorLock);
    } catch (error) {
      if (error.code === "EEXIST") {
        fail("ALLOCATOR_LOCKED", "another process is allocating an emulator serial");
      }
      throw error;
    }
    await writeJsonAtomically(path.join(allocatorLock, "owner.json"), {
      ownerPid: this.pid,
      acquiredAtMs: this.now(),
    });
    const counterFile = path.join(this.stateRoot, "counters", "ports.json");
    let lease;
    try {
      let launchCounter = 0;
      try {
        const counter = JSON.parse(await readFile(counterFile, "utf8"));
        if (!Number.isSafeInteger(counter.launches) || counter.launches < 0) {
          fail("STATE_INVALID", "invalid global emulator launch counter");
        }
        launchCounter = counter.launches;
      } catch (error) {
        if (error instanceof EmulatorError) throw error;
        if (error.code !== "ENOENT") {
          fail("STATE_INVALID", `unable to read launch counter: ${error.message}`);
        }
      }
      const portCount = (portRange.end - portRange.start) / 2 + 1;
      let lastConflict;
      for (let offset = 0; offset < portCount; offset += 1) {
        const index = (launchCounter + offset) % portCount;
        const port = portRange.start + index * 2;
        if (await isPortBusy(port)) {
          lastConflict = new EmulatorError(
            "RESIDUAL_EMULATOR",
            `emulator-${port} already exists outside this launch`,
          );
          continue;
        }
        try {
          lease = await this.acquire(avdName, port);
          await writeJsonAtomically(counterFile, {
            launches: launchCounter + offset + 1,
          });
          return lease;
        } catch (error) {
          if (error.code === "PORT_CONFLICT") {
            lastConflict = error;
            continue;
          }
          throw error;
        }
      }
      fail("PORT_RANGE_EXHAUSTED", "no emulator console port is available", {
        causeCode: lastConflict?.code,
        cause: lastConflict?.message,
      });
    } catch (error) {
      if (lease) await this.release(lease);
      throw error;
    } finally {
      await rm(allocatorLock, { recursive: true, force: true });
    }
  }

  async attachRuntime(lease, runtime) {
    const updated = {
      ...lease,
      serial: runtime.serial,
      emulatorPid: runtime.pid,
      launchedAt: runtime.startedAt,
    };
    for (const lock of [lease.avdLock, lease.portLock]) {
      const owner = await readJson(path.join(lock, "owner.json"), "LEASE_NOT_FOUND");
      if (owner.id !== lease.id) {
        fail("LEASE_OWNERSHIP_MISMATCH", `lease ownership changed for ${lock}`);
      }
      await writeJsonAtomically(path.join(lock, "owner.json"), updated);
    }
    return updated;
  }

  async release(lease) {
    for (const lock of [lease.avdLock, lease.portLock]) {
      const relative = path.relative(this.stateRoot, path.resolve(lock));
      if (relative.startsWith("..") || path.isAbsolute(relative)) {
        fail("STATE_INVALID", "lease path escapes the configured state root");
      }
      const owner = await readJson(path.join(lock, "owner.json"), "LEASE_NOT_FOUND");
      if (owner.id !== lease.id || owner.avdName !== lease.avdName || owner.port !== lease.port) {
        fail("LEASE_OWNERSHIP_MISMATCH", `lease ownership changed for ${lock}`);
      }
    }
    await Promise.all([
      rm(lease.avdLock, { recursive: true }),
      rm(lease.portLock, { recursive: true }),
    ]);
  }
}

export const parseWebViewVersion = (output) => {
  const match = output.match(
    /Current WebView package \(name, version\): \(([A-Za-z][A-Za-z0-9_.]+), ([0-9]+(?:\.[0-9]+){2,3})\)/u,
  );
  if (!match) {
    fail(
      "WEBVIEW_VERSION_UNPARSEABLE",
      "unable to parse the current WebView package and version",
    );
  }
  return { packageName: match[1], version: match[2] };
};

export const runCommand = async (command, args, options = {}) => {
  try {
    const result = await execFileAsync(command, args, {
      cwd: options.cwd,
      env: options.env,
      timeout: options.timeoutMs ?? 30_000,
      maxBuffer: options.maxBuffer ?? 4 * 1024 * 1024,
      encoding: "utf8",
      windowsHide: true,
    });
    return { code: 0, stdout: result.stdout, stderr: result.stderr };
  } catch (error) {
    return {
      code: Number.isInteger(error.code) ? error.code : 1,
      stdout: error.stdout ?? "",
      stderr: error.stderr ?? error.message,
      signal: error.signal,
      timedOut: error.killed === true,
    };
  }
};

export const spawnEmulator = async (command, args, options = {}) => {
  await mkdir(path.dirname(options.logFile), { recursive: true });
  const log = await open(options.logFile, "a", 0o600);
  const child = spawn(command, args, {
    detached: true,
    env: options.env,
    windowsHide: true,
    stdio: ["ignore", log.fd, log.fd],
  });
  await new Promise((resolve, reject) => {
    child.once("spawn", resolve);
    child.once("error", reject);
  });
  child.unref();
  await log.close();
  return { pid: child.pid };
};

const processIsAlive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code === "EPERM";
  }
};

const defaultSleep = (durationMs) =>
  new Promise((resolve) => {
    setTimeout(resolve, durationMs);
  });

const property = (output, name) => {
  const prefix = `${name}=`;
  return output
    .split(/\r?\n/u)
    .find((line) => line.startsWith(prefix))
    ?.slice(prefix.length)
    .trim();
};

const normalizedFingerprint = (metadata) =>
  crypto.createHash("sha256").update(JSON.stringify(metadata)).digest("hex");

const parseConfigurationLocale = (output) => {
  const match = output.match(/\b([a-z]{2,3})(?:-r|-)([A-Z]{2})\b/u);
  if (!match) {
    fail("DEVICE_METADATA_UNAVAILABLE", "unable to parse locale from activity configuration");
  }
  return `${match[1]}-${match[2]}`;
};

export class EmulatorRunner {
  constructor(options) {
    this.stateRoot = path.resolve(options.stateRoot);
    this.sdkRoot = options.sdkRoot ? path.resolve(options.sdkRoot) : undefined;
    this.avdRoot = options.avdRoot ? path.resolve(options.avdRoot) : undefined;
    this.javaHome = options.javaHome ? path.resolve(options.javaHome) : undefined;
    this.profiles = validateProfileDocument(options.profiles);
    this.command = options.command ?? runCommand;
    this.spawn = options.spawn ?? spawnEmulator;
    this.isProcessAlive = options.isProcessAlive ?? processIsAlive;
    this.sleep = options.sleep ?? defaultSleep;
    this.stopTimeoutMs = options.stopTimeoutMs ?? 30_000;
    this.bootTimeoutMs = options.bootTimeoutMs ?? 180_000;
    this.snapshotTimeoutMs = options.snapshotTimeoutMs ?? 300_000;
    this.leaseStore = options.leaseStore ?? new FileLeaseStore(this.stateRoot);
    this.minimumFreeBytes = options.minimumFreeBytes ?? 8 * 1024 * 1024 * 1024;
    this.platform = options.platform ?? process.platform;
    this.writeState = options.writeState ?? writeJsonAtomically;
  }

  profile(profileId) {
    const profile = this.profiles.profiles.find((candidate) => candidate.id === profileId);
    if (!profile) fail("PROFILE_NOT_FOUND", `unknown emulator profile: ${profileId}`);
    return profile;
  }

  runtimeFile(profile) {
    return path.join(this.stateRoot, "runtime", `${profile.avdName}.json`);
  }

  avdDirectory(profile) {
    return path.join(this.avdRoot, `${profile.avdName}.avd`);
  }

  avdOwnerFile(profile) {
    return path.join(this.avdDirectory(profile), ".ai-auto-owner.json");
  }

  async assertOwnedAvd(profile, options = {}) {
    const directory = this.avdDirectory(profile);
    try {
      await access(directory);
    } catch (error) {
      if (error.code === "ENOENT" && options.allowMissing) return false;
      if (error.code === "ENOENT") {
        fail("AVD_NOT_FOUND", `AVD does not exist: ${profile.avdName}`);
      }
      throw error;
    }
    let owner;
    try {
      owner = JSON.parse(await readFile(this.avdOwnerFile(profile), "utf8"));
    } catch {
      fail(
        "AVD_OWNERSHIP_CONFLICT",
        `AVD ${profile.avdName} is not owned by this runner`,
      );
    }
    if (
      owner.schemaVersion !== "1.0"
      || owner.profileId !== profile.id
      || owner.avdName !== profile.avdName
      || owner.systemImage !== profile.systemImage
      || owner.systemImageSha256 !== profile.packageSha256
    ) {
      fail("AVD_OWNERSHIP_CONFLICT", `AVD ownership marker is invalid: ${profile.avdName}`);
    }
    return true;
  }

  environment() {
    if (!this.sdkRoot || !this.avdRoot || !this.javaHome) {
      fail("SDK_ROOT_REQUIRED", "sdkRoot, avdRoot, and javaHome must be explicit");
    }
    const pathValue = process.env.PATH ?? "";
    return {
      ...process.env,
      ANDROID_HOME: this.sdkRoot,
      ANDROID_SDK_ROOT: this.sdkRoot,
      ANDROID_AVD_HOME: this.avdRoot,
      JAVA_HOME: this.javaHome,
      PATH: `${path.join(this.javaHome, "bin")}${path.delimiter}${pathValue}`,
    };
  }

  tools() {
    const extension = this.platform === "win32" ? ".exe" : "";
    return {
      adb: path.join(this.sdkRoot, "platform-tools", `adb${extension}`),
      emulator: path.join(this.sdkRoot, "emulator", `emulator${extension}`),
      java: path.join(this.javaHome, "bin", `java${extension}`),
      avdClasspath: path.join(
        this.sdkRoot,
        "cmdline-tools",
        "latest",
        "lib",
        "avdmanager-classpath.jar",
      ),
    };
  }

  avdManagerArgs(args) {
    return [
      `-Dcom.android.sdkmanager.toolsdir=${path.join(
        this.sdkRoot,
        "cmdline-tools",
        "latest",
      )}`,
      "-classpath",
      this.tools().avdClasspath,
      "com.android.sdklib.tool.AvdManagerCli",
      ...args,
    ];
  }

  async verifyProfile(profileId) {
    const profile = this.profile(profileId);
    this.environment();
    const tools = this.tools();
    for (const [name, file] of Object.entries(tools)) {
      try {
        await access(file);
      } catch {
        fail("SDK_TOOL_MISSING", `required Android SDK tool is missing: ${name}`, { file });
      }
    }
    const toolchainProperties = {
      commandLineToolsRevision: path.join(
        this.sdkRoot,
        "cmdline-tools",
        "latest",
        "source.properties",
      ),
      platformToolsRevision: path.join(this.sdkRoot, "platform-tools", "source.properties"),
      emulatorRevision: path.join(this.sdkRoot, "emulator", "source.properties"),
      platformRevision: path.join(
        this.sdkRoot,
        "platforms",
        "android-36",
        "source.properties",
      ),
      buildToolsRevision: path.join(
        this.sdkRoot,
        "build-tools",
        "36.0.0",
        "source.properties",
      ),
    };
    for (const [name, file] of Object.entries(toolchainProperties)) {
      let content;
      try {
        content = await readFile(file, "utf8");
      } catch {
        fail("SDK_TOOL_MISSING", `required Android SDK package metadata is missing: ${name}`, {
          file,
        });
      }
      const actual = property(content, "Pkg.Revision");
      const expected = this.profiles.toolchain[name];
      if (actual !== expected) {
        fail("SDK_TOOL_VERSION_MISMATCH", `${name} does not match the fixed revision`, {
          expected,
          actual: actual ?? null,
        });
      }
    }
    const imageDirectory = path.join(
      this.sdkRoot,
      "system-images",
      `android-${profile.apiLevel}`,
      profile.tag,
      profile.abi,
    );
    const propertiesFile = path.join(imageDirectory, "source.properties");
    let properties;
    try {
      properties = await readFile(propertiesFile, "utf8");
    } catch {
      fail("SYSTEM_IMAGE_MISSING", `system image is not installed: ${profile.systemImage}`);
    }
    const revision = property(properties, "Pkg.Revision");
    if (!revision || revision !== profile.packageRevision) {
      fail("SYSTEM_IMAGE_VERSION_MISMATCH", "installed system image revision is not pinned", {
        expected: profile.packageRevision,
        actual: revision ?? null,
      });
    }
    const receipts = await readJson(
      path.join(this.stateRoot, "package-receipts.json"),
      "PACKAGE_RECEIPT_MISSING",
    );
    const receipt = receipts.packages?.[profile.systemImage];
    if (
      !receipt
      || receipt.revision !== profile.packageRevision
      || receipt.sha256 !== profile.packageSha256
    ) {
      fail("SYSTEM_IMAGE_HASH_MISMATCH", "installed system image receipt does not match profile", {
        package: profile.systemImage,
      });
    }
    return { profile, imageDirectory, tools };
  }

  async ensureDiskSpace() {
    const target = this.avdRoot ?? this.stateRoot;
    await mkdir(target, { recursive: true });
    const stats = await statfs(target);
    const freeBytes = Number(stats.bavail) * Number(stats.bsize);
    if (freeBytes < this.minimumFreeBytes) {
      fail("DISK_SPACE_LOW", "insufficient free space for deterministic emulator", {
        freeBytes,
        requiredBytes: this.minimumFreeBytes,
      });
    }
  }

  async create(profileId) {
    const { profile, tools } = await this.verifyProfile(profileId);
    await this.ensureDiskSpace();
    await mkdir(this.avdRoot, { recursive: true });
    const alreadyExists = await this.assertOwnedAvd(profile, { allowMissing: true });
    if (alreadyExists) {
      fail("AVD_ALREADY_EXISTS", `owned AVD already exists: ${profile.avdName}`);
    }
    const result = await this.command(
      tools.java,
      this.avdManagerArgs([
        "create",
        "avd",
        "--name",
        profile.avdName,
        "--package",
        profile.systemImage,
        "--device",
        profile.device,
      ]),
      { env: this.environment(), timeoutMs: 120_000 },
    );
    if (result.code !== 0) {
      fail("AVD_CREATE_FAILED", `avdmanager failed: ${result.stderr.trim()}`);
    }
    const configFile = path.join(this.avdRoot, `${profile.avdName}.avd`, "config.ini");
    try {
      await this.writeDeterministicAvdConfig(configFile, profile);
      await writeJsonAtomically(this.avdOwnerFile(profile), {
        schemaVersion: "1.0",
        profileId: profile.id,
        avdName: profile.avdName,
        systemImage: profile.systemImage,
        systemImageSha256: profile.packageSha256,
        createdAt: new Date().toISOString(),
      });
    } catch (error) {
      await this.command(
        tools.java,
        this.avdManagerArgs(["delete", "avd", "--name", profile.avdName]),
        { env: this.environment(), timeoutMs: 60_000 },
      );
      await rm(this.avdDirectory(profile), { recursive: true, force: true });
      fail("AVD_INITIALIZATION_FAILED", `unable to initialize owned AVD: ${error.message}`);
    }
    return this.result(profile, null, { state: "created" });
  }

  async writeDeterministicAvdConfig(configFile, profile) {
    let existing = "";
    try {
      existing = await readFile(configFile, "utf8");
    } catch {
      fail("AVD_CONFIG_MISSING", `AVD config was not created: ${configFile}`);
    }
    const [width, height] = profile.resolution.split("x");
    const fixed = new Map([
      ["hw.lcd.width", width],
      ["hw.lcd.height", height],
      ["hw.lcd.density", String(profile.densityDpi)],
      ["hw.keyboard", "yes"],
      ["hw.gpu.enabled", "yes"],
      ["hw.gpu.mode", "swiftshader_indirect"],
      ["skin.dynamic", "yes"],
      ["showDeviceFrame", "no"],
      ["fastboot.forceColdBoot", "no"],
      ["fastboot.forceFastBoot", "yes"],
      ["snapshot.present", "yes"],
    ]);
    const retained = existing
      .split(/\r?\n/u)
      .filter(Boolean)
      .filter((line) => !fixed.has(line.split("=", 1)[0]));
    const content = [
      ...retained,
      ...[...fixed].map(([key, value]) => `${key}=${value}`),
      "",
    ].join(os.EOL);
    await writeFile(configFile, content, "utf8");
  }

  async start(profileId, options = {}) {
    const { profile, tools } = await this.verifyProfile(profileId);
    await this.ensureDiskSpace();
    await this.assertOwnedAvd(profile);
    const runtimeFile = this.runtimeFile(profile);
    try {
      await access(runtimeFile);
      fail("RUNTIME_ALREADY_EXISTS", `${profile.avdName} already has runtime state`);
    } catch (error) {
      if (error instanceof EmulatorError) throw error;
      if (error.code !== "ENOENT") throw error;
    }
    const lease = await this.leaseStore.allocate(
      profile.avdName,
      this.profiles.portRange,
      async (port) => {
      const serial = `emulator-${port}`;
      const existing = await this.command(
        tools.adb,
        ["-s", serial, "get-state"],
        { env: this.environment(), timeoutMs: 2_000 },
      );
        return existing.code === 0;
      },
    );
    const serial = `emulator-${lease.port}`;
    const logFile = path.join(this.stateRoot, "logs", `${profile.avdName}.log`);
    const args = [
      `@${profile.avdName}`,
      "-port",
      String(lease.port),
      "-no-window",
      "-no-audio",
      "-no-boot-anim",
      "-gpu",
      "swiftshader_indirect",
      "-no-metrics",
      "-timezone",
      profile.timezone,
      "-no-snapshot-save",
    ];
    if (options.restoreSnapshot === false) {
      args.push(
        "-change-locale",
        profile.locale,
        "-wipe-data",
        "-no-snapshot-load",
      );
    } else {
      args.push("-snapshot", profile.snapshot);
    }
    let launched;
    try {
      launched = await this.spawn(tools.emulator, args, {
        env: this.environment(),
        logFile,
      });
    } catch (error) {
      await this.leaseStore.release(lease);
      fail("EMULATOR_START_FAILED", `unable to launch emulator: ${error.message}`);
    }
    const runtime = {
      schemaVersion: "1.0",
      profileId: profile.id,
      avdName: profile.avdName,
      serial,
      port: lease.port,
      pid: launched.pid,
      lease,
      logFile,
      startedAt: new Date().toISOString(),
    };
    try {
      runtime.lease = await this.leaseStore.attachRuntime(lease, runtime);
      await this.writeState(runtimeFile, runtime);
      return this.result(profile, runtime, { state: "starting" });
    } catch (error) {
      await this.command(
        tools.adb,
        ["-s", serial, "emu", "kill"],
        { env: this.environment(), timeoutMs: 5_000 },
      );
      const deadline = Date.now() + this.stopTimeoutMs;
      do {
        const state = await this.command(
          tools.adb,
          ["-s", serial, "get-state"],
          { env: this.environment(), timeoutMs: 2_000 },
        );
        if (state.code !== 0 && !this.isProcessAlive(runtime.pid)) {
          await this.leaseStore.release(runtime.lease);
          fail(
            "START_STATE_FAILED_CLEANED",
            `emulator state persistence failed and the process was cleaned: ${error.message}`,
          );
        }
        await this.sleep(250);
      } while (Date.now() < deadline);
      try {
        await this.writeState(runtimeFile, {
          ...runtime,
          statePersistenceError: error.message,
          recoveryRequired: true,
        });
      } catch {
        // lease owner 文件仍包含 pid/serial，可用于不释放锁的人工恢复。
      }
      fail(
        "START_STATE_FAILED_LOCK_RETAINED",
        "emulator state persistence failed; process and leases require explicit recovery",
        { serial, pid: runtime.pid },
      );
    }
  }

  async waitForBoot(profileId) {
    const profile = this.profile(profileId);
    const runtime = await readJson(this.runtimeFile(profile));
    const tools = this.tools();
    const deadline = Date.now() + this.bootTimeoutMs;
    while (Date.now() < deadline) {
      if (!this.isProcessAlive(runtime.pid)) {
        fail("EMULATOR_EXITED", `${runtime.avdName} exited before boot completed`);
      }
      const state = await this.command(
        tools.adb,
        ["-s", runtime.serial, "get-state"],
        { env: this.environment(), timeoutMs: 5_000 },
      );
      if (state.code === 0 && state.stdout.trim() === "device") {
        const boot = await this.command(
          tools.adb,
          ["-s", runtime.serial, "shell", "getprop", "sys.boot_completed"],
          { env: this.environment(), timeoutMs: 5_000 },
        );
        if (boot.code === 0 && boot.stdout.trim() === "1") {
          await this.configureDevice(profile, runtime.serial);
          const metadata = await this.deviceMetadata(profile, runtime.serial);
          const stableMetadata = { ...metadata };
          delete stableMetadata.serial;
          const updated = {
            ...runtime,
            bootedAt: new Date().toISOString(),
            deviceFingerprint: normalizedFingerprint(stableMetadata),
            metadata,
          };
          await this.writeState(this.runtimeFile(profile), updated);
          return this.result(profile, updated, { state: "booted" });
        }
      }
      await this.sleep(1_000);
    }
    fail("BOOT_TIMEOUT", `${runtime.avdName} did not boot before timeout`, {
      serial: runtime.serial,
      timeoutMs: this.bootTimeoutMs,
    });
  }

  async probeWebView(profileId) {
    const profile = this.profile(profileId);
    await this.assertOwnedAvd(profile);
    const runtime = await readJson(this.runtimeFile(profile));
    const tools = this.tools();
    const state = await this.command(
      tools.adb,
      ["-s", runtime.serial, "get-state"],
      { env: this.environment(), timeoutMs: 5_000 },
    );
    const boot = await this.command(
      tools.adb,
      ["-s", runtime.serial, "shell", "getprop", "sys.boot_completed"],
      { env: this.environment(), timeoutMs: 5_000 },
    );
    if (state.code !== 0 || state.stdout.trim() !== "device" || boot.stdout.trim() !== "1") {
      fail("DEVICE_NOT_BOOTED", `${runtime.serial} is not ready for WebView probing`);
    }
    const result = await this.command(
      tools.adb,
      ["-s", runtime.serial, "shell", "dumpsys", "webviewupdate"],
      { env: this.environment(), timeoutMs: 10_000 },
    );
    if (result.code !== 0) {
      fail("DEVICE_METADATA_UNAVAILABLE", "unable to query the current WebView package");
    }
    const webView = parseWebViewVersion(result.stdout);
    return this.result(profile, runtime, { state: "probed", webView });
  }

  async configureDevice(profile, serial) {
    const tools = this.tools();
    const [width, height] = profile.resolution.split("x");
    const commands = [
      ["shell", "wm", "size", `${width}x${height}`],
      ["shell", "wm", "density", String(profile.densityDpi)],
      ["shell", "settings", "put", "secure", "navigation_mode", "2"],
      ["shell", "settings", "put", "system", "accelerometer_rotation", "0"],
      ["shell", "settings", "put", "system", "user_rotation", "0"],
    ];
    for (const args of commands) {
      const result = await this.command(
        tools.adb,
        ["-s", serial, ...args],
        { env: this.environment(), timeoutMs: 10_000 },
      );
      if (result.code !== 0) {
        fail("DEVICE_CONFIG_FAILED", `device configuration failed: ${args.join(" ")}`, {
          stderr: result.stderr.trim(),
        });
      }
    }
  }

  async deviceMetadata(profile, serial) {
    const tools = this.tools();
    const queries = [
      ["shell", "getprop", "ro.build.fingerprint"],
      ["shell", "getprop", "ro.build.version.sdk"],
      ["shell", "getprop", "ro.product.model"],
      ["shell", "wm", "size"],
      ["shell", "wm", "density"],
      ["shell", "cmd", "activity", "get-config"],
      ["shell", "getprop", "persist.sys.timezone"],
      ["shell", "settings", "get", "secure", "navigation_mode"],
      ["shell", "dumpsys", "webviewupdate"],
    ];
    const values = [];
    for (const args of queries) {
      const result = await this.command(
        tools.adb,
        ["-s", serial, ...args],
        { env: this.environment(), timeoutMs: 10_000 },
      );
      if (result.code !== 0 || !result.stdout.trim()) {
        fail("DEVICE_METADATA_UNAVAILABLE", `unable to read device metadata: ${args.join(" ")}`);
      }
      values.push(result.stdout.trim());
    }
    const webView = parseWebViewVersion(values[8]);
    if (webView.packageName !== profile.webViewPackage) {
      fail("WEBVIEW_PACKAGE_MISMATCH", "current WebView package does not match the profile", {
        expected: profile.webViewPackage,
        actual: webView.packageName,
      });
    }
    if (webView.version !== profile.webViewVersion) {
      fail("WEBVIEW_VERSION_MISMATCH", "current WebView version does not match the profile", {
        expected: profile.webViewVersion,
        actual: webView.version,
      });
    }
    const metadata = {
      serial,
      avdName: profile.avdName,
      apiLevel: profile.apiLevel,
      buildFingerprint: values[0],
      reportedApiLevel: Number(values[1]),
      model: values[2],
      resolution: values[3],
      density: values[4],
      locale: parseConfigurationLocale(values[5]),
      timezone: values[6],
      navigationMode: values[7],
      webView,
      systemImage: profile.systemImage,
      systemImageRevision: profile.packageRevision,
      systemImageSha256: profile.packageSha256,
    };
    const expectedMetadata = {
      reportedApiLevel: profile.apiLevel,
      resolution: profile.resolution,
      densityDpi: profile.densityDpi,
      locale: profile.locale,
      timezone: profile.timezone,
      navigationMode: "2",
    };
    const actualMetadata = {
      reportedApiLevel: metadata.reportedApiLevel,
      resolution: [...metadata.resolution.matchAll(/[0-9]+x[0-9]+/gu)].at(-1)?.[0] ?? null,
      densityDpi: Number([...metadata.density.matchAll(/[0-9]+/gu)].at(-1)?.[0]),
      locale: metadata.locale,
      timezone: metadata.timezone,
      navigationMode: metadata.navigationMode,
    };
    if (JSON.stringify(actualMetadata) !== JSON.stringify(expectedMetadata)) {
      fail("DEVICE_FINGERPRINT_MISMATCH", "device metadata does not match the fixed profile", {
        expected: expectedMetadata,
        actual: actualMetadata,
      });
    }
    return metadata;
  }

  async restore(profileId) {
    const profile = this.profile(profileId);
    const runtime = await readJson(this.runtimeFile(profile));
    const result = await this.command(
      this.tools().adb,
      ["-s", runtime.serial, "emu", "avd", "snapshot", "load", profile.snapshot],
      { env: this.environment(), timeoutMs: this.snapshotTimeoutMs },
    );
    if (result.timedOut) {
      fail("SNAPSHOT_RESTORE_TIMEOUT", `snapshot restore timed out: ${profile.snapshot}`, {
        timeoutMs: this.snapshotTimeoutMs,
      });
    }
    if (result.code !== 0 || !/\bOK\b/u.test(result.stdout)) {
      fail("SNAPSHOT_RESTORE_FAILED", `unable to restore snapshot ${profile.snapshot}`, {
        stdout: result.stdout.trim(),
        stderr: result.stderr.trim(),
      });
    }
    return this.waitForBoot(profileId);
  }

  async saveSnapshot(profileId) {
    const profile = this.profile(profileId);
    const runtime = await readJson(this.runtimeFile(profile));
    const result = await this.command(
      this.tools().adb,
      ["-s", runtime.serial, "emu", "avd", "snapshot", "save", profile.snapshot],
      { env: this.environment(), timeoutMs: this.snapshotTimeoutMs },
    );
    if (result.timedOut) {
      fail("SNAPSHOT_SAVE_TIMEOUT", `snapshot save timed out: ${profile.snapshot}`, {
        timeoutMs: this.snapshotTimeoutMs,
      });
    }
    if (result.code !== 0 || !/\bOK\b/u.test(result.stdout)) {
      fail("SNAPSHOT_SAVE_FAILED", `unable to save snapshot ${profile.snapshot}`, {
        stdout: result.stdout.trim(),
        stderr: result.stderr.trim(),
      });
    }
    return this.result(profile, runtime, { state: "snapshot-saved" });
  }

  async writeSnapshotMarker(profileId, value) {
    const profile = this.profile(profileId);
    const runtime = await readJson(this.runtimeFile(profile));
    if (!/^[A-Za-z0-9._-]{1,64}$/u.test(value)) {
      fail("CONFIG_INVALID", "snapshot marker contains unsupported characters");
    }
    const result = await this.command(
      this.tools().adb,
      [
        "-s",
        runtime.serial,
        "shell",
        "settings",
        "put",
        "global",
        "ai_auto_snapshot_marker",
        value,
      ],
      { env: this.environment(), timeoutMs: 10_000 },
    );
    if (result.code !== 0) {
      fail("SNAPSHOT_MARKER_WRITE_FAILED", "unable to write the snapshot marker");
    }
    return this.result(profile, runtime, { state: "marker-written", marker: value });
  }

  async readSnapshotMarker(profileId) {
    const profile = this.profile(profileId);
    const runtime = await readJson(this.runtimeFile(profile));
    const result = await this.command(
      this.tools().adb,
      [
        "-s",
        runtime.serial,
        "shell",
        "settings",
        "get",
        "global",
        "ai_auto_snapshot_marker",
      ],
      { env: this.environment(), timeoutMs: 10_000 },
    );
    if (result.code !== 0) {
      fail("SNAPSHOT_MARKER_READ_FAILED", "unable to read the snapshot marker");
    }
    return result.stdout.trim();
  }

  async stop(profileId) {
    const profile = this.profile(profileId);
    const runtimeFile = this.runtimeFile(profile);
    const runtime = await readJson(runtimeFile);
    await this.command(
      this.tools().adb,
      ["-s", runtime.serial, "emu", "kill"],
      { env: this.environment(), timeoutMs: 10_000 },
    );
    const deadline = Date.now() + this.stopTimeoutMs;
    do {
      const state = await this.command(
        this.tools().adb,
        ["-s", runtime.serial, "get-state"],
        { env: this.environment(), timeoutMs: 2_000 },
      );
      if (state.code !== 0 && !this.isProcessAlive(runtime.pid)) {
        await this.leaseStore.release(runtime.lease);
        await rm(runtimeFile);
        return this.result(profile, runtime, { state: "stopped" });
      }
      await this.sleep(250);
    } while (Date.now() < deadline);
    fail(
      "STOP_FAILED_LOCK_RETAINED",
      `${runtime.avdName} did not stop cleanly; runtime and leases were retained`,
      { serial: runtime.serial, pid: runtime.pid },
    );
  }

  async delete(profileId) {
    const { profile, tools } = await this.verifyProfile(profileId);
    await this.assertOwnedAvd(profile);
    try {
      await access(this.runtimeFile(profile));
      fail("RUNTIME_ALREADY_EXISTS", `stop ${profile.avdName} before deleting it`);
    } catch (error) {
      if (error instanceof EmulatorError) throw error;
      if (error.code !== "ENOENT") throw error;
    }
    const result = await this.command(
      tools.java,
      this.avdManagerArgs(["delete", "avd", "--name", profile.avdName]),
      { env: this.environment(), timeoutMs: 60_000 },
    );
    if (result.code !== 0) {
      fail("AVD_DELETE_FAILED", `avdmanager failed: ${result.stderr.trim()}`);
    }
    const iniFile = path.join(this.avdRoot, `${profile.avdName}.ini`);
    for (const residue of [this.avdDirectory(profile), iniFile]) {
      try {
        await access(residue);
        fail("AVD_DELETE_INCOMPLETE", `AVD residue remains after delete: ${residue}`);
      } catch (error) {
        if (error instanceof EmulatorError) throw error;
        if (error.code !== "ENOENT") throw error;
      }
    }
    return this.result(profile, null, { state: "deleted" });
  }

  result(profile, runtime, extra) {
    return {
      profileId: profile.id,
      apiLevel: profile.apiLevel,
      avdName: profile.avdName,
      serial: runtime?.serial ?? null,
      deviceFingerprint: runtime?.deviceFingerprint ?? null,
      ...extra,
    };
  }
}
