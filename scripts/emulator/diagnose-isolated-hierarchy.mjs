/** 脚本用途：最小复现主屏输入时isolated息屏层级，先保存原始UIA产出与CLI响应，再判定契约。 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { DisplaySession,options,ensure,sha256 } from './focus-session.mjs';
import { primaryPackage, primaryInputID, openPrimaryInput, inputWindowDiagnostics } from './focus-baseline.mjs';
import { parseFocus,waitTypingBaseline,waitPrimaryState,requireWindowsUnavailable,resetVirtualFixture } from './measure-virtual-display-focus.mjs';
import { pngStats } from './virtual-display-png.mjs';
const opt=options(process.argv.slice(2)),s=new DisplaySession(opt,'focus');s.report.diagnosticOnly=true;
const remote='/data/local/tmp/aactl-isolated-diagnostic.xml';
const snapshot=()=>{const r=s.focusObservation();return parseFocus(r.window,r.ime,r.processes,r.xml);};
const save=(name,value)=>fs.writeFileSync(path.join(s.root,name),value);
try {
 await s.prepare({startHelper:false});await openPrimaryInput(s);
 s.action(0,'text',['--text','Primary'],'SSID preset text=Primary; never Save',primaryPackage);s.event('baseline',await waitTypingBaseline(snapshot));
 s.startHelper();const create=()=>s.cli(['display','create','--width','720','--height','1280','--dpi','240','--focus','isolated']).displayId;
 const old=create();s.cli(['display','close','--display',String(old)]);const id=create();
 s.action(id,'launch',['--package','dev.aiauto.fixture','--activity','.MainActivity'],'fixture on recreated screen');resetVirtualFixture(s,id);s.tap(id,'dev.aiauto.fixture:id/text_input',0.85);
 s.action(id,'text',['--text','Diagnostic'],'virtual text');ensure(s.hierarchy(id).includes('text="INPUT:Diagnostic"'),'Virtual input missing');s.event('awake',snapshot());
 s.cli(['action','sleep']);await waitPrimaryState(s,false);
 const frame=path.join(s.root,'virtual-asleep.png');s.cli(['observe','screenshot','--display',String(id),'--output',frame]);s.event('asleep-frame',pngStats(fs.readFileSync(frame)));
 s.shell('rm','-f',remote);const dump=s.raw(['shell','uiautomator','dump','--windows',remote],true),exists=s.raw(['shell','test','-f',remote],true).status===0;
 let bytes=Buffer.alloc(0);
 if(exists){s.guard();const r=spawnSync(opt.adb,['-s',opt.device,'exec-out','cat',remote],{timeout:15000,maxBuffer:8*1024*1024,windowsHide:true,env:{...process.env,MSYS_NO_PATHCONV:'1'}});ensure(!r.error&&r.status===0,'Raw XML read failed');bytes=r.stdout;save('raw-windows.xml',bytes.toString('utf8').replace(/\b(text|content-desc|title)="[^"]*"/g,'$1="[redacted]"'));}
 const raw={at:new Date().toISOString(),...dump,exists,bytes:bytes.length,sha256:sha256(bytes),displayIds:[...bytes.toString('utf8').matchAll(/<display id="(\d+)"/g)].map(m=>Number(m[1]))};save('raw-result.json',JSON.stringify(raw,null,2));s.event('raw-window-result',raw);
 const result=s.envelope(['observe','hierarchy','--display',String(id)]),response=s.lastEnvelope;
 save('actual-cli-response.json',JSON.stringify(response,(_key,value)=>typeof value==='string'?value.replace(/\b(text|content-desc|title)="[^"]*"/g,'$1="[redacted]"'):value,2));s.event('actual-cli-result',{ok:result.ok,error:result.error});
 save('power.txt',s.shell('dumpsys','power'));save('display.txt',s.shell('dumpsys','display'));
 s.failureObservation(0);s.preserveEvidence('before-contract-assertion');s.lastEnvelope=response;
 requireWindowsUnavailable(result);s.report.ok=true;
}catch(e){s.report.ok=false;s.report.error=e.message;}
finally{if(s.safe)try{s.shell('rm','-f',remote);}catch(e){s.report.rawCleanupError=e.message;}s.finish(s.report.ok?null:Error(s.report.error));}
