/**
 * 脚本用途：固定 read-only 模拟器验证虚拟摄像头不可用的明确错误、关联清理和普通虚拟屏继续可用。
 * 安全约束：复用既有唯一设备/指纹守卫和 finally 清理，只安装本地测试 fixture，不操作真机。
 */
import { DisplaySession, options, ensure } from './focus-session.mjs';

const opt = options(process.argv.slice(2));
const s = new DisplaySession(opt, 'virtual-camera');
const marker = /\bcom\.android\.shell\s*\|\s*00:00:00:00:00:10\b/;
const associationRows = value => value.split(/\r?\n/).filter(l => l.includes('|') && !l.includes('Package Name')).sort();
let baseline;
try {
  await s.prepare({ startHelper: false });
  baseline = s.shell('cmd', 'companiondevice', 'list', '0');
  ensure(!marker.test(baseline), '工具标记关联已有遗留，基线不干净');
  s.helperStarted = true;
  const hello = s.cli(['display', 'start', '--virtual-camera', '--helper', opt.helper]);
  ensure(hello.protocolVersion === 2 && hello.capabilities.virtualCamera === true, '摄像头能力未显式启用');
  const associated = s.shell('cmd', 'companiondevice', 'list', '0');
  ensure(marker.test(associated), '未建立工具标记关联');
  const repeated = s.cli(['display', 'start', '--virtual-camera', '--helper', opt.helper]);
  ensure(repeated.capabilities.virtualCamera && s.shell('cmd', 'companiondevice', 'list', '0') === associated, '重复启动没有复用关联');
  const result = s.envelope(['display', 'create', '--width', '640', '--height', '480', '--dpi', '240', '--camera', 'back']);
  if (result.ok) {
    s.event('camera-actual-supported', { screen: result.data });
    s.cli(['display', 'close', '--display', String(result.data.displayId)]);
  } else {
    ensure(result.error.code === 'VIRTUAL_CAMERA_UNAVAILABLE' && result.error.message.length > 20, '错误没有明确说明摄像头不可用及原因');
    s.event('camera-unavailable', { error: result.error });
    ensure(s.cli(['display', 'list']).displays.length === 0, '创建失败遗留虚拟屏');
  }
  const normal = s.cli(['display', 'create', '--width', '640', '--height', '480', '--dpi', '240']);
  ensure(normal.displayId > 0 && normal.camera === undefined, '普通屏受到摄像头失败影响');
  s.cli(['display', 'close', '--display', String(normal.displayId)]);
  s.cli(['display', 'stop']); s.helperStarted = false;
  ensure(JSON.stringify(associationRows(s.shell('cmd', 'companiondevice', 'list', '0'))) === JSON.stringify(associationRows(baseline)), '工具清理影响其它关联或有遗留');
  ensure(!s.shell('ls', '-1', '/data/local/tmp').split(/\r?\n/).some(l => /^aactl-camera-[0-9a-f-]{36}\.img$/.test(l)), '图片临时文件遗留');
  // 显式开启入口不存在时必须给出可操作错误，不能因为设备支持能力而暗中创建。
  s.startHelper();
  const disabled = s.envelope(['display', 'create', '--width', '640', '--height', '480', '--dpi', '240', '--camera', 'back']);
  ensure(!disabled.ok && disabled.error.code === 'VIRTUAL_CAMERA_NOT_ENABLED', '普通 helper 隐式开启摄像头');
  s.report.ok = true;
} catch (e) { s.report.ok = false; s.report.error = e.message; }
finally { s.finish(s.report.ok ? null : Error(s.report.error)); }
