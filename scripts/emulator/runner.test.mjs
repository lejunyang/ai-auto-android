// 脚本用途：验证模拟器生命周期锁、版本失败关闭和停止清理不会串用设备或提前释放资源。
import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  EmulatorError,
  EmulatorRunner,
  FileLeaseStore,
  loadProfiles,
  parseWebViewVersion,
} from "./runner.mjs";

const profileDocument = {
  schemaVersion: "1.0",
  portRange: { start: 5554, end: 5560 },
  toolchain: {
    commandLineToolsRevision: "22.0",
    platformToolsRevision: "37.0.0",
    emulatorRevision: "36.6.11",
    platformRevision: "2",
    buildToolsRevision: "36.0.0",
  },
  profiles: [
    {
      id: "api-33",
      apiLevel: 33,
      abi: "arm64-v8a",
      tag: "google_apis",
      systemImage: "system-images;android-33;google_apis;arm64-v8a",
      packageRevision: "12",
      packageSha256: "a".repeat(64),
      avdName: "ai-auto-api-33",
      device: "pixel_6",
      resolution: "1080x2400",
      densityDpi: 420,
      locale: "zh-CN",
      timezone: "Asia/Shanghai",
      navigationMode: "gestural",
      snapshot: "clean",
      webViewPackage: "com.google.android.webview",
      webViewVersion: "126.0.6478.122",
    },
  ],
};

const createState = async () => mkdtemp(path.join(os.tmpdir(), "emulator-runner-test-"));

test("repository profiles and archive descriptors agree on every package revision", async () => {
  const repositoryProfiles = await loadProfiles(
    path.join(import.meta.dirname, "profiles.json"),
  );
  const archives = JSON.parse(
    await readFile(path.join(import.meta.dirname, "image-archives.json"), "utf8"),
  );

  for (const profile of repositoryProfiles.profiles) {
    assert.equal(
      archives.packages[profile.systemImage]?.revision,
      profile.packageRevision,
      profile.systemImage,
    );
  }
});

test("profile package hashes are mandatory and malformed hashes fail closed", async () => {
  const stateRoot = await createState();
  const file = path.join(stateRoot, "profiles.json");
  const malformed = structuredClone(profileDocument);
  malformed.profiles[0].packageSha256 = "";
  await writeFile(file, JSON.stringify(malformed));

  await assert.rejects(
    loadProfiles(file),
    (error) => error instanceof EmulatorError && error.code === "CONFIG_INVALID",
  );
});

test("emulator console ports stay inside the platform recommended range", async () => {
  const stateRoot = await createState();
  const file = path.join(stateRoot, "profiles.json");
  const unsupported = structuredClone(profileDocument);
  unsupported.portRange.end = 5586;
  await writeFile(file, JSON.stringify(unsupported));

  await assert.rejects(
    loadProfiles(file),
    (error) => error instanceof EmulatorError && error.code === "CONFIG_INVALID",
  );
});

test("file leases reject a second process for the same AVD and port", async () => {
  const stateRoot = await createState();
  const first = new FileLeaseStore(stateRoot, { pid: 101, now: () => 10 });
  const second = new FileLeaseStore(stateRoot, { pid: 202, now: () => 20 });

  const lease = await first.acquire("ai-auto-api-33", 5554);
  await assert.rejects(
    second.acquire("ai-auto-api-33", 5556),
    (error) => error instanceof EmulatorError && error.code === "AVD_LOCKED",
  );
  await assert.rejects(
    second.acquire("different-avd", 5554),
    (error) => error instanceof EmulatorError && error.code === "PORT_CONFLICT",
  );

  await first.release(lease);
  const reacquired = await second.acquire("ai-auto-api-33", 5554);
  await second.release(reacquired);
});

test("global port allocation rotates serials and rejects a concurrent allocator", async () => {
  const stateRoot = await createState();
  const store = new FileLeaseStore(stateRoot, { pid: 505, now: () => 50 });
  const range = { start: 5554, end: 5560 };
  const first = await store.allocate("ai-auto-api-30", range, async () => false);
  await store.release(first);
  const second = await store.allocate("ai-auto-api-33", range, async () => false);
  assert.notEqual(first.port, second.port);
  await mkdir(path.join(stateRoot, "locks", "allocator.lock"));
  await assert.rejects(
    store.allocate("ai-auto-api-34", range, async () => false),
    (error) => error instanceof EmulatorError && error.code === "ALLOCATOR_LOCKED",
  );
  await store.release(second);
});

