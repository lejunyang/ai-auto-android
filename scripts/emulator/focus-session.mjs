/**
 * 脚本用途：为焦点测量提供固定模拟器守卫、按显示观察、动作时间线与 finally 清理。
 * 安全约束：只用仓库本地产物；不改全局设置、不设 PIN、不接触其它设备或 AVD 配置。
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { requireTestSerial } from './verify-virtual-display.mjs';
export const fingerprint = 'google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys';
export const fixturePackage = 'dev.aiauto.fixture';
export const ensure = (ok,message) => { if (!ok) throw Error(message); };
export const pause = ms => new Promise(resolve => setTimeout(resolve,ms));
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
/** 仅本地daemon瞬断且重新确认同一设备时重发一次只读命令；任何输入/安装/清理都不重发。 */
export function retryObservedRead(first,args,confirm,retry) {
 const readonly=args[0]==='-s'&&args[2]==='shell'&&(['getprop','dumpsys','ps','sha256sum'].includes(args[3])||(args[3]==='pm'&&args[4]==='path'));
 if(readonly&&!first.error&&first.status!==0&&/daemon still not running|cannot connect to daemon at tcp:5037/.test(String(first.stderr))&&confirm())return retry();
 return first;
}
/** IME注册列表可能超过32KiB，必须取当前客户端等原始行，不能截掉真正的当前状态。 */
export function currentImeEvidence(text) {
 return text.split(/\r?\n/).filter(l=>/^\s*(mCurClient=|mCurMethodId=|mCurTokenDisplayId=|mCurFocusedWindow=|mCurFocusedWindowClient=|mCurAttribute=|mInputShown=|mShowRequested=|mShowExplicitlyRequested=|mShowForced=|mImeWindowVis=|mInteractive=)/.test(l)||l.includes('mSelfReportedDisplayId=')).join('\n');
}
export function options(argv,extra=[],values=[]) {
 const result={};
 for(let i=0;i<argv.length;i+=2) {
  const key=argv[i]?.slice(2);ensure(['device','adb','aactl','helper','fixture','run',...extra,...values].includes(key)&&argv[i+1]&&!(key in result),'Invalid or duplicate option');result[key]=argv[i+1];
 }
 requireTestSerial(result.device);
 for(const key of ['adb','aactl','helper','fixture',...extra]) ensure(result[key]&&fs.statSync(result[key]).isFile(),`Missing local artifact: ${key}`);
 ensure(/^[a-zA-Z0-9-]{1,40}$/.test(result.run??''),'Safe run name required');return result;
}
export function node(xml,resourceId) {
 const matches=[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]).filter(n=>n.includes(`resource-id="${resourceId}"`));
 ensure(matches.length===1,`Expected exactly one node: ${resourceId}; found ${matches.length}`);return matches[0];
}
export function point(xml,resourceId,fraction=0.5) {
 const n=node(xml,resourceId),m=/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(n);ensure(m,'Missing node bounds');
 const [l,t,r,b]=m.slice(1).map(Number);ensure(r>l&&b>t,'Empty bounds');return {x:Math.floor((l+r)/2),y:Math.floor(t+(b-t)*fraction)};
}
export function attr(tag,name) {const m=new RegExp(`\\b${name}="([^"]*)"`).exec(tag);ensure(m,`Missing attribute ${name}`);return m[1];}

