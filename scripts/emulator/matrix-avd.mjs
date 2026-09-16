/**
 * 脚本用途：为跨 API 设备矩阵创建一次性 AVD，并把分辨率与密度显式设置到贴近真机
 * 的档位（默认 1080x2400 / 420dpi，与 `profiles.json` 的正式 profile 一致）。
 *
 * 为什么需要它：N31 的 runner.mjs 是分辨率设置的权威实现，但它依赖 `avdmanager`
 * 创建 AVD，而 osdk 把同一 system image 同时暴露在规范目录与
 * `~v1~<percent-encoded>` 别名目录下，`avdmanager` 的本地仓库扫描因此把该 package
 * 判为重复并最终报 `contains no system images`。这不是 profile 或 receipt 的问题
 * ——`cli.mjs verify` 在补齐真实 SHA-256 后已经通过。
 *
 * 因此本脚本只替代「创建 AVD」这一步，其余照抄 runner 的做法：写入
 * `hw.lcd.width/height/density`，启动后再用 `wm size` / `wm density` 覆盖，
 * **并回读校验实际生效值**。回读是关键——上一轮的教训是手工 AVD 沿用了模拟器
 * 默认的 320x640 而无人察觉，导致 84 次执行全部跑在一个远离真机的档位上。
 */

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

/** 贴近真机的默认档位，与 profiles.json 中正式 profile 保持一致。 */
const DEFAULT_RESOLUTION = "1080x2400";
const DEFAULT_DENSITY = 420;

const sdkRoot = () => {
  const home = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  if (!home) throw new Error("ANDROID_HOME is not set");
  return home;
};

const avdRoot = () => {
  const root = process.env.ANDROID_AVD_HOME;
  if (!root) throw new Error("ANDROID_AVD_HOME is not set");
  return root;
};

const exe = (name) => (process.platform === "win32" ? `${name}.exe` : name);
const adbPath = () => path.join(sdkRoot(), "platform-tools", exe("adb"));

const adb = (serial, args, timeoutMs = 120_000) => {
  const result = spawnSync(adbPath(), ["-s", serial, ...args], {
    encoding: "utf8",
    timeout: timeoutMs,
  });
  return `${result.stdout ?? ""}${result.stderr ?? ""}`;
};

/**
 * 以既有 AVD 的 config.ini 为模板生成新 AVD。
 *
 * 模板里的 hw.lcd.* 必须整体替换：base AVD 常常是 320x640/160dpi 这类小屏默认值，
 * 只改 width/height 而漏掉 density 会得到「高分辨率 + 低密度」的畸形组合，
 * 布局与真机差异反而更大。
 */
const createAvd = (name, apiLevel, templateAvd, resolution, density) => {
  const [width, height] = resolution.split("x");
  const target = path.join(avdRoot(), `${name}.avd`);
  if (existsSync(target)) rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });

  writeFileSync(
    path.join(avdRoot(), `${name}.ini`),
    ["avd.ini.encoding=UTF-8", `path=${target}`, `target=android-${apiLevel}`, ""].join("\n"),
    "ascii",
  );

  const template = readFileSync(
    path.join(avdRoot(), `${templateAvd}.avd`, "config.ini"),
    "utf8",
  );
  const image = path.join(
    sdkRoot(),
    "system-images",
    `android-${apiLevel}`,
    "google_apis",
    "x86_64",
  );
  const replacements = new Map([
    ["image.sysdir.1", `${image}${path.sep}`],
    ["AvdId", name],
    ["avd.ini.displayname", name],
    ["hw.lcd.width", width],
    ["hw.lcd.height", height],
    ["hw.lcd.density", String(density)],
  ]);

  const lines = template.split(/\r?\n/u);
  const seen = new Set();
  const rewritten = lines.map((line) => {
    const key = line.split("=")[0];
    if (replacements.has(key)) {
      seen.add(key);
      return `${key}=${replacements.get(key)}`;
    }
    return line;
  });
  // 模板缺失的键要补齐，否则会静默沿用模拟器默认值。
  for (const [key, value] of replacements) {
    if (!seen.has(key)) rewritten.push(`${key}=${value}`);
  }
  writeFileSync(path.join(target, "config.ini"), rewritten.join("\n"), "ascii");
};

/** 启动后强制设置并**回读校验**，不接受「设置命令返回 0」作为生效证据。 */
const applyAndVerifyScreen = (serial, resolution, density) => {
  const [width, height] = resolution.split("x");
  adb(serial, ["shell", "wm", "size", `${width}x${height}`]);
  adb(serial, ["shell", "wm", "density", String(density)]);
  // 关掉自动旋转，避免矩阵中途因传感器事件改变方向而影响布局断言。
  adb(serial, ["shell", "settings", "put", "system", "accelerometer_rotation", "0"]);
  adb(serial, ["shell", "settings", "put", "system", "user_rotation", "0"]);

  const sizeOut = adb(serial, ["shell", "wm", "size"]);
  const densityOut = adb(serial, ["shell", "wm", "density"]);
  // 有 override 时取最后一个匹配：输出形如 "Physical size: A\nOverride size: B"。
  const actualSize = [...sizeOut.matchAll(/(\d+)x(\d+)/gu)].at(-1);
  const actualDensity = [...densityOut.matchAll(/(\d+)/gu)].at(-1);

  const okSize = actualSize && actualSize[1] === width && actualSize[2] === height;
  const okDensity = actualDensity && actualDensity[1] === String(density);
  return {
    requested: { resolution, density },
    actual: {
      resolution: actualSize ? `${actualSize[1]}x${actualSize[2]}` : null,
      density: actualDensity ? Number(actualDensity[1]) : null,
    },
    verified: Boolean(okSize && okDensity),
  };
};

const main = () => {
  const [command, ...rest] = process.argv.slice(2);
  const resolution = process.env.AACTL_MATRIX_RESOLUTION ?? DEFAULT_RESOLUTION;
  const density = Number(process.env.AACTL_MATRIX_DENSITY ?? DEFAULT_DENSITY);

  if (command === "create") {
    const [name, apiLevel, templateAvd] = rest;
    if (!name || !apiLevel || !templateAvd) {
      throw new Error("Usage: create <avdName> <apiLevel> <templateAvd>");
    }
    createAvd(name, Number(apiLevel), templateAvd, resolution, density);
    console.log(JSON.stringify({ created: name, resolution, density }, null, 2));
    return;
  }

  if (command === "apply") {
    const [serial] = rest;
    if (!serial) throw new Error("Usage: apply <serial>");
    if (!serial.startsWith("emulator-")) {
      throw new Error(`Refusing to reconfigure ${serial}: emulators only.`);
    }
    const report = applyAndVerifyScreen(serial, resolution, density);
    console.log(JSON.stringify(report, null, 2));
    if (!report.verified) process.exitCode = 1;
    return;
  }

  if (command === "delete") {
    const [name] = rest;
    if (!name) throw new Error("Usage: delete <avdName>");
    // 只删本脚本按 aactl- 前缀创建的一次性 AVD，避免误删用户自己的 AVD。
    if (!name.startsWith("aactl-")) {
      throw new Error(`Refusing to delete ${name}: only aactl- prefixed AVDs are disposable.`);
    }
    rmSync(path.join(avdRoot(), `${name}.avd`), { recursive: true, force: true });
    rmSync(path.join(avdRoot(), `${name}.ini`), { force: true });
    console.log(JSON.stringify({ deleted: name }, null, 2));
    return;
  }

  throw new Error("Usage: matrix-avd.mjs <create|apply|delete> ...");
};

try {
  main();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