test("WebView metadata requires an exact package and semantic version", () => {
  assert.deepEqual(
    parseWebViewVersion(
      "Current WebView package (name, version): (com.google.android.webview, 126.0.6478.122)",
    ),
    {
      packageName: "com.google.android.webview",
      version: "126.0.6478.122",
    },
  );
  assert.throws(
    () => parseWebViewVersion("Current WebView package: unavailable"),
    (error) =>
      error instanceof EmulatorError && error.code === "WEBVIEW_VERSION_UNPARSEABLE",
  );
});

test("stop failure retains both AVD and port leases for explicit recovery", async () => {
  const stateRoot = await createState();
  const leases = new FileLeaseStore(stateRoot, { pid: 303, now: () => 30 });
  const lease = await leases.acquire("ai-auto-api-33", 5554);
  await mkdir(path.join(stateRoot, "runtime"), { recursive: true });
  await writeFile(
    path.join(stateRoot, "runtime", "ai-auto-api-33.json"),
    JSON.stringify({
      avdName: "ai-auto-api-33",
      serial: "emulator-5554",
      port: 5554,
      pid: 999,
      lease,
    }),
  );
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot: path.join(stateRoot, "sdk"),
    avdRoot: path.join(stateRoot, "avd"),
    javaHome: path.join(stateRoot, "java"),
    profiles: profileDocument,
    command: async () => ({ code: 1, stdout: "", stderr: "emulator rejected kill" }),
    isProcessAlive: () => true,
    sleep: async () => {},
    stopTimeoutMs: 1,
  });

  await assert.rejects(
    runner.stop("api-33"),
    (error) => error instanceof EmulatorError && error.code === "STOP_FAILED_LOCK_RETAINED",
  );

  await assert.doesNotReject(readFile(path.join(lease.avdLock, "owner.json"), "utf8"));
  await assert.doesNotReject(readFile(path.join(lease.portLock, "owner.json"), "utf8"));
});

test("confirmed stop removes runtime and releases leases", async () => {
  const stateRoot = await createState();
  const leases = new FileLeaseStore(stateRoot, { pid: 404, now: () => 40 });
  const lease = await leases.acquire("ai-auto-api-33", 5554);
  await mkdir(path.join(stateRoot, "runtime"), { recursive: true });
  const runtimeFile = path.join(stateRoot, "runtime", "ai-auto-api-33.json");
  await writeFile(
    runtimeFile,
    JSON.stringify({
      avdName: "ai-auto-api-33",
      serial: "emulator-5554",
      port: 5554,
      pid: 1000,
      lease,
    }),
  );
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot: path.join(stateRoot, "sdk"),
    avdRoot: path.join(stateRoot, "avd"),
    javaHome: path.join(stateRoot, "java"),
    profiles: profileDocument,
    command: async (_command, args) => {
      if (args.includes("get-state")) return { code: 1, stdout: "", stderr: "not found" };
      return { code: 0, stdout: "OK", stderr: "" };
    },
    isProcessAlive: () => false,
    sleep: async () => {},
    stopTimeoutMs: 10,
  });

  const result = await runner.stop("api-33");

  assert.equal(result.serial, "emulator-5554");
  await assert.rejects(readFile(runtimeFile, "utf8"), { code: "ENOENT" });
  await assert.rejects(readFile(path.join(lease.avdLock, "owner.json"), "utf8"), {
    code: "ENOENT",
  });
  await assert.rejects(readFile(path.join(lease.portLock, "owner.json"), "utf8"), {
    code: "ENOENT",
  });
});

test("Windows tool paths use native executables without batch or shell wrapping", async () => {
  const stateRoot = await createState();
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot: path.join(stateRoot, "sdk"),
    avdRoot: path.join(stateRoot, "avd"),
    javaHome: path.join(stateRoot, "java"),
    profiles: profileDocument,
    platform: "win32",
  });

  assert.deepEqual(runner.tools(), {
    adb: path.join(stateRoot, "sdk", "platform-tools", "adb.exe"),
    emulator: path.join(stateRoot, "sdk", "emulator", "emulator.exe"),
    java: path.join(stateRoot, "java", "bin", "java.exe"),
    avdClasspath: path.join(
      stateRoot,
      "sdk",
      "cmdline-tools",
      "latest",
      "lib",
      "avdmanager-classpath.jar",
    ),
  });
});

