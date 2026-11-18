/** 脚本用途：保留窗口dump的原始产出形态，比较焦点标志与主屏息屏的组合；仅固定一次性模拟器。 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { DisplaySession, options, ensure, fixturePackage, pause, sha256 } from './focus-session.mjs';
import { pngStats } from './virtual-display-png.mjs';

const opt=options(process.argv.slice(2),[],['mode']);
ensure(['isolated','steal'].includes(opt.mode),'--mode isolated|steal required; helper must match the label');
const s=new DisplaySession(opt,'window-dumps'),remote='/data/local/tmp/aactl-window-probe.xml';
s.report.mode=opt.mode;let rawEvidenceBytes=0;
const dump=phase=>{
 s.shell('rm','-f',remote);
 const command=s.raw(['shell','uiautomator','dump','--windows',remote],true);
 const exists=s.raw(['shell','test','-f',remote],true).status===0;
 let bytes=Buffer.alloc(0);
 if(exists) {
  s.guard();
  const read=spawnSync(opt.adb,['-s',opt.device,'exec-out','cat',remote],{timeout:15000,maxBuffer:8*1024*1024,windowsHide:true,env:{...process.env,MSYS_NO_PATHCONV:'1'}});
  ensure(!read.error&&read.status===0,`Cannot read raw window dump: ${read.error?.code??read.status}`);bytes=read.stdout;
  // 读取预算与生产解析器一致，原始证据总额另限512KiB；超额时仍记录准确长度、摘要及分组。
  if(rawEvidenceBytes+bytes.length<=512*1024){fs.writeFileSync(path.join(s.root,`${phase}.xml`),bytes);rawEvidenceBytes+=bytes.length;}
 }
 const result={phase,commandStatus:command.status,stdout:command.stdout,stderr:command.stderr,exists,bytes:bytes.length,sha256:sha256(bytes),displayIds:[...bytes.toString('utf8').matchAll(/<display id="(\d+)"/g)].map(m=>Number(m[1])),topFocusedDisplay:Number(/mTopFocusedDisplayId=(\d+)/.exec(s.shell('dumpsys','window'))?.[1])};
 s.event('raw-dump',result);s.shell('rm','-f',remote);return result;
};
const frame=(id,phase)=>{
 const file=path.join(s.root,'frame.png');s.cli(['observe','screenshot','--display',String(id),'--output',file]);
 const stats=pngStats(fs.readFileSync(file));fs.unlinkSync(file);ensure(stats.width===720&&stats.height===1280&&stats.nonBlackRatio>0.01,'Invalid frame');s.event('frame',{phase,...stats});
};
try {
 await s.prepare();s.cli(['action','unlock']);
 // 最终脚本使用v2显式策略；Round10的旧标志对照使用当时的v1客户端/脚本与独立jar。
 const id=s.cli(['display','create','--width','720','--height','1280','--dpi','240','--focus',opt.mode]).displayId;
 s.event('action',{package:fixturePackage,action:'launch',displayId:id,expected:'fixture on virtual display'});
 s.cli(['action','launch','--display',String(id),'--package',fixturePackage,'--activity','.MainActivity']);
 ensure(s.hierarchy(id).includes('text="PAGE:MAIN"'),'Launch failed');
 s.tap(id,`${fixturePackage}:id/text_input`,0.85);
 s.action(id,'text',['--text','Awake'],'Awake in virtual input');ensure(s.hierarchy(id).includes('text="INPUT:Awake"'),'Awake injection failed');
 dump('awake');frame(id,'awake');
 s.event('action',{action:'sleep',expected:'primary off; virtual group remains awake'});s.cli(['action','sleep']);await pause(1000);
 ensure(s.cli(['observe','screen-state']).on===false,'Primary still awake');dump('asleep');frame(id,'asleep-before-input');
 s.event('action',{package:fixturePackage,action:'text/key',displayId:id,expected:'append Sleep then DELETE; verify after primary wake'});
 s.cli(['action','text','--display',String(id),'--text','Sleep']);s.cli(['action','key','--display',String(id),'--key','DELETE']);
 frame(id,'asleep-after-input');dump('asleep-after-input');
 s.cli(['action','wake']);s.cli(['action','unlock']);dump('rewoken');
 ensure(s.hierarchy(id).includes('text="INPUT:AwakeSlee"'),'Sleeping text/key injection did not arrive');
 s.event('input-verified',{awake:'Awake',afterSleep:'AwakeSlee'});s.report.ok=true;
}catch(e){s.report.ok=false;s.report.error=e.message;}
finally{if(s.safe)try{s.shell('rm','-f',remote);}catch(e){s.report.rawCleanupError=e.message;}s.finish(s.report.ok?null:Error(s.report.error));}
