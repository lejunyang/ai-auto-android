/**
 * 脚本用途：在 disposable emulator 上自动预置无障碍授权、披露同意与桌面桥会话，
 * 把桥侧验证所需的手工点击固化为可复现步骤。
 *
 * 授权边界（AGENTS.md 第 58/59 行）：本脚本只允许在一次性模拟器与 debug 变体上运行。
 * 它拒绝在非 emulator- 前缀的 serial 上执行——真机的无障碍授权、安装确认与
 * 敏感权限必须由用户亲自完成，不得由脚本绕过或伪造。
 *
 * 三个阶段的必要性各不相同：
 * - accessibility：写 secure settings 后系统会真正 bind 服务（已实测），无需点系统设置；
 * - disclosure：App 自身要求的知情同意，未接受时所有无障碍方法都返回 PERMISSION_DENIED，
 *   包括既有的 ui.snapshot。这是 App 的设计而非系统限制，只能通过界面完成；
 * - bridge：一次性码只显示在屏幕上且 2 分钟失效，必须从 UI 层级读取后立即配对。
 */

import { spawnSync } from "node:child_process";

/** 无障碍服务的完整组件名。 */
const ACCESSIBILITY_SERVICE =
  "dev.aiauto.android/dev.aiauto.android.accessibility.AiAutomationAccessibilityService";

const APP_PACKAGE = "dev.aiauto.android";
const BRIDGE_PORT = 38383;

/** 界面上确认类按钮的文案，按优先级排列。 */
const CONFIRM_LABELS = ["同意并打开系统无障碍设置", "NEXT", "Only this time", "OK"];

/**
 * 运行一条 adb 命令并返回 stdout。
 * 失败不抛异常：调用方需要根据输出判断状态，而多数探测命令的非零退出是正常的。
 */