test("Windows fake SDK verifies native exe tools and package metadata", async () => {
  const stateRoot = await createState();
  const sdkRoot = path.join(stateRoot, "sdk");
  const javaHome = path.join(stateRoot, "java");
  const profile = profileDocument.profiles[0];
  const files = [
    path.join(sdkRoot, "platform-tools", "adb.exe"),
    path.join(sdkRoot, "emulator", "emulator.exe"),
    path.join(javaHome, "bin", "java.exe"),
    path.join(
      sdkRoot,
      "cmdline-tools",
      "latest",
      "lib",
      "avdmanager-classpath.jar",
    ),
  ];
  await Promise.all(files.map(async (file) => {
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, "");
  }));
  for (const [directory, revision] of [
    ["cmdline-tools/latest", "22.0"],
    ["platform-tools", "37.0.0"],
    ["emulator", "36.6.11"],
    ["platforms/android-36", "2"],
    ["build-tools/36.0.0", "36.0.0"],
    ["system-images/android-33/google_apis/arm64-v8a", "12"],
  ]) {
    const metadata = path.join(sdkRoot, directory, "source.properties");
    await mkdir(path.dirname(metadata), { recursive: true });
    await writeFile(metadata, `Pkg.Revision=${revision}\n`);
  }
  await writeFile(
    path.join(stateRoot, "package-receipts.json"),
    JSON.stringify({
      packages: {
        [profile.systemImage]: {
          revision: profile.packageRevision,
          sha256: profile.packageSha256,
        },
      },
    }),
  );
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot,
    avdRoot: path.join(stateRoot, "avd"),
    javaHome,
    profiles: profileDocument,
    platform: "win32",
    minimumFreeBytes: 0,
  });

  const verified = await runner.verifyProfile("api-33");

  assert.equal(verified.profile.id, "api-33");
  assert.equal(verified.tools.adb, path.join(sdkRoot, "platform-tools", "adb.exe"));
  assert.equal(verified.tools.emulator, path.join(sdkRoot, "emulator", "emulator.exe"));
  assert.equal(verified.tools.java, path.join(javaHome, "bin", "java.exe"));
});

test("runtime state write failure releases leases only after process cleanup", async () => {
  const stateRoot = await createState();
  let releases = 0;
  const lease = {
    id: "cleanup-lease",
    avdName: "ai-auto-api-33",
    port: 5554,
  };
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot: path.join(stateRoot, "sdk"),
    avdRoot: path.join(stateRoot, "avd"),
    javaHome: path.join(stateRoot, "java"),
    profiles: profileDocument,
    command: async () => ({ code: 1, stdout: "", stderr: "not found" }),
    spawn: async () => ({ pid: 6161 }),
    isProcessAlive: () => false,
    sleep: async () => {},
    stopTimeoutMs: 0,
    minimumFreeBytes: 0,
    writeState: async () => {
      throw new Error("disk write failed");
    },
    leaseStore: {
      allocate: async () => lease,
      attachRuntime: async () => lease,
      release: async () => {
        releases += 1;
      },
    },
  });
  runner.verifyProfile = async () => ({
    profile: profileDocument.profiles[0],
    tools: {
      adb: "adb",
      emulator: "emulator",
      java: "java",
      avdClasspath: "avdmanager-classpath.jar",
    },
  });
  runner.ensureDiskSpace = async () => {};
  runner.assertOwnedAvd = async () => true;

  await assert.rejects(
    runner.start("api-33"),
    (error) => error instanceof EmulatorError && error.code === "START_STATE_FAILED_CLEANED",
  );
  assert.equal(releases, 1);
});

test("runtime state write failure retains leases while emulator remains alive", async () => {
  const stateRoot = await createState();
  let launched = false;
  let releases = 0;
  const lease = {
    id: "retained-lease",
    avdName: "ai-auto-api-33",
    port: 5554,
  };
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot: path.join(stateRoot, "sdk"),
    avdRoot: path.join(stateRoot, "avd"),
    javaHome: path.join(stateRoot, "java"),
    profiles: profileDocument,
    command: async (_command, args) => {
      if (args.includes("get-state") && launched) {
        return { code: 0, stdout: "device\n", stderr: "" };
      }
      return { code: 1, stdout: "", stderr: "not found" };
    },
    spawn: async () => {
      launched = true;
      return { pid: 7171 };
    },
    isProcessAlive: () => true,
    sleep: async () => {},
    stopTimeoutMs: 0,
    minimumFreeBytes: 0,
    writeState: async () => {
      throw new Error("disk write failed");
    },
    leaseStore: {
      allocate: async () => lease,
      attachRuntime: async () => lease,
      release: async () => {
        releases += 1;
      },
    },
  });
  runner.verifyProfile = async () => ({
    profile: profileDocument.profiles[0],
    tools: {
      adb: "adb",
      emulator: "emulator",
      java: "java",
      avdClasspath: "avdmanager-classpath.jar",
    },
  });
  runner.ensureDiskSpace = async () => {};
  runner.assertOwnedAvd = async () => true;

  await assert.rejects(
    runner.start("api-33"),
    (error) =>
      error instanceof EmulatorError && error.code === "START_STATE_FAILED_LOCK_RETAINED",
  );
  assert.equal(releases, 0);
});

