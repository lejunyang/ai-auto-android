// 测试用途：验证一次性模拟器添加网络基线的精确文本、单次启动与只取消不保存边界。
// 脚本用途：为基线搭建与取消清理提供纯模拟会话测试，防止输入提示语误判和误点保存。
import test from 'node:test';
import assert from 'node:assert/strict';
import { primaryPackage, primaryInputID, requireEmptyPrimary, openPrimaryInput, closePrimaryInput } from './focus-baseline.mjs';
const field=(text='Enter the SSID',focused=true)=>`<node package="com.android.settings" resource-id="com.android.settings:id/ssid" text="${text}" focused="${focused}"/>`;
const cancel='<node package="com.android.settings" resource-id="android:id/button2" text="Cancel" bounds="[10,20][30,40]" clickable="true" enabled="true"/>';

test('empty input is exactly the SSID hint, with the expected package and focus',()=>{
 assert.equal(primaryPackage,'com.android.settings');assert.equal(primaryInputID,'com.android.settings:id/ssid');
 assert.doesNotThrow(()=>requireEmptyPrimary(field()));
 for(const xml of [field(''),field('arbitrary'),field('Enter the SSID',false),field().replace('package="com.android.settings"','package="other"'),field()+field()])assert.throws(()=>requireEmptyPrimary(xml));
});
test('opening is one explicit display-0 intent and never types over an unknown value',async()=>{
 const commands=[],events=[];let reads=0;
 const s={report:{},hierarchy(){reads++;return field();},shell(...args){commands.push(args);return args[0]==='am'?'Status: ok\nActivity: com.android.settings/.wifi.WifiDialogActivity':'Network Id SSID\n0 AndroidWifi';},event(...args){events.push(args);}};
 await openPrimaryInput(s,{wait:async()=>{}});
 assert.equal(commands.filter(c=>c[0]==='am').length,1);assert.ok(commands.find(c=>c[0]==='am').includes('0'));assert.ok(reads>=3);assert.equal(typeof s.primaryBaselineCleanup,'function');
 await assert.rejects(openPrimaryInput({...s,hierarchy:()=>field('already populated')},{attempts:2,wait:async()=>{}}));
});
test('cleanup uses freshly observed Cancel once, verifies closure and compares network lists',()=>{
 let reads=0;const actions=[];
 const s={report:{},primaryNetworksBefore:'0 AndroidWifi',cli:()=>({on:true}),hierarchy(){return ++reads===1?field()+cancel:'<displays/>';},action(...args){actions.push(args);},shell:()=> '0 AndroidWifi',event(){}};
 closePrimaryInput(s);assert.equal(actions.length,1);assert.deepEqual(actions[0].slice(0,3),[0,'tap',['--x','20','--y','30']]);assert.equal(s.report.primaryBaselineCleanup.networksUnchanged,true);
 assert.throws(()=>closePrimaryInput({...s,hierarchy:()=>field()+cancel.replace('Cancel','Save')}),/Cancel/);
 assert.throws(()=>closePrimaryInput({...s,hierarchy:()=>field()+field()+cancel}),/Ambiguous/);
 assert.throws(()=>closePrimaryInput({...s,hierarchy:()=>'<displays/>',shell:()=> '0 AndroidWifi\n1 unexpected'}),/Network list changed/);
});
test('cleanup never repeats a cancel tap if the dialog does not close',()=>{
 let taps=0,reads=0;const s={report:{},primaryNetworksBefore:'0 AndroidWifi',cli:()=>({on:true}),hierarchy(){reads++;return field()+cancel;},action(){taps++;},shell:()=> '0 AndroidWifi',event(){}};
 assert.throws(()=>closePrimaryInput(s,{attempts:3}),/did not close/);assert.equal(taps,1);assert.equal(reads,4);
});
