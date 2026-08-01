// 测试用途：验证 N52 production residue 的固定身份、真实计数与文件系统失败关闭边界。
import assert from "node:assert/strict";
import {
  lstat,
  mkdir,
  mkdtemp,
  open,
  readFile,
  readdir,
  realpath,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  createProductionResidueAdapter,
  FIXED_RESIDUE_PACKAGES,
} from "../src/production-residue.mjs";
import {
  ProductionMatrixError,
  resolveProductionConfig,
} from "../src/production.mjs";

const PROFILE = Object.freeze({
  profileId: "api-30",
  apiLevel: 30,
});
const N31_PROFILE = Object.freeze({
  id: "api-30",
  apiLevel: 30,
  avdName: "ai-auto-api-30",
  snapshot: "clean",
});
const BINDING = Object.freeze({
  profileId: "api-30",
  apiLevel: 30,
  serial: "emulator-5554",
  deviceFingerprint: "a".repeat(64),
  snapshot: "clean",
});
const directoryLinkType = process.platform === "win32" ? "junction" : "dir";

const bindingDocument = (overrides = {}) => ({
  schemaVersion: "1.0",
  ...BINDING,
  ...overrides,
});

const providerResult = (kind, count, overrides = {}) => ({
  ...BINDING,
  kind,
  count,
  ...overrides,
});

const typedProviders = (counts = {}, overrides = {}) =>
  Object.freeze(Object.fromEntries(
    ["appData", "bridgeSessions", "testServices", "ownedProcesses"].map(
      (kind) => [
        kind,
        Object.freeze({
          probe: async (request) => ({
            profileId: request.profileId,
            apiLevel: request.apiLevel,
            available: true,
          }),
          inspect: async (request) => {
            await overrides.onInspect?.(kind, request);
            return providerResult(
              kind,
              counts[kind] ?? 0,
              overrides.results?.[kind],
            );
          },
        }),
      ],
    ),
  ));

const createFixture = async ({
  counts = {},
  providers = typedProviders(counts),
  filesystem,
} = {}) => {
  const toolRoot = await mkdtemp(path.join(os.tmpdir(), "n52-residue-"));
  const stateRoot = path.join(toolRoot, "emulator-state");
  await mkdir(stateRoot, { recursive: true });
  const environment = {
    AACTL_TOOLCHAIN_ROOT: toolRoot,
    ANDROID_SDK_ROOT: path.join(toolRoot, "android-sdk"),
    ANDROID_AVD_HOME: path.join(toolRoot, "android-avd"),
    AACTL_EMULATOR_STATE: stateRoot,
    JAVA_HOME: path.join(toolRoot, "jdk", "Contents", "Home"),
  };
  const config = resolveProductionConfig(environment);
  const adapter = await createProductionResidueAdapter(config, {
    filesystem: filesystem ?? {
      lstat,
      open,
      readdir,
      realpath,
    },
    loadProfiles: async () => ({
      schemaVersion: "1.0",
      profiles: [N31_PROFILE],
    }),
    providers,
  });
  return {
    adapter,
    config,
    stateRoot,
    toolRoot,
    cleanup: () => rm(toolRoot, { recursive: true, force: true }),
  };
};

const writeJson = async (file, value) => {
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, `${JSON.stringify(value)}\n`, {
    encoding: "utf8",
    mode: 0o600,
  });
};

const writeBoundArtifacts = async (
  stateRoot,
  rootKind,
  { logs = 0, screenshots = 0 } = {},
) => {
  const profileRoot = path.join(
    stateRoot,
    rootKind,
    "n52-production-matrix",
    BINDING.profileId,
  );
  await writeJson(path.join(profileRoot, "binding.json"), bindingDocument());
  for (const [kind, count, extension] of [
    ["logs", logs, ".log"],
    ["screenshots", screenshots, ".png"],
  ]) {
    for (let index = 0; index < count; index += 1) {
      const file = path.join(profileRoot, kind, `artifact-${index}${extension}`);
      await mkdir(path.dirname(file), { recursive: true });
      await writeFile(file, `${kind}-${index}`, { mode: 0o600 });
    }
  }
};