test("snapshot command timeouts return dedicated stable errors", async () => {
  const stateRoot = await createState();
  const runtimeDirectory = path.join(stateRoot, "runtime");
  await mkdir(runtimeDirectory, { recursive: true });
  await writeFile(
    path.join(runtimeDirectory, "ai-auto-api-33.json"),
    JSON.stringify({
      profileId: "api-33",
      avdName: "ai-auto-api-33",
      serial: "emulator-5554",
      port: 5554,
      pid: 8181,
      lease: {},
    }),
  );
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot: path.join(stateRoot, "sdk"),
    avdRoot: path.join(stateRoot, "avd"),
    javaHome: path.join(stateRoot, "java"),
    profiles: profileDocument,
    command: async () => ({
      code: 1,
      stdout: "",
      stderr: "",
      timedOut: true,
    }),
    snapshotTimeoutMs: 321_000,
  });

  await assert.rejects(
    runner.saveSnapshot("api-33"),
    (error) =>
      error instanceof EmulatorError
      && error.code === "SNAPSHOT_SAVE_TIMEOUT"
      && error.details.timeoutMs === 321_000,
  );
  await assert.rejects(
    runner.restore("api-33"),
    (error) =>
      error instanceof EmulatorError
      && error.code === "SNAPSHOT_RESTORE_TIMEOUT"
      && error.details.timeoutMs === 321_000,
  );
});

