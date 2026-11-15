/**
 * 脚本用途：在负责人真机只验证 helper 的摄像头注册、图片接收、显示标志和清理，不安装、授权或启动任何应用。
 * 安全约束：每步核对唯一 serial、固定指纹、当前用户未锁定；异常保留证据后仅在守卫仍通过时清理，不重试动作。
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { deflateSync } from 'node:zlib';
import { createHash } from 'node:crypto';

const serial = '324462538298';
const fingerprint = 'nubia/CN_PQ85A02/PQ85A02:16/BQ2A.251016.001-BP2A.250605.031.A3/b6416:user/release-keys';
const opt = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i]?.slice(2);
  if (!['device', 'adb', 'aactl', 'helper', 'evidence'].includes(key) || !process.argv[i + 1] || key in opt) throw Error('参数无效或重复');
  opt[key] = process.argv[i + 1];
}
const ensure = (value, message) => { if (!value) throw Error(message); };
ensure(opt.device === serial, '仅允许明确的负责人真机 serial');
for (const key of ['adb', 'aactl', 'helper']) ensure(opt[key] && fs.statSync(opt[key]).isFile(), `必须传入真实可执行文件/产物路径：${key}`);
ensure(opt.evidence && !fs.existsSync(opt.evidence), '证据目录必须为新的仓库外目录');
const root = path.resolve(opt.evidence), repo = path.resolve(import.meta.dirname, '..');
ensure(!root.toLowerCase().startsWith(repo.toLowerCase() + path.sep) && root.toLowerCase() !== repo.toLowerCase(), '真机证据不得写入仓库');
fs.mkdirSync(root, { recursive: true });
const report = { serial, fingerprint, startedAt: new Date().toISOString(), steps: [] };
let identity, baseline, helperStarted = false;
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
  const dump = shell('dumpsys', 'media.camera');
  const lines = dump.split(/\r?\n/).filter(l => /^Number of |^== Camera device |^== Camera Provider HAL.*\/virtual\//.test(l) || /deviceId|Device Id|Device ID|Virtual device|virtualDevice/i.test(l));
  fs.writeFileSync(path.join(root, `${label}-camera.txt`), lines.join('\n'));
  const counts = [...dump.matchAll(/^Number of ([^:\r\n]+): (\d+)/gm)].map(m => [m[1], Number(m[2])]);
  const providers = lines.filter(l => /^== Camera Provider HAL.*\/virtual\//.test(l));
  ensure(counts.length >= 2 && providers.length === 1, '无法解析相机数量或虚拟 provider');
  event(label, { counts, providers }); return { counts, providers };
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
  baseline = cameraEvidence('相机基线'); focusEvidence('焦点基线');
  helperStarted = true;
  const hello = cli(['display', 'start', '--virtual-camera', '--helper', opt.helper]).data;
  ensure(hello.protocolVersion === 2 && hello.capabilities.virtualCamera === true, '能力握手不符');
  for (const focus of ['isolated', 'steal']) {
    const screen = cli(['display', 'create', '--width', '720', '--height', '1280', '--dpi', '240', '--focus', focus, '--camera', 'back']).data;
    ensure(screen.displayId > 0 && screen.camera === 'back' && screen.cameraInfo.virtualDeviceId > 0 && (screen.displayFlags & 2) === 0, '归属/安全显示标志不符');
    const registered = cameraEvidence(`${focus}-注册`);
    ensure(registered.counts[0][1] === baseline.counts[0][1] + 1 && /1 devices:/.test(registered.providers[0]), '未获得新增一个虚拟摄像头的证据');
    cli(['display', 'list']); cli(['display', 'status']); focusEvidence(`${focus}-空屏焦点`);
    cli(['observe', 'hierarchy', '--display', String(screen.displayId)], ['DISPLAY_EMPTY', 'WINDOWS_UNAVAILABLE']);
    cli(['observe', 'screenshot', '--display', String(screen.displayId), '--output', path.join(root, `${focus}-empty.png`)], undefined, true);
    for (const portrait of [false, true]) {
      const file = path.join(root, portrait ? 'portrait.png' : 'quadrants.png'); pattern(file, portrait);
      cli(['camera', 'set-image', '--display', String(screen.displayId), '--image', file]);
    }
    const status = cli(['display', 'status']).data;
    ensure(status.displays.length === 1 && status.displays[0].cameraInfo.streams.length === 0, '无应用时出现非预期流');
    cli(['display', 'close', '--display', String(screen.displayId)]);
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
    report.cleaned = true; event('清理通过', { associations, files });
  } catch (e) { report.cleaned = false; report.cleanupError = e.message; report.ok = false; }
  report.finishedAt = new Date().toISOString(); save();
}
console.log(JSON.stringify({ ok: report.ok, cleaned: report.cleaned, error: report.error, cleanupError: report.cleanupError, evidence: root }));
if (!report.ok) process.exitCode = 1;
