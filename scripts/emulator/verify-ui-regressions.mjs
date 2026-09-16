/**
 * 脚本用途：在指定 API 的模拟器上验证 B5 / B6 两个既有失败用例的修复结果。
 *
 * 为什么单独写：这两个用例都不需要无障碍服务，但都对**屏幕可用高度**敏感
 * （一个是 Lazy 列表不组合屏外内容，一个是滚动列里的目标在视口外）。屏幕相关的
 * 修复只在一个 API 上验证没有说服力——同样的布局在不同 API 上可用高度不同，
 * 正是 B5 只在 API 35 暴露的原因。
 *
 * 与 action-surface-matrix.mjs 的分工：那个脚本跑完整动作面矩阵并预置无障碍，
 * 这个只跑两个类、不动无障碍设置，因此更快且不会因服务绑定问题干扰结论。
 */

import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

/** 本轮要验证的两个类，均无 manualAccessibility 门控。 */
const TARGET_CLASSES = [
  "dev.aiauto.android.MainActivityLaunchModeInstrumentedTest",
  "dev.aiauto.android.ui.recording.RecordingEditorScreenInstrumentedTest",
];

const RUNNER = "dev.aiauto.android.test/androidx.test.runner.AndroidJUnitRunner";
const APP_PACKAGE = "dev.aiauto.android";

const adbPath = () => {
  const home = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  if (!home) throw new Error("ANDROID_HOME is not set");
  return path.join(home, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
};

const adb = (serial, args, timeoutMs = 600_000) => {
  const result = spawnSync(adbPath(), ["-s", serial, ...args], {
    encoding: "utf8",
    timeout: timeoutMs,
    maxBuffer: 64 * 1024 * 1024,
  });
  return `${result.stdout ?? ""}${result.stderr ?? ""}`;
};

/** 只认终局形式，避免把「没跑完」当成通过。 */
const parseResult = (output) => {
  const ok = /OK \((\d+) tests?\)/.exec(output);
  if (ok) return { status: "pass", tests: Number(ok[1]), failures: 0 };
  const ran = /Tests run: (\d+),\s+Failures: (\d+)/.exec(output);
  if (ran) return { status: "fail", tests: Number(ran[1]), failures: Number(ran[2]) };
  return { status: "unknown", tests: 0, failures: 0 };
};

const firstFailure = (output) => {
  const match = /java\.lang\.AssertionError[^\n]*/.exec(output);
  return match ? match[0].slice(0, 240) : "";
};

const main = () => {
  const serial = process.argv[2];
  if (!serial) {
    console.error("Usage: node scripts/emulator/verify-ui-regressions.mjs <serial>");
    process.exitCode = 2;
    return;
  }
  if (!serial.startsWith("emulator-")) {
    throw new Error(`Refusing to provision ${serial}: emulators only.`);
  }

  const apiLevel = adb(serial, ["shell", "getprop", "ro.build.version.sdk"]).trim();
  const release = adb(serial, ["shell", "getprop", "ro.build.version.release"]).trim();
  const screen = adb(serial, ["shell", "wm", "size"]).trim();

  const apkDir = "android/app/build/outputs/apk";
  const appApk = path.join(apkDir, "debug", "app-debug.apk");
  const testApk = path.join(apkDir, "androidTest", "debug", "app-debug-androidTest.apk");
  for (const apk of [appApk, testApk]) {
    if (!existsSync(apk)) {
      console.error(`missing apk: ${apk}`);
      process.exitCode = 2;
      return;
    }
  }
  adb(serial, ["install", "-r", "-t", appApk]);
  adb(serial, ["install", "-r", "-t", testApk]);
  // 重装会把包置为 enabled=0，先恢复再跑（见 docs 平台事实 F8）。
  adb(serial, ["shell", "pm", "enable", APP_PACKAGE]);

  const results = TARGET_CLASSES.map((className) => {
    const output = adb(serial, [
      "shell",
      "am",
      "instrument",
      "-w",
      "-e",
      "class",
      className,
      RUNNER,
    ]);
    return { className, ...parseResult(output), failure: firstFailure(output) };
  });

  adb(serial, ["uninstall", "dev.aiauto.android.test"]);

  console.log(
    JSON.stringify(
      {
        serial,
        apiLevel: Number(apiLevel),
        release,
        screen,
        results,
        allPassed: results.every((entry) => entry.status === "pass"),
      },
      null,
      2,
    ),
  );
};

try {
  main();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