export class DisplaySession {
 constructor(opt,category) {
  this.opt=opt;this.serial=opt.device;this.started=Date.now();this.safe=false;this.helperStarted=false;this.lastXML='';
  this.root=path.resolve('scripts/emulator/build',category,opt.run);ensure(!fs.existsSync(this.root),'Evidence directory exists');fs.mkdirSync(this.root,{recursive:true});
  this.report={run:opt.run,startedAt:new Date().toISOString(),steps:[]};
 }
 exec(program,args,allow=false) {
  const invoke=argv=>spawnSync(program,argv,{encoding:'utf8',timeout:45000,maxBuffer:8*1024*1024,windowsHide:true,env:{...process.env,MSYS_NO_PATHCONV:'1',AACTL_ADB_PATH:this.opt.adb}});
  let r=invoke(args);
  if(program===this.opt.adb)r=retryObservedRead(r,args,()=>{
   const found=invoke(['devices','-l']),all=String(found.stdout).split(/\r?\n/).filter(l=>l&&!l.startsWith('List of devices'));
   if(found.status!==0||all.length!==1||!all[0].startsWith(`${this.serial} `)||!/\sdevice\s/.test(all[0])||(this.identity&&all[0]!==this.identity))return false;
   const identity=invoke(['-s',this.serial,'shell','getprop','ro.build.fingerprint']);
   if(identity.status!==0||identity.stdout.trim()!==fingerprint)return false;
   this.event('readonly-daemon-retry',{command:args[3],sameTransport:true});return true;
  },()=>invoke(args));
  ensure(!r.error,`Process error: ${r.error?.message}`);if(!allow) ensure(r.status===0,`${path.basename(program)} failed: ${r.stderr?.slice(0,300)}`);
  return {status:r.status,stdout:(r.stdout??'').trim(),stderr:(r.stderr??'').trim()};
 }
 raw(args,allow=false) {return this.exec(this.opt.adb,['-s',this.serial,...args],allow);}
 devices() {return this.exec(this.opt.adb,['devices','-l']).stdout.split(/\r?\n/).filter(l=>l&&!l.startsWith('List of devices'));}
 guard() {
  const all=this.devices();ensure(all.length===1&&all[0].startsWith(`${this.serial} `)&&/\sdevice\s/.test(all[0]),'Device count/serial/online state changed; stopping');
  if(this.identity) ensure(all[0]===this.identity,'Transport identity changed; stopping');
  ensure(this.raw(['shell','getprop','ro.build.fingerprint']).stdout===fingerprint,'Fingerprint changed; stopping');
 }
 shell(...args) {this.guard();return this.raw(['shell',...args]).stdout;}
 event(step,evidence={}) {const entry={step,elapsedMs:Date.now()-this.started,...evidence};this.report.steps.push(entry);console.error(JSON.stringify(entry));}
 envelope(args) {
  this.guard();const r=this.exec(this.opt.aactl,[...args,'--device',this.serial,'--json'],true);
  const value=JSON.parse(r.stdout);ensure(value.schemaVersion==='1.0'&&typeof value.ok==='boolean'&&(r.status===0)===value.ok,'Invalid CLI envelope');this.lastEnvelope={args:[...args],observedAt:new Date().toISOString(),result:value};return value;
 }
 cli(args) {const r=this.envelope(args);ensure(r.ok,`${r.error?.code}: ${r.error?.message}`);return r.data;}
 hierarchy(id) {
  const r=this.cli(['observe','hierarchy','--display',String(id)]),ids=[...r.xml.matchAll(/<display id="(\d+)"/g)].map(m=>Number(m[1]));
  ensure(r.format==='uiautomator-windows-xml'&&ids.length===1&&ids[0]===id,'Wrong hierarchy display/format');this.lastXML=r.xml;this.lastHierarchy={displayId:id,observedAt:new Date().toISOString()};this.lastObservation={...this.lastHierarchy,xml:r.xml};return r.xml;
 }
 focusObservation() {
  const xml=this.hierarchy(0),raw={...this.lastHierarchy,xml,window:this.shell('dumpsys','window'),ime:this.shell('dumpsys','input_method'),processes:this.shell('ps','-A','-o','PID,NAME'),activity:this.shell('dumpsys','activity','activities')};
  this.lastObservation=raw;return raw;
 }
 failureObservation(id) {
  // 非层级判据（如电源状态）超时前主动采关联层级；原错误响应与无输出必须如实保留。
  const result=this.envelope(['observe','hierarchy','--display',String(id)]);
  this.lastObservation={displayId:id,observedAt:new Date().toISOString(),xml:result.ok?result.data.xml:'',hierarchyError:result.error,window:this.shell('dumpsys','window'),ime:this.shell('dumpsys','input_method'),processes:this.shell('ps','-A','-o','PID,NAME'),activity:this.shell('dumpsys','activity','activities')};
 }
 preserveStateReadFailure() {
  // CLI内部的原始display输出未导出；先保存真实错误，再采同现场关联原文，不能冒称为内部字节。
  const original=this.lastEnvelope,index=(this.report.stateReadFailures??0)+1,prefix=`state-read-${index}`;
  const write=(name,value)=>{const bytes=Buffer.from(value),total=fs.readdirSync(this.root).reduce((n,f)=>n+fs.statSync(path.join(this.root,f)).size,0);ensure(total+bytes.length<1024*1024,'Evidence exceeds 1MiB');fs.writeFileSync(path.join(this.root,`${prefix}-${name}`),bytes);};
  write('response.json',JSON.stringify(original,null,2));
  const display=this.shell('dumpsys','display');ensure(Buffer.byteLength(display)<=128*1024,'Display diagnostic exceeds budget');write('related-display.txt',display);
  write('related-power.txt',this.shell('dumpsys','power').split(/\r?\n/).filter(l=>/mWakefulness|mDisplayGroup|mUserActivitySummary|mWakeLockSummary|mDisplayReady|mScreenState|Display Power|mHoldingDisplaySuspendBlocker/.test(l)).join('\n'));
  this.failureObservation(0);this.lastEnvelope=original;this.preserveEvidence('retryable-state-observation');this.lastEnvelope=original;
  this.report.stateReadFailures=index;
 }
 preserveEvidence(reason) {
  if(!this.safe)return;
  // 使用触发判据的原始层级，不重新dump覆盖它；截图和相关系统分段在任何清理/重试前采集。
  const raw=this.lastObservation;ensure(raw&&typeof raw.xml==='string','No original observation to preserve');
  const index=(this.report.evidence?.length??0)+1,prefix=`evidence-${index}`;
  const write=(name,value)=>{const bytes=Buffer.isBuffer(value)?value:Buffer.from(value);const total=fs.readdirSync(this.root).reduce((n,f)=>n+fs.statSync(path.join(this.root,f)).size,0);ensure(total+bytes.length<1024*1024,'Evidence exceeds 1MiB');fs.writeFileSync(path.join(this.root,`${prefix}-${name}`),bytes);};
  const redact=text=>text.replace(/\b(text|content-desc|title)="[^"]*"/g,'$1="[redacted]"');
  write('hierarchy.xml',redact(raw.xml));
  if(this.lastEnvelope)write('response.json',JSON.stringify(this.lastEnvelope,(_key,value)=>typeof value==='string'?redact(value):value,2));
  if(raw.hierarchyError)write('hierarchy-error.json',JSON.stringify(raw.hierarchyError));
  const window=raw.window??this.shell('dumpsys','window'),ime=raw.ime??this.shell('dumpsys','input_method'),activity=raw.activity??this.shell('dumpsys','activity','activities');
  const primary=window.split(/^\s*Display: mDisplayId=/m).find(p=>/^0\b/.test(p))??'';
  write('window.txt',(/mTopFocusedDisplayId=\d+/.exec(window)?.[0]??'')+'\nDisplay: mDisplayId='+primary);
  write('ime.txt',currentImeEvidence(ime));
  write('activity.txt',activity.split(/^Display #/m).filter(p=>p.startsWith('0 (activities')||p.startsWith(`${raw.displayId} (activities`)).join('\nDisplay #').slice(0,32768));
  write('processes.txt',(raw.processes??this.shell('ps','-A','-o','PID,NAME')).split(/\r?\n/).filter(l=>/settings|fixture|inputmethod/.test(l)).join('\n'));
  const target=path.join(this.root,`${prefix}-screen.png`);this.cli(['observe','screenshot','--display',String(raw.displayId),'--output',target]);ensure(fs.statSync(target).size<=512*1024,'Screenshot budget exceeded');
  ensure(fs.readdirSync(this.root).reduce((n,f)=>n+fs.statSync(path.join(this.root,f)).size,0)<1024*1024,'Evidence total exceeds 1MiB');
  this.report.evidence??=[];this.report.evidence.push({reason,prefix,source:raw.observedAt,capturedAt:new Date().toISOString(),displayId:raw.displayId,hierarchy:'original observation, redacted'});
  // 主屏基线由Settings主进程承载；退出记录与crash缓冲必须在模拟器销毁前保存。
  write('crash.txt',this.shell('logcat','-d','-b','crash','-t','80').slice(-32768));
  write('exit-info.txt',this.shell('dumpsys','activity','exit-info','com.android.settings').slice(0,32768));
  write('timeline.json',JSON.stringify(this.report.steps,null,2));
  this.lastPreservedObservation=raw;
 }
 action(id,kind,args,expected,packageName=fixturePackage) {
  // 调用方先读取当前状态并提供期望；每步结束必须重新观察，不在这里重试输入。
  this.guard();this.event('action',{package:packageName,action:kind,displayId:id,expected,verification:'fresh per-display hierarchy / dumpsys'});
  return this.cli(['action',kind,'--display',String(id),...args]);
 }
 tap(id,resourceId,fraction=0.5,packageName=fixturePackage) {
  const xy=point(this.hierarchy(id),resourceId,fraction);
  this.action(id,'tap',['--x',String(xy.x),'--y',String(xy.y)],`target ${resourceId}`,packageName);return this.hierarchy(id);
 }
 async prepare({startHelper=true}={}) {
  this.guard();this.identity=this.devices()[0];ensure(this.raw(['shell','getprop','sys.boot_completed']).stdout==='1','Wait for boot before running');
  ensure(this.raw(['emu','avd','name']).stdout.split('\n')[0].trim()==='osdk-test-35','Wrong AVD');
  ensure(this.shell('getprop','ro.build.version.sdk')==='35','Wrong API');
  ensure(this.raw(['shell','pidof','aactl-displayd'],true).stdout==='','Pre-existing helper; refusing takeover');
  ensure(this.raw(['forward','--list']).stdout==='','Pre-existing forwards; refusing takeover');
  const env={fingerprint,transport:this.identity,avd:'osdk-test-35',api:35,size:this.shell('wm','size'),density:this.shell('wm','density'),locale:this.shell('getprop','persist.sys.locale')||this.shell('getprop','ro.product.locale'),navigationMode:this.shell('settings','get','secure','navigation_mode'),abi:this.shell('getprop','ro.product.cpu.abi')};
  ensure(env.size==='Physical size: 320x640'&&env.density==='Physical density: 160'&&env.locale==='en-US'&&env.navigationMode==='2','Profile differs; refusing settings changes');
  for(const key of ['helper','fixture','aactl']) env[`${key}SHA256`]=sha256(fs.readFileSync(this.opt[key]));
  this.report.environment=env;this.safe=true;this.event('environment',env);
  this.guard();this.raw(['install','-r','-t',this.opt.fixture]);ensure(/versionCode=1\b/.test(this.shell('dumpsys','package',fixturePackage)),'Unexpected fixture version');
  if(startHelper)this.startHelper();
 }
 startHelper() {this.helperStarted=true;return this.cli(['display','start','--helper',this.opt.helper]);}
 describeSystemApp(packageName) {
  const info=this.shell('dumpsys','package',packageName),paths=this.shell('pm','path',packageName).split(/\r?\n/).map(l=>l.replace(/^package:/,''));
  ensure(paths.length&&paths.every(p=>/^\/(system|product|system_ext)\/[A-Za-z0-9_./-]+\.apk$/.test(p)),`Non-system app source: ${packageName}`);
  return {package:packageName,versionCode:/versionCode=(\d+)/.exec(info)?.[1],versionName:/versionName=([^\r\n]+)/.exec(info)?.[1],source:'fixed system image',apks:paths.map(p=>({path:p,sha256:this.shell('sha256sum',p).split(/\s+/)[0]}))};
 }
 cleanup() {
  if(!this.safe)return;
  // 原始失败现场由finish先保存，再取消测试对话框；取消失败仍继续清helper并关闭模拟器。
  try {this.primaryBaselineCleanup?.();}catch(e){
   this.report.ok=false;this.report.baselineCleanupError=e.message;
   try{this.preserveEvidence('baseline-cleanup');}catch(e){this.report.evidenceError=e.message;}
  }
  // 即使测试失败也清理本会话；身份变化时 guard 拒绝后续设备访问。
  try {
   this.guard();if(this.helperStarted)this.cli(['display','stop']);this.helperStarted=false;
   ensure(this.raw(['shell','pidof','aactl-displayd'],true).stdout==='','Helper remains');
   ensure(/Display Devices: size=1\b/.test(this.shell('dumpsys','display')),'Virtual displays remain');
   const forwards=this.raw(['forward','--list']).stdout;
   for(const line of forwards.split(/\r?\n/).filter(Boolean)) {
    const m=/^(emulator-5554) (tcp:\d+) localabstract:aactl-displayd-v1$/.exec(line);ensure(m,'Unknown forward remains');this.raw(['forward','--remove',m[2]]);
   }
   ensure(this.raw(['forward','--list']).stdout==='','Forward leak');
   this.shell('rm','-f','/data/local/tmp/aactl-focus-policy.jar','/data/local/tmp/aactl-primary-focus.jar');
   const files=this.raw(['shell','ls','/data/local/tmp/aactl-displayd.apk','/data/local/tmp/aactl-displayd.log','/data/local/tmp/aactl-focus-policy.jar','/data/local/tmp/aactl-primary-focus.jar'],true);
   ensure(files.status!==0&&files.stdout==='','Temporary files remain');
   this.report.cleanup={helper:0,displays:0,forwards:0,temporaryFiles:0,pin:'never set',globalSettings:'unchanged'};
  }catch(e){this.report.ok=false;this.report.cleanupError=e.message;}
  try {this.guard();this.raw(['emu','kill']);this.report.emulatorKillRequested=true;}catch(e){this.report.ok=false;this.report.killError=e.message;}
 }
 finish(error) {
  if(error){
   this.report.ok=false;this.report.error=error.message.slice(0,1000);
   // 在截图/清理调用覆盖响应缓存之前保存触发失败的实际信封，不能只保留断言的泛化报错。
   if(this.lastEnvelope)fs.writeFileSync(path.join(this.root,'failure-response.json'),JSON.stringify(this.lastEnvelope,(_key,value)=>typeof value==='string'?value.replace(/\b(text|content-desc|title)="[^"]*"/g,'$1="[redacted]"'):value,2));
   if(!this.failureAlreadyPreserved&&this.lastObservation!==this.lastPreservedObservation)try{this.preserveEvidence('failure');}catch(e){this.report.evidenceError=e.message.slice(0,300);}
  }
  this.cleanup();this.report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(this.root,'report.json'),JSON.stringify(this.report,null,2));console.log(JSON.stringify(this.report));if(!this.report.ok)process.exitCode=1;
 }
}
