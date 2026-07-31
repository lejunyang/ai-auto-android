// 测试用途：验证 verified install CLI 只接受固定 manifest/profile，并在成功失败时停止 owned emulator。
import assert from "node:assert/strict";
import test from "node:test";

import { runVerifiedInstall } from "../src/verified-install-cli.mjs";

const environment = Object.freeze({
  ANDROID_SDK_ROOT: "/Volumes/aigo S7 Media/SDK/android-tools/android-sdk",
  ANDROID_AVD_HOME: "/Volumes/aigo S7 Media/SDK/android-tools/android-avd",
  JAVA_HOME:
    "/Volumes/aigo S7 Media/SDK/android-tools/jdk-temurin-21.0.7+6/Contents/Home",
  AACTL_EMULATOR_STATE:
    "/Volumes/aigo S7 Media/SDK/android-tools/emulator-state",
  AACTL_ANDROID_APK_CACHE:
    "/Volumes/aigo S7 Media/SDK/android-tools/android-apk-cache",
});
const descriptor = Object.freeze({
  package: "com.example.fixture",
  version: "1.2.3",
  versionCode: 123,
  sha256: "a".repeat(64),
});

const dependencies = (failAt = null) => {
  const calls = [];
  const call = async (name, value) => {
    calls.push(name);
    if (failAt === name) throw Object.assign(new Error(name), { code: name });
    return value;
  };
  return {
    calls,
    value: {
      loadProfiles: async () => call("loadProfiles", { schemaVersion: "1.0" }),
      loadManifest: async () => call("loadManifest", { package: descriptor.package }),
      inspector: { inspect: async () => ({}) },
      verify: async () => call("verify", descriptor),
      emulator: {
        start: async () => call("start", {
          profileId: "api-30",
          serial: "emulator-5554",
          state: "starting",
        }),
        waitForBoot: async () => call("waitForBoot", {
          profileId: "api-30",
          serial: "emulator-5554",
          deviceFingerprint: "b".repeat(64),
          state: "booted",
        }),
        restore: async () => call("restore", {
          profileId: "api-30",
          serial: "emulator-5554",
          deviceFingerprint: "b".repeat(64),
          state: "booted",
        }),
        stop: async () => call("stop", {
          profileId: "api-30",
          serial: "emulator-5554",
          state: "stopped",
        }),
      },
      command: async (_executable, args) => {
        if (args[2] === "install") {
          return call("install", {
            code: 0,
            stdout: "Success\n",
            stderr: "",
            timedOut: false,
          });
        }
        if (args[2] === "shell" && args[4] === "path") {
          return call("path", {
            code: 0,
            stdout: "package:/data/app/example/base.apk\n",
            stderr: "",
            timedOut: false,
          });
        }
        if (args[2] === "pull") {
          throw new Error("pull must be covered by the lower-level runner tests");
        }
        throw new Error("unexpected command");
      },
    },
  };
};

test("CLI rejects arbitrary paths serial and incomplete external environment", async () => {
  for (const argv of [
    ["--manifest", "../unsafe.json", "--profile", "api-30"],
    ["--manifest", "safe.json", "--profile", "api-31"],
    ["--manifest", "safe.json", "--profile", "api-30", "--serial", "emulator-5554"],
  ]) {
    await assert.rejects(
      () => runVerifiedInstall({ argv, environment, dependencies: {} }),
      (error) => error.code === "USAGE_ERROR",
    );
  }
  await assert.rejects(
    () => runVerifiedInstall({
      argv: ["--manifest", "safe.json", "--profile", "api-30"],
      environment: { ...environment, AACTL_ANDROID_APK_CACHE: "/tmp/cache" },
      dependencies: {},
    }),
    (error) => error.code === "ENVIRONMENT_INVALID",
  );
});

test("CLI stops owned emulator when boot validation fails", async () => {
  const fixture = dependencies();
  fixture.value.emulator.waitForBoot = async () => {
    fixture.calls.push("waitForBoot");
    return {
      profileId: "api-30",
      serial: "emulator-5556",
      deviceFingerprint: "b".repeat(64),
      state: "booted",
    };
  };

  await assert.rejects(
    () => runVerifiedInstall({
      argv: ["--manifest", "safe.json", "--profile", "api-30"],
      environment,
      dependencies: fixture.value,
    }),
    (error) => error.code === "RUNNER_CONTEXT_DRIFT",
  );
  assert.deepEqual(fixture.calls, [
    "loadProfiles",
    "loadManifest",
    "verify",
    "start",
    "waitForBoot",
    "stop",
  ]);
});
