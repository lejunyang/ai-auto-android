/**
 * 脚本用途：仅在一次性 emulator-5554 上经真实 aactl 验证虚拟显示、主屏息屏与 PIN 往返。
 * 安全约束：只允许 osdk-test-35/API35；不访问真机、不配置 AVD、不自动下载产物；
 * PIN 与超时只在 test-only 路径暂设，finally 恢复并关闭模拟器。辅助进程协议全部经 CLI。
 */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { pngStats } from "./virtual-display-png.mjs";

const fixturePackage = "dev.aiauto.fixture";
const expectedFingerprint = "google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys";
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const hash = data => createHash("sha256").update(data).digest("hex");
const ensure = (condition, message) => { if (!condition) throw new Error(message); };

/** 在任何进程调用前拒绝真机与其它 serial；PIN 仅是 disposable emulator 的测试 fixture。 */
export function requireTestSerial(serial) {
  ensure(serial?.startsWith("emulator-"), "Refusing non-emulator: test PIN and screen_off_timeout are emulator-only");
  ensure(serial === "emulator-5554", "Only emulator-5554 is permitted");
}

/** 按显示分段读取主屏前台 Activity，避免把最后获得输入焦点的虚拟屏当作主屏。 */
export function primaryTopActivity(dump) {
  const section = dump.split(/^Display #/m).find(part => part.startsWith("0 (activities from top to bottom):"));
  const match = section && /topResumedActivity=ActivityRecord\{\S+\s+u\d+\s+(\S+)\s+t\d+/.exec(section);
  ensure(match, "Cannot identify display 0 topResumedActivity");
  return match[1];
}

/** 空显示可能不列出活动分段；按指定编号返回前台，不能借用主屏或全局尾部。 */
export function topActivityOnDisplay(dump, displayId) {
  const sections = dump.split(/^Display #/m).filter(part => part.startsWith(`${displayId} (activities from top to bottom):`));
  ensure(sections.length <= 1, "Duplicate activity display section");
  return sections.length ? /topResumedActivity=ActivityRecord\{\S+\s+u\d+\s+(\S+)\s+t\d+/.exec(sections[0])?.[1] ?? null : null;
}

/** 专用错误、显示归属与无前台三项必须同时成立，不能把不归属或观察失败当作空屏成功。 */
export function assertEmptyOwnedDisplay(result, owned, activity) {
  ensure(!result.ok && result.error?.code === "DISPLAY_EMPTY" && owned && activity === null,
    "Expected an owned empty display after BACK, without primary fallback");
}

function options(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i += 2) {
    ensure(["--device", "--adb", "--aactl", "--helper", "--fixture", "--run"].includes(argv[i]) && argv[i + 1], "Usage: node verify-virtual-display.mjs --device emulator-5554 --adb PATH --aactl PATH --helper APK --fixture APK --run NAME");
    ensure(!(argv[i].slice(2) in result), "Duplicate option"); result[argv[i].slice(2)] = argv[i + 1];
  }
  requireTestSerial(result.device);
  for (const key of ["adb", "aactl", "helper", "fixture"]) ensure(result[key] && fs.statSync(result[key]).isFile(), `Missing ${key}`);
  ensure(/^[a-zA-Z0-9-]{1,40}$/.test(result.run ?? ""), "A safe run name is required");
  return result;
}

/** 测试只接受当前 fixture 的节点坐标，每次动作都重新 dump，失败不猜坐标或重试动作。 */
function fixtureNode(xml, id) {
  const node = [...xml.matchAll(/<node\b[^>]*>/g)].map(m => m[0]).find(n => n.includes(`resource-id="${fixturePackage}:id/${id}"`));
  ensure(node, `Fixture node is absent: ${id}`); return node;
}
function nodeBounds(xml, id) {
  const match = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(fixtureNode(xml, id));
  ensure(match, `Fixture node is absent: ${id}`);
  const [x1, y1, x2, y2] = match.slice(1).map(Number); ensure(x2 > x1 && y2 > y1, "Node has empty bounds");
  return { x1, y1, x2, y2, x: Math.floor((x1 + x2) / 2), y: Math.floor((y1 + y2) / 2) };
}

async function main() {
  const opt = options(process.argv.slice(2)), serial = opt.device;
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "build", "virtual-display", opt.run);
  ensure(!fs.existsSync(root), "Refusing to overwrite an existing evidence directory"); fs.mkdirSync(root, { recursive: true });
  const report = { run: opt.run, device: serial, startedAt: new Date().toISOString(), steps: [], environment: {} };
  let identity, originalTimeout, pinSet = false, helperStarted = false, lastXML = "", lastFrame, safeToClean = false;
  // 测试凭据不进入 trace、命令日志或报告；不对外部设备使用这一固定 fixture。
  const testPin = "2468";
  const event = (step, evidence = {}) => { const entry = { step, elapsedMs: Date.now() - started, ...evidence }; report.steps.push(entry); console.error(JSON.stringify(entry)); };
  const started = Date.now();
  const exec = (program, args, allowFailure = false) => {
    const result = spawnSync(program, args, { encoding: "utf8", timeout: 45000, maxBuffer: 8 * 1024 * 1024, windowsHide: true, env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
    if (!allowFailure) ensure(!result.error && result.status === 0, `Process failed (${path.basename(program)}): ${(result.stderr ?? "").slice(0, 500)}`);
    return { status: result.status, stdout: (result.stdout ?? "").trim(), stderr: (result.stderr ?? "").trim() };
  };
  const raw = args => exec(opt.adb, ["-s", serial, ...args]);
  const devices = () => exec(opt.adb, ["devices", "-l"]).stdout.split(/\r?\n/).filter(l => l && !l.startsWith("List of devices"));
  const guard = () => {
    const all = devices(); ensure(all.length === 1 && all[0].startsWith(`${serial} `) && /\sdevice\s/.test(all[0]), "Device count, serial or online state changed; stopping");
    if (identity) ensure(all[0] === identity, "Device transport identity changed; stopping");
    ensure(raw(["shell", "getprop", "ro.build.fingerprint"]).stdout === expectedFingerprint, "Device fingerprint changed; stopping");
  };
  const shell = (...args) => { guard(); return raw(["shell", ...args]).stdout; };
  const cliEnvelope = args => {
    guard(); const r = exec(opt.aactl, [...args, "--device", serial, "--json"], true);
    ensure(r.stdout.length < 8 * 1024 * 1024, "CLI output budget exceeded");
    let value; try { value = JSON.parse(r.stdout); } catch { throw new Error("CLI did not return a JSON envelope"); }
    ensure(value.schemaVersion === "1.0" && typeof value.ok === "boolean", "Invalid CLI envelope");
    ensure((r.status === 0) === value.ok, "CLI exit code and envelope disagree"); return value;
  };
  const cli = args => { const r = cliEnvelope(args); ensure(r.ok, `CLI failed: ${r.error?.code}: ${r.error?.message}`); return r.data; };
  const hierarchy = id => {
    const r = cli(["observe", "hierarchy", ...(id ? ["--display", String(id)] : [])]); lastXML = r.xml;
    if (id) {
      const groups = [...r.xml.matchAll(/<display id="(\d+)"/g)].map(m => Number(m[1]));
      ensure(r.format === "uiautomator-windows-xml" && groups.length === 1 && groups[0] === id, "CLI returned the wrong display group or XML format");
    }
    return r.xml;
  };
  const capture = id => {
    const file = path.join(root, "latest.png");
    const result = cli(["observe", "screenshot", "--display", String(id), "--output", file]);
    try {
      const body = fs.readFileSync(file); lastFrame = body;
      const stats = pngStats(body);
      ensure(stats.width === 720 && stats.height === 1280 && stats.nonBlackRatio > 0.01, "CLI accepted a black or invalid fixture frame");
      return { bytes: body.length, sha256: result.sha256, ...stats };
    } finally { if (fs.existsSync(file)) fs.unlinkSync(file); }
  };
  const tapNode = (id, name, verticalFraction = 0.5) => {
    const xml = hierarchy(id), bounds = nodeBounds(xml, name);
    // 固定 API35 fixture 的输入框上部被标题栏遮挡；只在最新 bounds 的可见下部点选。
    const y = Math.floor(bounds.y1 + (bounds.y2 - bounds.y1) * verticalFraction);
    event("action", { package: fixturePackage, action: "ui.tap", node: name, expected: "fixture state changes", condition: "fresh hierarchy postcondition" });
    cli(["action", "tap", "--display", String(id), "--x", String(bounds.x), "--y", String(y)]);
    return hierarchy(id);
  };
  const mainActivity = () => primaryTopActivity(shell("dumpsys", "activity", "activities"));
  try {
    const all = devices(); ensure(all.length === 1 && all[0].startsWith(`${serial} `) && /\sdevice\s/.test(all[0]), "Expected only emulator-5554 online"); identity = all[0]; guard();
    ensure(raw(["shell", "getprop", "sys.boot_completed"]).stdout === "1", "sys.boot_completed must be 1 before testing"); safeToClean = true;
    // Windows emulator 控制台可能输出 CRCRLF，逐行 trim 后仍严格匹配固定 AVD 名。
    ensure(raw(["emu", "avd", "name"]).stdout.split("\n")[0].trim() === "osdk-test-35", "Unexpected AVD");
    ensure(shell("getprop", "ro.build.version.sdk") === "35", "API 35 is required");
    originalTimeout = shell("settings", "get", "system", "screen_off_timeout");
    report.environment = { avd: "osdk-test-35", api: 35, fingerprint: expectedFingerprint, transport: identity, size: shell("wm", "size"), density: shell("wm", "density"), locale: shell("getprop", "persist.sys.locale") || shell("getprop", "ro.product.locale"), navigationMode: shell("settings", "get", "secure", "navigation_mode"), originalTimeout, abi: shell("getprop", "ro.product.cpu.abi"), helperSHA256: hash(fs.readFileSync(opt.helper)), fixtureSHA256: hash(fs.readFileSync(opt.fixture)), aactlSHA256: hash(fs.readFileSync(opt.aactl)) };
    event("environment", report.environment);
    ensure(report.environment.size === "Physical size: 320x640" && report.environment.density === "Physical density: 160" && report.environment.locale === "en-US" && report.environment.navigationMode === "2", "Fixed emulator profile differs; refusing settings changes");
    // 安装仅限仓库本地构建的无副作用 debug fixture，不下载或重新分发 APK。
    guard(); raw(["install", "-r", "-t", opt.fixture]);
    ensure(/versionCode=1\b/.test(shell("dumpsys", "package", fixturePackage)), "Unexpected fixture version");
    cli(["action", "unlock"]); cli(["action", "key", "--key", "HOME"]); await pause(1500);
    const mainBefore = mainActivity(); ensure(mainBefore.startsWith("com.google.android.apps.nexuslauncher/"), "Primary display is not the launcher");
    const hello = cli(["display", "start", "--helper", opt.helper]); helperStarted = true; ensure(hello.protocolVersion === 1, "Wrong protocol");
    let screen = cli(["display", "create", "--width", "720", "--height", "1280", "--dpi", "240"]), id = screen.displayId;
    ensure(cli(["display", "list"]).displays.some(s => s.displayId === id), "Created display is absent");
    event("action", { package: fixturePackage, action: "app.launch", displayId: id, expected: "PAGE:MAIN on virtual display; primary unchanged" });
    cli(["action", "launch", "--display", String(id), "--package", fixturePackage, "--activity", ".MainActivity"]);
    ensure(hierarchy(id).includes('text="PAGE:MAIN"'), "Fixture did not launch on target display");
    ensure(mainActivity() === mainBefore, "Primary activity changed after virtual launch");
    event("1-create-launch", { displayId: id, primaryActivity: mainBefore, frame: capture(id) });
    const beforeTap = capture(id), xml = tapNode(id, "click_target"); ensure(xml.includes('text="CLICK:1"'), "Virtual tap postcondition failed");
    const afterTap = capture(id); ensure(beforeTap.sha256 !== afterTap.sha256, "Tap did not change the frame");
    ensure(mainActivity() === mainBefore, "Primary activity changed after virtual tap");
    event("2-tap-isolation", { click: 1, frame: afterTap, primaryUnchanged: true });
    // 普通 dump 的活动窗口歧义只作观察记录，不能用它判断主屏是否受影响。
    const ordinary = cliEnvelope(["observe", "hierarchy"]);
    const packages = ordinary.ok ? [...new Set([...ordinary.data.xml.matchAll(/package="([^"]*)"/g)].map(m => m[1]))] : [];
    event("2-ordinary-hierarchy-observation", { ok: ordinary.ok, format: ordinary.data?.format, packages, observed: packages.includes(fixturePackage) ? "virtual fixture" : packages.includes("com.google.android.apps.nexuslauncher") ? "primary launcher" : "other or unavailable", errorCode: ordinary.error?.code });
    capture(id);
    ensure(fixtureNode(tapNode(id, "text_input", 0.85), "text_input").includes('focused="true"'), "Input field itself is not focused");
    event("action", { package: fixturePackage, action: "ui.setText", displayId: id, expected: "INPUT:E2EAscii" });
    cli(["action", "text", "--display", String(id), "--text", "E2EAscii"]); ensure(hierarchy(id).includes("E2EAscii"), "ASCII text did not reach fixture");
    event("action", { package: fixturePackage, action: "ui.pressKey", key: "DELETE", displayId: id, expected: "INPUT:E2EAsci" });
    cli(["action", "key", "--display", String(id), "--key", "DELETE"]); ensure(hierarchy(id).includes('text="INPUT:E2EAsci"'), "Text/key postcondition failed");
    // 无虚拟屏IME时BACK直接作用于根Activity；单独验证退出与空屏，再显式重建测试前提。
    ensure(fixtureNode(hierarchy(id), "text_input").includes('focused="true"'), "Input lost focus before BACK");
    event("action", { package: fixturePackage, action: "ui.pressKey", key: "BACK", displayId: id, expected: "root Activity exits, display remains owned and empty" });
    cli(["action", "key", "--display", String(id), "--key", "BACK"]);
    const empty = cliEnvelope(["observe", "hierarchy", "--display", String(id)]);
    const owned = cli(["display", "list"]).displays.some(d => d.displayId === id);
    const afterBackActivity = topActivityOnDisplay(shell("dumpsys", "activity", "activities"), id);
    assertEmptyOwnedDisplay(empty, owned, afterBackActivity);
    ensure(mainActivity() === mainBefore, "Primary activity changed after virtual BACK");
    event("2-back-empty", { displayId: id, code: empty.error.code, owned, activity: afterBackActivity });
    event("action", { package: fixturePackage, action: "app.launch", displayId: id, expected: "PAGE:MAIN restored on same display" });
    cli(["action", "launch", "--display", String(id), "--package", fixturePackage, "--activity", ".MainActivity"]);
    const relaunched = hierarchy(id); ensure(relaunched.includes('text="PAGE:MAIN"'), "Fixture did not recover after empty display");
    // 根页面退出后不承诺进程内状态仍在；若进程被回收，明确建立原CLICK:1前提，后续仍严格验CLICK:2。
    if (relaunched.includes('text="CLICK:0"')) ensure(tapNode(id, "click_target").includes('text="CLICK:1"'), "Could not restore click precondition");
    else ensure(relaunched.includes('text="CLICK:1"'), "Unexpected click state after relaunch");
    ensure(mainActivity() === mainBefore, "Primary activity changed after virtual relaunch");
    event("2-back-relaunch", { displayId: id, restored: true, clickPrecondition: 1 });
    const list = nodeBounds(hierarchy(id), "vertical_scroll_target");
    event("action", { package: fixturePackage, action: "ui.swipe", displayId: id, expected: "positive VERTICAL_OFFSET", condition: "fresh list bounds and post-action hierarchy" });
    cli(["action", "swipe", "--display", String(id), "--x1", String(list.x), "--y1", String(Math.floor(list.y2 - (list.y2 - list.y1) / 5)), "--x2", String(list.x), "--y2", String(Math.floor(list.y1 + (list.y2 - list.y1) / 5)), "--duration", "400"]);
    ensure(/text="VERTICAL_OFFSET:[1-9]\d*"/.test(hierarchy(id)), "Swipe did not change list offset");
    event("2-inputs", { text: "ASCII fixture verified", key: "DELETE", back: "DISPLAY_EMPTY + relaunch", swipe: "positive vertical offset" });
    shell("settings", "put", "system", "screen_off_timeout", "15000");
    ensure(shell("settings", "get", "system", "screen_off_timeout") === "15000", "Timeout write failed");
    cli(["action", "sleep"]); await pause(1000); ensure(cli(["observe", "screen-state"]).on === false, "Primary display did not sleep");
    const sleepBefore = capture(id); ensure(tapNode(id, "click_target").includes('text="CLICK:2"'), "Screen-off tap failed"); const sleepAfter = capture(id); ensure(sleepBefore.sha256 !== sleepAfter.sha256, "Screen-off tap frame did not change");
    event("3-screen-off-input", { click: 2, frame: sleepAfter });
    const idleStart = Date.now(); for (let i = 0; i < 13; i++) { await pause(5000); if (i % 2 === 1) console.error(`idle ${Date.now() - idleStart}ms; no display actions`); }
    const status = cli(["display", "status"]), display = status.displays.find(s => s.displayId === id); ensure(display?.groupAwake === true && status.credentialLocked === false && status.keepAwakeError === null, "Virtual group did not stay awake");
    ensure(cli(["observe", "screen-state"]).on === false, "Primary woke during virtual keepalive");
    event("3-idle-65s", { waitedMs: Date.now() - idleStart, groupAwake: display.groupAwake, frame: capture(id) });
    cli(["action", "wake"]); cli(["action", "unlock"]); ensure(mainActivity() === mainBefore, "Primary activity changed across screen-off run");
    // 以下是明确 test-only 的 PIN 往返；不输入凭据解锁，清除测试 PIN 后才调用无凭据解锁。
    ensure(shell("locksettings", "set-pin", testPin).startsWith("Pin set to"), "Test PIN was not set"); pinSet = true; cli(["action", "sleep"]);
    for (let attempt = 0; attempt < 12; attempt++) { const s = cli(["display", "status"]); if (s.credentialLocked && s.displays.find(d => d.displayId === id)?.groupAwake === false) break; await pause(1000); }
    const locked = cli(["display", "status"]); ensure(locked.credentialLocked && locked.displays.find(d => d.displayId === id)?.groupAwake === false, "PIN did not produce credential-locked sleeping group");
    const lockedResult = cliEnvelope(["observe", "screenshot", "--display", String(id), "--output", path.join(root, "forbidden.png")]); ensure(!lockedResult.ok && lockedResult.error.code === "DEVICE_LOCKED" && !fs.existsSync(path.join(root, "forbidden.png")), "Locked capture returned a frame");
    event("4-pin-lock", { code: lockedResult.error.code, groupAwake: false });
    ensure(shell("locksettings", "clear", "--old", testPin).includes("Lock credential cleared"), "Test PIN was not cleared"); pinSet = false; cli(["action", "wake"]); cli(["action", "unlock"]);
    const recovered = cli(["display", "status"]); ensure(!recovered.credentialLocked, "Credential lock remains after test PIN removal");
    const result = cliEnvelope(["observe", "screenshot", "--display", String(id), "--output", path.join(root, "recovery.png")]);
    {
      ensure(!result.ok && result.error.code === "DISPLAY_ASLEEP" && !fs.existsSync(path.join(root, "recovery.png")), "Expected DISPLAY_ASLEEP without a frame after removing test PIN");
      cli(["display", "close", "--display", String(id)]); screen = cli(["display", "create", "--width", "720", "--height", "1280", "--dpi", "240"]); id = screen.displayId;
      cli(["action", "launch", "--display", String(id), "--package", fixturePackage, "--activity", ".MainActivity"]); ensure(hierarchy(id).includes('text="PAGE:MAIN"'), "Fixture rebuild launch failed");
      event("4-recovery", { behavior: "DISPLAY_ASLEEP + close/create/relaunch", displayId: id, frame: capture(id) });
    }
    const invalid = cliEnvelope(["observe", "hierarchy", "--display", "2147483647"]); ensure(!invalid.ok && invalid.error.code === "DISPLAY_NOT_FOUND", "Invalid target did not fail distinctly");
    cli(["display", "stop"]); helperStarted = false;
    guard(); const processCheck = exec(opt.adb, ["-s", serial, "shell", "pidof", "aactl-displayd"], true); ensure(processCheck.status === 1 && processCheck.stdout === "", "Helper survived stop");
    ensure(/Display Devices: size=1\b/.test(shell("dumpsys", "display")), "Virtual display survived stop");
    ensure(raw(["forward", "--list"]).stdout === "", "Forward leak");
    const files = exec(opt.adb, ["-s", serial, "shell", "ls", "/data/local/tmp/aactl-displayd.apk", "/data/local/tmp/aactl-displayd.log"], true); ensure(files.status !== 0 && files.stdout === "", "Helper temporary files remain");
    const missing = cliEnvelope(["display", "status"]); ensure(!missing.ok && missing.error.code === "DISPLAYD_NOT_RUNNING", "Stopped helper did not return distinct error");
    event("5-stop-clean", { processes: 0, virtualDisplays: 0, forwards: 0, temporaryFiles: 0, stoppedCode: missing.error.code }); report.ok = true;
  } catch (error) {
    report.ok = false; report.error = String(error.message).replaceAll(testPin, "[redacted]").slice(0, 1000);
    // 失败证据总额小于 1MiB；仅 fixture 帧、删去所有文本的 hierarchy 和有界时间线。
    const redacted = lastXML.replace(/\b(text|content-desc|title)="[^"]*"/g, '$1="[redacted]"').slice(0, 65536);
    fs.writeFileSync(path.join(root, "failure-hierarchy.xml"), redacted);
    if (lastFrame?.length <= 512 * 1024) fs.writeFileSync(path.join(root, "failure-fixture.png"), lastFrame);
    if (safeToClean) { try { guard(); const log = raw(["logcat", "-d", "-t", "40", "-s", "displayd:I"]).stdout; fs.writeFileSync(path.join(root, "failure-log-window.txt"), log.slice(-16384).replace(/\b\d{4,}\b/g, "[redacted]")); } catch { /* 身份变化时不再访问设备。 */ } }
  } finally {
    if (safeToClean) {
      try {
        guard(); if (pinSet) { shell("locksettings", "clear", "--old", testPin); pinSet = false; }
        if (originalTimeout === "null") shell("settings", "delete", "system", "screen_off_timeout"); else if (originalTimeout !== undefined) shell("settings", "put", "system", "screen_off_timeout", originalTimeout);
        if (helperStarted) cli(["display", "stop"]);
        ensure(raw(["forward", "--list"]).stdout === "", "Forward remains during cleanup");
        raw(["emu", "kill"]); report.emulatorKillRequested = true;
      } catch (error) { report.ok = false; report.cleanupError = String(error.message).slice(0, 500); }
    }
    report.finishedAt = new Date().toISOString(); fs.writeFileSync(path.join(root, "report.json"), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report)); if (!report.ok) process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(error.message); process.exitCode = 1; });
