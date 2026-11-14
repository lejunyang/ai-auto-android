/**
 * 脚本用途：主屏设置搜索输入时，逐步测量虚拟屏fixture对焦点和IME的影响；两种helper使用同一判据。
 * 安全约束：只在固定一次性模拟器操作本地fixture/系统搜索，不修改设置，不使用聚焦或IME策略探针。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DisplaySession, options, ensure, node, attr, fixturePackage } from './focus-session.mjs';
import { pngStats } from './virtual-display-png.mjs';
import { assertEmptyOwnedDisplay, topActivityOnDisplay } from './verify-virtual-display.mjs';
export const primaryPackage='com.google.android.settings.intelligence';
export const primaryInputID=`${primaryPackage}:id/open_search_view_edit_text`;

/** 证据严格取显示0与当前IME客户端；即使顶层焦点在其它显示也不替换主屏数据。 */
export function parseFocus(windowDump,imeDump,processes,xml) {
 const section=windowDump.split(/^\s*Display: mDisplayId=/m).find(s=>/^0\b/.test(s));ensure(section,'Missing primary display section');
 const field=(text,re,name)=>{const m=re.exec(text);ensure(m,`Missing current ${name}`);return m[1];};
 const client=field(imeDump,/^\s*mCurClient=(ClientState\{[^}]+\}|null)/m,'IME client');
 const display=/mSelfReportedDisplayId=(\d+)/.exec(client),pid=/mPid=(\d+)/.exec(client);
 const clientProcess=pid?processes.split(/\r?\n/).map(s=>s.trim()).find(s=>s.split(/\s+/)[0]===pid[1])?.replace(/^\d+\s+/,''):null;
 ensure(client==='null'||(display&&clientProcess),`Cannot attribute current IME client: ${client}; process=${clientProcess}; candidates=${processes.split(/\r?\n/).filter(l=>/settings.intelligence|fixture|inputmethod/.test(l)).join(',')}`);
 const primaryWindows=[...xml.matchAll(/<window\b[^>]*>[\s\S]*?<\/window>/g)].map(m=>m[0]);
 const appWindows=primaryWindows.filter(w=>w.includes(`resource-id="${primaryInputID}"`));ensure(appWindows.length===1,'Primary input window not unique');
 const input=node(xml,primaryInputID),appWindow=appWindows[0].match(/^<window\b[^>]*>/)[0];
 return {
  topFocusedDisplay:Number(field(windowDump,/mTopFocusedDisplayId=(\d+)/,'top focused display')),
  focusWindow:field(section,/^\s*mCurrentFocus=(.+)$/m,'focus window'),focusApp:field(section,/^\s*mFocusedApp=(.+)$/m,'focused app'),
  windowActive:attr(appWindow,'active')==='true',windowFocused:attr(appWindow,'focused')==='true',
  client,clientProcess,clientDisplay:display?Number(display[1]):null,
  imeService:field(imeDump,/^\s*mCurMethodId=(.+)$/m,'IME service'),imeTokenDisplay:Number(field(imeDump,/^\s*mCurTokenDisplayId=(\d+)/m,'IME token display')),
  inputShown:field(imeDump,/^\s*mInputShown=(true|false)/m,'input shown')==='true',
  primaryImeVisible:field(section,/type=ime\b[^\r\n]*visible=(true|false)/,'primary IME insets')==='true',
  primaryImeWindow:primaryWindows.some(w=>/^<window\b[^>]*type="TYPE_INPUT_METHOD"/.test(w)),
  primaryInput:attr(input,'text'),primaryInputFocused:attr(input,'focused')==='true'
 };
}
export function requireBaseline(b) {
 ensure(b.primaryInput==='Primary'&&b.primaryInputFocused&&b.clientProcess===primaryPackage&&b.clientDisplay===0&&b.inputShown&&b.primaryImeVisible&&b.primaryImeWindow,'Primary typing baseline not established');
}

/** 无窗口输出不等于空屏或坏XML，必须保留可执行的恢复建议。 */
export function requireWindowsUnavailable(result) {
 ensure(!result.ok&&result.error?.code==='WINDOWS_UNAVAILABLE'&&result.error.message.includes('Wake')&&result.error.message.includes('steal'),'Expected actionable WINDOWS_UNAVAILABLE');
}

