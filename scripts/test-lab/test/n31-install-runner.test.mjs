// 测试用途：验证 N31 安装 runner 只接受 verified descriptor、固定 ADB argv 和同字节设备制品。
import assert from "node:assert/strict";
import {
  copyFile,
  mkdir,
  mkdtemp,
  readdir,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { runVerifiedExternalAppLifecycle } from "../src/lifecycle.mjs";
import { ExternalAppError } from "../src/manifest.mjs";
import { createN31InstallRunner } from "../src/n31-install-runner.mjs";
import { verifyExternalAppArtifact } from "../src/verifier.mjs";
import {
  baseManifest,
  fakeInspector,
  writeCacheArtifact,
} from "./helpers.mjs";

const root = await mkdtemp(path.join(os.tmpdir(), "n46-n31-runner-"));
const repositoryRoot = path.join(root, "repository");
const cacheRoot = path.join(root, "cache");
const tempRoot = path.join(root, "temporary");
await mkdir(repositoryRoot);
await mkdir(cacheRoot);
await mkdir(tempRoot);
await writeCacheArtifact(cacheRoot);
const inspector = fakeInspector();
const descriptor = await verifyExternalAppArtifact({
  manifest: baseManifest(),
  cacheRoot,
  repositoryRoot,
  inspector,
});
const expected = Object.freeze({
  profileId: "api-30",
  serial: "emulator-5554",
  deviceFingerprint: "a".repeat(64),
});

const makeFixture = ({
  restoreResult = expected,
  packagePaths = [`package:/data/app/example/base.apk\n`],
  pullSource = descriptor.artifactPath,
  installFailure = false,
} = {}) => {
  const calls = [];
  const runner = createN31InstallRunner({
    emulatorRunner: {
      restore: async (profileId) => {
        calls.push(["restore", profileId]);
        return restoreResult;
      },
    },
    command: async (_adbPath, args, options) => {
      calls.push(["adb", [...args], options]);
      if (args[2] === "wait-for-device") {
        return { code: 0, stdout: "", stderr: "", timedOut: false };
      }
      if (
        args[2] === "shell"
        && args[3] === "pm"
        && args[4] === "path"
        && args[5] === "android"
      ) {
        return {
          code: 0,
          stdout: "package:/system/framework/framework-res.apk\n",
          stderr: "",
          timedOut: false,
        };
      }
      if (args[2] === "install") {
        if (installFailure) {
          return {
            code: 1,
            stdout: "Performing Push Install\n",
            stderr: "INSTALL_FAILED_TEST_ONLY\n",
            timedOut: false,
          };
        }
        return {
          code: 0,
          stdout: "Performing Push Install\nSuccess\n",
          stderr:
            `${args[4]}: 1 file pushed, 0 skipped. 128.5 MB/s ` +
            `(${descriptor.sizeBytes} bytes in 0.412s)\n`,
          timedOut: false,
        };
      }
      if (
        args[2] === "shell"
        && args[3] === "pm"
        && args[4] === "path"
      ) {
        return {
          code: 0,
          stdout: packagePaths.join(""),
          stderr: "",
          timedOut: false,
        };
      }
      if (args[2] === "pull") {
        await copyFile(pullSource, args[4]);
        return {
          code: 0,
          stdout: "",
          stderr: "1 file pulled\n",
          timedOut: false,
        };
      }
      if (args[2] === "shell" && args[3] === "pm" && args[4] === "clear") {
        return { code: 0, stdout: "", stderr: "Success\n", timedOut: false };
      }
      throw new Error(`unexpected argv: ${args.join(" ")}`);
    },
    adbPath: "/external-sdk/platform-tools/adb",
    inspector,
    ...expected,
    tempRoot,
  });
  return { calls, runner };
};

test("verified lifecycle uses fixed install query pull clear and restore order", async () => {
  const fixture = makeFixture();

  const result = await runVerifiedExternalAppLifecycle({
    descriptor,
    serial: expected.serial,
    snapshot: "clean",
    runner: fixture.runner,
  });

  assert.equal(result.status, "completed");
  assert.deepEqual(fixture.calls[0], ["restore", "api-30"]);
  assert.deepEqual(fixture.calls[1].slice(0, 2), [
    "adb",
    ["-s", "emulator-5554", "wait-for-device"],
  ]);
  assert.deepEqual(fixture.calls[2].slice(0, 2), [
    "adb",
    ["-s", "emulator-5554", "shell", "pm", "path", "android"],
  ]);
  assert.deepEqual(fixture.calls[3][1].slice(0, 4), [
    "-s",
    "emulator-5554",
    "install",
    "--no-streaming",
  ]);
  assert.equal(path.basename(fixture.calls[3][1][4]), "install.apk");
  assert.ok(fixture.calls[3][1][4].startsWith(`${tempRoot}${path.sep}`));
  assert.equal(fixture.calls[3][2].timeoutMs, 5 * 60 * 1000);
  assert.deepEqual(fixture.calls[4].slice(0, 2), [
    "adb",
    ["-s", "emulator-5554", "shell", "pm", "path", descriptor.package],
  ]);
  assert.deepEqual(fixture.calls[5][1].slice(0, 4), [
    "-s",
    "emulator-5554",
    "pull",
    "/data/app/example/base.apk",
  ]);
  assert.equal(path.basename(fixture.calls[5][1][4]), "device.apk");
  assert.ok(fixture.calls[5][1][4].startsWith(`${tempRoot}${path.sep}`));
  assert.equal(fixture.calls[5][2].timeoutMs, 10 * 60 * 1000);
  assert.deepEqual(fixture.calls[6].slice(0, 2), [
    "adb",
    ["-s", "emulator-5554", "shell", "pm", "clear", descriptor.package],
  ]);
  assert.deepEqual(fixture.calls[7], ["restore", "api-30"]);
  assert.deepEqual(await readdir(tempRoot), []);
});

test("context drift and non owned emulator fail before adb", async () => {
  const fixture = makeFixture({
    restoreResult: { ...expected, serial: "emulator-5556" },
  });

  await assert.rejects(
    () => runVerifiedExternalAppLifecycle({
      descriptor,
      serial: expected.serial,
      snapshot: "clean",
      runner: fixture.runner,
    }),
    (error) =>
      error instanceof ExternalAppError
      && error.code === "CLEAN_SNAPSHOT_RESTORE_FAILED",
  );
  assert.deepEqual(fixture.calls, [["restore", "api-30"]]);
});

test("split or ambiguous package path is rejected and final restore still runs", async () => {
  const fixture = makeFixture({
    packagePaths: [
      "package:/data/app/example/base.apk\n",
      "package:/data/app/example/split_config.arm64_v8a.apk\n",
    ],
  });

  await assert.rejects(
    () => runVerifiedExternalAppLifecycle({
      descriptor,
      serial: expected.serial,
      snapshot: "clean",
      runner: fixture.runner,
    }),
    (error) =>
      error instanceof ExternalAppError
      && error.code === "INSTALLED_INSPECTION_FAILED",
  );
  assert.equal(fixture.calls.filter(([kind]) => kind === "restore").length, 2);
});

test("pulled bytes mismatch fails before inspector and temporary file is removed", async () => {
  const replacement = path.join(root, "replacement");
  await writeCacheArtifact(root, "b".repeat(64), Buffer.from("wrong"));
  await copyFile(path.join(root, "sha256", "bb", "b".repeat(64), "artifact"), replacement);
  const fixture = makeFixture({ pullSource: replacement });
  const callsBefore = inspector.calls.length;

  await assert.rejects(
    () => runVerifiedExternalAppLifecycle({
      descriptor,
      serial: expected.serial,
      snapshot: "clean",
      runner: fixture.runner,
    }),
    (error) =>
      error instanceof ExternalAppError
      && error.code === "INSTALLED_INSPECTION_FAILED",
  );
  assert.equal(inspector.calls.length, callsBefore);
  assert.deepEqual(await readdir(tempRoot), []);
});

test("failed install preserves primary error and skips clear before final restore", async () => {
  const fixture = makeFixture({ installFailure: true });

  await assert.rejects(
    () => runVerifiedExternalAppLifecycle({
      descriptor,
      serial: expected.serial,
      snapshot: "clean",
      runner: fixture.runner,
    }),
    (error) =>
      error instanceof ExternalAppError
      && error.code === "VERIFIED_INSTALL_FAILED",
  );
  assert.equal(
    fixture.calls.filter(
      ([kind, args]) => kind === "adb" && args[2] === "shell" && args[4] === "clear",
    ).length,
    0,
  );
  assert.equal(fixture.calls.filter(([kind]) => kind === "restore").length, 2);
});
