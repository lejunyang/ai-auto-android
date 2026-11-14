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
export function options(argv,extra=[]) {
 const result={};
 for(let i=0;i<argv.length;i+=2) {
  const key=argv[i]?.slice(2);ensure(['device','adb','aactl','helper','fixture','run',...extra].includes(key)&&argv[i+1]&&!(key in result),'Invalid or duplicate option');result[key]=argv[i+1];
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
  const r=spawnSync(program,args,{encoding:'utf8',timeout:45000,maxBuffer:8*1024*1024,windowsHide:true,env:{...process.env,MSYS_NO_PATHCONV:'1',AACTL_ADB_PATH:this.opt.adb}});
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
  const value=JSON.parse(r.stdout);ensure(value.schemaVersion==='1.0'&&typeof value.ok==='boolean'&&(r.status===0)===value.ok,'Invalid CLI envelope');return value;
 }
 cli(args) {const r=this.envelope(args);ensure(r.ok,`${r.error?.code}: ${r.error?.message}`);return r.data;}
 hierarchy(id) {
  const r=this.cli(['observe','hierarchy','--display',String(id)]),ids=[...r.xml.matchAll(/<display id="(\d+)"/g)].map(m=>Number(m[1]));
  ensure(r.format==='uiautomator-windows-xml'&&ids.length===1&&ids[0]===id,'Wrong hierarchy display/format');this.lastXML=r.xml;this.lastHierarchy={displayId:id,observedAt:new Date().toISOString()};return r.xml;
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
 async prepare() {
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
  this.helperStarted=true;this.cli(['display','start','--helper',this.opt.helper]);
 }
 cleanup() {
  if(!this.safe)return;
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
   // 失败后重新观察，不能把动作前缓存冒充动作后证据；抓取失败也明确标注来源时间。
   let fresh=false;
   if(this.safe)try{this.hierarchy(0);fresh=true;}catch(e){this.report.hierarchyEvidenceError=e.message.slice(0,300);}
   this.report.failureHierarchy={...this.lastHierarchy,capturedAfterFailure:fresh};
   fs.writeFileSync(path.join(this.root,'failure-hierarchy.xml'),this.lastXML.replace(/\b(text|content-desc|title)="[^"]*"/g,'$1="[redacted]"').slice(0,65536));
   if(this.safe)try {
    this.guard();const log=this.raw(['logcat','-d','-t','40','-s','displayd:I']).stdout;
    fs.writeFileSync(path.join(this.root,'failure-log-window.txt'),log.slice(-16384));
    // 一次性模拟器的主屏只有本地 fixture；图片限512KiB，所有失败证据总额小于1MiB。
    if(this.lastXML.includes(`package="${fixturePackage}"`)) {
     const target=path.join(this.root,'failure-primary.png');this.cli(['observe','screenshot','--display','0','--output',target]);
     if(fs.statSync(target).size>512*1024)fs.unlinkSync(target);
    }
   }catch(e){this.report.evidenceError=e.message.slice(0,300);}
  }
  this.cleanup();this.report.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(this.root,'report.json'),JSON.stringify(this.report,null,2));console.log(JSON.stringify(this.report));if(!this.report.ok)process.exitCode=1;
 }
}