const adb = (serial, args, { binary = false } = {}) => {
  const result = spawnSync("adb", ["-s", serial, ...args], {
    encoding: binary ? "buffer" : "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  return binary ? result.stdout ?? Buffer.alloc(0) : (result.stdout ?? "").trim();
};

/** 在设备上执行 shell 命令。 */
const shell = (serial, command) => adb(serial, ["shell", command]);

/** 拒绝在真机上运行：真机授权必须由用户完成。 */
const requireDisposableEmulator = (serial) => {
  if (!serial.startsWith("emulator-")) {
    throw new Error(
      `Refusing to provision ${serial}: only disposable emulators may be provisioned ` +
        "automatically. Real devices require the user to grant accessibility, " +
        "installation and sensitive permissions in person.",
    );
  }
};

/** 读取当前界面层级。 */
const hierarchy = (serial) => {
  shell(serial, "uiautomator dump /data/local/tmp/provision.xml");
  return adb(serial, ["exec-out", "cat", "/data/local/tmp/provision.xml"]);
};

/**
 * 从层级里取出带坐标的文本节点。
 * 同时收集 text 与 content-desc：Compose 界面的按钮文字常在其中之一。
 */
const textNodes = (xml) => {
  const nodes = [];
  const pattern =
    /text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  let match;
  while ((match = pattern.exec(xml)) !== null) {
    const label = (match[1].trim() || match[2].trim());
    if (!label) continue;
    nodes.push({
      label,
      x: Math.trunc((Number(match[3]) + Number(match[5])) / 2),
      y: Math.trunc((Number(match[4]) + Number(match[6])) / 2),
    });
  }
  return nodes;
};

/** 取出可勾选控件（同意勾选框在层级里没有文字）。 */
const checkboxNodes = (xml) => {
  const nodes = [];
  const pattern =
    /class="[^"]*CheckBox[^"]*"[^>]*?checked="(true|false)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  let match;
  while ((match = pattern.exec(xml)) !== null) {
    nodes.push({
      checked: match[1] === "true",
      x: Math.trunc((Number(match[2]) + Number(match[4])) / 2),
      y: Math.trunc((Number(match[3]) + Number(match[5])) / 2),
    });
  }
  return nodes;
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const tap = async (serial, node, pause = 2500) => {
  shell(serial, `input tap ${node.x} ${node.y}`);
  await sleep(pause);
};

/** 按关键词找节点，找不到返回 undefined。 */
const findNode = (xml, keyword) =>
  textNodes(xml).find((node) => node.label.includes(keyword));

/**
 * 启用无障碍服务并确认系统真的完成绑定。
 *
 * 判据取 dumpsys 的 Bound services 而非 settings 的值：settings 只是一个字符串，
 * 写入成功不等于服务已 bind。同时检查 Crashed services 为空——服务崩溃时
 * Bound 段落可能仍有残留记录。
 */
const provisionAccessibility = async (serial) => {
  shell(serial, `settings put secure enabled_accessibility_services ${ACCESSIBILITY_SERVICE}`);
  shell(serial, "settings put secure accessibility_enabled 1");

  // 绑定是异步的：写完 settings 后系统要启动 App 进程并 bind 服务。
  // 实测紧接着读 dumpsys 会拿到 Bound services:{}，而几秒后才出现记录；
  // 因此轮询而非单次判定，否则脚本在冷启动或 pm clear 之后必然误报失败。
  let bound = false;
  let crashed = false;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    await sleep(2000);
    const dump = shell(serial, "dumpsys accessibility");
    bound = (/Bound services:\{([^}]*)/.exec(dump)?.[1] ?? "").includes("Service[");
    crashed = (/Crashed services:\{([^}]*)\}/.exec(dump)?.[1] ?? "").trim().length > 0;
    if (bound) break;
  }

  const enabled = shell(serial, "settings get secure enabled_accessibility_services");
  return { stage: "accessibility", enabled, bound, crashed };
};

/**
 * 在 App 内接受无障碍披露并配置目标包。
 *
 * targetPackages 决定服务会处理哪些应用；App 要求它非空，
 * 因此不能只勾同意就返回。
 */
const provisionDisclosure = async (serial, targetPackages) => {
  shell(serial, `am start -n ${APP_PACKAGE}/.MainActivity`);
  await sleep(6000);

  // 主界面可能停在上次退出的子页面，先用返回键回到顶层。
  for (let attempt = 0; attempt < 3; attempt += 1) {
    if (findNode(hierarchy(serial), "无障碍执行")) break;
    shell(serial, "input keyevent KEYCODE_BACK");
    await sleep(2500);
  }

  const entry = findNode(hierarchy(serial), "无障碍执行");
  if (!entry) {
    return { stage: "disclosure", ok: false, reason: "accessibility entry not found" };
  }
  await tap(serial, entry, 3500);

  // 披露页内容长于一屏，输入框与勾选框需要滚动才可见。
  shell(serial, "input swipe 160 500 160 180 400");
  await sleep(2500);

  let xml = hierarchy(serial);
  const field = /class="android\.widget\.EditText"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/
    .exec(xml);
  if (!field) {
    return { stage: "disclosure", ok: false, reason: "target package field not found" };
  }
  await tap(serial, {
    x: Math.trunc((Number(field[1]) + Number(field[3])) / 2),
    y: Math.trunc((Number(field[2]) + Number(field[4])) / 2),
  }, 1500);

  // 先清空：脚本可能重复运行，输入框里会残留上一次的值，
  // 直接追加会拼出 "com.android.settingsdev.aiauto.android" 这种非法包名（已实测踩到）。
  // 全选后删除比逐字符退格可靠——包名长度不定。
  shell(serial, "input keyevent KEYCODE_MOVE_END");
  for (let index = 0; index < 120; index += 1) {
    shell(serial, "input keyevent KEYCODE_DEL");
  }
  await sleep(1200);

  // 逐个包写入：adb input text 对逗号与点号安全，但不接受空格。
  for (const [index, name] of targetPackages.entries()) {
    shell(serial, `input text ${index === 0 ? name : "," + name}`);
    await sleep(800);
  }
  shell(serial, "input keyevent KEYCODE_ESCAPE");
  await sleep(1500);

  xml = hierarchy(serial);
  const [checkbox] = checkboxNodes(xml);
  if (!checkbox) {
    return { stage: "disclosure", ok: false, reason: "consent checkbox not found" };
  }
  if (!checkbox.checked) {
    await tap(serial, checkbox, 2000);
  }

  // 勾选后确认按钮可能仍在可视区之下：输入法收起与内容高度变化都会移动布局。
  // 因此再滚一次并重新读层级，而不是沿用勾选前的坐标。
  shell(serial, "input swipe 160 500 160 200 400");
  await sleep(2500);

  xml = hierarchy(serial);
  const confirm = CONFIRM_LABELS.map((label) => findNode(xml, label)).find(Boolean);
  if (!confirm) {
    return {
      stage: "disclosure",
      ok: false,
      reason: "confirm button not found",
      visibleLabels: textNodes(xml).map((node) => node.label.slice(0, 24)),
    };
  }
  await tap(serial, confirm, 5000);

  // 确认按钮会跳到系统无障碍设置页，返回即可——服务已在 stage 1 启用。
  shell(serial, "input keyevent KEYCODE_BACK");
  await sleep(3000);

  return { stage: "disclosure", ok: true, targetPackages };
};

/**
 * 开启桌面桥并读出一次性码。
 *
 * 不返回码本身：一次性码是短期凭据，写进脚本输出会落入日志与 CI 产物。
 * 调用方拿到的是"已配对"这一事实，会话由 aactl 自己持有。
 */
const provisionBridge = async (serial) => {
  // 披露确认会跳到系统无障碍设置页，返回键未必直接回到 App 主界面
  // （中间可能还压着披露页本身）。因此显式重新拉起 MainActivity，
  // 再用返回键退到能看见「桌面桥」入口的顶层。
  shell(serial, `am start -n ${APP_PACKAGE}/.MainActivity`);
  await sleep(4000);

  let entry;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    entry = findNode(hierarchy(serial), "桌面桥");
    if (entry) break;
    shell(serial, "input keyevent KEYCODE_BACK");
    await sleep(2500);
  }
  if (!entry) {
    return {
      stage: "bridge",
      ok: false,
      reason: "desktop bridge entry not found",
      visibleLabels: textNodes(hierarchy(serial)).map((node) => node.label.slice(0, 24)),
    };
  }
  await tap(serial, entry, 3000);

  // 开启按钮在页面底部，需要滚动。
  shell(serial, "input swipe 160 500 160 180 400");
  await sleep(2500);

  let xml = hierarchy(serial);
  const openButton = findNode(xml, "开启桌面桥");
  if (openButton) {
    await tap(serial, openButton, 4000);
    xml = hierarchy(serial);
  }

  const code = textNodes(xml)
    .map((node) => node.label.replace(/\s/g, ""))
    .find((label) => /^\d{6}$/.test(label));
  if (!code) {
    return { stage: "bridge", ok: false, reason: "pairing code not visible" };
  }

  adb(serial, ["forward", `tcp:${BRIDGE_PORT}`, `tcp:${BRIDGE_PORT}`]);
  return { stage: "bridge", ok: true, code, forwarded: BRIDGE_PORT };
};