const writeStaleN31State = async (stateRoot) => {
  const avdLock = path.join(
    stateRoot,
    "locks",
    "avd",
    `${N31_PROFILE.avdName}.lock`,
  );
  const portLock = path.join(stateRoot, "locks", "port", "5554.lock");
  const lease = {
    id: "019fbe00-0000-7000-8000-000000000052",
    avdName: N31_PROFILE.avdName,
    port: 5554,
    avdLock,
    portLock,
    ownerPid: 4700,
    acquiredAtMs: 1,
    serial: BINDING.serial,
    emulatorPid: 4752,
    launchedAt: "2026-08-01T12:00:00.000Z",
  };
  const logFile = path.join(
    stateRoot,
    "logs",
    `${N31_PROFILE.avdName}-${BINDING.serial}.log`,
  );
  const runtimeFile = path.join(
    stateRoot,
    "runtime",
    `${N31_PROFILE.avdName}.json`,
  );
  await writeJson(runtimeFile, {
    schemaVersion: "1.0",
    profileId: BINDING.profileId,
    avdName: N31_PROFILE.avdName,
    serial: BINDING.serial,
    port: 5554,
    pid: 4752,
    lease,
    logFile,
    startedAt: "2026-08-01T12:00:00.000Z",
    deviceFingerprint: BINDING.deviceFingerprint,
  });
  await writeJson(path.join(avdLock, "owner.json"), lease);
  await writeJson(path.join(portLock, "owner.json"), lease);
  await mkdir(path.dirname(logFile), { recursive: true });
  await writeFile(logFile, "stale emulator log\n", { mode: 0o600 });
};

const assertCode = (code) => (error) =>
  error instanceof ProductionMatrixError && error.code === code;

test("固定 package 集和空固定 roots 由八类 inspector 实际判零", async (currentTest) => {
  const inspected = [];
  const fixture = await createFixture({
    providers: typedProviders({}, {
      onInspect: (kind, request) => inspected.push([kind, request]),
    }),
  });
  currentTest.after(fixture.cleanup);
  await fixture.adapter.probe(PROFILE);

  const results = {};
  for (const kind of [
    "appData",
    "bridgeSessions",
    "testServices",
    "screenshots",
    "logs",
    "ownedProcesses",
    "runtimeFiles",
    "leases",
  ]) {
    results[kind] = await fixture.adapter[kind](BINDING);
    assert.equal(results[kind].count, 0);
    assert.deepEqual(
      {
        profileId: results[kind].profileId,
        apiLevel: results[kind].apiLevel,
        serial: results[kind].serial,
        deviceFingerprint: results[kind].deviceFingerprint,
        snapshot: results[kind].snapshot,
      },
      BINDING,
    );
  }
  assert.deepEqual(FIXED_RESIDUE_PACKAGES, [
    "dev.aiauto.android",
    "dev.aiauto.fixture",
    "dev.aiauto.webfixture",
  ]);
  assert.deepEqual(inspected[0][1].packages, FIXED_RESIDUE_PACKAGES);
});

test("typed providers 的非零 App Bridge service process 结果不会被清零", async (currentTest) => {
  const fixture = await createFixture({
    counts: {
      appData: 1,
      bridgeSessions: 2,
      testServices: 3,
      ownedProcesses: 4,
    },
  });
  currentTest.after(fixture.cleanup);
  await fixture.adapter.probe(PROFILE);

  for (const [kind, count] of [
    ["appData", 1],
    ["bridgeSessions", 2],
    ["testServices", 3],
    ["ownedProcesses", 4],
  ]) {
    assert.equal((await fixture.adapter[kind](BINDING)).count, count);
  }
});

test("固定 staging/report 截图与日志和 N31 日志按普通文件真实计数", async (currentTest) => {
  const fixture = await createFixture();
  currentTest.after(fixture.cleanup);
  await writeBoundArtifacts(fixture.stateRoot, "staging", {
    screenshots: 2,
    logs: 1,
  });
  await writeBoundArtifacts(fixture.stateRoot, "reports", {
    screenshots: 1,
    logs: 2,
  });
  await writeStaleN31State(fixture.stateRoot);
  await fixture.adapter.probe(PROFILE);

  assert.equal((await fixture.adapter.screenshots(BINDING)).count, 3);
  assert.equal((await fixture.adapter.logs(BINDING)).count, 4);
});

test("stop 已删除 runtime 时固定 N31 log 仍按非零残留计数", async (currentTest) => {
  const fixture = await createFixture();
  currentTest.after(fixture.cleanup);
  const logFile = path.join(
    fixture.stateRoot,
    "logs",
    `${N31_PROFILE.avdName}-${BINDING.serial}.log`,
  );
  await mkdir(path.dirname(logFile), { recursive: true });
  await writeFile(logFile, "stopped emulator log\n", { mode: 0o600 });
  await fixture.adapter.probe(PROFILE);

  assert.equal((await fixture.adapter.logs(BINDING)).count, 1);
});

