/**
 * 脚本用途：固定 read-only 模拟器验证摄像头错误、保留关联后同号复用、默认移除和普通屏可用。
 * 安全约束：复用既有唯一设备/指纹守卫和 finally 清理，只安装本地测试 fixture，不操作真机。
 */
import { DisplaySession, options, ensure } from './focus-session.mjs';

const opt = options(process.argv.slice(2));
const s = new DisplaySession(opt, 'virtual-camera');
const marker = /\bcom\.android\.shell\s*\|\s*00:00:00:00:00:10\b/;
const associationRows = value => value.split(/\r?\n/).filter(l => l.includes('|') && !l.includes('Package Name')).sort();
// 只从完整匹配的工具行取编号，不把其它包/地址或Max ID当作已保留的关联。
function toolAssociationID(value) {
  const ids = value.split(/\r?\n/).map(l => /^\s*([1-9][0-9]*)\s*\|\s*com\.android\.shell\s*\|\s*00:00:00:00:00:10\s*$/.exec(l)?.[1]).filter(Boolean);
  ensure(ids.length <= 1, '重复工具关联');
  return Number(ids[0] ?? 0);
}
function ensureNoToolFiles() {
  const files = s.shell('ls', '-1', '/data/local/tmp').split(/\r?\n/);
  ensure(!files.some(l => /^(aactl-displayd\.(apk|log)|aactl-camera-[0-9a-f-]{36}\.img)$/.test(l)), 'helper或图片临时文件遗留');
}
let baseline;
try {
  await s.prepare({ startHelper: false });
  baseline = s.shell('cmd', 'companiondevice', 'list', '0');
  ensure(!marker.test(baseline), '工具标记关联已有遗留，基线不干净');
  // finish先保留失败现场再调用此清理钩子；即使停在保留阶段，也以默认stop移除关联后再核对。
  s.primaryBaselineCleanup = () => {
    if (s.helperStarted) {
      try { s.cli(['display', 'stop']); }
      finally { s.helperStarted = false; } // 清理失败不盲目重复stop，后续守卫仍检查残留并关闭此测试模拟器。
    }
    ensure(JSON.stringify(associationRows(s.shell('cmd', 'companiondevice', 'list', '0'))) === JSON.stringify(associationRows(baseline)), '最终关联未恢复基线');
    ensureNoToolFiles();
    s.report.cameraAssociations = 0;
    s.event('final-camera-association-cleanup', { remainingToolAssociations: 0 });
  };
  s.helperStarted = true;
  const hello = s.cli(['display', 'start', '--virtual-camera', '--helper', opt.helper]);
  ensure(hello.protocolVersion === 2 && hello.capabilities.virtualCamera === true, '摄像头能力未显式启用');
  const associated = s.shell('cmd', 'companiondevice', 'list', '0');
  ensure(marker.test(associated), '未建立工具标记关联');
  const id = toolAssociationID(associated);
  ensure(id > 0, '关联编号缺失');
  s.event('association-created', { userId: 0, id });
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
  const kept = s.cli(['display', 'stop', '--keep-camera-association']);
  ensure(kept.stopped && kept.keptCameraAssociation?.userId === 0 && kept.keptCameraAssociation?.id === id, '保留结果未报告实际关联编号');
  ensure(toolAssociationID(s.shell('cmd', 'companiondevice', 'list', '0')) === id, '保留后编号改变或关联消失');
  ensure(s.raw(['shell', 'pidof', 'aactl-displayd'], true).stdout === '', '保留后helper仍运行');
  ensureNoToolFiles();
  s.event('association-kept', { ...kept, helper: 0, temporaryFiles: 0 });
  // helperStarted保持true表示本会话仍拥有待清理关联，任意后续失败都必须默认stop，不留常驻关联。
  const restarted = s.cli(['display', 'start', '--virtual-camera', '--helper', opt.helper]);
  ensure(restarted.capabilities.virtualCamera && toolAssociationID(s.shell('cmd', 'companiondevice', 'list', '0')) === id, '重启未复用同一关联');
  s.event('association-reused-after-restart', { userId: 0, id });
  s.cli(['display', 'stop']); s.helperStarted = false;
  ensure(JSON.stringify(associationRows(s.shell('cmd', 'companiondevice', 'list', '0'))) === JSON.stringify(associationRows(baseline)), '工具清理影响其它关联或有遗留');
  s.event('association-removed', { userId: 0, id, remainingToolAssociations: 0 });
  ensure(!s.shell('ls', '-1', '/data/local/tmp').split(/\r?\n/).some(l => /^aactl-camera-[0-9a-f-]{36}\.img$/.test(l)), '图片临时文件遗留');
  // 显式开启入口不存在时必须给出可操作错误，不能因为设备支持能力而暗中创建。
  s.startHelper();
  const disabled = s.envelope(['display', 'create', '--width', '640', '--height', '480', '--dpi', '240', '--camera', 'back']);
  ensure(!disabled.ok && disabled.error.code === 'VIRTUAL_CAMERA_NOT_ENABLED', '普通 helper 隐式开启摄像头');
  s.report.ok = true;
} catch (e) { s.report.ok = false; s.report.error = e.message; }
finally { s.finish(s.report.ok ? null : Error(s.report.error)); }