const parseArguments = (argv) => {
  const options = new Map();
  let command = "";
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) {
      if (!command) command = token;
      continue;
    }
    const [name, inline] = token.slice(2).split("=");
    options.set(name, inline ?? argv[++index] ?? "");
  }
  return { command, options };
};

const main = async () => {
  const { command, options } = parseArguments(process.argv.slice(2));
  const serial = options.get("device") ?? "";
  if (!serial) {
    console.error(
      "Usage: node scripts/emulator/provision-bridge.mjs provision " +
        "--device emulator-5554 [--target-packages a,b]",
    );
    process.exitCode = 2;
    return;
  }
  requireDisposableEmulator(serial);

  const targetPackages = (options.get("target-packages") ?? "com.android.settings")
    .split(",")
    .map((name) => name.trim())
    .filter(Boolean);

  if (command !== "provision" && command !== "accessibility") {
    console.error(`Unknown command ${command}. Supported: provision, accessibility.`);
    process.exitCode = 2;
    return;
  }

  const report = { device: serial, stages: [] };

  const accessibility = await provisionAccessibility(serial);
  report.stages.push(accessibility);
  if (!accessibility.bound || accessibility.crashed) {
    console.log(JSON.stringify({ ...report, ok: false }, null, 2));
    process.exitCode = 1;
    return;
  }
  if (command === "accessibility") {
    console.log(JSON.stringify({ ...report, ok: true }, null, 2));
    return;
  }

  const disclosure = await provisionDisclosure(serial, targetPackages);
  report.stages.push(disclosure);
  if (!disclosure.ok) {
    console.log(JSON.stringify({ ...report, ok: false }, null, 2));
    process.exitCode = 1;
    return;
  }

  const bridge = await provisionBridge(serial);
  // 一次性码不进报告：短期凭据不应落入日志或 CI 产物。
  report.stages.push({ ...bridge, code: bridge.code ? "<redacted>" : undefined });
  if (!bridge.ok) {
    console.log(JSON.stringify({ ...report, ok: false }, null, 2));
    process.exitCode = 1;
    return;
  }

  report.ok = true;
  report.next = `aactl bridge open --device ${serial} --code <code shown on device>`;
  console.log(JSON.stringify(report, null, 2));
};

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
