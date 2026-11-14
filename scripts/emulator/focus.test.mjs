/** 脚本用途：验证主屏证据按显示隔离、当前IME归属与基线判据，防止历史或虚拟屏数据冒充主屏。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { parseFocus, requireBaseline, requireWindowsUnavailable, primaryInputID, primaryPackage, waitTypingBaseline, resetVirtualFixture, waitPrimaryState } from './measure-virtual-display-focus.mjs';
import { options, DisplaySession, retryObservedRead, currentImeEvidence } from './focus-session.mjs';
import { waitStable, openPrimarySearch, inputWindowDiagnostics, recoverPrimaryBaseline } from './focus-baseline.mjs';
const xml=`<displays><display id="0"><window active="true" focused="true"><node resource-id="${primaryInputID}" text="Primary" focused="true" /></window><window title="Localized keyboard" type="TYPE_INPUT_METHOD"></window></display></displays>`;
const windows='mTopFocusedDisplayId=2\n  Display: mDisplayId=2\n mCurrentFocus=virtual\n mFocusedApp=virtual\n type=ime visible=true\n  Display: mDisplayId=0\n mCurrentFocus=primary-window\n mFocusedApp=primary-app\n type=ime visible=false';
const ime=' mCurClient=ClientState{abc mUid=123 mPid=456 mSelfReportedDisplayId=2} mCurSeq=3\n mCurMethodId=fixed-ime\n mCurTokenDisplayId=0\n mInputShown=true\n history mInputShown=false';

test('missing window output requires its own code and actionable recovery',()=>{
 const error={code:'WINDOWS_UNAVAILABLE',message:'Wake primary, or rebuild with steal'};
 assert.doesNotThrow(()=>requireWindowsUnavailable({ok:false,error}));
 for(const code of ['DISPLAY_EMPTY','DISPLAY_NOT_FOUND','ACTION_FAILED'])assert.throws(()=>requireWindowsUnavailable({ok:false,error:{...error,code}}));
 assert.throws(()=>requireWindowsUnavailable({ok:true,error}));
 assert.throws(()=>requireWindowsUnavailable({ok:false,error:{...error,message:'no windows'}}));
});
test('reversed displays keep primary focus and current virtual IME client distinct',()=>{
 const r=parseFocus(windows,ime,'PID NAME\n456 virtual.app',xml);
 assert.equal(r.focusWindow,'primary-window');assert.equal(r.focusApp,'primary-app');assert.equal(r.primaryImeVisible,false);assert.equal(r.clientDisplay,2);assert.equal(r.clientProcess,'virtual.app');assert.equal(r.inputShown,true);assert.equal(r.imeTokenDisplay,0);assert.equal(r.primaryInput,'Primary');assert.equal(r.topFocusedDisplay,2);assert.equal(r.primaryImeWindow,true);
});
test('missing primary/current evidence fails instead of borrowing history',()=>{
 assert.throws(()=>parseFocus(windows.replace('mDisplayId=0','mDisplayId=3'),ime,'456 virtual.app',xml));
 assert.throws(()=>parseFocus(windows,ime.replace(' mInputShown=true',' history mInputShown=true'),'456 virtual.app',xml));
 assert.throws(()=>parseFocus(windows,ime,'999 other.app',xml));
});
test('baseline requires every original focus, client, text and visible IME criterion',()=>{
 const b={primaryInput:'Primary',primaryInputFocused:true,clientProcess:primaryPackage,clientDisplay:0,inputShown:true,primaryImeVisible:true,primaryImeWindow:true};
 requireBaseline(b);
 for(const key of Object.keys(b))assert.throws(()=>requireBaseline({...b,[key]:typeof b[key]==='boolean'?false:typeof b[key]==='number'?2:'wrong'}),key);
});
test('non-emulator options are rejected before touching artifact paths',()=>{
 assert.throws(()=>options(['--device','REAL123']),/non-emulator/);assert.throws(()=>options(['--device','emulator-5556']),/emulator-5554/);
});

test('stability needs two consecutive matches and resets on missing or changed targets',async()=>{
 const sequence=['A','A','B','A','A'];let at=0;
 const r=await waitStable(()=>sequence[at++],s=>s==='B'?null:s,{attempts:5,wait:async()=>{}});
 assert.equal(r.ready,true);assert.equal(at,2);
 at=0;const changed=['A','B','A','A'];
 const reset=await waitStable(()=>changed[at++],s=>s==='B'?null:s,{attempts:4,wait:async()=>{}});
 assert.equal(reset.ready,true);assert.equal(at,4);
 at=0;const never=await waitStable(()=>String(at++),s=>s,{attempts:3,wait:async()=>{}});
 assert.equal(never.ready,false);assert.equal(at,3);
});
test('search retry preserves failed observation and uses newly located coordinates',async()=>{
 const home=x=>`<node package="com.android.settings" text="Search settings" resource-id="search" bounds="[${x},0][${x+20},20]"/>`;
 const input=`<node resource-id="${primaryInputID}" bounds="[0,0][20,20]"/>`;
 let taps=0,reads=0;const actions=[],evidence=[];
 const s={hierarchy(){reads++;return taps===2?input:home(taps===0?0:100);},action(...args){actions.push(args);taps++;},event(){},preserveEvidence(reason){evidence.push(reason);}};
 assert.equal((await openPrimarySearch(s,{attempts:2,wait:async()=>{}})).ready,true);
 assert.equal(taps,2);assert.deepEqual(actions.map(a=>a[2]),[['--x','10','--y','10'],['--x','110','--y','10']]);assert.deepEqual(evidence,['search-open-1']);assert.ok(reads>=8);
});
test('input window diagnostics distinguish no input from duplicate windows',()=>{
 const missing=inputWindowDiagnostics('<displays><window><node package="com.android.settings"/></window></displays>');
 assert.equal(missing.matchingWindows,0);assert.equal(missing.inputNodes,0);
 const doubled=xml.replace('</displays>',xml.match(/<window active[\s\S]*?<\/window>/)[0]+'</displays>');
 assert.equal(inputWindowDiagnostics(doubled).matchingWindows,2);
 assert.throws(()=>parseFocus(windows,ime,'456 virtual.app','<displays/>'),/"matchingWindows":0/);
 assert.throws(()=>parseFocus(windows,ime,'456 virtual.app',doubled),/"matchingWindows":2/);
});
test('failure evidence preserves the triggering XML without taking a replacement hierarchy',()=>{
 const base=path.resolve('scripts/emulator/build');fs.mkdirSync(base,{recursive:true});const root=fs.mkdtempSync(path.join(base,'evidence-test-'));
 try {
  const s=Object.create(DisplaySession.prototype);Object.assign(s,{safe:true,root,report:{steps:[{step:'original-action'}]},lastObservation:{displayId:0,observedAt:'original-time',xml:'<displays><node text="private" resource-id="original"/></displays>',window:'mTopFocusedDisplayId=0\n Display: mDisplayId=0\n mCurrentFocus=original',ime:'mInputShown=true',processes:'123 com.android.settings',activity:'Display #0 (activities from top to bottom):\n original'}});
  s.hierarchy=()=>{throw Error('must not replace the original XML');};s.shell=(...args)=>{if(args[0]==='logcat'||args[2]==='exit-info')return '';throw Error('original system evidence already present');};
  s.cli=args=>{assert.deepEqual(args.slice(0,4),['observe','screenshot','--display','0']);fs.writeFileSync(args.at(-1),Buffer.from('fake screenshot'));};
  s.preserveEvidence('test-failure');
  const saved=fs.readFileSync(path.join(root,'evidence-1-hierarchy.xml'),'utf8');assert.ok(saved.includes('resource-id="original"'));assert.ok(!saved.includes('private'));
  assert.equal(s.report.evidence[0].source,'original-time');assert.ok(fs.existsSync(path.join(root,'evidence-1-screen.png')));assert.ok(fs.existsSync(path.join(root,'evidence-1-timeline.json')));
 }finally{for(const f of fs.readdirSync(root))fs.unlinkSync(path.join(root,f));fs.rmdirSync(root);}
});
test('daemon retry requires a readonly command and current confirmed device identity',()=>{
 const failed={status:1,stderr:'cannot connect to daemon at tcp:5037'},ok={status:0};let retries=0,checks=0;
 const run=args=>retryObservedRead(failed,args,()=>{checks++;return true;},()=>{retries++;return ok;});
 assert.equal(run(['-s','emulator-5554','shell','dumpsys','package']),ok);assert.equal(retries,1);assert.equal(checks,1);
 for(const args of [['-s','emulator-5554','shell','input','text','a'],['-s','emulator-5554','install','x.apk'],['-s','emulator-5554','shell','rm','x']])assert.equal(run(args),failed);
 assert.equal(retries,1);assert.equal(checks,1);
 assert.equal(retryObservedRead(failed,['-s','emulator-5554','shell','getprop'],()=>false,()=>{throw Error('wrong identity retry');}),failed);
});
test('current IME evidence survives a registration list larger than the old prefix budget',()=>{
 const current='  mCurClient=ClientState{mPid=42 mSelfReportedDisplayId=0}\n  mCurMethodId=ime\n  mInputShown=true\n  mCurTokenDisplayId=0';
 assert.equal(currentImeEvidence('Registration metadata'.repeat(4000)+'\n'+current),current);
});
test('typing baseline waits for all original criteria and the same current client twice',async()=>{
 const good={primaryInput:'Primary',primaryInputFocused:true,clientProcess:primaryPackage,clientDisplay:0,inputShown:true,primaryImeVisible:true,primaryImeWindow:true};let reads=0;
 const state=await waitTypingBaseline(()=>({...good,client:++reads===1?'old':'new'}),{wait:async()=>{}});assert.equal(state.client,'new');assert.equal(reads,3);
 reads=0;await assert.rejects(waitTypingBaseline(()=>{reads++;return {...good,client:'same',inputShown:false};},{attempts:3,wait:async()=>{}}));assert.equal(reads,3);
});
test('fixture reset is a visible typed tap and must clear both process counters and input',()=>{
 const clear=`<node resource-id="dev.aiauto.fixture:id/text_input" text=""/><node text="CLICK:0"/><node text="INPUT:"/>`;const actions=[];
 const s={tap(id,node){actions.push([id,node]);return clear;},event(){}};resetVirtualFixture(s,3);assert.deepEqual(actions,[[3,'dev.aiauto.fixture:id/reset_target']]);
 assert.throws(()=>resetVirtualFixture({...s,tap:()=>clear.replace('CLICK:0','CLICK:1')},3));assert.throws(()=>resetVirtualFixture({...s,tap:()=>clear.replace('text=""','text="retained"')},3));
});
test('power transition waits on bounded observations without repeating input and saves timeout context',async()=>{
 let reads=0,saved=0,context=0;const s={cli(args){assert.deepEqual(args,['observe','screen-state']);return {on:++reads<3};},event(){},failureObservation(){context++;},preserveEvidence(){saved++;}};
 await waitPrimaryState(s,false,{wait:async()=>{}});assert.equal(reads,3);assert.equal(saved,0);
 reads=0;s.cli=()=>{reads++;return {on:true};};await assert.rejects(waitPrimaryState(s,false,{attempts:3,wait:async()=>{}}));assert.equal(reads,3);assert.equal(context,1);assert.equal(saved,1);
});
test('failure saves the actual response before screenshot observation can replace the cache',t=>{
 const base=path.resolve('scripts/emulator/build');fs.mkdirSync(base,{recursive:true});const root=fs.mkdtempSync(path.join(base,'response-test-'));const previous=process.exitCode;
 t.mock.method(console,'log',()=>{});
 try {
  const s=Object.create(DisplaySession.prototype);Object.assign(s,{root,report:{steps:[]},lastObservation:{},lastEnvelope:{args:['observe','hierarchy','--display','3'],result:{ok:false,error:{code:'ACTUAL_ERROR'}}}});
  s.cleanup=()=>{};s.preserveEvidence=()=>{s.lastEnvelope={result:{ok:true,data:{format:'png'}}};};s.finish(Error('assertion'));
  const response=JSON.parse(fs.readFileSync(path.join(root,'failure-response.json'),'utf8'));assert.equal(response.result.error.code,'ACTUAL_ERROR');assert.equal(response.args.at(-1),'3');
 }finally{process.exitCode=previous;for(const f of fs.readdirSync(root))fs.unlinkSync(path.join(root,f));fs.rmdirSync(root);}
});
test('baseline rebuild requires fresh homepage evidence and is forbidden after helper startup',async()=>{
 const calls=[];let builds=0;const s={helperStarted:false,preserveEvidence(){calls.push('save');},hierarchy(){calls.push('observe');return '<node package="com.android.settings" text="Search settings"/>';},event(){}};
 assert.equal(await recoverPrimaryBaseline(s,async()=>{calls.push('build');if(++builds===1)throw Error('state change');return 'ready';}),'ready');assert.deepEqual(calls,['build','save','observe','build']);
 await assert.rejects(recoverPrimaryBaseline({...s,helperStarted:true},async()=>{throw Error('must not execute');}),/after product/);
 builds=0;await assert.rejects(recoverPrimaryBaseline({...s,hierarchy:()=>'<displays/>'},async()=>{builds++;throw Error('missing');}));assert.equal(builds,1);
 builds=0;await assert.rejects(recoverPrimaryBaseline(s,async()=>{builds++;throw Error('still missing');}));assert.equal(builds,2);
});
test('offline, extra device or changed transport stops before any further device read',()=>{
 const online='emulator-5554 device product:fixed transport_id:58';let reads=0;
 for(const devices of [['emulator-5554 offline transport_id:58'],[online,'REAL123 device transport_id:99'],[online.replace('58','99')]]) {
  const s=Object.create(DisplaySession.prototype);Object.assign(s,{serial:'emulator-5554',identity:online,devices:()=>devices,raw(){reads++;throw Error('must not continue');}});
  assert.throws(()=>s.guard());assert.equal(reads,0);
 }
});
