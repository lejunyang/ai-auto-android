/**
 * 脚本用途：在指定 API 的一次性模拟器上运行动作面与既有能力的设备测试矩阵，
 * 并把每个测试类在该 API 上的通过/失败结果汇总为可回写文档的 JSON。
 *
 * 为什么需要这个脚本：多 API 矩阵要在每个目标上重复「预置无障碍 → 安装 APK →
 * 逐类跑测试 → 收集结果」这一串步骤。手工执行既容易漏步骤，也无法保证各 API
 * 的前置条件一致——而前置条件不一致时，跨 API 的结果差异就无法归因到平台差异。
 *
 * 授权边界（AGENTS.md）：只允许在 emulator- 前缀的 serial 上运行。真机的无障碍
 * 授权与安装确认必须由用户亲自完成，脚本不得绕过。
 */

import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

/** 需要无障碍服务的测试类：跑之前必须确认服务已绑定。 */
const ACCESSIBILITY_CLASSES = [
  "dev.aiauto.android.accessibility.ActionSurfaceDeviceTest",
  "dev.aiauto.android.accessibility.ActionSurfaceNodeActionDeviceTest",
  "dev.aiauto.android.accessibility.ActionSurfaceRemainingDeviceTest",
  "dev.aiauto.android.accessibility.GlobalActionDeviceTest",
  "dev.aiauto.android.accessibility.TransientMessageDeviceTest",
];

/**
 * 不需要无障碍服务的测试类。
 *
 * 单独成组是因为它们的失败原因与无障碍无关，混在一起会让「服务没绑上」
 * 这一类环境问题污染其余结论。
 */
const PLAIN_CLASSES = [
  "dev.aiauto.android.MainActivityLaunchModeInstrumentedTest",
  "dev.aiauto.android.automation.recording.AndroidScriptEnvironmentProviderDeviceTest",
  "dev.aiauto.android.ui.recording.RecordingScreensInstrumentedTest",
  "dev.aiauto.android.ui.recording.RecordingEditorScreenInstrumentedTest",
];

const ACCESSIBILITY_SERVICE =
  "dev.aiauto.android/dev.aiauto.android.accessibility.AiAutomationAccessibilityService";
const TEST_SERVICE =
  "dev.aiauto.android/dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService";
const APP_PACKAGE = "dev.aiauto.android";
const RUNNER = "dev.aiauto.android.test/androidx.test.runner.AndroidJUnitRunner";

