/** 脚本用途：验证主屏证据按显示隔离、当前IME归属与基线判据，防止历史或虚拟屏数据冒充主屏。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseFocus, requireBaseline, primaryInputID, primaryPackage } from './measure-virtual-display-focus.mjs';
import { options } from './focus-session.mjs';
const xml=`<displays><display id="0"><window active="true" focused="true"><node resource-id="${primaryInputID}" text="Primary" focused="true" /></window><window title="Localized keyboard" type="TYPE_INPUT_METHOD"></window></display></displays>`;
const windows='mTopFocusedDisplayId=2\n  Display: mDisplayId=2\n mCurrentFocus=virtual\n mFocusedApp=virtual\n type=ime visible=true\n  Display: mDisplayId=0\n mCurrentFocus=primary-window\n mFocusedApp=primary-app\n type=ime visible=false';
const ime=' mCurClient=ClientState{abc mUid=123 mPid=456 mSelfReportedDisplayId=2} mCurSeq=3\n mCurMethodId=fixed-ime\n mCurTokenDisplayId=0\n mInputShown=true\n history mInputShown=false';
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
