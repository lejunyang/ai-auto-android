/**
 * 脚本用途：按显示测量虚拟屏动作对主屏焦点与 IME 的影响，原始/缓解两轮使用相同判据。
 * 安全约束：只在固定一次性模拟器运行；输入仅为 fixture/设置搜索，不改设置或确认任何敏感操作。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DisplaySession, options, ensure, node, point, attr, fixturePackage, pause } from './focus-session.mjs';

/** 按显示分段取当前窗口与 Insets，不使用普通活动窗口层级或历史 IME 条目。 */
export function parseFocus(windowDump,imeDump,processes,xml) {
 const section=windowDump.split(/^\s*Display: mDisplayId=/m).find(s=>/^0\b/.test(s));ensure(section,'Missing primary display section');
 const field=(text,re,name)=>{const m=re.exec(text);ensure(m,`Missing current ${name}`);return m[1];};
 const focusWindow=field(section,/^\s*mCurrentFocus=(.+)$/m,'focus window');
 const focusApp=field(section,/^\s*mFocusedApp=(.+)$/m,'focused app');
 const client=field(imeDump,/^\s*mCurClient=(ClientState\{[^}]+\}|null)/m,'IME client');
 const clientDisplay=/mSelfReportedDisplayId=(\d+)/.exec(client),pid=/mPid=(\d+)/.exec(client);
 const clientProcess=pid?processes.split(/\r?\n/).map(s=>s.trim()).find(s=>s.startsWith(`${pid[1]} `))?.replace(/^\d+\s+/,''):null;
 ensure(client==='null'||(clientDisplay&&clientProcess),'Cannot attribute current IME client');
 const primaryImeVisible=field(section,/type=ime\b[^\r\n]*visible=(true|false)/,'primary IME insets')==='true';
 return {focusWindow,focusApp,client,clientProcess,clientDisplay:clientDisplay?Number(clientDisplay[1]):null,
  imeService:field(imeDump,/^\s*mCurMethodId=(.+)$/m,'IME service'),
  imeTokenDisplay:Number(field(imeDump,/^\s*mCurTokenDisplayId=(\d+)/m,'IME token display')),
  inputShown:field(imeDump,/^\s*mInputShown=(true|false)/m,'input shown')==='true',primaryImeVisible,
  primaryInput:attr(node(xml,`${fixturePackage}:id/text_input`),'text'),primaryInputFocused:attr(node(xml,`${fixturePackage}:id/text_input`),'focused')==='true'};
}