const adbPath = () => {
  const home = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  if (!home) throw new Error("ANDROID_HOME is not set");
  return path.join(home, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
};

const adb = (serial, args, timeoutMs = 300_000) => {
  const result = spawnSync(adbPath(), ["-s", serial, ...args], {
    encoding: "utf8",
    timeout: timeoutMs,
    maxBuffer: 64 * 1024 * 1024,
  });
  return `${result.stdout ?? ""}${result.stderr ?? ""}`;
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 拒绝在真机上运行：真机授权必须由用户完成。 */
const requireEmulator = (serial) => {
  if (!serial.startsWith("emulator-")) {
    throw new Error(
      `Refusing to run the matrix on ${serial}: only disposable emulators may be ` +
        "provisioned automatically.",
    );
  }
};

/**
 * 启用两个无障碍服务并等待真正 bind。
 *
 * 判据取 dumpsys 的 Bound services 而非 settings 的值：写入 settings 只是改了
 * 一个字符串，服务是否 bind 是异步结果。另外重装 APK 会把包置为 enabled=0
 * 并重置 accessibility_enabled（上一轮实测踩到），因此每次都先 pm enable。
 */
const provisionAccessibility = async (serial) => {
  adb(serial, ["shell", "pm", "enable", APP_PACKAGE]);
  // 清空必须用 `settings delete` 而非 `put ""`：API 35 上传空串会被判为
  // "Bad arguments" 而静默不生效，导致旧值残留、服务停在 Crashed 状态。
  adb(serial, ["shell", "settings", "delete", "secure", "enabled_accessibility_services"]);
  adb(serial, ["shell", "settings", "put", "secure", "accessibility_enabled", "0"]);
  await sleep(2500);
  adb(serial, [
    "shell",
    "settings",
    "put",
    "secure",
    "enabled_accessibility_services",
    `${ACCESSIBILITY_SERVICE}:${TEST_SERVICE}`,
  ]);
  adb(serial, ["shell", "settings", "put", "secure", "accessibility_enabled", "1"]);

  for (let attempt = 0; attempt < 10; attempt += 1) {
    await sleep(2000);
    const dump = adb(serial, ["shell", "dumpsys", "accessibility"]);
    const bound = /Bound services:\{([^}]*)/.exec(dump)?.[1] ?? "";
    if (bound.includes("Service[")) {
      const crashed = (/Crashed services:\{([^}]*)\}/.exec(dump)?.[1] ?? "").trim();
      return { bound: true, crashed: crashed.length > 0 };
    }
  }
  return { bound: false, crashed: false };
};

/**
 * 解析 instrumentation 输出。
 *
 * 只认 `OK (n tests)` 与 `Tests run: n, Failures: m` 两种终局形式：
 * 其余中间输出（包括进度点）不足以判断结果，据此判断会把未跑完当成通过。
 */
const parseResult = (output) => {
  const ok = /OK \((\d+) tests?\)/.exec(output);
  if (ok) return { status: "pass", tests: Number(ok[1]), failures: 0 };
  const ran = /Tests run: (\d+),\s+Failures: (\d+)/.exec(output);
  if (ran) {
    return { status: "fail", tests: Number(ran[1]), failures: Number(ran[2]) };
  }
  if (/Process crashed|INSTRUMENTATION_ABORTED|Unable to find instrumentation/.test(output)) {
    return { status: "error", tests: 0, failures: 0 };
  }
  return { status: "unknown", tests: 0, failures: 0 };
};

/** 提取失败原因的首行，供文档如实记录而不是只写「失败」。 */
const firstFailure = (output) => {
  const match = /(?:java\.lang\.\w+(?:Error|Exception)|AssertionFailedError)[^\n]*/.exec(output);
  return match ? match[0].slice(0, 300) : "";
};

const runClass = (serial, className, extraArgs) => {
  const output = adb(
    serial,
    ["shell", "am", "instrument", "-w", ...extraArgs, "-e", "class", className, RUNNER],
    900_000,
  );
  return { className, ...parseResult(output), failure: firstFailure(output) };
};

const main = async () => {
  const serial = process.argv[2];
  const apkDir = process.argv[3] ?? "android/app/build/outputs/apk";
  if (!serial) {
    console.error(
      "Usage: node scripts/emulator/action-surface-matrix.mjs <serial> [apkDir]",
    );
    process.exitCode = 2;
    return;
  }
  requireEmulator(serial);

  const apiLevel = adb(serial, ["shell", "getprop", "ro.build.version.sdk"]).trim();
  const release = adb(serial, ["shell", "getprop", "ro.build.version.release"]).trim();
  const screen = adb(serial, ["shell", "wm", "size"]).trim();

  const appApk = path.join(apkDir, "debug", "app-debug.apk");
  const testApk = path.join(apkDir, "androidTest", "debug", "app-debug-androidTest.apk");
  for (const apk of [appApk, testApk]) {
    if (!existsSync(apk)) {
      console.error(`missing apk: ${apk}`);
      process.exitCode = 2;
      return;
    }
  }
  adb(serial, ["install", "-r", "-t", appApk], 600_000);
  adb(serial, ["install", "-r", "-t", testApk], 600_000);

  const provisioning = await provisionAccessibility(serial);

  const results = [];
  for (const className of PLAIN_CLASSES) {
    results.push(runClass(serial, className, []));
  }
  // 无障碍类测试统一传 manualAccessibility=true：这些用例以此门控，
  // 不传会全部 skip 而报出「通过」，那是假证据。
  for (const className of ACCESSIBILITY_CLASSES) {
    results.push(
      runClass(serial, className, ["-e", "manualAccessibility", "true"]),
    );
  }

  // 清理：卸载测试 APK 但保留应用，便于失败后人工复查设备状态。
  adb(serial, ["uninstall", "dev.aiauto.android.test"]);

  console.log(
    JSON.stringify(
      {
        serial,
        apiLevel: Number(apiLevel),
        release,
        screen,
        provisioning,
        results,
        totals: {
          classes: results.length,
          passed: results.filter((entry) => entry.status === "pass").length,
          tests: results.reduce((sum, entry) => sum + entry.tests, 0),
          failures: results.reduce((sum, entry) => sum + entry.failures, 0),
        },
      },
      null,
      2,
    ),
  );
};

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