async function main() {
 const opt=options(process.argv.slice(2),[],['mode','scenario']);ensure(['unmitigated','mitigated'].includes(opt.mode),'--mode unmitigated|mitigated required');
 ensure(opt.scenario===undefined||opt.scenario==='acceptance','--scenario acceptance is the only extension');
 ensure(!opt.scenario||opt.mode==='mitigated','isolated acceptance requires mitigated mode');
 const s=new DisplaySession(opt,'focus');s.report.mode=opt.mode;let id,virtualLaunched=false;
 const snapshot=()=>parseFocus(s.shell('dumpsys','window'),s.shell('dumpsys','input_method'),s.shell('ps','-A','-o','PID,NAME'),s.hierarchy(0));
 const virtual=()=>{
  if(id===undefined)return null;
  if(!virtualLaunched)return {launched:false,displayId:id,status:s.cli(['display','status']).displays.find(d=>d.displayId===id)};
  const xml=s.hierarchy(id),inputs=[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]).filter(n=>n.includes(`resource-id="${fixturePackage}:id/text_input"`));
  return {packages:[...new Set([...xml.matchAll(/package="([^"]*)"/g)].map(m=>m[1]))],input:inputs.length===1?attr(inputs[0],'text'):null,inputFocused:inputs.length===1?attr(inputs[0],'focused')==='true':null,click:/text="CLICK:(\d+)"/.exec(xml)?.[1]??null};
 };
 let probeIndex=0;
 const measure=step=>{
  const before=snapshot(),virtualBefore=virtual(),probe=String.fromCharCode(97+probeIndex++);
  s.action(0,'text',['--text',probe],`append ${probe} to primary search input`,primaryPackage);
  const after=snapshot(),virtualAfter=virtual();
  const r={phase:step,mode:opt.mode,before,virtualBefore,probe,after,virtualAfter,probeLanded:after.primaryInput===before.primaryInput+probe};
  s.event('measurement',r);return r;
 };
 try {
  await s.prepare({startHelper:false});
  s.report.systemApps=['com.android.settings',primaryPackage,'com.google.android.deskclock','com.google.android.inputmethod.latin'].map(p=>s.describeSystemApp(p));
  s.hierarchy(0);s.action(0,'launch',['--package','com.android.settings','--activity','.Settings'],'Settings primary search entry','com.android.settings');
  const xml=s.hierarchy(0),search=[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]).filter(n=>/text="Search settings"/.test(n));
  ensure(search.length===1,'Search settings entry not unique');s.tap(0,attr(search[0],'resource-id'),0.5,'com.android.settings');
  s.tap(0,primaryInputID,0.5,primaryPackage);s.action(0,'text',['--text','Primary'],'primary search text=Primary',primaryPackage);
  const baseline=snapshot();s.event('baseline',baseline);requireBaseline(baseline);measure('baseline');
  s.startHelper();const focus=opt.mode==='mitigated'?'isolated':'steal';
  const create=()=>{const d=s.cli(['display','create','--width','720','--height','1280','--dpi','240','--focus',focus]);ensure(d.focus===focus&&d.focusIsolated===(focus==='isolated'),'Wrong focus policy/actual isolation');return d.displayId;};
  id=create();measure('helper-create');
  s.hierarchy(0);s.action(id,'launch',['--package',fixturePackage,'--activity','.MainActivity'],'fixture PAGE:MAIN on virtual display');
  ensure(s.hierarchy(id).includes('text="PAGE:MAIN"'),'Virtual fixture launch failed');virtualLaunched=true;measure('launch-fixture');
  s.tap(id,`${fixturePackage}:id/click_target`);measure('tap-non-input');
  s.tap(id,`${fixturePackage}:id/text_input`,0.85);measure('tap-input');
  s.hierarchy(id);s.action(id,'text',['--text','Virtual'],'ASCII Virtual in virtual fixture');
  s.hierarchy(id);measure('text');
  s.hierarchy(id);s.action(id,'key',['--key','DELETE'],'delete one character from virtual fixture');s.hierarchy(id);measure('key-targeted');
  // 两屏都有可编辑内容时测不带显示的按键去向，记录两侧差值，不预设主屏或虚拟屏。
  const keyBefore={primary:snapshot(),virtual:virtual()};
  s.event('action',{package:'current input focus',action:'ui.pressKey DELETE',displayId:'omitted',expected:'measure actual destination',verification:'both display hierarchies'});
  s.cli(['action','key','--key','DELETE']);
  s.event('unqualified-key',{before:keyBefore,after:{primary:snapshot(),virtual:virtual()}});measure('key-unqualified');
  s.hierarchy(id);s.action(id,'launch',['--package','com.google.android.deskclock'],'Clock on virtual display','com.google.android.deskclock');
  ensure(s.hierarchy(id).includes('package="com.google.android.deskclock"'),'Clock missing on target');measure('launch-clock');
  const frame=path.join(s.root,'latest.png');s.cli(['observe','screenshot','--display',String(id),'--output',frame]);
  const stats=pngStats(fs.readFileSync(frame));ensure(stats.width===720&&stats.height===1280&&stats.nonBlackRatio>0.01,'Virtual frame invalid');fs.unlinkSync(frame);s.event('virtual-frame',stats);
  const rows=s.report.steps.filter(r=>r.step==='measurement');
  // 未缓解是测量而非必须不受影响；缓解后必须同时保住主屏与虚拟屏的可验证输入。
  if(opt.mode==='mitigated') {
   ensure(rows.every(r=>r.before.topFocusedDisplay===0&&r.before.clientProcess===primaryPackage&&r.before.clientDisplay===0&&r.before.inputShown&&r.before.primaryImeVisible&&r.before.primaryImeWindow&&r.before.windowActive&&r.before.windowFocused&&r.before.primaryInputFocused&&r.probeLanded),'Mitigation did not preserve all primary input criteria');
   ensure(rows.find(r=>r.phase==='text')?.virtualBefore.input==='Virtual','Virtual ASCII text did not arrive');
   ensure(rows.find(r=>r.phase==='key-targeted')?.virtualBefore.input==='Virtua','Virtual key did not arrive');
  }
  if(opt.scenario==='acceptance') {
   // 原焦点测量完整保留；清除时钟/fixture任务后，用新的空屏验证根Activity的BACK语义。
   s.report.focus='isolated';const primaryBefore=snapshot();
   s.cli(['display','close','--display',String(id)]);id=create();virtualLaunched=false;
   s.action(id,'launch',['--package',fixturePackage,'--activity','.MainActivity'],'fresh root Activity');
   s.tap(id,`${fixturePackage}:id/click_target`);
   ensure(s.hierarchy(id).includes('text="CLICK:1"'),'Fresh virtual tap failed');
   s.tap(id,`${fixturePackage}:id/text_input`,0.85);
   s.action(id,'text',['--text','BackCheck'],'BackCheck reaches focused virtual input');
   ensure(s.hierarchy(id).includes('text="INPUT:BackCheck"'),'Virtual input missing before BACK');
   s.action(id,'key',['--key','BACK'],'root Activity ends; owned empty display');
   const empty=s.envelope(['observe','hierarchy','--display',String(id)]);
   assertEmptyOwnedDisplay(empty,s.cli(['display','list']).displays.some(d=>d.displayId===id&&d.focus==='isolated'&&d.focusIsolated===true),topActivityOnDisplay(s.shell('dumpsys','activity','activities'),id));
   s.event('isolated-back',{code:empty.error.code,owned:true,activity:null});
   s.action(id,'launch',['--package',fixturePackage,'--activity','.MainActivity'],'PAGE:MAIN restored');
   ensure(s.hierarchy(id).includes('text="PAGE:MAIN"'),'Relaunch failed');
   s.tap(id,`${fixturePackage}:id/text_input`,0.85);
   s.action(id,'text',['--text','AwakeX'],'AwakeX in virtual input');
   s.action(id,'key',['--key','DELETE'],'Awake in virtual input');
   ensure(s.hierarchy(id).includes('text="INPUT:Awake"'),'Virtual key/text recovery failed');
   const p=snapshot();
   ensure(p.topFocusedDisplay===0&&p.clientProcess===primaryPackage&&p.clientDisplay===0&&p.inputShown&&p.primaryImeVisible&&p.primaryImeWindow&&p.windowActive&&p.windowFocused&&p.primaryInputFocused&&p.primaryInput===primaryBefore.primaryInput,'Primary input changed across BACK/relaunch');
   s.event('isolated-relaunch',{primary:p,virtualInput:'Awake'});
   const capture=phase=>{const file=path.join(s.root,'latest.png');s.cli(['observe','screenshot','--display',String(id),'--output',file]);const bytes=fs.readFileSync(file),stats=pngStats(bytes);fs.unlinkSync(file);ensure(stats.width===720&&stats.height===1280&&stats.nonBlackRatio>0.01,'Black or invalid frame');s.event('isolated-frame',{phase,...stats});};
   capture('awake');
   s.event('action',{action:'sleep',expected:'primary off; isolated hierarchy unavailable, frame/input retained'});
   s.cli(['action','sleep']);ensure(s.cli(['observe','screen-state']).on===false,'Primary did not sleep');
   capture('asleep-before-input');const missing=s.envelope(['observe','hierarchy','--display',String(id)]);requireWindowsUnavailable(missing);
   s.event('isolated-no-windows',{code:missing.error.code});
   // 息屏时无新层级，不猜坐标；键盘注入后用唤醒后的精确文本验证真实落点。
   s.event('action',{package:fixturePackage,displayId:id,action:'text/key',expected:'append Sleep, DELETE; after wake INPUT:AwakeSlee'});
   s.cli(['action','text','--display',String(id),'--text','Sleep']);s.cli(['action','key','--display',String(id),'--key','DELETE']);capture('asleep-after-input');
   ensure(s.cli(['observe','screen-state']).on===false,'Virtual injection woke primary');
   s.cli(['action','wake']);s.cli(['action','unlock']);
   ensure(s.hierarchy(id).includes('text="INPUT:AwakeSlee"'),'Sleeping injection or wake hierarchy recovery failed');
   const primaryAfter=node(s.hierarchy(0),primaryInputID);
   ensure(attr(primaryAfter,'text')===primaryBefore.primaryInput,'Sleeping virtual input changed primary text');
   s.event('isolated-rewoken',{hierarchyRestored:true,virtualInput:'AwakeSlee',primaryText:attr(primaryAfter,'text')});
  }
  s.report.ok=true;
 }catch(e){s.report.ok=false;s.report.error=e.message;}
 finally{s.finish(s.report.ok?null:Error(s.report.error??'measurement failed'));}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(e=>{console.error(e.message);process.exitCode=1;});
