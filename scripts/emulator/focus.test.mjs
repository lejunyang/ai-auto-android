/** 脚本用途：验证焦点证据按显示隔离、当前 IME 归属及证据缺失时停止，防止活动窗口或历史条目冒充主屏。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseFocus } from './measure-virtual-display-focus.mjs';
import { options } from './focus-session.mjs';
const xml='<displays><display id="0"><window><node resource-id="dev.aiauto.fixture:id/text_input" text="Primary" focused="true" /></window></display></displays>';
const windows='  Display: mDisplayId=2\n mCurrentFocus=virtual\n mFocusedApp=virtual\n type=ime visible=true\n  Display: mDisplayId=0\n mCurrentFocus=primary-window\n mFocusedApp=primary-app\n type=ime visible=false';
const ime=' mCurClient=ClientState{abc mUid=123 mPid=456 mSelfReportedDisplayId=2} mCurSeq=3\n mCurMethodId=fixed-ime\n mCurTokenDisplayId=0\n mInputShown=true\n history mInputShown=false';
test('reversed display sections never substitute virtual focus or IME visibility',()=>{
 const r=parseFocus(windows,ime,'PID NAME\n456 virtual.app',xml);
 assert.equal(r.focusWindow,'primary-window');assert.equal(r.focusApp,'primary-app');assert.equal(r.primaryImeVisible,false);assert.equal(r.clientDisplay,2);assert.equal(r.clientProcess,'virtual.app');assert.equal(r.inputShown,true);assert.equal(r.imeTokenDisplay,0);assert.equal(r.primaryInput,'Primary');
});
test('missing primary/current evidence fails instead of borrowing history',()=>{
 assert.throws(()=>parseFocus(windows.replace('mDisplayId=0','mDisplayId=3'),ime,'456 virtual.app',xml));
 assert.throws(()=>parseFocus(windows,ime.replace(' mInputShown=true',' history mInputShown=true'),'456 virtual.app',xml));
 assert.throws(()=>parseFocus(windows,ime,'999 other.app',xml));
});
test('non-emulator options are rejected before touching artifact paths',()=>{
 assert.throws(()=>options(['--device','REAL123']),/non-emulator/);
 assert.throws(()=>options(['--device','emulator-5556']),/emulator-5554/);
});
