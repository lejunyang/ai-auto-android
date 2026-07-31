// 测试用途：验证五包矩阵固定顺序、单 emulator 生命周期、失败清理和无敏感报告。
import assert from "node:assert/strict";
import test from "node:test";

import {
  parsePassiveSmokeArguments,
  passiveSmokeEnvironment,
  runPassiveSmokeMatrix,
} from "../src/passive-smoke-cli.mjs";

const root = "/Volumes/aigo S7 Media/SDK/android-tools";
const environment = Object.freeze({
  ANDROID_SDK_ROOT: `${root}/android-sdk`,
  ANDROID_AVD_HOME: `${root}/android-avd`,
  JAVA_HOME: `${root}/jdk/Contents/Home`,
  AACTL_EMULATOR_STATE: `${root}/emulator-state`,
  AACTL_ANDROID_APK_CACHE: `${root}/android-apk-cache`,
  GOCACHE: `${root}/go-cache`,
  GOMODCACHE: `${root}/go-mod-cache`,
});
const packages = Object.freeze([
  "tv.danmaku.bili",
  "com.ss.android.ugc.aweme",
  "com.max.xiaoheihe",
  "com.tencent.mm",
  "com.eg.android.AlipayGphone",
]);

const fixture = (
  failPackage = null,
  stopRetainedOnce = false,
  failCode = null,
) => {
  const calls = [];
  let stopCalls = 0;
  let manifestIndex = 0;
  const descriptorByPackage = new Map(packages.map((packageName, index) => [
    packageName,
    Object.freeze({
      package: packageName,
      version: `1.0.${index}`,
      versionCode: index + 1,
      sha256: String(index + 1).repeat(64),
    }),
  ]));
  const deviceEnvelope = (devices) => ({
    code: 0,
    stdout: `${JSON.stringify({
      schemaVersion: "1.0",
      requestId: "00000000-0000-4000-8000-000000000000",
      ok: true,
      data: { devices, count: devices.length },
      error: null,
      meta: { durationMs: 1 },
    })}\n`,
    stderr: "",
  });
  let online = false;
  const value = {
    loadProfiles: async () => ({
      profiles: [{
        id: "api-34",
        apiLevel: 34,
        avdName: "ai-auto-api-34",
      }],
    }),
    loadManifest: async (file) => {
      calls.push(["manifest", file]);
      const packageName = packages[manifestIndex];
      manifestIndex += 1;
      return { package: packageName };
    },
    verify: async ({ manifest }) => {
      calls.push(["verify", manifest.package]);
      return descriptorByPackage.get(manifest.package);
    },
    inspector: { inspect: async () => ({}) },
    emulator: {
      start: async () => {
        calls.push(["start"]);
        online = true;
        return { serial: "emulator-5554" };
      },
      waitForBoot: async () => {
        calls.push(["wait"]);
        return {
          serial: "emulator-5554",
          profileId: "api-34",
          state: "booted",
          deviceFingerprint: "a".repeat(64),
        };
      },
      restore: async () => {
        calls.push(["restore"]);
        return {
          serial: "emulator-5554",
          profileId: "api-34",
          deviceFingerprint: "a".repeat(64),
        };
      },
      stop: async () => {
        calls.push(["stop"]);
        stopCalls += 1;
        if (stopRetainedOnce && stopCalls === 1) {
          const error = new Error("retained");
          error.code = "STOP_FAILED_LOCK_RETAINED";
          throw error;
        }
        online = false;
      },
    },
    command: async (executable, args) => {
      calls.push(["command", executable, [...args]]);
      if (args[0] === "devices") {
        return deviceEnvelope(online ? [{
          serial: "emulator-5554",
          state: "device",
          transport: "emulator",
        }] : []);
      }
      if (executable === "go") return { code: 0, stdout: "", stderr: "" };
      throw new Error("unexpected command");
    },
    createRunner: ({ serial }) => {
      calls.push(["runner", serial]);
      return Object.freeze({ serial });
    },
    createScenario: ({ targetPackage }) => {
      calls.push(["scenario", targetPackage]);
      return async () => ({ targetPackageVisible: true });
    },
    lifecycle: async ({ descriptor, serial, runner, scenario }) => {
      calls.push(["lifecycle", descriptor.package, serial, runner.serial]);
      if (descriptor.package === failPackage) {
        const error = new Error("private failure");
        error.code = failCode
          ?? (failPackage === "tv.danmaku.bili"
            ? "SCENARIO_FAILED"
            : "VERIFIED_INSTALL_FAILED");
        throw error;
      }
      return {
        installed: true,
        scenarioResult: await scenario(),
      };
    },
    createTemporaryRoot: async () => "/private/tmp/passive-smoke-test",
    removeTemporaryRoot: async (temporary) => calls.push(["remove", temporary]),
    persistReport: async (report) => calls.push(["report", report]),
    persistFailure: async (report) => calls.push(["failure", report]),
  };
  return { calls, value };
};