test("fake SDK completes create start boot snapshot restore stop and delete", async () => {
  const stateRoot = await createState();
  const sdkRoot = path.join(stateRoot, "sdk");
  const avdRoot = path.join(stateRoot, "avd");
  const profile = profileDocument.profiles[0];
  const executableSuffix = process.platform === "win32" ? ".exe" : "";
  const imageDirectory = path.join(
    sdkRoot,
    "system-images",
    "android-33",
    "google_apis",
    "arm64-v8a",
  );
  const toolFiles = [
    path.join(sdkRoot, "platform-tools", `adb${executableSuffix}`),
    path.join(sdkRoot, "emulator", `emulator${executableSuffix}`),
    path.join(sdkRoot, "cmdline-tools", "latest", "lib", "avdmanager-classpath.jar"),
    path.join(stateRoot, "java", "bin", `java${executableSuffix}`),
  ];
  await Promise.all(toolFiles.map(async (file) => {
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, "");
  }));
  const packageMetadata = new Map([
    [path.join(sdkRoot, "cmdline-tools", "latest", "source.properties"), "22.0"],
    [path.join(sdkRoot, "platform-tools", "source.properties"), "37.0.0"],
    [path.join(sdkRoot, "emulator", "source.properties"), "36.6.11"],
    [path.join(sdkRoot, "platforms", "android-36", "source.properties"), "2"],
    [path.join(sdkRoot, "build-tools", "36.0.0", "source.properties"), "36.0.0"],
  ]);
  await Promise.all([...packageMetadata].map(async ([file, revision]) => {
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, `Pkg.Revision=${revision}\n`);
  }));
  await mkdir(imageDirectory, { recursive: true });
  await writeFile(path.join(imageDirectory, "source.properties"), "Pkg.Revision=12\n");
  await writeFile(
    path.join(stateRoot, "package-receipts.json"),
    JSON.stringify({
      packages: {
        [profile.systemImage]: {
          revision: "12",
          sha256: "a".repeat(64),
        },
      },
    }),
  );
  let alive = true;
  let runtimeSerial;
  let snapshotMarker = "clean";
  let launchedArguments = [];
  const commandLog = [];
  const command = async (executable, args) => {
    commandLog.push([executable, args]);
    const avdCommandIndex = args.indexOf("com.android.sdklib.tool.AvdManagerCli");
    const avdCommand = avdCommandIndex >= 0 ? args[avdCommandIndex + 1] : null;
    if (avdCommand === "create") {
      const avdDirectory = path.join(avdRoot, `${profile.avdName}.avd`);
      await mkdir(avdDirectory, { recursive: true });
      await writeFile(path.join(avdDirectory, "config.ini"), "hw.ramSize=2048\n");
      return { code: 0, stdout: "created", stderr: "" };
    }
    if (avdCommand === "delete") {
      await rm(path.join(avdRoot, `${profile.avdName}.avd`), {
        recursive: true,
        force: true,
      });
      await rm(path.join(avdRoot, `${profile.avdName}.ini`), { force: true });
      return { code: 0, stdout: "deleted", stderr: "" };
    }
    if (args.includes("get-state")) {
      return alive && args[1] === runtimeSerial
        ? { code: 0, stdout: "device\n", stderr: "" }
        : { code: 1, stdout: "", stderr: "not found" };
    }
    if (args.includes("sys.boot_completed")) {
      return { code: 0, stdout: "1\n", stderr: "" };
    }
    if (args.includes("webviewupdate")) {
      return {
        code: 0,
        stdout:
          "Current WebView package (name, version): "
          + "(com.google.android.webview, 126.0.6478.122)\n",
        stderr: "",
      };
    }
    if (args.includes("ro.build.fingerprint")) {
      return { code: 0, stdout: "google/fake/fake:13/test:userdebug/test-keys\n", stderr: "" };
    }
    if (args.includes("ro.build.version.sdk")) {
      return { code: 0, stdout: "33\n", stderr: "" };
    }
    if (args.includes("ro.product.model")) {
      return { code: 0, stdout: "Fake Pixel\n", stderr: "" };
    }
    if (args.includes("get-config")) {
      return { code: 0, stdout: "config: mcc0mnc [zh-rCN] ldltr sw411dp\n", stderr: "" };
    }
    if (args.includes("persist.sys.timezone")) {
      return { code: 0, stdout: "Asia/Shanghai\n", stderr: "" };
    }
    if (args.includes("navigation_mode") && args.includes("get")) {
      return { code: 0, stdout: "2\n", stderr: "" };
    }
    if (args.includes("size") && args.length === 5) {
      return { code: 0, stdout: "Physical size: 1080x2400\n", stderr: "" };
    }
    if (args.includes("density") && args.length === 5) {
      return { code: 0, stdout: "Physical density: 420\n", stderr: "" };
    }
    if (args.includes("snapshot")) {
      if (args.includes("load")) snapshotMarker = "clean";
      return { code: 0, stdout: "OK\n", stderr: "" };
    }
    if (args.includes("ai_auto_snapshot_marker")) {
      if (args.includes("put")) {
        snapshotMarker = args.at(-1);
        return { code: 0, stdout: "", stderr: "" };
      }
      return { code: 0, stdout: `${snapshotMarker}\n`, stderr: "" };
    }
    if (args.includes("kill")) {
      alive = false;
      return { code: 0, stdout: "OK\n", stderr: "" };
    }
    return { code: 0, stdout: "", stderr: "" };
  };
  const runner = new EmulatorRunner({
    stateRoot,
    sdkRoot,
    avdRoot,
    javaHome: path.join(stateRoot, "java"),
    profiles: profileDocument,
    command,
    spawn: async (_executable, args) => {
      launchedArguments = args;
      runtimeSerial = `emulator-${args[args.indexOf("-port") + 1]}`;
      return { pid: 12345 };
    },
    isProcessAlive: () => alive,
    sleep: async () => {},
    minimumFreeBytes: 0,
  });

  assert.equal((await runner.create("api-33")).state, "created");
  assert.equal((await runner.start("api-33")).serial, runtimeSerial);
  assert(launchedArguments.includes("-snapshot"));
  assert(launchedArguments.includes("-no-snapshot-save"));
  assert(!launchedArguments.includes("-wipe-data"));
  assert(!launchedArguments.includes("-change-locale"));
  const booted = await runner.waitForBoot("api-33");
  assert.equal(booted.state, "booted");
  assert.match(booted.deviceFingerprint, /^[a-f0-9]{64}$/u);
  assert.equal((await runner.saveSnapshot("api-33")).state, "snapshot-saved");
  await runner.writeSnapshotMarker("api-33", "dirty");
  assert.equal(await runner.readSnapshotMarker("api-33"), "dirty");
  assert.equal((await runner.restore("api-33")).state, "booted");
  assert.equal(await runner.readSnapshotMarker("api-33"), "clean");
  assert.equal((await runner.stop("api-33")).state, "stopped");
  assert.equal((await runner.delete("api-33")).state, "deleted");
  assert(
    commandLog.every(([executable, args]) =>
      Array.isArray(args)
      && !/(?:^|[/\\])(?:sh|bash|zsh|cmd|powershell)(?:\.exe)?$/iu.test(executable)
      && !args.some((arg) => ["&&", "||", "|", ">", ">>"].includes(arg)),
    ),
    "all external commands must remain argv arrays without a shell executable",
  );
});