test("stale N31 runtime、双 lease 与 typed process 均报告非零", async (currentTest) => {
  const fixture = await createFixture({
    counts: { ownedProcesses: 1 },
  });
  currentTest.after(fixture.cleanup);
  await writeStaleN31State(fixture.stateRoot);
  await fixture.adapter.probe(PROFILE);

  assert.equal((await fixture.adapter.runtimeFiles(BINDING)).count, 1);
  assert.equal((await fixture.adapter.leases(BINDING)).count, 2);
  assert.equal((await fixture.adapter.ownedProcesses(BINDING)).count, 1);
});

test("缺失任一 typed capability 时 probe 稳定 unavailable 且不执行检查", async (currentTest) => {
  let inspections = 0;
  const providers = {
    ...typedProviders({}, {
      onInspect: () => {
        inspections += 1;
      },
    }),
  };
  delete providers.testServices;
  const fixture = await createFixture({ providers });
  currentTest.after(fixture.cleanup);

  await assert.rejects(
    () => fixture.adapter.probe(PROFILE),
    assertCode("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE"),
  );
  assert.equal(inspections, 0);
});

test("staging、artifact 或 N31 状态 symlink 一律失败关闭且不遍历目标", async (currentTest) => {
  for (const target of ["staging-root", "screenshot", "runtime"]) {
    await currentTest.test(target, async () => {
      const fixture = await createFixture();
      try {
        const outside = await mkdtemp(path.join(os.tmpdir(), "n52-outside-"));
        try {
          const sentinel = path.join(outside, "sentinel.png");
          await writeFile(sentinel, "outside", { mode: 0o600 });
          if (target === "staging-root") {
            await mkdir(path.join(fixture.stateRoot, "staging"), {
              recursive: true,
            });
            await symlink(
              outside,
              path.join(
                fixture.stateRoot,
                "staging",
                "n52-production-matrix",
              ),
              directoryLinkType,
            );
          } else if (target === "screenshot") {
            await writeBoundArtifacts(fixture.stateRoot, "staging");
            const screenshotRoot = path.join(
              fixture.stateRoot,
              "staging",
              "n52-production-matrix",
              BINDING.profileId,
              "screenshots",
            );
            await mkdir(screenshotRoot, { recursive: true });
            await symlink(sentinel, path.join(screenshotRoot, "linked.png"));
          } else {
            await mkdir(path.join(fixture.stateRoot, "runtime"), {
              recursive: true,
            });
            await symlink(
              sentinel,
              path.join(
                fixture.stateRoot,
                "runtime",
                `${N31_PROFILE.avdName}.json`,
              ),
            );
          }
          await fixture.adapter.probe(PROFILE);
          const inspect = target === "runtime"
            ? fixture.adapter.runtimeFiles
            : fixture.adapter.screenshots;
          await assert.rejects(
            () => inspect(BINDING),
            assertCode("PRODUCTION_RESIDUE_INSPECTION_FAILED"),
          );
          assert.equal(await readFile(sentinel, "utf8"), "outside");
        } finally {
          await rm(outside, { recursive: true, force: true });
        }
      } finally {
        await fixture.cleanup();
      }
    });
  }
});

test("runtime、lease 与 artifact binding 漂移不会被计为当前设备残留", async (currentTest) => {
  for (const drift of ["runtime", "lease", "artifact"]) {
    await currentTest.test(drift, async () => {
      const fixture = await createFixture();
      try {
        if (drift === "artifact") {
          await writeBoundArtifacts(fixture.stateRoot, "reports", {
            screenshots: 1,
          });
          await writeJson(
            path.join(
              fixture.stateRoot,
              "reports",
              "n52-production-matrix",
              BINDING.profileId,
              "binding.json",
            ),
            bindingDocument({ deviceFingerprint: "b".repeat(64) }),
          );
        } else {
          await writeStaleN31State(fixture.stateRoot);
          if (drift === "runtime") {
            const runtimeFile = path.join(
              fixture.stateRoot,
              "runtime",
              `${N31_PROFILE.avdName}.json`,
            );
            const runtime = JSON.parse(await readFile(runtimeFile, "utf8"));
            runtime.deviceFingerprint = "b".repeat(64);
            await writeJson(runtimeFile, runtime);
          } else {
            const ownerFile = path.join(
              fixture.stateRoot,
              "locks",
              "port",
              "5554.lock",
              "owner.json",
            );
            const owner = JSON.parse(await readFile(ownerFile, "utf8"));
            owner.serial = "emulator-5556";
            await writeJson(ownerFile, owner);
          }
        }
        await fixture.adapter.probe(PROFILE);
        const inspect = drift === "artifact"
          ? fixture.adapter.screenshots
          : drift === "runtime"
            ? fixture.adapter.runtimeFiles
            : fixture.adapter.leases;
        await assert.rejects(
          () => inspect(BINDING),
          assertCode("PRODUCTION_BINDING_DRIFT"),
        );
      } finally {
        await fixture.cleanup();
      }
    });
  }
});

