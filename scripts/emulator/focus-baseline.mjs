/**
 * 脚本用途：在一次性API35模拟器打开添加网络对话框，借SSID输入框测主屏焦点与IME。
 * 安全约束：绝不点Save、不保存网络配置；只点最新层级中的Cancel，并核对网络列表不变。
 */
import { ensure, attr, point, node, pause } from './focus-session.mjs';
export const primaryPackage='com.android.settings';
export const primaryInputID=primaryPackage+':id/ssid';
const tags=xml=>[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]);
export const primaryInput=xml=>{const found=tags(xml).filter(n=>n.includes('resource-id="'+primaryInputID+'"'));return found.length===1?found[0]:null;};
const hasPrimaryInput=xml=>tags(xml).some(n=>n.includes('resource-id="'+primaryInputID+'"'));
/** 空输入框在UIAutomator里显示hint；只接受固定提示语，不能把任意文本视为空值。 */
export function requireEmptyPrimary(xml) {
 const n=node(xml,primaryInputID);
 ensure(attr(n,'package')===primaryPackage&&attr(n,'text')==='Enter the SSID'&&attr(n,'focused')==='true','Expected focused SSID input with exact empty hint');
 return n;
}
/** 两次连续读到同一目标节点才认为稳定；消失或变化都会清除前次匹配。 */
export async function waitStable(read,select,{attempts=6,wait=()=>pause(250),observe=()=>{}}={}) {
 let previous=null,last=null;
 for(let i=0;i<attempts;i++) {
  const xml=await read(),match=select(xml);last={xml,match};observe(i+1,match);
  if(match&&match===previous)return {...last,ready:true};
  previous=match;if(i+1<attempts)await wait();
 }
 return {...last,ready:false};
}
/** 保留窗口原始头与节点数量，明确区分SSID窗口缺失和重复，不借其它窗口的数据。 */
export function inputWindowDiagnostics(xml) {
 const windows=[...xml.matchAll(/<window\b[^>]*>[\s\S]*?<\/window>/g)].map(m=>m[0]);
 return {matchingWindows:windows.filter(w=>w.includes('resource-id="'+primaryInputID+'"')).length,
  inputNodes:tags(xml).filter(n=>n.includes('resource-id="'+primaryInputID+'"')).length,
  packages:[...new Set([...xml.matchAll(/package="([^"]*)"/g)].map(m=>m[1]))],
  windows:windows.map(w=>w.match(/^<window\b[^>]*>/)[0])};
}
export async function openPrimaryInput(s,{attempts=6,wait=()=>pause(250)}={}) {
 s.primaryNetworksBefore=s.shell('cmd','wifi','list-networks');
 s.primaryBaselineCleanup=()=>closePrimaryInput(s);
 s.hierarchy(0);s.event('action',{package:primaryPackage,action:'WIFI_DIALOG',displayId:0,expected:'Add network; focused empty SSID; never Save'});
 const launch=s.shell('am','start','-W','--display','0','-a','com.android.settings.WIFI_DIALOG');
 ensure(launch.includes('Status: ok')&&launch.includes('com.android.settings/.wifi.WifiDialogActivity'),'Unexpected Wi-Fi dialog launch');
 const ready=await waitStable(()=>s.hierarchy(0),xml=>{try{return requireEmptyPrimary(xml);}catch{return null;}},{attempts,wait});
 ensure(ready.ready,'SSID empty hint/focus did not stabilize: '+JSON.stringify(inputWindowDiagnostics(ready.xml)));
 s.event('primary-dialog',{activity:'com.android.settings/.wifi.WifiDialogActivity',emptyHint:'Enter the SSID',input:requireEmptyPrimary(ready.xml),networksBefore:s.primaryNetworksBefore});
 return ready;
}
/** 只执行一次基于新节点的Cancel，随后有界只读观察；网络列表变化即失败，不尝试改回配置。 */
export function closePrimaryInput(s,{attempts=6}={}) {
 ensure(typeof s.primaryNetworksBefore==='string','Missing original network list');
 if(!s.cli(['observe','screen-state']).on){s.cli(['action','wake']);s.cli(['action','unlock']);}
 const xml=s.hierarchy(0);let cancelled=false;
 if(hasPrimaryInput(xml)) {
  ensure(primaryInput(xml),'Ambiguous SSID input during cleanup');
  const cancel=node(xml,'android:id/button2');
  ensure(attr(cancel,'package')===primaryPackage&&attr(cancel,'text').toUpperCase()==='CANCEL'&&attr(cancel,'clickable')==='true'&&attr(cancel,'enabled')==='true','Expected enabled Cancel button; refusing any other action');
  const xy=point(xml,'android:id/button2');
  s.action(0,'tap',['--x',String(xy.x),'--y',String(xy.y)],'Cancel Add network without saving',primaryPackage);
  let closed=false;
  for(let i=0;i<attempts;i++){if(!hasPrimaryInput(s.hierarchy(0))){closed=true;break;}}
  ensure(closed,'Add network dialog did not close after Cancel');cancelled=true;
 }
 const networksAfter=s.shell('cmd','wifi','list-networks');ensure(networksAfter===s.primaryNetworksBefore,'Network list changed; refusing to modify saved networks');
 s.report.primaryBaselineCleanup={cancelled,networksUnchanged:true,networksBefore:s.primaryNetworksBefore,networksAfter};
 s.event('primary-dialog-closed',s.report.primaryBaselineCleanup);
}