async function main() {
 const opt=options(process.argv.slice(2),['policy','focus']),s=new DisplaySession(opt,'focus');let policyDisplay,originalPolicy;
 const policy=op=>{
  s.guard();const r=s.raw(['shell',`CLASSPATH=/data/local/tmp/aactl-focus-policy.jar app_process / FocusImePolicy ${policyDisplay} ${op}`]);
  const m=/before=(\d+) after=(\d+)/.exec(r.stdout);ensure(m,'Fixed policy probe failed');return {before:Number(m[1]),after:Number(m[2])};
 };
 const snapshot=()=>{
  const xml=s.hierarchy(0);
  return parseFocus(s.shell('dumpsys','window','displays'),s.shell('dumpsys','input_method'),s.shell('ps','-A','-o','PID,NAME'),xml);
 };
 const measure=(series,step,probe)=>{
  const before=snapshot();
  s.action(0,'text',['--text',probe],`append ${probe} to primary fixture input`);
  const after=snapshot();
  const result={series,step,before,probe,after,probeLanded:after.primaryInput===before.primaryInput+probe};
  s.event('measurement',result);return result;
 };
 const searchInput=xml=>{
  const nodes=[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]).filter(n=>n.includes('class="android.widget.EditText"')&&!n.includes('package="com.google.android.inputmethod.latin"'));
  ensure(nodes.length===1,`Expected one virtual search input, found ${nodes.length}`);return attr(nodes[0],'resource-id');
 };
 const describePackage=packageName=>{
  const info=s.shell('dumpsys','package',packageName),paths=s.shell('pm','path',packageName).split(/\r?\n/).map(l=>l.replace(/^package:/,''));
  ensure(paths.length&&paths.every(p=>/^\/(system|product|system_ext)\/[A-Za-z0-9_./-]+\.apk$/.test(p)),`Non-system app source: ${packageName}`);
  return {package:packageName,versionCode:/versionCode=(\d+)/.exec(info)?.[1],versionName:/versionName=([^\r\n]+)/.exec(info)?.[1],source:'fixed system image',apks:paths.map(p=>({path:p,sha256:s.shell('sha256sum',p).split(/\s+/)[0]}))};
 };
 try {
  await s.prepare();s.guard();s.raw(['push',opt.policy,'/data/local/tmp/aactl-focus-policy.jar']);
  s.guard();s.raw(['push',opt.focus,'/data/local/tmp/aactl-primary-focus.jar']);
  s.report.systemApps=['com.android.settings','com.google.android.settings.intelligence','com.google.android.deskclock','com.google.android.inputmethod.latin'].map(describePackage);
  for(const series of ['unmitigated','local-policy']) {
   const screen=s.cli(['display','create','--width','720','--height','1280','--dpi','240']),id=screen.displayId;policyDisplay=id;originalPolicy=policy('get').before;
   s.event('policy',{series,displayId:id,originalPolicy});
   if(series==='local-policy') {const r=policy('local');ensure(r.after===0&&r.before!==r.after,'Local policy not applied as a real change');s.event('mitigation',{method:'IWindowManager.setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_LOCAL=0)',...r});}
   // 先准备另一应用的搜索输入框，再构造用户在主屏输入的基线，不把准备阶段混入测量。
   s.hierarchy(0);s.action(id,'launch',['--package','com.android.settings','--activity','.Settings'],'Settings on virtual display','com.android.settings');
   let xml=s.hierarchy(id);
   const searchNodes=[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]).filter(n=>/text="Search settings"/.test(n));
   ensure(searchNodes.length===1,'Settings search entry not unique');
   const searchID=attr(searchNodes[0],'resource-id');s.tap(id,searchID,0.5,'com.android.settings');
   xml=s.hierarchy(id);const inputID=searchInput(xml);s.event('virtual-input-ready',{series,inputID});
   s.event('action',{package:fixturePackage,action:'app.stop',expected:'reset only disposable fixture before independent baseline'});s.cli(['action','stop','--package',fixturePackage]);
   s.hierarchy(0);s.action(0,'launch',['--package',fixturePackage,'--activity','.MainActivity'],'fixture primary input baseline');
   // 固定主屏输入框被标题栏遮挡，框架按显示与 resource ID 聚焦；不改测量判据或 fixture。
   s.hierarchy(0);s.event('action',{package:fixturePackage,displayId:0,action:'test-only UiAutomation ACTION_FOCUS/ACTION_CLICK',expected:'primary fixture input focused and IME visible',verification:'same per-display snapshot baseline'});
   s.shell('CLASSPATH=/data/local/tmp/aactl-primary-focus.jar app_process / PrimaryInputFocus');
   ensure(attr(node(s.hierarchy(0),`${fixturePackage}:id/text_input`),'focused')==='true','Framework did not focus primary input');
   s.action(0,'text',['--text','Primary'],'primary input contains Primary');
   const baseline=snapshot();s.event('baseline-observation',{series,...baseline});ensure(baseline.primaryInput==='Primary'&&baseline.primaryInputFocused&&baseline.clientProcess===fixturePackage&&baseline.clientDisplay===0&&baseline.inputShown&&baseline.primaryImeVisible,'Primary typing baseline not established');
   s.event('baseline',{series,...baseline});
   measure(series,'baseline','a');
   // 非输入区坐标来自当前目标屏 content bounds 的下部；该搜索页没有可执行的设置项。
   xml=s.hierarchy(id);const bodyID='android:id/content';const xy=point(xml,bodyID,0.75);
   s.action(id,'tap',['--x',String(xy.x),'--y',String(xy.y)],'tap non-input search background','com.google.android.settings.intelligence');s.hierarchy(id);measure(series,'tap-non-input','b');
   s.tap(id,inputID,0.85,'com.google.android.settings.intelligence');measure(series,'tap-input','c');
   s.hierarchy(id);s.action(id,'text',['--text','VirtualProbe'],'query text reaches virtual search only','com.google.android.settings.intelligence');
   ensure(attr(node(s.hierarchy(id),inputID),'text').includes('VirtualProbe'),'Virtual text did not reach search field');measure(series,'text','d');
   s.hierarchy(id);s.action(id,'key',['--key','DPAD_LEFT'],'move virtual text cursor without changing text','com.google.android.settings.intelligence');
   ensure(attr(node(s.hierarchy(id),inputID),'text').includes('VirtualProbe'),'Virtual key unexpectedly changed text');measure(series,'key','e');
   s.hierarchy(id);s.action(id,'launch',['--package','com.google.android.deskclock'],'launch Clock on virtual display','com.google.android.deskclock');
   ensure(s.hierarchy(id).includes('package="com.google.android.deskclock"'),'Clock not on target display');measure(series,'launch','f');
   const restored=policy(`restore${originalPolicy}`);ensure(restored.after===originalPolicy,'IME policy restore failed');originalPolicy=undefined;
   s.cli(['display','close','--display',String(id)]);policyDisplay=undefined;
  }
  s.report.ok=true;
 }catch(e){s.report.ok=false;s.report.error=e.message;}
 finally {
  if(originalPolicy!==undefined) {try{const r=policy(`restore${originalPolicy}`);ensure(r.after===originalPolicy,'Policy not restored');s.report.policyRestored=true;}catch(e){s.report.policyRestoreError=e.message;s.report.ok=false;}}
  s.finish(s.report.ok?null:Error(s.report.error??'cleanup failure'));
 }
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(e=>{console.error(e.message);process.exitCode=1;});