test("CLI 只接受一个固定 profile 参数和统一外置环境", () => {
  assert.deepEqual(
    parsePassiveSmokeArguments(["--profile", "api-34"]),
    { profile: "api-34" },
  );
  for (const argv of [
    [],
    ["--profile", "api-35"],
    ["--profile", "api-34", "--manifest", "unsafe.json"],
    ["--serial", "emulator-5554"],
  ]) {
    assert.throws(
      () => parsePassiveSmokeArguments(argv),
      (error) => error.code === "USAGE_ERROR",
    );
  }
  assert.equal(passiveSmokeEnvironment(environment).cacheRoot, `${root}/android-apk-cache`);
  assert.throws(
    () => passiveSmokeEnvironment({
      ...environment,
      AACTL_ANDROID_APK_CACHE: "/tmp/cache",
    }),
    (error) => error.code === "ENVIRONMENT_INVALID",
  );
});

test("五个固定 manifest 使用一台明确 emulator 串行运行并输出无敏感报告", async () => {
  const { calls, value } = fixture();
  const report = await runPassiveSmokeMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });

  assert.deepEqual(report.apps.map((app) => app.package), packages);
  assert.equal(calls.filter(([name]) => name === "start").length, 1);
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
  assert.deepEqual(
    calls.filter(([name]) => name === "lifecycle").map(([, packageName]) => packageName),
    packages,
  );
  assert.equal(calls.filter(([name]) => name === "report").length, 1);
  const serialized = JSON.stringify(report);
  for (const forbidden of ["xml", "text", "content-desc", "bounds", "screenshot"]) {
    assert.equal(serialized.includes(forbidden), false);
  }
  assert.deepEqual(calls.at(-1), ["report", report]);
});

test("中途失败停止后续 App 并仍 stop、等待零设备和删除临时目录", async () => {
  const { calls, value } = fixture("com.max.xiaoheihe");
  await assert.rejects(
    () => runPassiveSmokeMatrix({
      argv: ["--profile", "api-34"],
      environment,
      dependencies: value,
    }),
    /private failure/u,
  );
  assert.deepEqual(
    calls.filter(([name]) => name === "lifecycle").map(([, packageName]) => packageName),
    packages.slice(0, 3),
  );
  assert.equal(calls.filter(([name]) => name === "stop").length, 1);
  assert.equal(calls.filter(([name]) => name === "remove").length, 1);
  assert.equal(calls.filter(([name]) => name === "report").length, 0);
  const failure = calls.find(([name]) => name === "failure");
  assert.deepEqual(failure, [
    "failure",
    {
      schemaVersion: "1.0",
      profileId: "api-34",
      apiLevel: 34,
      package: "com.max.xiaoheihe",
      stage: "install-and-inspect",
      errorCode: "VERIFIED_INSTALL_FAILED",
      completedApps: [
        { package: "tv.danmaku.bili", status: "observed" },
        { package: "com.ss.android.ugc.aweme", status: "observed" },
      ],
      cleaned: true,
    },
  ]);
  for (const forbidden of ["xml", "text", "stderr", "private failure"]) {
    assert.equal(JSON.stringify(failure).includes(forbidden), false);
  }
});

test("已安全清理的 hierarchy 不可用只分类当前包并继续其余四包", async () => {
  const { calls, value } = fixture("tv.danmaku.bili");
  const report = await runPassiveSmokeMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });
  assert.deepEqual(
    calls.filter(([name]) => name === "lifecycle").map(([, packageName]) => packageName),
    packages,
  );
  assert.deepEqual(report.apps[0].observation, {
    status: "unavailable",
    stage: "install-and-inspect",
    errorCode: "SCENARIO_FAILED",
  });
  assert.equal(report.apps.slice(1).every(
    (app) => app.observation.status === "observed",
  ), true);
  assert.equal(report.succeeded, false);
  assert.equal(calls.filter(([name]) => name === "failure").length, 0);
  assert.equal(calls.filter(([name]) => name === "report").length, 1);
});

test("stop 已下线但保留锁时只做一次 N31 固定重试", async () => {
  const { calls, value } = fixture(null, true);
  const report = await runPassiveSmokeMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });
  assert.equal(report.apps.length, 5);
  assert.equal(calls.filter(([name]) => name === "stop").length, 2);
  assert.equal(calls.filter(([name]) => name === "report").length, 1);
});

test("设备制品复核失败分类当前包并继续后续包", async () => {
  const { calls, value } = fixture(
    "com.ss.android.ugc.aweme",
    false,
    "INSTALLED_INSPECTION_FAILED",
  );
  const report = await runPassiveSmokeMatrix({
    argv: ["--profile", "api-34"],
    environment,
    dependencies: value,
  });
  assert.deepEqual(
    calls.filter(([name]) => name === "lifecycle").map(([, packageName]) => packageName),
    packages,
  );
  assert.deepEqual(report.apps[1], {
    package: "com.ss.android.ugc.aweme",
    version: "1.0.1",
    versionCode: 2,
    sha256: "2".repeat(64),
    installed: false,
    observation: {
      status: "unavailable",
      stage: "install-and-inspect",
      errorCode: "INSTALLED_INSPECTION_FAILED",
    },
  });
  assert.equal(report.apps[0].observation.status, "observed");
  assert.equal(report.apps.slice(2).every(
    (app) => app.observation.status === "observed",
  ), true);
  assert.equal(calls.filter(([name]) => name === "failure").length, 0);
});
