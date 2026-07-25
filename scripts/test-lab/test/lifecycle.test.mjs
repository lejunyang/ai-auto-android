// 测试用途：验证 verified descriptor 默认不安装，并约束 clean AVD、清理与恢复失败关闭。
import assert from "node:assert/strict";
import {
  mkdir,
  mkdtemp,
  readFile,
  rename,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { ExternalAppError } from "../src/manifest.mjs";
import {
  createAdbInstallArgv,
  runVerifiedExternalAppLifecycle,
} from "../src/lifecycle.mjs";
import { verifyExternalAppArtifact } from "../src/verifier.mjs";
import {
  baseManifest,
  fakeInspector,
  writeCacheArtifact,
} from "./helpers.mjs";

const root = await mkdtemp(path.join(os.tmpdir(), "n46-lifecycle-"));
const repositoryRoot = path.join(root, "repository");
const cacheRoot = path.join(root, "external-cache");
await mkdir(repositoryRoot);
await mkdir(cacheRoot);
await writeCacheArtifact(cacheRoot);
const descriptor = await verifyExternalAppArtifact({
  manifest: baseManifest(),
  cacheRoot,
  repositoryRoot,
  inspector: fakeInspector(),
});

const rejectsCode = async (operation, code) => {
  await assert.rejects(operation, (error) => {
    assert.ok(error instanceof ExternalAppError);
    assert.equal(error.code, code);
    return true;
  });
};

const fakeRunner = (failAt = null) => {
  const calls = [];
  const call = async (name, value = true) => {
    calls.push(name);
    if (failAt === name) throw new Error(`private-${name}-details`);
    return value;
  };
  return {
    calls,
    restoreCleanSnapshot: async () => call("restoreCleanSnapshot"),
    installVerified: async () => call("installVerified"),
    inspectInstalled: async () => {
      await call("inspectInstalled");
      return {
        package: descriptor.package,
        version: descriptor.version,
        versionCode: descriptor.versionCode,
        signingCertificateSha256: descriptor.signingCertificateSha256,
      };
    },
    clearAppData: async () => call("clearAppData"),
    restoreFinalSnapshot: async () => call("restoreFinalSnapshot"),
  };
};

test("默认无 runner 时只返回 verified descriptor 且不安装", async () => {
  const result = await runVerifiedExternalAppLifecycle({
    descriptor,
    serial: "emulator-5554",
    snapshot: "clean",
  });
  assert.deepEqual(result, {
    status: "verified-only",
    installed: false,
    descriptor,
  });
});

test("固定 argv provider 只接受明确 serial 和 verified descriptor", () => {
  assert.deepEqual(
    createAdbInstallArgv({
      serial: "emulator-5554",
      descriptor,
    }),
    [
      "-s",
      "emulator-5554",
      "install",
      "--no-streaming",
      descriptor.artifactPath,
    ],
  );
  assert.throws(
    () => createAdbInstallArgv({
      serial: "emulator-5554; shell id",
      descriptor,
    }),
    (error) => error.code === "RUNNER_INPUT_INVALID",
  );
  assert.throws(
    () => createAdbInstallArgv({
      serial: "emulator-5554",
      descriptor: { ...descriptor, kind: "unverified" },
    }),
    (error) => error.code === "DESCRIPTOR_INVALID",
  );
});

test("手工构造或复制的同形 descriptor 不能触发安装", async () => {
  const runner = fakeRunner();
  await rejectsCode(
    () => runVerifiedExternalAppLifecycle({
      descriptor: { ...descriptor },
      serial: "emulator-5554",
      snapshot: "clean",
      runner,
    }),
    "DESCRIPTOR_INVALID",
  );
  assert.deepEqual(runner.calls, []);
});

test("clean snapshot 前置失败时不安装也不运行清理", async () => {
  const runner = fakeRunner("restoreCleanSnapshot");
  await rejectsCode(
    () => runVerifiedExternalAppLifecycle({
      descriptor,
      serial: "emulator-5554",
      snapshot: "clean",
      runner,
    }),
    "CLEAN_SNAPSHOT_RESTORE_FAILED",
  );
  assert.deepEqual(runner.calls, ["restoreCleanSnapshot"]);
});

test("descriptor 签发后 cache 被替换时在安装调用前失败", async () => {
  const isolatedRoot = await mkdtemp(path.join(os.tmpdir(), "n46-stale-"));
  const isolatedRepository = path.join(isolatedRoot, "repository");
  const isolatedCache = path.join(isolatedRoot, "cache");
  await mkdir(isolatedRepository);
  await mkdir(isolatedCache);
  const artifactPath = await writeCacheArtifact(isolatedCache);
  const staleDescriptor = await verifyExternalAppArtifact({
    manifest: baseManifest(),
    cacheRoot: isolatedCache,
    repositoryRoot: isolatedRepository,
    inspector: fakeInspector(),
  });
  await writeFile(
    artifactPath,
    Buffer.alloc(staleDescriptor.sizeBytes, 0x78),
  );
  const runner = fakeRunner();
  await rejectsCode(
    () => runVerifiedExternalAppLifecycle({
      descriptor: staleDescriptor,
      serial: "emulator-5554",
      snapshot: "clean",
      runner,
    }),
    "ARTIFACT_HASH_MISMATCH",
  );
  assert.deepEqual(runner.calls, [
    "restoreCleanSnapshot",
    "restoreFinalSnapshot",
  ]);
});

test("相同字节原子替换 artifact identity 时安装调用仍为零", async () => {
  const isolatedRoot = await mkdtemp(path.join(os.tmpdir(), "n46-identity-"));
  const isolatedRepository = path.join(isolatedRoot, "repository");
  const isolatedCache = path.join(isolatedRoot, "cache");
  await mkdir(isolatedRepository);
  await mkdir(isolatedCache);
  const artifactPath = await writeCacheArtifact(isolatedCache);
  const identityDescriptor = await verifyExternalAppArtifact({
    manifest: baseManifest(),
    cacheRoot: isolatedCache,
    repositoryRoot: isolatedRepository,
    inspector: fakeInspector(),
  });
  const replacementPath = path.join(path.dirname(artifactPath), "replacement");
  await writeFile(replacementPath, await readFile(artifactPath));
  await rename(replacementPath, artifactPath);
  const runner = fakeRunner();
  await rejectsCode(
    () => runVerifiedExternalAppLifecycle({
      descriptor: identityDescriptor,
      serial: "emulator-5554",
      snapshot: "clean",
      runner,
    }),
    "CACHE_ENTRY_CHANGED",
  );
  assert.deepEqual(runner.calls, [
    "restoreCleanSnapshot",
    "restoreFinalSnapshot",
  ]);
});

test("fake runner 只能通过类型化端口安装并在结束时清理恢复", async () => {
  const runner = fakeRunner();
  const result = await runVerifiedExternalAppLifecycle({
    descriptor,
    serial: "emulator-5554",
    snapshot: "clean",
    runner,
    scenario: async ({ installed }) => {
      assert.equal(installed.package, descriptor.package);
      return { status: "passed" };
    },
  });
  assert.deepEqual(result, {
    status: "completed",
    installed: true,
    descriptor,
    scenarioResult: { status: "passed" },
  });
  assert.deepEqual(runner.calls, [
    "restoreCleanSnapshot",
    "installVerified",
    "inspectInstalled",
    "clearAppData",
    "restoreFinalSnapshot",
  ]);
});

test("安装后 package、version 或签名不匹配时仍执行最终清理", async () => {
  const runner = fakeRunner();
  runner.inspectInstalled = async () => {
    runner.calls.push("inspectInstalled");
    return {
      package: descriptor.package,
      version: "wrong",
      versionCode: descriptor.versionCode,
      signingCertificateSha256: descriptor.signingCertificateSha256,
    };
  };
  await rejectsCode(
    () => runVerifiedExternalAppLifecycle({
      descriptor,
      serial: "emulator-5554",
      snapshot: "clean",
      runner,
    }),
    "INSTALLED_METADATA_MISMATCH",
  );
  assert.deepEqual(runner.calls.slice(-2), [
    "clearAppData",
    "restoreFinalSnapshot",
  ]);
});

test("App 数据清理或最终 snapshot 恢复失败均失败关闭", async () => {
  for (const [failAt, code] of [
    ["clearAppData", "APP_DATA_CLEAR_FAILED"],
    ["restoreFinalSnapshot", "FINAL_SNAPSHOT_RESTORE_FAILED"],
  ]) {
    const runner = fakeRunner(failAt);
    await rejectsCode(
      () => runVerifiedExternalAppLifecycle({
        descriptor,
        serial: "emulator-5554",
        snapshot: "clean",
        runner,
      }),
      code,
    );
    assert.ok(runner.calls.includes("restoreFinalSnapshot"));
  }
});

test("runner 对象缺失类型化端口时拒绝执行", async () => {
  await rejectsCode(
    () => runVerifiedExternalAppLifecycle({
      descriptor,
      serial: "emulator-5554",
      snapshot: "clean",
      runner: { installVerified: async () => true },
    }),
    "RUNNER_INVALID",
  );
});
