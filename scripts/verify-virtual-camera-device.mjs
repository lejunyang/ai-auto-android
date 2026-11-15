/**
 * 脚本用途：验证真机helper与清理；可选只在自建isolated屏按包名启动已安装fixture，权限框出现即停，不安装或授权。
 * 安全约束：每步核对唯一 serial、固定指纹、当前用户未锁定；异常保留证据后仅在守卫仍通过时清理，不重试动作。
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { deflateSync } from 'node:zlib';
import { createHash } from 'node:crypto';

const serial = '324462538298';
const fingerprint = 'nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6416:user/release-keys';
const displayFlags = { SECURE: 0x0002 };
const removeModeDestroyContent = 1;
const fixturePackage = 'dev.aiauto.fixture';
const installedFixtureSHA256 = '05ade33a16f9342d412dba6ea73c1ef4f27d7712aba548669fbb22489e9e9517';
const opt = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i]?.slice(2);
  if (!['device', 'adb', 'aactl', 'helper', 'evidence', 'launch-fixture'].includes(key) || !process.argv[i + 1] || key in opt) throw Error('参数无效或重复');
  opt[key] = process.argv[i + 1];
}
const ensure = (value, message) => { if (!value) throw Error(message); };
ensure(opt.device === serial, '仅允许明确的负责人真机 serial');
ensure(opt['launch-fixture'] === undefined || opt['launch-fixture'] === 'true', 'launch-fixture只接受显式true');
for (const key of ['adb', 'aactl', 'helper']) ensure(opt[key] && fs.statSync(opt[key]).isFile(), `必须传入真实可执行文件/产物路径：${key}`);
ensure(opt.evidence && !fs.existsSync(opt.evidence), '证据目录必须为新的仓库外目录');
const root = path.resolve(opt.evidence), repo = path.resolve(import.meta.dirname, '..');
ensure(!root.toLowerCase().startsWith(repo.toLowerCase() + path.sep) && root.toLowerCase() !== repo.toLowerCase(), '真机证据不得写入仓库');
fs.mkdirSync(root, { recursive: true });
const report = { serial, fingerprint, startedAt: new Date().toISOString(), steps: [] };
let identity, baseline, cacheBaseline, helperStarted = false;
function run(program, args) {
  const r = spawnSync(program, args, { encoding: 'utf8', timeout: 45000, maxBuffer: 2 * 1024 * 1024, windowsHide: true, env: { ...process.env, AACTL_ADB_PATH: opt.adb, MSYS_NO_PATHCONV: '1' } });
  ensure(!r.error, `进程异常：${r.error?.message}`);
  return { status: r.status, stdout: r.stdout.trim(), stderr: r.stderr.trim() };
}
function raw(...args) { const r = run(opt.adb, ['-s', serial, ...args]); ensure(r.status === 0, `ADB 失败：${args[0]} ${r.stderr.slice(0, 250)}`); return r.stdout; }
function guard() {
  const r = run(opt.adb, ['devices', '-l']);
  const devices = r.stdout.split(/\r?\n/).filter(l => l && !l.startsWith('List of devices'));
  ensure(r.status === 0 && devices.length === 1 && devices[0].startsWith(`${serial} `) && /\sdevice\s/.test(devices[0]), '设备列表/在线状态变化，停止');
  if (identity) ensure(devices[0] === identity, 'transport 身份变化，停止'); else identity = devices[0];
  ensure(raw('shell', 'getprop', 'ro.build.fingerprint') === fingerprint, '设备指纹变化，停止');
  ensure(raw('shell', 'am', 'get-current-user') === '0', '前台用户变化，停止');
  const trust = raw('shell', 'dumpsys', 'trust');
  ensure(/\(id=0,[^\n]*\(current\):[^\n]*\bdeviceLocked=0\b/.test(trust), '凭据锁定或状态未知，停止，不唤醒');
}
function event(step, data = {}) { const e = { step, at: new Date().toISOString(), ...data }; report.steps.push(e); console.error(JSON.stringify(e)); save(); }
function save() { fs.writeFileSync(path.join(root, 'report.json'), JSON.stringify(report, null, 2)); }
function shell(...args) { guard(); return raw('shell', ...args); }
function cli(args, expected, emptyCapture = false) {
  guard(); event('动作/观察', { target: '本工具 helper/虚拟屏', argv: args, expected: expected ?? (emptyCapture ? '空屏记录PNG或FRAME_NOT_READY；不算读回验收' : '成功响应；失败立即停止') });
  const r = run(opt.aactl, [...args, '--device', serial, '--json']);
  const value = JSON.parse(r.stdout); ensure(value.schemaVersion === '1.0' && (r.status === 0) === value.ok, 'CLI 信封错误');
  event('响应', { ok: value.ok, data: value.data, error: value.error });
  if (emptyCapture && !value.ok && value.error?.code === 'FRAME_NOT_READY') {
    event('空屏无帧', { displayId: args[args.indexOf('--display') + 1], conclusion: '无应用内容，记录无帧；不构成取帧能力通过' }); return value;
  }
  if (expected) ensure(!value.ok && expected.includes(value.error?.code), '未获得预期的空屏观察结果');
  else ensure(value.ok, `${value.error?.code}: ${value.error?.message}`);
  return value;
}
function cameraEvidence(label) {
  guard();
  // 相机服务曾因设备端截断崩溃：直接完整写主机文件，不设stdout缓冲上限/管道过滤或提前终止。
  const rawPath = path.join(root, `${label}-camera-full.txt`), errorPath = path.join(root, `${label}-camera-stderr.txt`);
  const output = fs.openSync(rawPath, 'wx', 0o600), errors = fs.openSync(errorPath, 'wx', 0o600);
  try {
    event('完整取回相机dump', { target: serial, rawPath, filtering: '仅在主机且进程完成后过滤' });
    const result = spawnSync(opt.adb, ['-s', serial, 'shell', 'dumpsys', 'media.camera'], {
      stdio: ['ignore', output, errors], windowsHide: true, env: { ...process.env, MSYS_NO_PATHCONV: '1' },
    });
    ensure(!result.error && result.status === 0 && !result.signal, '相机dump未完整成功返回，保留原始文件并停止');
  } finally { fs.closeSync(output); fs.closeSync(errors); }
  const dump = fs.readFileSync(rawPath, 'utf8');
  const lines = dump.split(/\r?\n/).filter(l => /^Number of |^== Camera device |^== Camera Provider HAL.*\/virtual\//.test(l) || /deviceId|Device Id|Device ID|Virtual device|virtualDevice/i.test(l));
  fs.writeFileSync(path.join(root, `${label}-camera.txt`), lines.join('\n'));
  const counts = [...dump.matchAll(/^Number of ([^:\r\n]+): (\d+)/gm)].map(m => [m[1], Number(m[2])]);
  const providers = lines.filter(l => /^== Camera Provider HAL.*\/virtual\//.test(l));
  ensure(counts.length >= 2 && providers.length === 1, '无法解析相机数量或虚拟 provider');
  event(label, { counts, providers }); return { counts, providers };
}
function cacheEvidence(label) {
  const entries = [];
  for (const directory of ['/data/local/tmp/dalvik-cache', '/data/local/tmp/dalvik-cache/arm64']) {
    guard();
    // 固定格式不用shell管道元字符，adb shell会再次解释argv。
    const result = run(opt.adb, ['-s', serial, 'shell', 'stat', '-c', '%n,%F,%a,%u,%g,%Y,%Z,%i', directory]);
    if (result.status !== 0) {
      ensure(/No such file or directory/.test(result.stderr + result.stdout), '缓存目录stat失败，不能当作不存在');
      entries.push({ path: directory, exists: false });
    } else {
      ensure(result.stdout.split(',').length === 8 && result.stdout.includes(',directory,'), '缓存路径不是预期普通目录');
      entries.push({ path: directory, exists: true, stat: result.stdout, entries: shell('ls', '-A', directory).split(/\r?\n/).filter(Boolean) });
    }
  }
  event(label, { cacheDirectories: entries }); return entries;
}
function focusEvidence(label) {
  const window = shell('dumpsys', 'window');
  const top = /mTopFocusedDisplayId=(\d+)/.exec(window)?.[1];
  const power = shell('dumpsys', 'power').split(/\r?\n/).filter(l => /mWakefulness=|mDisplayGroupWakefulness=|mGroupId=|mWakefulness=/.test(l));
  // 只记录顶层显示编号和电源分组，避免收集负责人主屏应用/输入内容。
  event(label, { topFocusedDisplayId: top === undefined ? null : Number(top), power });
}
function crc32(bytes) { let c = 0xffffffff; for (const b of bytes) { c ^= b; for (let i = 0; i < 8; i++) c = (c >>> 1) ^ ((c & 1) ? 0xedb88320 : 0); } return (c ^ 0xffffffff) >>> 0; }
function chunk(type, data) { const tag = Buffer.from(type), size = Buffer.alloc(4), crc = Buffer.alloc(4); size.writeUInt32BE(data.length); crc.writeUInt32BE(crc32(Buffer.concat([tag, data]))); return Buffer.concat([size, tag, data, crc]); }
// 本地确定性验收图不含文字、个人信息或下载素材；负责人后续可复用相同四象限判据。
function pattern(file, portrait) {
  const w = portrait ? 480 : 640, h = portrait ? 640 : 480, pixels = Buffer.alloc((w * 3 + 1) * h);
  const colors = portrait
    ? [[255, 255, 255], [0, 0, 255], [0, 255, 0], [255, 0, 0]]
    : [[255, 0, 0], [0, 255, 0], [0, 0, 255], [255, 255, 255]];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const color = colors[(y < h / 2 ? 0 : 2) + (x < w / 2 ? 0 : 1)];
    const i = y * (w * 3 + 1) + 1 + x * 3; pixels.set(color, i);
  }
  const header = Buffer.alloc(13); header.writeUInt32BE(w); header.writeUInt32BE(h, 4); header[8] = 8; header[9] = 2;
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]));
}
try {
  guard(); event('身份与制品', { identity, api: shell('getprop', 'ro.build.version.sdk'), abi: shell('getprop', 'ro.product.cpu.abi'), sha256: Object.fromEntries(['aactl', 'helper'].map(k => [k, createHash('sha256').update(fs.readFileSync(opt[k])).digest('hex')])) });
  ensure(!shell('ps', '-A', '-o', 'NAME').split(/\r?\n/).includes('aactl-displayd'), '已有 helper，拒绝接管');
  const associations = shell('cmd', 'companiondevice', 'list', '0'); event('关联基线', { associations });
  ensure(!/com\.android\.shell\s*\|\s*00:00:00:00:00:10/.test(associations), '已有工具标记关联，先人工核实来源');
  if (opt['launch-fixture'] === 'true') {
    const apk = shell('pm', 'path', fixturePackage).replace(/^package:/, '');
    ensure(/^\/data\/app\/[A-Za-z0-9_./~=+-]+\.apk$/.test(apk), 'fixture安装路径不唯一或格式异常');
    const sha256 = shell('sha256sum', apk).split(/\s+/)[0];
    ensure(sha256 === installedFixtureSHA256, '已安装fixture摘要不同，停止，不安装或替换');
    const packageInfo = shell('dumpsys', 'package', fixturePackage);
    event('已安装fixture身份', { package: fixturePackage, sha256, versionCode: /versionCode=(\d+)/.exec(packageInfo)?.[1], source: '负责人已安装的本地debug构建；本脚本不安装' });
  }
  baseline = cameraEvidence('相机基线'); focusEvidence('焦点基线');
  cacheBaseline = cacheEvidence('缓存基线');
  const wrapper = shell('cat', '/system/bin/uiautomator');
  event('系统层级命令缓存来源', { lines: wrapper.split(/\r?\n/).filter(l => /run_base=|tmp_cache=|mkdir -p|export ANDROID_DATA|exec app_process/.test(l)) });
  // 普通模式只启动/查询状态，不创建或操作任何应用，用于区分helper初始化与后续层级命令。
  helperStarted = true;
  cli(['display', 'start', '--helper', opt.helper]);
  cacheEvidence('普通helper启动后'); cli(['display', 'status']); cacheEvidence('普通helper初始化后');
  cli(['display', 'stop']); helperStarted = false;
  ensure(JSON.stringify(cacheEvidence('普通helper停止后')) === JSON.stringify(cacheBaseline), '普通helper阶段改变共享缓存，保留证据并停止');
  helperStarted = true;
  const hello = cli(['display', 'start', '--virtual-camera', '--helper', opt.helper]).data;
  cacheEvidence('摄像头helper启动后');
  ensure(hello.protocolVersion === 2 && hello.capabilities.virtualCamera === true, '能力握手不符');
  for (const focus of ['isolated', 'steal']) {
    const screen = cli(['display', 'create', '--width', '720', '--height', '1280', '--dpi', '240', '--focus', focus, '--camera', 'back']).data;
    ensure(screen.displayId > 0 && screen.camera === 'back' && screen.cameraInfo.virtualDeviceId > 0 && (screen.displayFlags & displayFlags.SECURE) === 0, '归属/安全显示标志不符');
    ensure(JSON.stringify(cacheEvidence(`${focus}-创建后`)) === JSON.stringify(cacheBaseline), '摄像头创建阶段改变共享缓存，保留证据并停止');
    const registered = cameraEvidence(`${focus}-注册`);
    ensure(registered.counts[0][1] === baseline.counts[0][1] + 1 && /1 devices:/.test(registered.providers[0]), '未获得新增一个虚拟摄像头的证据');
    cli(['display', 'list']);
    const createdStatus = cli(['display', 'status']).data.displays.find(d => d.displayId === screen.displayId);
    ensure(createdStatus?.removeMode === removeModeDestroyContent, '系统removeMode未证实销毁内容，不计通过');
    event(`${focus}-移除策略`, { displayId: screen.displayId, displayFlags: createdStatus.displayFlags, displayGroupId: createdStatus.displayGroupId, groupAwake: createdStatus.groupAwake, removeMode: createdStatus.removeMode });
    focusEvidence(`${focus}-空屏焦点`);
    cli(['observe', 'hierarchy', '--display', String(screen.displayId)], ['DISPLAY_EMPTY', 'WINDOWS_UNAVAILABLE']);
    cacheEvidence(`${focus}-系统层级命令后`);
    const launchFixture = opt['launch-fixture'] === 'true' && focus === 'isolated';
    if (launchFixture) {
      cli(['action', 'launch', '--display', String(screen.displayId), '--package', fixturePackage]);
      const observation = cli(['observe', 'hierarchy', '--display', String(screen.displayId)]).data;
      ensure(!observation.xml.includes('com.android.permissioncontroller'), '出现权限框，停止并报告，绝不点击');
      ensure(observation.xml.includes('text="PAGE:MAIN"') && observation.xml.includes(`package="${fixturePackage}"`), '按包名启动后没有同屏PAGE:MAIN');
      event('按包名定向启动通过', { package: fixturePackage, displayId: screen.displayId, marker: 'PAGE:MAIN' });
    }
    cli(['observe', 'screenshot', '--display', String(screen.displayId), '--output', path.join(root, `${focus}-${launchFixture ? 'fixture' : 'empty'}.png`)], undefined, !launchFixture);
    for (const portrait of [false, true]) {
      const file = path.join(root, portrait ? 'portrait.png' : 'quadrants.png'); pattern(file, portrait);
      cli(['camera', 'set-image', '--display', String(screen.displayId), '--image', file]);
    }
    const status = cli(['display', 'status']).data;
    ensure(status.displays.length === 1 && status.displays[0].cameraInfo.streams.length === 0, '无应用时出现非预期流');
    cli(['display', 'close', '--display', String(screen.displayId)]);
    cacheEvidence(`${focus}-关闭后`);
    ensure(JSON.stringify(cameraEvidence(`${focus}-关闭`)) === JSON.stringify(baseline), '关闭后相机数量未恢复');
  }
  report.ok = true;
} catch (e) { report.ok = false; report.error = e.message; event('停止原因', { error: e.message }); }
finally {
  try {
    guard();
    if (helperStarted) { cli(['display', 'stop']); helperStarted = false; }
    ensure(!shell('ps', '-A', '-o', 'NAME').split(/\r?\n/).includes('aactl-displayd'), 'helper 未退出');
    const associations = shell('cmd', 'companiondevice', 'list', '0');
    ensure(!/com\.android\.shell\s*\|\s*00:00:00:00:00:10/.test(associations), '工具关联遗留');
    const files = shell('ls', '-1', '/data/local/tmp').split(/\r?\n/).filter(l => /^aactl-displayd\.(apk|log)$|^aactl-camera-[0-9a-f-]{36}\.img$/.test(l));
    ensure(files.length === 0, '工具临时文件遗留');
    const forwards = raw('forward', '--list'); ensure(!forwards.split(/\r?\n/).some(l => l.startsWith(serial + ' ') && l.endsWith('localabstract:aactl-displayd-v1')), '工具 forward 遗留');
    if (baseline) ensure(JSON.stringify(cameraEvidence('最终清理')) === JSON.stringify(baseline), '相机最终数量不符');
    if (cacheBaseline) {
      const currentCache = cacheEvidence('共享缓存最终检查');
      report.sharedRuntimeCache = { baseline: cacheBaseline, current: currentCache, policy: '启动前已存在的共享目录原样保留；不是helper所有物，禁止删除' };
      ensure(JSON.stringify(currentCache) === JSON.stringify(cacheBaseline), '共享缓存改变，保留给负责人决定，不能声称清理通过');
    }
    report.cleaned = true; event('清理通过', { associations, files });
  } catch (e) { report.cleaned = false; report.cleanupError = e.message; report.ok = false; }
  report.finishedAt = new Date().toISOString(); save();
}
console.log(JSON.stringify({ ok: report.ok, cleaned: report.cleaned, error: report.error, cleanupError: report.cleanupError, evidence: root }));
if (!report.ok) process.exitCode = 1;