test("typed provider 回显 fingerprint 漂移由 inspector 拒绝", async (currentTest) => {
  const fixture = await createFixture({
    providers: typedProviders({}, {
      results: {
        bridgeSessions: { deviceFingerprint: "b".repeat(64) },
      },
    }),
  });
  currentTest.after(fixture.cleanup);
  await fixture.adapter.probe(PROFILE);

  await assert.rejects(
    () => fixture.adapter.bridgeSessions(BINDING),
    assertCode("PRODUCTION_BINDING_DRIFT"),
  );
});

test("目录检查超过固定条目预算时稳定失败而不是截断为零", async (currentTest) => {
  const fixture = await createFixture();
  currentTest.after(fixture.cleanup);
  await writeBoundArtifacts(fixture.stateRoot, "staging");
  const screenshotRoot = path.join(
    fixture.stateRoot,
    "staging",
    "n52-production-matrix",
    BINDING.profileId,
    "screenshots",
  );
  await mkdir(screenshotRoot, { recursive: true });
  await Promise.all(
    Array.from({ length: 257 }, (_, index) =>
      writeFile(path.join(screenshotRoot, `${index}.png`), "x", {
        mode: 0o600,
      })),
  );
  await fixture.adapter.probe(PROFILE);

  await assert.rejects(
    () => fixture.adapter.screenshots(BINDING),
    assertCode("PRODUCTION_RESIDUE_INSPECTION_FAILED"),
  );
});

test("lstat 前后目录 identity 变化时检查失败关闭", async (currentTest) => {
  const realFilesystem = {
    lstat,
    open,
    readdir,
    realpath,
  };
  let profileStats = 0;
  let profileRoot;
  const filesystem = {
    ...realFilesystem,
    lstat: async (candidate) => {
      const stats = await lstat(candidate);
      if (candidate === profileRoot) {
        profileStats += 1;
        if (profileStats > 1) {
          return new Proxy(stats, {
            get: (target, property) =>
              property === "ino" ? Number(target.ino) + 1 : target[property],
          });
        }
      }
      return stats;
    },
  };
  const fixture = await createFixture({ filesystem });
  currentTest.after(fixture.cleanup);
  profileRoot = path.join(
    fixture.stateRoot,
    "staging",
    "n52-production-matrix",
    BINDING.profileId,
  );
  await writeBoundArtifacts(fixture.stateRoot, "staging", {
    screenshots: 1,
  });
  await fixture.adapter.probe(PROFILE);

  await assert.rejects(
    () => fixture.adapter.screenshots(BINDING),
    assertCode("PRODUCTION_RESIDUE_INSPECTION_FAILED"),
  );
});

test("owned process typed probe 期间 runtime 身份变化时失败关闭", async (currentTest) => {
  let runtimeFile;
  const providers = typedProviders({ ownedProcesses: 1 }, {
    onInspect: async (kind) => {
      if (kind !== "ownedProcesses") return;
      const runtime = JSON.parse(await readFile(runtimeFile, "utf8"));
      runtime.pid += 1;
      runtime.lease.emulatorPid += 1;
      await writeJson(runtimeFile, runtime);
    },
  });
  const fixture = await createFixture({ providers });
  currentTest.after(fixture.cleanup);
  await writeStaleN31State(fixture.stateRoot);
  runtimeFile = path.join(
    fixture.stateRoot,
    "runtime",
    `${N31_PROFILE.avdName}.json`,
  );
  await fixture.adapter.probe(PROFILE);

  await assert.rejects(
    () => fixture.adapter.ownedProcesses(BINDING),
    assertCode("PRODUCTION_RESIDUE_INSPECTION_FAILED"),
  );
});

test("未 probe、任意 serial 和 clean snapshot 漂移均拒绝检查", async (currentTest) => {
  const fixture = await createFixture();
  currentTest.after(fixture.cleanup);

  await assert.rejects(
    () => fixture.adapter.logs(BINDING),
    assertCode("PRODUCTION_RESIDUE_PROVIDER_UNAVAILABLE"),
  );
  await fixture.adapter.probe(PROFILE);
  for (const binding of [
    { ...BINDING, serial: "device-user-owned" },
    { ...BINDING, snapshot: "dirty" },
  ]) {
    await assert.rejects(
      () => fixture.adapter.logs(binding),
      assertCode("PRODUCTION_BINDING_DRIFT"),
    );
  }
});
